package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowCuePolicyTest {
    private fun latFor(m: Double) = m / 111_194.9

    private fun lonFor(m: Double) = m / 111_194.9

    @Test
    fun aStraightStretchIsStraight() {
        val poly = TrailPolyline((0..20).map { LatLng(latFor(it * 20.0), 0.0) })
        assertTrue(FollowCuePolicy.isStraightAhead(poly, 100.0))
    }

    @Test
    fun aCornerAheadIsNotStraight() {
        // 200 m north, then 200 m east. Standing 30 m before the corner, the trail ahead bends —
        // the corner is comfortably interior to the 40 m lookahead window, not sitting on its edge.
        val north = (0..10).map { LatLng(latFor(it * 20.0), 0.0) }
        val east = (1..10).map { LatLng(latFor(200.0), lonFor(it * 20.0)) }
        val poly = TrailPolyline(north + east)

        assertFalse(FollowCuePolicy.isStraightAhead(poly, 170.0), "a corner 30 m ahead is not straight")
    }

    @Test
    fun aCornerExactlyAtTheWindowsEdgeStillReadsStraight() {
        // Not a bug to fix later: "is the next 40 m straight" is true when the corner is exactly
        // 40 m away, because the 40 m about to be walked genuinely is a straight line. sagittaOver
        // checks vertices strictly interior to its window, which is what makes it density-invariant.
        // The corner becomes interior to the window on the very next fix past 160 m, but its sagitta
        // only clears the 4 m suppression threshold a few metres further on — for this geometry, at
        // ~164 m. That gap is real, not a discontinuity: it self-corrects well before the corner is
        // reached, which is what the 30-m-out case above checks.
        val north = (0..10).map { LatLng(latFor(it * 20.0), 0.0) }
        val east = (1..10).map { LatLng(latFor(200.0), lonFor(it * 20.0)) }
        val poly = TrailPolyline(north + east)

        assertTrue(FollowCuePolicy.isStraightAhead(poly, 160.0), "corner exactly at the far edge")
        assertFalse(FollowCuePolicy.isStraightAhead(poly, 165.0), "5 m past the edge, sagitta clears the threshold")
    }

    @Test
    fun straightnessDoesNotDependOnRecordingDensity() {
        // The trap the whole redesign exists to avoid: the same path, recorded twice as densely,
        // must not answer differently.
        fun corner(spacingM: Double): TrailPolyline {
            val n = (200.0 / spacingM).toInt()
            val north = (0..n).map { LatLng(latFor(it * spacingM), 0.0) }
            val east = (1..n).map { LatLng(latFor(200.0), lonFor(it * spacingM)) }
            return TrailPolyline(north + east)
        }

        assertEquals(
            FollowCuePolicy.isStraightAhead(corner(2.0), 170.0),
            FollowCuePolicy.isStraightAhead(corner(20.0), 170.0),
            "same corner, different densities, different answers",
        )
    }
}
