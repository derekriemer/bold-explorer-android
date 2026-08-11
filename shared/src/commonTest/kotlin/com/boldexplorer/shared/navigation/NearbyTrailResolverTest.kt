package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.TrailPointRow
import kotlin.math.abs
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

    // ── TrailPosition (S0b) ─────────────────────────────────────────────────────────

    @Test
    fun resolve_carriesAProjectedTrailPosition() {
        // The point of S0b: this resolver already walked every segment and threw away which one
        // won. It now reports the projection instead of only a scalar distance.
        val points = verticalLine(trailId = 1, lon = 0.0, count = 5)
        val near = NearbyTrailResolver.resolve(LatLng(0.00015, 0.0), accuracyM = 5.0, points = points).single()
        val position = assertNotNull(near.position, "projection should be reported")
        assertTrue(position.alongTrackM > 0.0, "along-track should be inside the trail, got ${position.alongTrackM}")
        assertTrue(position.alongTrackM < 100.0, "along-track should not exceed the trail, got ${position.alongTrackM}")
    }

    @Test
    fun distanceM_agreesWithTheProjectedCrossTrack() {
        // distanceM must not drift from the projection it is now derived from.
        val points = verticalLine(trailId = 1, lon = 0.0, count = 5)
        val near = NearbyTrailResolver.resolve(LatLng(0.00015, 0.00005), accuracyM = 20.0, points = points).single()
        val position = assertNotNull(near.position)
        assertEquals(near.distanceM, abs(position.crossTrackM), 0.05, "distance is the magnitude of cross-track")
    }

    @Test
    fun crossTrackIsSigned_soSideIsRecoverable() {
        // The genuinely new capability: an unsigned distance cannot say which side of the trail
        // the user is on, so "the trail is 12 m to your right" was previously impossible.
        val points = verticalLine(trailId = 1, lon = 0.0, count = 5)
        val east = NearbyTrailResolver.resolve(LatLng(0.00015, 0.00005), accuracyM = 20.0, points = points).single()
        val west = NearbyTrailResolver.resolve(LatLng(0.00015, -0.00005), accuracyM = 20.0, points = points).single()
        assertTrue(assertNotNull(east.position).crossTrackM > 0.0, "east of a northward trail reads right-positive")
        assertTrue(assertNotNull(west.position).crossTrackM < 0.0, "west reads left-negative")
    }

    @Test
    fun singlePointTrail_stillResolves() {
        val points = listOf(tp(trailId = 1, position = 0, lat = 0.0, lon = 0.0))
        val near = NearbyTrailResolver.resolve(LatLng(0.00005, 0.0), accuracyM = 20.0, points = points).single()
        assertNotNull(near.position, "a single-point trail still has a position")
        assertEquals(0.0, assertNotNull(near.position).alongTrackM, 1e-9, "along-track is zero")
    }

}
