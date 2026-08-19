package com.boldexplorer.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * ADR 0002 §3 — the anchor [FollowArming] chose seeds acquisition as a selection rule, replacing
 * the deleted `tieBreakByDirection`.
 */
class ProgressTrackerArmingTest {
    private val jittered = TrailPolyline(LollipopFixture.jittered)
    private val lollipop = TrailPolyline(LollipopFixture.points)

    private val TrailMatch.confirmed: Double get() = assertNotNull(confirmedAlongM, "no confirmed position")

    @Test
    fun aPriorAwayFromTheNearestCandidate_stillAcquiresThePriorsStrand() {
        // (0, 475): exactly on the outbound strand of the jittered fixture; the return strand is
        // 15 m away and would not be nearest even by cross-track, but a prior at the wrong end of
        // the trail must not steer acquisition there either.
        val tracker = ProgressTracker(jittered, TravelDirection.Reverse, acquisitionPriorM = 475.0)

        val match = tracker.onFix(sampleAt(northM = 475.0, eastM = 0.0, timestampMs = 0))

        assertEquals(475.0, match.confirmed, 6.0, "acquires the outbound strand the walker was armed on")
    }

    @Test
    fun theSplitBrainGuard_aPriorBindsEvenWhenGpsDriftsTowardTheOtherStrand() {
        // The walker chose the outbound strand (prior 475). Before the first fix arrives, GPS
        // drifts 12 m east — nearer the return strand (15 m east) than the outbound one (0 m east).
        // A tie-break-by-direction would follow the drift onto the wrong pass; the prior must not.
        val tracker = ProgressTracker(jittered, TravelDirection.Reverse, acquisitionPriorM = 475.0)

        val match = tracker.onFix(sampleAt(northM = 475.0, eastM = 12.0, timestampMs = 0))

        assertEquals(
            475.0,
            match.confirmed,
            6.0,
            "the prior must win even though the return strand's cross-track is smaller",
        )
    }

    @Test
    fun defaultPriorAtTheTraversalStart_reproducesTodaysForwardAcquisition() {
        // No explicit prior: on the plain (un-jittered) lollipop the junction's two passes coincide
        // exactly, so this is the tie `tieBreakByDirection` used to resolve. The default prior (0.0
        // for Forward) reproduces the old "prefer the earliest occurrence" answer.
        val tracker = ProgressTracker(lollipop, TravelDirection.Forward)

        val match = tracker.onFix(sampleAt(northM = LollipopFixture.JUNCTION_OUT_M, eastM = 0.0, timestampMs = 0))

        assertEquals(LollipopFixture.JUNCTION_OUT_M, match.confirmed, 6.0, "forward prefers the earlier pass")
    }

    @Test
    fun defaultPriorAtTheTraversalStart_reproducesTodaysReverseAcquisition() {
        // Same tie, opposite direction: the default prior for Reverse is the traversal start
        // (totalLengthM), which is nearest the *later* pass — the old "prefer the latest occurrence"
        // answer for a reverse follow.
        val tracker = ProgressTracker(lollipop, TravelDirection.Reverse)

        val match = tracker.onFix(sampleAt(northM = LollipopFixture.JUNCTION_OUT_M, eastM = 0.0, timestampMs = 0))

        assertEquals(LollipopFixture.JUNCTION_BACK_M, match.confirmed, 6.0, "reverse prefers the later pass")
    }
}
