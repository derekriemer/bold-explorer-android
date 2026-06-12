package com.boldexplorer.db

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrailRepositoryTest {
    @Test
    fun createUpdateAndRemove_roundTrips() =
        runTest {
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)

            val id = trails.create("Old", "desc")
            trails.update(id, name = "New")

            assertEquals("New", trails.getById(id)?.name)
            assertEquals("desc", trails.getById(id)?.description)

            trails.remove(id)
            assertNull(trails.getById(id))
        }

    @Test
    fun remove_cleansTrailWaypointAndCollectionReferences() =
        runTest {
            val db = createTestDatabase()
            val trails = TrailRepositoryImpl(db)
            val waypoints = WaypointRepositoryImpl(db)
            val collections = CollectionRepositoryImpl(db)

            val trailId = trails.create("Loop", null)
            val waypointId = waypoints.create("Point", 1.0, 2.0, null, null)
            val collectionId = collections.create("Favorites", null)
            waypoints.attach(trailId, waypointId)
            collections.attachTrail(collectionId, trailId)

            trails.remove(trailId)

            assertEquals(emptyList(), waypoints.forTrail(trailId))
            assertEquals(emptyList(), collections.trailsForCollection(collectionId))
        }
}
