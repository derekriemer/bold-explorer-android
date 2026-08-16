package com.boldexplorer.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The v5 → v6 migration (`5.sqm`): adds `trail_annotation` (ADR 0001, S5b).
 *
 * What it deliberately does **not** do is move anything. Whether an existing attached point was a
 * mistake or a deliberate extension is not knowable from the data, so demoting one to an annotation
 * is a question for the human — the migration only builds the place for the answer to live.
 */
class MigrationV5Test {
    /** The schema at version 5: everything current except `trail_annotation`. */
    private fun createV5Driver(): SqlDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BoldExplorerDatabase.Schema.create(driver)
        driver.execute(null, "DROP TABLE trail_annotation", 0)
        return driver
    }

    private fun SqlDriver.exec(sql: String) = execute(null, sql, 0)

    private fun seedTrailWithAttachedPoint(driver: SqlDriver) {
        driver.exec("INSERT INTO collection (name, description, created_at) VALUES ('Trip', null, 1)")
        driver.exec("INSERT INTO trail (name, created_at, tentative) VALUES ('Twelve', 1, 0)")
        driver.exec("INSERT INTO waypoint (name, lat, lon, created_at, kind, tentative) VALUES ('tp', 1.0, 1.0, 1, 'track_point', 0)")
        // The `dos` case, left exactly as it is: still a vertex, still at position 139.
        driver.exec("INSERT INTO waypoint (name, lat, lon, created_at, kind, tentative) VALUES ('dos', 2.0, 2.0, 2, 'waypoint', 0)")
        driver.exec("INSERT INTO trail_waypoint (trail_id, waypoint_id, position, created_at) VALUES (1, 1, 138, 1)")
        driver.exec("INSERT INTO trail_waypoint (trail_id, waypoint_id, position, created_at) VALUES (1, 2, 139, 2)")
    }

    private fun migrate(driver: SqlDriver): BoldExplorerDatabase {
        BoldExplorerDatabase.Schema.migrate(driver, 5, 6)
        return BoldExplorerDatabase(driver)
    }

    @Test
    fun existingAttachmentsAreLeftExactlyWhereTheyAre() =
        runTest {
            val driver = createV5Driver()
            seedTrailWithAttachedPoint(driver)

            val db = migrate(driver)

            // Geometry untouched: `dos` is still a vertex at position 139. Demoting it is the
            // owner's call, and the migration does not make it.
            assertEquals(
                listOf(138L, 139L),
                db.trailWaypointQueries
                    .getByTrail(1)
                    .executeAsList()
                    .map { it.position },
            )
            assertEquals(emptyList(), db.trailAnnotationQueries.rowsForTrail(1).executeAsList())
        }

    @Test
    fun theNewTableAcceptsAnAnnotation() =
        runTest {
            val driver = createV5Driver()
            seedTrailWithAttachedPoint(driver)
            val db = migrate(driver)

            db.trailAnnotationQueries.insert(1, 2, 137, 4.5, 100)

            val row = db.trailAnnotationQueries.rowsForTrail(1).executeAsOne()
            assertEquals(2L, row.waypoint_id)
            assertEquals(4.5, row.offset_m)
        }

    @Test
    fun aWaypointAnnotatesATrailOnlyOnce() =
        runTest {
            val driver = createV5Driver()
            seedTrailWithAttachedPoint(driver)
            val db = migrate(driver)

            db.trailAnnotationQueries.insert(1, 2, 137, 4.5, 100)

            // Attaching twice is a mistake, not two annotations — the unique index says so rather
            // than leaving the app to remember.
            assertFailsWith<Exception> {
                db.trailAnnotationQueries.insert(1, 2, 0, 0.0, 200)
            }
        }
}
