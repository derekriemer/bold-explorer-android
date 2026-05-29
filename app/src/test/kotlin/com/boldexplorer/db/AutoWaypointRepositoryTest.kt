package com.boldexplorer.db

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoWaypointRepositoryTest {

    @Test
    fun createAndRemoveForTrail_roundTrips() = runTest {
        val db = createTestDatabase()
        val trails = TrailRepositoryImpl(db)
        val autoWaypoints = AutoWaypointRepositoryImpl(db)

        val trailId = trails.create("Loop", null)
        autoWaypoints.create(
            trailId = trailId,
            name = "Cue",
            segmentIndex = 1,
            offsetM = 12.5,
            lat = 1.0,
            lon = 2.0,
        )

        val created = autoWaypoints.forTrail(trailId)
        assertEquals(1, created.size)
        assertEquals("Cue", created.first().name)
        assertEquals(12.5, created.first().offsetM)

        autoWaypoints.removeForTrail(trailId)
        assertEquals(emptyList(), autoWaypoints.forTrail(trailId))
    }
}
