package com.boldexplorer.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SENTINEL = "__bx_migration_general_v4__"

/**
 * Tests for the v4 → v5 migration (`4.sqm`): adds `tentative` and back-fills a sentinel-marked
 * "General" collection for any orphaned user waypoints / trails. Track points stay collection-less.
 */
class MigrationV4Test {
    /** Builds the schema as it existed at version 4 — waypoint/trail WITHOUT the `tentative` column. */
    private fun createV4Driver(): SqlDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            """
            CREATE TABLE collection (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                description TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE trail (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                description TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE waypoint (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                elev_m REAL,
                description TEXT,
                created_at INTEGER NOT NULL,
                kind TEXT NOT NULL DEFAULT 'waypoint'
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE collection_waypoint (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                collection_id INTEGER NOT NULL REFERENCES collection(id),
                waypoint_id INTEGER NOT NULL REFERENCES waypoint(id),
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE collection_trail (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                collection_id INTEGER NOT NULL REFERENCES collection(id),
                trail_id INTEGER NOT NULL REFERENCES trail(id),
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE trail_waypoint (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                trail_id INTEGER NOT NULL REFERENCES trail(id),
                waypoint_id INTEGER NOT NULL REFERENCES waypoint(id),
                position INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        return driver
    }

    private fun SqlDriver.exec(sql: String) = execute(null, sql, 0)

    /** Migrate the seeded v4 driver to the current schema (v5) and wrap it in the typed database. */
    private fun migrate(driver: SqlDriver): BoldExplorerDatabase {
        BoldExplorerDatabase.Schema.migrate(driver, 4, 5)
        return BoldExplorerDatabase(driver)
    }

    private fun sentinelCollections(db: BoldExplorerDatabase) =
        db.collectionQueries
            .getAll()
            .executeAsList()
            .filter { it.description == SENTINEL }

    @Test
    fun emptyDatabase_createsNoMigrationCollection() =
        runTest {
            val driver = createV4Driver()
            val db = migrate(driver)
            assertEquals(emptyList(), db.collectionQueries.getAll().executeAsList())
        }

    @Test
    fun noOrphans_createsNoMigrationCollection() =
        runTest {
            val driver = createV4Driver()
            // A waypoint and a trail that already belong to a collection.
            driver.exec("INSERT INTO collection (name, description, created_at) VALUES ('Trip', 'mine', 1)")
            driver.exec("INSERT INTO waypoint (name, lat, lon, created_at, kind) VALUES ('WP', 1, 1, 1, 'waypoint')")
            driver.exec("INSERT INTO trail (name, created_at) VALUES ('Loop', 1)")
            driver.exec("INSERT INTO collection_waypoint (collection_id, waypoint_id, created_at) VALUES (1, 1, 1)")
            driver.exec("INSERT INTO collection_trail (collection_id, trail_id, created_at) VALUES (1, 1, 1)")

            val db = migrate(driver)

            assertEquals(emptyList(), sentinelCollections(db))
            assertEquals(
                1,
                db.collectionQueries
                    .getAll()
                    .executeAsList()
                    .size,
            )
        }

    @Test
    fun orphans_attachedToSingleMigrationCollection_trackPointsExempt() =
        runTest {
            val driver = createV4Driver()
            driver.exec("INSERT INTO trail (name, created_at) VALUES ('Loop', 1)")
            driver.exec("INSERT INTO waypoint (name, lat, lon, created_at, kind) VALUES ('Orphan', 1, 1, 1, 'waypoint')")
            // A track point on the trail — must NOT be adopted into a collection.
            driver.exec("INSERT INTO waypoint (name, lat, lon, created_at, kind) VALUES ('Track', 2, 2, 1, 'track_point')")
            driver.exec("INSERT INTO trail_waypoint (trail_id, waypoint_id, position, created_at) VALUES (1, 2, 1, 1)")

            val db = migrate(driver)
            val collections = CollectionRepositoryImpl(db)

            // Exactly one sentinel-marked migration collection.
            val sentinel = sentinelCollections(db)
            assertEquals(1, sentinel.size)
            val migrationId = sentinel.first().id

            // Orphan user waypoint (id 1) and the trail are attached to it.
            assertEquals(listOf("Orphan"), collections.waypointsForCollection(migrationId).map { it.name })
            assertEquals(listOf("Loop"), collections.trailsForCollection(migrationId).map { it.name })

            // The track point (id 2) is never a collection member.
            assertTrue(collections.collectionsForWaypoint(2).isEmpty())
        }

    @Test
    fun preexistingUserGeneral_isNotAdopted() =
        runTest {
            val driver = createV4Driver()
            // The user already owns a collection literally named "General" — must be left alone.
            driver.exec("INSERT INTO collection (name, description, created_at) VALUES ('General', 'user notes', 1)")
            driver.exec("INSERT INTO waypoint (name, lat, lon, created_at, kind) VALUES ('Orphan', 1, 1, 1, 'waypoint')")

            val db = migrate(driver)
            val collections = CollectionRepositoryImpl(db)

            // The migration created its OWN sentinel collection, distinct from the user's General.
            val sentinel = sentinelCollections(db)
            assertEquals(1, sentinel.size)
            val userGeneral =
                db.collectionQueries
                    .getAll()
                    .executeAsList()
                    .first { it.description == "user notes" }
            assertTrue(sentinel.first().id != userGeneral.id)

            // The user's General did not adopt the orphan.
            assertEquals(emptyList(), collections.waypointsForCollection(userGeneral.id))
            assertEquals(listOf("Orphan"), collections.waypointsForCollection(sentinel.first().id).map { it.name })
        }

    @Test
    fun tentativeColumn_defaultsFalseAfterMigration() =
        runTest {
            val driver = createV4Driver()
            driver.exec("INSERT INTO collection (name, description, created_at) VALUES ('Trip', 'mine', 1)")
            driver.exec("INSERT INTO waypoint (name, lat, lon, created_at, kind) VALUES ('WP', 1, 1, 1, 'waypoint')")
            driver.exec("INSERT INTO collection_waypoint (collection_id, waypoint_id, created_at) VALUES (1, 1, 1)")

            val db = migrate(driver)
            val wp = WaypointRepositoryImpl(db).getById(1)
            assertEquals(false, wp?.tentative)
        }
}
