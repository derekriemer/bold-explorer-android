package com.boldexplorer.db

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionRepositoryTest {

    @Test
    fun attachAndDetachWaypoint_updatesCollectionContents() = runTest {
        val db = createTestDatabase()
        val collections = CollectionRepositoryImpl(db)
        val waypoints = WaypointRepositoryImpl(db)

        val collectionId = collections.create("Favorites", null)
        val waypointId = waypoints.create("Point", 1.0, 2.0, null, null)

        collections.attachWaypoint(collectionId, waypointId)
        assertEquals(listOf("Point"), collections.waypointsForCollection(collectionId).map { it.name })

        collections.detachWaypoint(collectionId, waypointId)
        assertEquals(emptyList(), collections.waypointsForCollection(collectionId))
    }

    @Test
    fun attachAndDetachTrail_updatesCollectionContents() = runTest {
        val db = createTestDatabase()
        val collections = CollectionRepositoryImpl(db)
        val trails = TrailRepositoryImpl(db)

        val collectionId = collections.create("Favorites", null)
        val trailId = trails.create("Loop", null)

        collections.attachTrail(collectionId, trailId)
        assertEquals(listOf("Loop"), collections.trailsForCollection(collectionId).map { it.name })

        collections.detachTrail(collectionId, trailId)
        assertEquals(emptyList(), collections.trailsForCollection(collectionId))
    }
}
