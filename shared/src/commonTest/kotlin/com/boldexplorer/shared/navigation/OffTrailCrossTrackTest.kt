package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Off-trail detection with cross-track distance as the necessary condition.
 *
 * The defect this fixes: `evaluateOffTrail` decided purely from `abs(relativeDeg) >= 60`, the angle
 * between the user's course and the bearing to the *current target*. A stale target behind the user
 * puts that over 60° while they stand squarely on the trail, so the app announced "you may be off
 * trail" every 45 s to someone who was not.
 *
 * The rule now: cross-track over gate is **necessary**; corroborating evidence shortens how long it
 * must be sustained rather than gating whether it counts at all. The primary corroborator is signed
 * cross-track *rate*, not the bearing angle — a stale target is what caused the original defect, so
 * divergence evidence should not be routed back through it.
 *
 * Note this is not a pure false-alert reduction: it also adds true positives. A user on a wrong
 * parallel path 30 m away has a small bearing delta and is never told today.
 */
class OffTrailCrossTrackTest {
    private fun coordinator() = TrailGuidanceCoordinator(TestScope())

    /** Trail running due north from (0,0) for ~111 m. At the equator, 1° lon ≈ 111 195 m. */
    private val active =
        TrailFollowerState.Active(
            waypoints = listOf(TrailPoint(1, "A", 0.0, 0.0), TrailPoint(2, "B", 0.001, 0.0)),
            currentIndex = 1,
            thresholdM = 10.0,
        )

    private fun lonFor(m: Double) = m / 111_194.9

    private fun sample(
        timestampMs: Long,
        eastM: Double,
        accuracyM: Double = 5.0,
    ) = LocationSample(
        lat = 0.0005,
        lon = lonFor(eastM),
        accuracy = accuracyM,
        timestamp = timestampMs,
    )

    /** Guidance with the user squarely on course — the angle condition does not corroborate. */
    private fun onCourseGuidance() =
        TrailGuidanceState(
            targetIndex = 1,
            targetName = "B",
            total = 2,
            distanceToTargetM = 80.0,
            desiredCourseDeg = 0.0,
            relativeDeg = 5.0,
            courseIsFresh = true,
        )

    /** A stale target behind the user — the exact condition that caused the original defect. */
    private fun staleTargetGuidance() =
        TrailGuidanceState(
            targetIndex = 1,
            targetName = "B",
            total = 2,
            distanceToTargetM = 300.0,
            desiredCourseDeg = 0.0,
            relativeDeg = 175.0,
            courseIsFresh = true,
        )

    @Test
    fun standingOnTheLine_neverFires_evenWithAStaleTargetBehind() {
        // The defect, as a test. Ten fixes squarely on the trail with the angle condition
        // screaming; not one of them may alert.
        val c = coordinator()
        c.resetThrottle(sample(0, eastM = 0.0))
        for (i in 1..10) {
            val eval = c.evaluateOffTrail(active, sample(100_000L + i * 1_000L, eastM = 0.0), staleTargetGuidance())
            if (eval != null) {
                assertFalse(eval.fired, "fix $i fired while standing on the trail: ${eval.disposition}")
                assertTrue(
                    eval.disposition.startsWith("bail:on_line"),
                    "expected an on-line bail, got ${eval.disposition}",
                )
            }
        }
    }

    @Test
    fun divergingWellOffTrail_firesQuickly() {
        val c = coordinator()
        c.resetThrottle(sample(0, eastM = 0.0))
        c.evaluateOffTrail(active, sample(100_000, eastM = 25.0), staleTargetGuidance())
        val second = assertNotNull(c.evaluateOffTrail(active, sample(101_000, eastM = 35.0), staleTargetGuidance()))
        assertTrue(second.fired, "diverging at 35 m off should fire on the fast path, got ${second.disposition}")
    }

    @Test
    fun parallelPathWellOffTrail_firesEventually() {
        // The new true positive: constant 30 m offset, walking parallel, small bearing delta. This
        // is silent today and should not be.
        val c = coordinator()
        c.resetThrottle(sample(0, eastM = 0.0))
        val onCourse = onCourseGuidance()
        var fired = false
        for (i in 1..8) {
            val eval = c.evaluateOffTrail(active, sample(100_000L + i * 1_000L, eastM = 30.0), onCourse)
            if (eval?.fired == true) fired = true
        }
        assertTrue(fired, "a sustained parallel path 30 m off trail must eventually alert")
    }

