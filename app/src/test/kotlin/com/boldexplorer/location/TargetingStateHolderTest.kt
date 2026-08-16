package com.boldexplorer.location

import com.boldexplorer.shared.navigation.ExternalTargetRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The cross-screen targeting channel, and specifically the outcome half of it (issue #78).
 *
 * The outcome exists because the request crosses ViewModels: the screen that raised it cannot see
 * whether the GPS screen honoured it, and used to announce success at the moment of asking. For a
 * blind user that is unfalsifiable — there is no screen to glance at and no target to hear.
 */
class TargetingStateHolderTest {
    @Test
    fun aTrailEndRequestCarriesWhichEnd() {
        val holder = TargetingStateHolder()

        holder.requestTrailEndTarget(trailId = 7L, isStart = false)

        assertEquals(ExternalTargetRequest.TrailEnd(7L, isStart = false), holder.externalTarget.value)
    }

    @Test
    fun aNewRequestClearsThePreviousOutcome() {
        // The hazard this guards: the outcome is a StateFlow, so a stale `true` left over from the
        // last request would be delivered to the observer the instant it subscribes — and the
        // screen would announce success for a request that has not been answered yet. Worse than
        // the bug being fixed, because it would be right often enough to look reliable.
        val holder = TargetingStateHolder()
        holder.requestWaypointTarget(1L)
        holder.reportTargetApplied(true)
        assertEquals(true, holder.targetApplied.value)

        holder.requestTrailEndTarget(trailId = 7L, isStart = true)

        assertNull(holder.targetApplied.value, "a fresh request must have no answer yet")
    }

    @Test
    fun anUnappliedRequestIsReportedRatherThanLeftSilent() {
        val holder = TargetingStateHolder()
        holder.requestTrailEndTarget(trailId = 7L, isStart = true)

        holder.reportTargetApplied(false)

        assertEquals(false, holder.targetApplied.value, "failure has to be sayable, not absent")
    }

    @Test
    fun consumingARequestDoesNotDiscardItsOutcome() {
        // The bridge clears the request as soon as it has handled it, which must not take the
        // answer with it — the raising screen may not have read it yet.
        val holder = TargetingStateHolder()
        holder.requestTrailEndTarget(trailId = 7L, isStart = true)
        holder.reportTargetApplied(false)

        holder.clear()

        assertNull(holder.externalTarget.value)
        assertEquals(false, holder.targetApplied.value)
    }

    @Test
    fun waypointAndTrailEndRequestsAreDistinguishable() {
        // They share one channel, so the consumer must be able to tell them apart without
        // inferring from an id — the inference that broke trail endpoints in the first place.
        val holder = TargetingStateHolder()

        holder.requestWaypointTarget(42L)
        assertEquals(ExternalTargetRequest.Waypoint(42L), holder.externalTarget.value)

        holder.requestTrailEndTarget(trailId = 42L, isStart = true)
        assertEquals(ExternalTargetRequest.TrailEnd(42L, isStart = true), holder.externalTarget.value)
    }
}
