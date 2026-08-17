package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals

class FollowSessionFactsTest {
    /** ~111.19 m per 0.001° of latitude. A 200 m due-north trail, 20 m spacing. */
    private fun latFor(m: Double) = m / 111_194.9

    private fun northPoints() = (0..10).map { LatLng(latFor(it * 20.0), 0.0) }

    @Test
    fun alongTrackForConvertsASegmentAndOffset() {
        // An annotation is stored as (segment, offset). Its along-track is where it actually sits,
        // and nothing downstream should have to know how that pair is spelled.
        val poly = TrailPolyline(northPoints())

        assertEquals(0.0, poly.alongTrackFor(0, 0.0), 0.5)
        assertEquals(90.0, poly.alongTrackFor(4, 10.0), 0.5, "segment 4 starts at 80 m")
    }

    @Test
    fun remainingIsMeasuredTowardTheEndYouAreWalkingTo() {
        // The whole point of the direction sign: a reverse follow counts down to the start, and
        // "how much further" must mean the same thing to the walker either way.
        val forward = FollowSession(northPoints(), TravelDirection.Forward)
        val reverse = FollowSession(northPoints(), TravelDirection.Reverse)

        assertEquals(150.0, forward.remainingM(50.0), 0.5)
        assertEquals(50.0, reverse.remainingM(50.0), 0.5)
    }

    @Test
    fun remainingNeverGoesNegativePastTheEnd() {
        val forward = FollowSession(northPoints(), TravelDirection.Forward)
        assertEquals(0.0, forward.remainingM(500.0), 0.5, "overshooting the end is still 'arrived'")
    }
}
