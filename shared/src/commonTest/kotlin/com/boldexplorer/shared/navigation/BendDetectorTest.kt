package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BendDetectorTest {
    private fun latFor(m: Double) = 40.0 + m / 111_194.9

    private fun lonOffsetFor(m: Double) = m / 111_194.9

    // Corner at along-track 100 m: comfortably inside BendTuning.DEFAULT's 150 m scan range plus
    // its 15 m baseline window, and far enough from either end for chordBearingAt's before/after
    // baselines to both resolve.

    /** 100 m north, then 200 m east — a right turn in recorded order, corner at along-track 100. */
    private fun rightAngleCorner(): TrailPolyline {
        val north = (0..5).map { LatLng(latFor(it * 20.0), -105.0) }
        val east = (1..10).map { LatLng(latFor(100.0), -105.0 + lonOffsetFor(it * 20.0)) }
        return TrailPolyline(north + east)
    }

    /** Mirror image: 100 m north, then 200 m west — a left turn in recorded order. */
    private fun leftAngleCorner(): TrailPolyline {
        val north = (0..5).map { LatLng(latFor(it * 20.0), -105.0) }
        val west = (1..10).map { LatLng(latFor(100.0), -105.0 - lonOffsetFor(it * 20.0)) }
        return TrailPolyline(north + west)
    }

    @Test
    fun aStraightTrailHasNoBend() {
        val poly = TrailPolyline((0..20).map { LatLng(latFor(it * 20.0), -105.0) })
        assertNull(BendDetector.findNextBend(poly, 0.0, TravelDirection.Forward))
    }

    @Test
    fun findsTheCornerAheadAndSignsItRight() {
        val poly = rightAngleCorner()
        val bend = BendDetector.findNextBend(poly, 0.0, TravelDirection.Forward)
        assertTrue(bend != null, "a 90 degree corner well within scan range must be found")
        assertEquals(100.0, bend!!.anchorAlongTrackM, 15.0, "anchors near the corner vertex")
        assertEquals(100.0, bend.distanceAheadM, 15.0)
        assertEquals(90.0, bend.turnDeg, 10.0, "a north-then-east corner is roughly a 90 degree right turn")
    }

    @Test
    fun findsTheCornerAheadAndSignsItLeft() {
        val poly = leftAngleCorner()
        val bend = BendDetector.findNextBend(poly, 0.0, TravelDirection.Forward)
        assertTrue(bend != null)
        assertEquals(-90.0, bend!!.turnDeg, 10.0, "a north-then-west corner is roughly a 90 degree left turn")
    }

    @Test
    fun aCornerBeyondScanRangeIsNotFound() {
        val poly = rightAngleCorner()
        val tight = BendTuning(scanRangeM = 20.0)
        assertNull(
            BendDetector.findNextBend(poly, 0.0, TravelDirection.Forward, tight),
            "the corner is 100 m ahead, well past a 20 m scan range",
        )
    }

    @Test
    fun reverseSeesTheSameCornerWithTheOppositeSign() {
        // Same geometry, walked backwards from near its far end: physically this walker turns from
        // heading west (reverse of the recorded east leg) to heading south (reverse of the recorded
        // north leg) -- a left turn, the mirror of the forward walker's right turn at the same corner.
        val poly = rightAngleCorner()
        val forward = BendDetector.findNextBend(poly, 0.0, TravelDirection.Forward)
        val reverse = BendDetector.findNextBend(poly, 220.0, TravelDirection.Reverse)

        assertTrue(forward != null && reverse != null)
        assertEquals(forward!!.anchorAlongTrackM, reverse!!.anchorAlongTrackM, 15.0, "same physical corner")
        assertTrue(forward.turnDeg > 0, "forward: right turn")
        assertTrue(reverse.turnDeg < 0, "reverse: the same corner mirrors to a left turn")
    }

    @Test
    fun distanceAheadIsMeasuredFromTheCurrentPosition() {
        val poly = rightAngleCorner()
        val near = BendDetector.findNextBend(poly, 50.0, TravelDirection.Forward)
        assertTrue(near != null)
        assertEquals(50.0, near!!.distanceAheadM, 15.0, "50 m short of the corner at 100")
    }

    @Test
    fun reportsTheNearerOfTwoTurnsNotWhicheverAScanHappensToFindFirst() {
        // Field bug (2026-09-02): a right turn shortly before a left one on the same stretch kept
        // being reported out of order -- the earlier sliding-window scan could cross its threshold
        // from a farther, sharper bend's partial capture before a nearer bend's window was centred
        // well enough on it to register. The fix walks actual vertices nearest-first and requires
        // each to be its own window's peak, which structurally can't return a farther vertex ahead
        // of a nearer qualifying one.
        val north = (0..3).map { LatLng(latFor(it * 20.0), -105.0) } // corner 1 (right) at 60
        val east = (1..5).map { LatLng(latFor(60.0), -105.0 + lonOffsetFor(it * 20.0)) } // to 160
        // corner 2 (left) at 160
        val north2 = (1..3).map { LatLng(latFor(60.0 + it * 20.0), -105.0 + lonOffsetFor(100.0)) }
        val poly = TrailPolyline(north + east + north2)

        val bend = BendDetector.findNextBend(poly, 0.0, TravelDirection.Forward)
        assertTrue(bend != null)
        assertEquals(60.0, bend!!.anchorAlongTrackM, 15.0, "the nearer corner (60) must win, not the farther one (160)")
        assertTrue(bend.turnDeg > 0, "the nearer corner is the right turn")
    }
}
