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
        assertTrue(FollowCuePolicy.isStraightAhead(poly, 100.0, TravelDirection.Forward))
    }

    @Test
    fun aCornerAheadIsNotStraight() {
        // 200 m north, then 200 m east. Standing 30 m before the corner, the trail ahead bends —
        // the corner is comfortably interior to the 40 m lookahead window, not sitting on its edge.
        val north = (0..10).map { LatLng(latFor(it * 20.0), 0.0) }
        val east = (1..10).map { LatLng(latFor(200.0), lonFor(it * 20.0)) }
        val poly = TrailPolyline(north + east)

        assertFalse(
            FollowCuePolicy.isStraightAhead(poly, 170.0, TravelDirection.Forward),
            "a corner 30 m ahead is not straight",
        )
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

        assertTrue(
            FollowCuePolicy.isStraightAhead(poly, 160.0, TravelDirection.Forward),
            "corner exactly at the far edge",
        )
        assertFalse(
            FollowCuePolicy.isStraightAhead(poly, 165.0, TravelDirection.Forward),
            "5 m past the edge, sagitta clears the threshold",
        )
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
            FollowCuePolicy.isStraightAhead(corner(2.0), 170.0, TravelDirection.Forward),
            FollowCuePolicy.isStraightAhead(corner(20.0), 170.0, TravelDirection.Forward),
            "same corner, different densities, different answers",
        )
    }

    @Test
    fun reverseLooksBehindNotAhead() {
        // Same query point, opposite verdict: forward's window is [170, 210] and finds the corner
        // at 200; reverse's window is [130, 170] — the straight approach already walked, with the
        // corner nowhere in it. States the asymmetry explicitly rather than merely exercising it.
        val north = (0..10).map { LatLng(latFor(it * 20.0), 0.0) }
        val east = (1..10).map { LatLng(latFor(200.0), lonFor(it * 20.0)) }
        val poly = TrailPolyline(north + east)

        assertFalse(
            FollowCuePolicy.isStraightAhead(poly, 170.0, TravelDirection.Forward),
            "forward: the corner is 30 m ahead",
        )
        assertTrue(
            FollowCuePolicy.isStraightAhead(poly, 170.0, TravelDirection.Reverse),
            "reverse: the 40 m behind 170 is the straight north leg, not the corner",
        )
    }

    @Test
    fun reverseNearTheStartShortensTheWindowRatherThanJustClamping() {
        // A short corner: north is a single 20 m segment (cumulative 0 -> 20), then east
        // continues — so the corner vertex sits at cumulative 20. A reverse follow at
        // alongTrackM = 10 is within the trail's last 40 m. Clamping the window's *start* to zero
        // without also shortening the lookahead would still ask sagittaOver about [0, 40] — the
        // corner at 20 is comfortably interior to that (0 < 20 < 40), which is the bug: judged by
        // a corner it has already walked past. Shortening the lookahead to
        // min(40, alongTrackM) = min(40, 10) = 10 gives the true window [0, 10], which the corner
        // (at 20) sits 10 m outside of.
        val north = (0..1).map { LatLng(latFor(it * 20.0), 0.0) }
        val east = (1..10).map { LatLng(latFor(20.0), lonFor(it * 20.0)) }
        val poly = TrailPolyline(north + east)

        assertTrue(
            FollowCuePolicy.isStraightAhead(poly, 10.0, TravelDirection.Reverse),
            "the corner sits 10 m outside the shortened window, not 20 m inside an unshortened one",
        )
    }
}
