package com.boldexplorer.db

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Permanent guard rail for the collection-centred data model (do not delete):
 *
 *  - Every `kind='waypoint'` row MUST belong to at least one collection.
 *  - No `kind='track_point'` row may belong to a collection (track points derive their collection
 *    through their trail).
 *
 * This protects the model against regressions in any create / import path for the life of the project.
 */
class WaypointOwnershipInvariantTest {
    @Test
    fun everyUserWaypointHasACollection_andNoTrackPointDoes() =
        runTest {
            val db = createTestDatabase()
            val waypoints = WaypointRepositoryImpl(db)
            val trails = TrailRepositoryImpl(db)
            val collections = CollectionRepositoryImpl(db)

            // Exercise representative create paths.
            val cid = collections.create("Trip", null)
            waypoints.create(cid, "Standalone", 1.0, 1.0, null, null)
            val trailId = trails.create(cid, "Loop", null)
            waypoints.createTrackPoint(trailId, "Track 1", 2.0, 2.0, null)
            // A named waypoint attached to the trail still owns a collection of its own.
            val named = waypoints.create(cid, "OnTrail", 3.0, 3.0, null, null)
            waypoints.attach(trailId, named)

            assertOwnershipInvariant(db)
        }
}

/** Asserts the collection-ownership invariant over the entire database. */
internal suspend fun assertOwnershipInvariant(db: BoldExplorerDatabase) {
    val collections = CollectionRepositoryImpl(db)

    // Named waypoints (getAll filters kind='waypoint') must each have ≥1 collection.
    db.waypointQueries.getAll().executeAsList().forEach { wp ->
        assertTrue(
            collections.collectionsForWaypoint(wp.id).isNotEmpty(),
            "User waypoint '${wp.name}' (id=${wp.id}) has no collection membership",
        )
    }

    // Track points (on any trail) must have NO collection membership.
    db.trailQueries.getAll().executeAsList().forEach { trail ->
        db.waypointQueries.trackPointsForTrail(trail.id).executeAsList().forEach { tp ->
            assertTrue(
                collections.collectionsForWaypoint(tp.id).isEmpty(),
                "Track point '${tp.name}' (id=${tp.id}) must not belong to a collection",
            )
        }
    }
}
