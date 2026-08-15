package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrailGuidanceCoordinatorTest {
    private fun coordinator() = TrailGuidanceCoordinator(TestScope())

    private val active =
        TrailFollowerState.Active(
            waypoints = listOf(TrailPoint(1, "A", 0.0, 0.0), TrailPoint(2, "B", 0.001, 0.0)),
            currentIndex = 1,
            thresholdM = 10.0,
        )

    private fun guidance(
        relativeDeg: Double?,
        distanceToTargetM: Double = 50.0,
        targetIndex: Int = 1,
        total: Int = 3,
    ) = TrailGuidanceState(
        targetIndex = targetIndex,
        targetName = "B",
        total = total,
        distanceToTargetM = distanceToTargetM,
        desiredCourseDeg = 0.0,
        relativeDeg = relativeDeg,
        courseIsFresh = true,
    )

    private fun sample(
        timestampMs: Long,
        lat: Double = 0.0,
        lon: Double = 0.0,
        heading: Double? = null,
        speed: Double? = null,
        accuracy: Double? = null,
    ) = LocationSample(
        lat = lat,
        lon = lon,
        accuracy = accuracy,
        heading = heading,
        speed = speed,
        timestamp = timestampMs,
    )

    /**
     * Drives a real [ProgressTracker] beside the coordinator, as the ViewModel does.
     *
     * Both detectors read the matcher now — wrong-way its confirmed along-track (S5a), off-trail
     * the chosen candidate's cross-track (S5) — so neither can be exercised without one. One
     * tracker per coordinator: both carry per-session state.
     *
     * The tracker acquires **on the line, abeam the first evaluated fix**, a second before it —
     * which is what follow-start does. The trail here is the lon = 0 meridian, so dropping the fix's
     * longitude puts it exactly on the line at the same along-track position. Acquiring 30 m off the
     * trail instead fails the match gate outright and leaves the tracker with no prior at all, which
     * is a real gap but a different one from what these tests cover.
     */
    private inner class Harness {
        val coordinator = TrailGuidanceCoordinator(TestScope())
        private val polyline = TrailPolyline(active.waypoints.map { LatLng(it.lat, it.lon) })
        private val tracker = ProgressTracker(polyline)
        private var acquired = false

        init {
            coordinator.startFollow(polyline, TravelDirection.Forward)
        }

        fun resetThrottle(sample: LocationSample) = coordinator.resetThrottle(sample)

        private fun match(sample: LocationSample): TrailMatch {
            if (!acquired) {
                tracker.onFix(sample.copy(lon = 0.0, timestamp = sample.timestamp - 1_000))
                acquired = true
            }
            return tracker.onFix(sample)
        }

        fun evaluateBacktrack(
            followState: TrailFollowerState,
            sample: LocationSample,
            guidance: TrailGuidanceState?,
        ) = coordinator.evaluateBacktrack(followState, sample, guidance, match(sample), TravelDirection.Forward)

        fun evaluateOffTrail(
            followState: TrailFollowerState,
            sample: LocationSample,
            guidance: TrailGuidanceState?,
        ) = coordinator.evaluateOffTrail(followState, sample, guidance, match(sample))
    }

    private fun harness() = Harness()

    /**
     * A fix ~30 m east of the trail, which runs due north from (0,0).
     *
     * Off-trail detection now requires cross-track over gate as a *necessary* condition, so these
     * tests — which exercise the sustain, cooldown and reset bookkeeping rather than the geometry —
     * must place the user genuinely off the trail. Previously they sat at (0,0), squarely on it,
     * and fired purely on the bearing angle. That combination is the defect this change removes.
     */
    private fun offTrailSample(
        timestampMs: Long,
        eastM: Double = 30.0,
    ) = LocationSample(
        lat = 0.0005,
        lon = eastM / 111_194.9,
        accuracy = 5.0,
        timestamp = timestampMs,
    )

    // ── Trusted course / slow-walker heading ────────────────────────────────────────

    @Test
    fun moderateWalkingCourse_becomesAvailableAfterSmoothing() {
        val c = coordinator()

        assertNull(c.updateTrustedCourse(sample(1_000, heading = 90.0, speed = 0.6)))
        assertNull(c.updateTrustedCourse(sample(2_000, heading = 90.0, speed = 0.6)))
        val smoothed = c.updateTrustedCourse(sample(3_000, heading = 90.0, speed = 0.6))

        assertNotNull(smoothed)
        assertEquals(90.0, c.navigationHeadingDeg.value!!, absoluteTolerance = 0.1)
        assertTrue(c.courseIsSmoothed())
    }

    @Test
    fun moderateWalkingCourse_expiresBeforeSmootherTelemetry() {
        val c = coordinator()
        c.updateTrustedCourse(sample(1_000, heading = 90.0, speed = 0.6))
        c.updateTrustedCourse(sample(2_000, heading = 90.0, speed = 0.6))
        c.updateTrustedCourse(sample(3_000, heading = 90.0, speed = 0.6))

        val staleSmoothed = c.updateTrustedCourse(sample(13_001, speed = 0.0))

        assertNull(staleSmoothed)
        assertNull(c.navigationHeadingDeg.value)
        assertNotNull(c.smoothedHeading.value)
    }

    // ── Inactive / null guards ──────────────────────────────────────────────────────

    @Test
    fun evaluations_returnNull_whenFollowerInactive() {
        val h = harness()
        val s = sample(100_000)
        val g = guidance(relativeDeg = 90.0)
        assertNull(h.coordinator.evaluateOrdinaryGuidance(TrailFollowerState.Idle, s, g))
        assertNull(h.evaluateOffTrail(TrailFollowerState.Idle, s, g))
        assertNull(h.evaluateBacktrack(TrailFollowerState.Idle, s, g))
    }

    // ── Off-trail detection ─────────────────────────────────────────────────────────

    @Test
    fun offTrail_firesOnlyAfterConsecutiveThreshold() {
        val c = harness()
        val first = c.evaluateOffTrail(active, offTrailSample(100_000), guidance(relativeDeg = 90.0))
        assertNotNull(first)
        assertFalse(first.fired)
        assertEquals(1, first.consecutiveCount)
        assertTrue(first.disposition.startsWith("hold:"), first.disposition)

        val second = c.evaluateOffTrail(active, offTrailSample(101_000), guidance(relativeDeg = 90.0))
        assertNotNull(second)
        assertTrue(second.fired)
        assertEquals(2, second.consecutiveCount)
        assertTrue(second.disposition.startsWith("fire:"), second.disposition)
    }

    @Test
    fun offTrail_resetsCountWhenBackOnTheTrail() {
        // "Back on course" now means back on the *line*, not back to a small bearing delta. The
        // angle deliberately stays at 90 here to prove it no longer drives the decision.
        val c = harness()
        c.evaluateOffTrail(active, offTrailSample(100_000), guidance(relativeDeg = 90.0)) // count 1
        val back = c.evaluateOffTrail(active, offTrailSample(101_000, eastM = 0.0), guidance(relativeDeg = 90.0))
        assertNotNull(back)
        assertEquals(0, back.consecutiveCount)
        assertFalse(back.fired)
        assertTrue(back.disposition.startsWith("bail:on_line"), back.disposition)
    }

    @Test
    fun offTrail_respectsCooldownAfterFiring() {
        val c = harness()
        c.evaluateOffTrail(active, offTrailSample(100_000), guidance(relativeDeg = 90.0))
        val fired = c.evaluateOffTrail(active, offTrailSample(101_000), guidance(relativeDeg = 90.0))
        assertTrue(fired!!.fired)

        // Still off-trail 1 s later → inside the 45 s cooldown, must not re-fire.
        val cooled = c.evaluateOffTrail(active, offTrailSample(102_000), guidance(relativeDeg = 90.0))
        assertNotNull(cooled)
        assertFalse(cooled.fired)
        assertEquals("bail:cooldown_1000ms", cooled.disposition)

        // Past the 45 s cooldown → fires again. Not much past it: a gap longer than
        // RECKONING_HORIZON_S would put the tracker in Lost, where there is no window to measure a
        // cross-track in and the detector correctly declines to say anything.
        val again = c.evaluateOffTrail(active, offTrailSample(150_000), guidance(relativeDeg = 90.0))
        assertTrue(again!!.fired)
    }

    @Test
    fun offTrail_suppressedDuringGraceWindow() {
        val c = harness()
        c.resetThrottle(offTrailSample(100_000)) // arms grace until 130_000
        assertNull(c.evaluateOffTrail(active, offTrailSample(120_000), guidance(relativeDeg = 90.0)))
        // After grace expires it evaluates again.
        assertNotNull(c.evaluateOffTrail(active, offTrailSample(131_000), guidance(relativeDeg = 90.0)))
    }

    @Test
    fun offTrail_stillCountsWithNoRelativeDeg() {
        // Inverted premise. A missing bearing used to bail outright, because the angle *was* the
        // decision. It is now only corroborating evidence, so an over-gate fix still counts — it
        // simply takes the longer sustain path.
        val c = harness()
        val eval = c.evaluateOffTrail(active, offTrailSample(100_000), guidance(relativeDeg = null))
        assertNotNull(eval)
        assertFalse(eval.fired)
        assertEquals(1, eval.consecutiveCount)
        assertEquals(NavigationPolicy.OFF_TRAIL_CONSECUTIVE_SLOW, eval.requiredCount)
    }

    // ── Backtrack detection ─────────────────────────────────────────────────────────

    @Test
    fun backtrack_firesAfterConsecutiveAlongTrackRegressions() {
        // Wrong-way is decided on along-track regression, so the fix has to actually move back down
        // the trail. Previously this test held position and merely grew distanceToTargetM, which is
        // the false-positive pattern the change removes: distance grows whenever the user passes a
        // turn or the target is stale, neither of which is going the wrong way.
        val h = harness()

        // Trail runs north from (0,0); walking south is genuine regression, ~9 m per fix. The step
        // has to stay inside the matcher's window — 22 m in one second is a car, and the matcher is
        // right to refuse to track it.
        fun back(
            i: Int,
            latDeg: Double,
        ) = h.evaluateBacktrack(active, sample(100_000L + i * 1_000L, lat = latDeg, accuracy = 5.0), guidance(90.0))
        assertEquals(0, back(0, 0.00080)!!.consecutiveCount)
        assertEquals(1, back(1, 0.00072)!!.consecutiveCount)
        assertEquals(2, back(2, 0.00064)!!.consecutiveCount)
        val fired = back(3, 0.00056)
        assertNotNull(fired)
        assertTrue(fired.fired)
        assertEquals("FIRING", fired.disposition)
    }

    @Test
    fun backtrack_noiseFloorDoesNotCount() {
        val h = harness()
        h.evaluateBacktrack(active, sample(100_000, lat = 0.0004), guidance(90.0, distanceToTargetM = 100.0))
        // ~1 m back is within the 2 m noise floor → not counted as backtracking.
        val noisy = h.evaluateBacktrack(active, sample(101_000, lat = 0.00039), guidance(90.0, distanceToTargetM = 101.0))
        assertNotNull(noisy)
        assertEquals(0, noisy.consecutiveCount)
        assertFalse(noisy.fired)
    }

    @Test
    fun backtrack_suppressedDuringGraceWindow() {
        val h = harness()
        h.resetThrottle(sample(100_000)) // arms grace until 120_000
        assertNull(h.evaluateBacktrack(active, sample(110_000), guidance(90.0, distanceToTargetM = 100.0)))
        assertNotNull(h.evaluateBacktrack(active, sample(121_000), guidance(90.0, distanceToTargetM = 100.0)))
    }

    // ── Ordinary guidance throttle ────────────────────────────────────────────────────

    @Test
    fun ordinaryGuidance_firesOnMajorCorrectionThenThrottles() {
        val c = coordinator()
        // resetThrottle is always called at follow-start in production; it seeds the time + location
        // baselines (the initial Long.MIN_VALUE baseline is unusable due to subtraction overflow).
        c.resetThrottle(sample(100_000, lat = 0.0))

        // 1 s later, same spot → inside the 30 s interval, suppressed.
        assertNull(c.evaluateOrdinaryGuidance(active, sample(101_000, lat = 0.0), guidance(relativeDeg = 90.0)))

        // Past the interval but without moving 25 m → still suppressed by the distance gate.
        assertNull(c.evaluateOrdinaryGuidance(active, sample(140_000, lat = 0.0), guidance(relativeDeg = 90.0)))

        // Past interval AND moved ~111 m north (0.001°) → fires.
        val fired = c.evaluateOrdinaryGuidance(active, sample(141_000, lat = 0.001), guidance(relativeDeg = 90.0))
        assertNotNull(fired)
        assertEquals(2, fired.checkpointN)

        // Immediately again at the new spot → throttled by the 30 s interval.
        assertNull(c.evaluateOrdinaryGuidance(active, sample(142_000, lat = 0.001), guidance(relativeDeg = 90.0)))
    }

    @Test
    fun ordinaryGuidance_ignoredWhenNotMajorCorrection() {
        val c = coordinator()
        assertNull(c.evaluateOrdinaryGuidance(active, sample(100_000), guidance(relativeDeg = 10.0)))
    }

    // ── Adopting a new trail ──────────────────────────────────────────────────────────

    @Test
    fun startFollow_resetsDetectionStateFromThePreviousTrail() {
        // startFollow replaced the geometry but reset nothing, leaving the reset to resetThrottle —
        // which follow-start only reaches when there is a cached GPS fix to refresh from. Start a
        // follow cold and the new trail inherits the old one's prevAlongTrackM and counters, so the
        // first fix on trail B is differenced against a position on trail A. With the count already
        // at 2, "you may be going the wrong way" can fire on the first or second fix of a walk.
        val h = harness()
        h.evaluateBacktrack(active, sample(100_000, lat = 0.00080, accuracy = 5.0), guidance(90.0))
        h.evaluateBacktrack(active, sample(101_000, lat = 0.00072, accuracy = 5.0), guidance(90.0))
        val armed = assertNotNull(h.evaluateBacktrack(active, sample(102_000, lat = 0.00064, accuracy = 5.0), guidance(90.0)))
        assertEquals(2, armed.consecutiveCount, "precondition: the detector is one fix from firing")

        h.coordinator.startFollow(TrailPolyline(active.waypoints.map { LatLng(it.lat, it.lon) }), TravelDirection.Forward)

        val afterAdopt =
            assertNotNull(h.evaluateBacktrack(active, sample(103_000, lat = 0.00056, accuracy = 5.0), guidance(90.0)))
        assertEquals(0, afterAdopt.consecutiveCount, "a new trail must not inherit the old one's count")
        assertFalse(afterAdopt.fired)
    }

    // ── Clear ─────────────────────────────────────────────────────────────────────────

    @Test
    fun clear_resetsDetectionState() {
        val h = harness()
        h.evaluateOffTrail(active, offTrailSample(100_000), guidance(relativeDeg = 90.0)) // count 1
        h.coordinator.clear()
        // After clear, the count restarts from zero, so the next qualifying fix is count 1 again.
        val afterClear = h.evaluateOffTrail(active, offTrailSample(101_000), guidance(relativeDeg = 90.0))
        assertNotNull(afterClear)
        assertEquals(1, afterClear.consecutiveCount)
        assertNull(h.coordinator.guidance.value)
    }
}
