package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.LocalFrame
import com.boldexplorer.shared.geo.haversineDistanceMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the cumulative along-trail coordinate.
 *
 * `alongTrackM` and `cumulativeM` must share one coordinate system — computing lengths by haversine
 * while projecting in-frame is the seam that exists in the current code and must not be rebuilt one
 * layer up. So these assert in-frame cumulative distance against haversine within a tolerance that
 * reflects the measured frame error, rather than asserting the arrays are self-consistent (which
 * they would be even if the whole scale were wrong).
 */
class TrailPolylineTest {
    /** ~111.2 m per 0.001° of latitude. A due-north ladder makes hand-checking easy. */
    private fun northLadder(count: Int): List<LatLng> = (0 until count).map { LatLng(40.0 + it * 0.001, -105.0) }

    @Test
    fun cumulativeM_startsAtZero() {
        val poly = TrailPolyline(northLadder(4))
        assertEquals(0.0, poly.cumulativeM[0], 1e-9, "first vertex is the origin of the coordinate")
    }

    @Test
    fun cumulativeM_isMonotonicallyIncreasing() {
        val poly = TrailPolyline(northLadder(6))
        for (i in 1 until poly.size) {
            assertTrue(
                poly.cumulativeM[i] > poly.cumulativeM[i - 1],
                "cumulative must increase: [$i]=${poly.cumulativeM[i]} vs [${i - 1}]=${poly.cumulativeM[i - 1]}",
            )
        }
    }

    @Test
    fun cumulativeM_matchesHaversineAccumulation() {
        val pts = northLadder(5)
        val poly = TrailPolyline(pts)
        var expected = 0.0
        for (i in 1 until pts.size) {
            expected += haversineDistanceMeters(pts[i - 1], pts[i])
            assertEquals(expected, poly.cumulativeM[i], 0.05, "cumulative at vertex $i")
        }
    }

    @Test
    fun totalLengthM_isTheLastCumulativeValue() {
        val poly = TrailPolyline(northLadder(5))
        assertEquals(poly.cumulativeM.last(), poly.totalLengthM, 1e-9, "total length")
    }

    @Test
    fun singlePoint_hasZeroLengthAndNoSegments() {
        val poly = TrailPolyline(listOf(LatLng(40.0, -105.0)))
        assertEquals(0.0, poly.totalLengthM, 1e-9, "total length")
        assertEquals(1, poly.size, "size")
        assertEquals(0, poly.segmentCount, "segment count")
    }

    @Test
    fun segmentCount_isOneFewerThanPointCount() {
        assertEquals(4, TrailPolyline(northLadder(5)).segmentCount, "segment count")
    }

    @Test
    fun emptyPointList_isRejected() {
        // A polyline with no geometry has no coordinate system to offer; fail loudly at
        // construction rather than returning NaN from every accessor later.
        //
        // An explicit frame is passed deliberately: with the default frame this would pass even
        // without TrailPolyline's own guard, because LocalFrame.centredOn rejects an empty list
        // first. Supplying the frame is what makes this test about TrailPolyline.
        val frame = LocalFrame(LatLng(40.0, -105.0))
        assertFailsWith<IllegalArgumentException> { TrailPolyline(emptyList(), frame) }
    }

    @Test
    fun duplicatePoints_produceZeroLengthSegmentsWithoutBreakingMonotonicity() {
        // Real recorded tracks contain repeated fixes when the user stands still. Cumulative must
        // not decrease, and must not produce NaN.
        val p = LatLng(40.0, -105.0)
        val poly = TrailPolyline(listOf(p, p, LatLng(40.001, -105.0)))
        assertTrue(poly.cumulativeM.all { it.isFinite() }, "no NaN from zero-length segments")
        assertEquals(0.0, poly.cumulativeM[1], 1e-9, "zero-length segment adds nothing")
        assertTrue(poly.totalLengthM > 100.0, "the real segment still counts")
    }

    @Test
    fun sharedFrame_producesComparableCoordinates() {
        // The affordance taken for #59: two polylines constructed against one frame must measure
        // in the same coordinate system, which is what junction geometry will require.
        val a = northLadder(3)
        val b = (0 until 3).map { LatLng(40.0 + it * 0.001, -104.999) }
        val shared = LocalFrame.centredOn(a + b)
        val polyA = TrailPolyline(a, shared)
        val polyB = TrailPolyline(b, shared)
        assertEquals(polyA.totalLengthM, polyB.totalLengthM, 0.05, "parallel ladders are equal length")
        assertEquals(shared.origin.lat, polyA.frame.origin.lat, 1e-12, "frame is shared, not rebuilt")
        assertEquals(shared.origin.lat, polyB.frame.origin.lat, 1e-12, "frame is shared, not rebuilt")
    }

    // ── worstDepartureOver (ADR 0001, S8) ───────────────────────────────────────────────────────

    private fun cornerLatFor(m: Double) = 40.0 + m / 111_194.9

    private fun cornerLonOffsetFor(m: Double) = m / 111_194.9

    @Test
    fun worstDepartureOver_isNullOnAStraightStretch() {
        val poly = TrailPolyline((0..20).map { LatLng(cornerLatFor(it * 20.0), -105.0) })
        assertEquals(null, poly.worstDepartureOver(0.0, 200.0), "nothing departs from a straight line")
    }

    @Test
    fun worstDepartureOver_anchorsToTheCornerVertexAndSignsLeftForARightTurn() {
        // 200 m north, then 200 m east — a right turn when walked in recorded order. The corner
        // vertex sits on the *left* of the straight start-to-end chord: cutting the corner (going
        // further before turning to reach the same endpoint) always bulges opposite the turn's own
        // direction, so a right turn's apex is left-of-chord and vice versa. crossTrackRightM is
        // right-positive for the chord, so that apex is negative here.
        val north = (0..10).map { LatLng(cornerLatFor(it * 20.0), -105.0) }
        val east = (1..10).map { LatLng(cornerLatFor(200.0), -105.0 + cornerLonOffsetFor(it * 20.0)) }
        val poly = TrailPolyline(north + east)

        val bend = poly.worstDepartureOver(0.0, 400.0)
        assertTrue(bend != null, "the corner departs from the start-to-end chord")
        assertEquals(200.0, bend!!.alongTrackM, 1.0, "anchors to the corner vertex, not merely somewhere in range")
        assertTrue(bend.departureM < 0.0, "a right turn's apex is left of the chord (negative)")
    }

    @Test
    fun worstDepartureOver_signsPositiveForALeftTurn() {
        // Mirror image of the corner above: 200 m north, then 200 m west.
        val north = (0..10).map { LatLng(cornerLatFor(it * 20.0), -105.0) }
        val west = (1..10).map { LatLng(cornerLatFor(200.0), -105.0 - cornerLonOffsetFor(it * 20.0)) }
        val poly = TrailPolyline(north + west)

        val bend = poly.worstDepartureOver(0.0, 400.0)
        assertTrue(bend != null)
        assertTrue(bend!!.departureM > 0.0, "a left turn's apex is right of the chord (positive)")
    }
}
