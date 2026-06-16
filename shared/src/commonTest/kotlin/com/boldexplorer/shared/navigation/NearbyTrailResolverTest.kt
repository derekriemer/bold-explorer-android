package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.TrailPointRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NearbyTrailResolverTest {
    // ~11.13 m per 0.0001° of latitude near the equator; used to place points at known distances.
    private fun tp(
        trailId: Long,
        position: Int,
        lat: Double,
        lon: Double,
    ) = TrailPointRow(id = trailId * 1000 + position, lat = lat, lon = lon, trailId = trailId, position = position)

    /** A vertical line (constant lon) on [trailId] with [count] points, [stepLat] apart from [baseLat]. */
    private fun verticalLine(
        trailId: Long,
        lon: Double,
        baseLat: Double = 0.0,
        stepLat: Double = 0.0001,
        count: Int = 3,
    ) = (0 until count).map { i -> tp(trailId, i, baseLat + i * stepLat, lon) }

    @Test
    fun emptySnapshot_returnsEmpty() {
        assertTrue(NearbyTrailResolver.resolve(LatLng(0.0, 0.0), accuracyM = null, points = emptyList()).isEmpty())
    }

    @Test
    fun nearTrail_withinFloor_isResolved() {
        // Line at lon=0; user ~11 m east (0.0001° lon). Perpendicular distance well under the 20 m floor.
        val points = verticalLine(trailId = 1, lon = 0.0)
        val result = NearbyTrailResolver.resolve(LatLng(0.0001, 0.0001), accuracyM = null, points = points)
        assertEquals(1, result.size)
        val near = result.single()
        assertEquals(1L, near.trailId)
        assertTrue(near.distanceM < NearbyTrailResolver.NEAR_TRAIL_FLOOR_M, "distance ${near.distanceM}")
    }

    @Test
    fun farTrail_beyondGate_returnsEmpty() {
        // Line ~111 km north of the user.
        val points = verticalLine(trailId = 1, lon = 0.0, baseLat = 1.0)
        assertTrue(NearbyTrailResolver.resolve(LatLng(0.0, 0.0), accuracyM = null, points = points).isEmpty())
    }

    @Test
    fun bothTrailsReturned_whenTwoInRange_nearestFirst() {
        val far = verticalLine(trailId = 1, lon = 0.0001) // ~11 m east
        val near = verticalLine(trailId = 2, lon = 0.00003) // ~3 m east
        val result = NearbyTrailResolver.resolve(LatLng(0.0001, 0.0), accuracyM = null, points = far + near)
        assertEquals(2, result.size)
        assertEquals(2L, result[0].trailId) // nearest first
        assertEquals(1L, result[1].trailId)
    }

    @Test
    fun accuracyWidensGate_admittingTrailBeyondFloor() {
        // Line ~30 m east of the user — beyond the 20 m floor but inside 2× a 20 m accuracy (40 m).
        val points = verticalLine(trailId = 1, lon = 0.00027)
        val loc = LatLng(0.0001, 0.0)

        assertTrue(NearbyTrailResolver.resolve(loc, accuracyM = null, points = points).isEmpty())

        val widened = NearbyTrailResolver.resolve(loc, accuracyM = 20.0, points = points)
        assertEquals(1, widened.size)
        assertEquals(1L, widened.single().trailId)
    }

    @Test
    fun singlePointTrail_usesPointDistance() {
        val points = listOf(tp(trailId = 1, position = 0, lat = 0.0, lon = 0.0))
        val result = NearbyTrailResolver.resolve(LatLng(0.00005, 0.0), accuracyM = null, points = points)
        val near = result.single()
        assertEquals(1L, near.trailId)
        assertEquals(0, near.nearestIndex)
    }

    @Test
    fun nearestIndex_reflectsClosestVertex_evenWhenUnordered() {
        // Points supplied out of position order; nearestIndex must be in position-sorted space.
        val points =
            listOf(
                tp(trailId = 1, position = 2, lat = 0.002, lon = 0.0),
                tp(trailId = 1, position = 0, lat = 0.0, lon = 0.0),
                tp(trailId = 1, position = 1, lat = 0.001, lon = 0.0),
            )
        // User sits beside the position-2 vertex.
        val near = NearbyTrailResolver.resolve(LatLng(0.002, 0.00001), accuracyM = null, points = points).single()
        assertNotNull(near)
        assertEquals(2, near.nearestIndex)
    }
}