    @Test
    fun convergingIsHeldLongerThanDiverging() {
        // Convergence may delay an alert but must never cancel one while cross-track stays over
        // gate, so this asserts the *delay*, not silence.
        //
        // Both arms use on-course guidance so the bearing angle does not corroborate in either.
        // The angle is allowed to select the fast path on its own, so leaving a stale target in
        // place would push both arms fast and the test would isolate nothing.
        val onCourse = onCourseGuidance()

        val diverging = coordinator()
        diverging.resetThrottle(sample(0, eastM = 0.0))
        diverging.evaluateOffTrail(active, sample(100_000, eastM = 25.0), onCourse)
        val d2 = assertNotNull(diverging.evaluateOffTrail(active, sample(101_000, eastM = 32.0), onCourse))

        val converging = coordinator()
        converging.resetThrottle(sample(0, eastM = 0.0))
        converging.evaluateOffTrail(active, sample(100_000, eastM = 32.0), onCourse)
        val c2 = assertNotNull(converging.evaluateOffTrail(active, sample(101_000, eastM = 30.0), onCourse))

        assertTrue(d2.fired, "diverging should have fired by the second fix: ${d2.disposition}")
        assertFalse(c2.fired, "converging should still be holding at the second fix: ${c2.disposition}")
    }

    @Test
    fun angleAloneCanSelectTheFastPath_butNeverSuppresses() {
        // The demotion, stated precisely: a corroborating angle shortens the sustain window, and a
        // non-corroborating one lengthens it — but neither can stop an over-gate fix from counting.
        val withAngle = coordinator()
        withAngle.resetThrottle(sample(0, eastM = 0.0))
        withAngle.evaluateOffTrail(active, sample(100_000, eastM = 30.0), staleTargetGuidance())
        val fast = assertNotNull(withAngle.evaluateOffTrail(active, sample(101_000, eastM = 30.0), staleTargetGuidance()))
        assertEquals(2, fast.requiredCount, "a corroborating angle selects the fast path")

        val withoutAngle = coordinator()
        withoutAngle.resetThrottle(sample(0, eastM = 0.0))
        withoutAngle.evaluateOffTrail(active, sample(100_000, eastM = 30.0), onCourseGuidance())
        val slow = assertNotNull(withoutAngle.evaluateOffTrail(active, sample(101_000, eastM = 30.0), onCourseGuidance()))
        assertEquals(5, slow.requiredCount, "no corroboration means a longer sustain, not silence")
        assertTrue(slow.consecutiveCount > 0, "the fix still counts toward the alert")
    }

    @Test
    fun gateWidensWithPoorAccuracyButIsCapped() {
        // Bad GPS must produce fewer confident alerts, not more — but the widening is bounded so a
        // wildly wrong accuracy report cannot switch off-trail detection off entirely.
        val loose = coordinator()
        loose.resetThrottle(sample(0, eastM = 0.0))
        var firedLoose = false
        for (i in 1..8) {
            val e = loose.evaluateOffTrail(active, sample(100_000L + i * 1_000L, eastM = 25.0, accuracyM = 30.0), staleTargetGuidance())
            if (e?.fired == true) firedLoose = true
        }
        assertFalse(firedLoose, "25 m off with 30 m accuracy is inside the widened gate")

        val tight = coordinator()
        tight.resetThrottle(sample(0, eastM = 0.0))
        var firedTight = false
        for (i in 1..8) {
            val e = tight.evaluateOffTrail(active, sample(100_000L + i * 1_000L, eastM = 25.0, accuracyM = 3.0), staleTargetGuidance())
            if (e?.fired == true) firedTight = true
        }
        assertTrue(firedTight, "25 m off with 3 m accuracy is comfortably outside the gate")
    }

    @Test
    fun crossTrackAndRate_areReportedForTheLog() {
        // S4 replays these logs to decide whether the angle condition adds anything over
        // cross-track rate, so both must actually be recorded.
        val c = coordinator()
        c.resetThrottle(sample(0, eastM = 0.0))
        c.evaluateOffTrail(active, sample(100_000, eastM = 25.0), staleTargetGuidance())
        val eval = assertNotNull(c.evaluateOffTrail(active, sample(101_000, eastM = 35.0), staleTargetGuidance()))
        assertNotNull(eval.crossTrackM, "cross-track must be logged")
        assertNotNull(eval.crossTrackRateMps, "cross-track rate must be logged")
        assertTrue(eval.crossTrackRateMps!! > 0.0, "moving away should read as positive divergence")
    }
}
