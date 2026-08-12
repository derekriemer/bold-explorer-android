package com.boldexplorer.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ADR 0001 Amendment 1 / S4c: a vertex-clamped candidate may confirm progress only while the walker
 * is within GPS accuracy of the vertex.
 *
 * Beyond that the tracker genuinely does not know where along the trail they are — an entire wedge
 * of ground shares one `alongTrackM` — so the honest state is `Uncertain`, not a `Matched` whose
 * position happens to be frozen. On the field walk of 2026-08-12 this reported `Matched` across 36
 * consecutive fixes at one unchanging along-track value while the walker moved 20–35 m between them.
 */
class ProgressTrackerVertexTest {
    /** A U with a 6 m top, so the two long legs are near-parallel and the apex wedge is wide. */
    private fun switchback() = TrailPolyline(switchbackShape(legM = 100.0, gapM = 6.0))

    /** Walks up the first leg and returns the tracker, matched, with the clock it left off at. */
    private fun walkedToTheApex(tracker: ProgressTracker): Long {
        var ts = 1_000L
        for (n in 0..95 step 5) {
            tracker.onFix(sampleAt(northM = n.toDouble(), eastM = 0.0, timestampMs = ts))
            ts += 4_000L
        }
        assertEquals(MatchState.Matched, tracker.match!!.state, "the walk up the leg should match")
        return ts
    }

    @Test
    fun aFixDeepInTheApexWedgeDoesNotConfirmProgress() {
        val tracker = ProgressTracker(switchback())
        val ts = walkedToTheApex(tracker)
        val confirmedBefore = tracker.match!!.confirmedAlongM!!

        // 20.6 m from the apex, behind it. Inside the 40 m match gate, so today this matches — and
        // reports an along-track value that an entire wedge of ground shares.
        val inWedge = tracker.onFix(sampleAt(northM = 110.0, eastM = -18.0, timestampMs = ts))

        assertEquals(ProjectionKind.VertexClamped, inWedge.chosen!!.kind, "fixture must land in the wedge")
        assertTrue(
            kotlin.math.abs(inWedge.chosen!!.crossTrackM) < 40.0,
            "fixture must be inside the match gate, or this proves nothing about the vertex rule",
        )
        assertNotEquals(MatchState.Matched, inWedge.state, "a wedge position is not a known position")
        assertEquals(confirmedBefore, inWedge.confirmedAlongM, "progress must not advance on a wedge fix")
    }

    @Test
    fun aFixCloseToTheApexStillConfirms() {
        // The complement, and the reason this is a radius rather than a ban. Rounding an apex puts
        // you within metres of it, where "you are at the apex" is true and the degeneracy is smaller
        // than the GPS error. Refusing to match there would drop the match at every switchback.
        val tracker = ProgressTracker(switchback())
        val ts = walkedToTheApex(tracker)

        val nearApex = tracker.onFix(sampleAt(northM = 103.0, eastM = -3.0, timestampMs = ts))

        assertEquals(ProjectionKind.VertexClamped, nearApex.chosen!!.kind, "fixture must land in the wedge")
        assertEquals(MatchState.Matched, nearApex.state, "close to the apex is a usable answer")
    }
}
