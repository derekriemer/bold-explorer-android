package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Regression test for a `Long.MIN_VALUE` sentinel overflow found in review, 2026-08-17.
 *
 * `lastOrdinaryGuidanceAtMs` used to start at `Long.MIN_VALUE`, and the throttle check was
 * `sample.timestamp - lastOrdinaryGuidanceAtMs < ORDINARY_GUIDANCE_INTERVAL_MS`. For a realistic
 * epoch-millis timestamp, `timestamp - Long.MIN_VALUE` overflows a signed 64-bit value and wraps
 * around to a large *negative* number — comfortably less than the 30 s interval — so the throttle
 * always short-circuited and ordinary guidance could never fire from a cold start.
 *
 * This was invisible until now because `resetThrottle` ran on every track-point advance, seconds
 * into any walk, which overwrote the sentinel before anyone could notice the gap. S6 removed that
 * call (grace is now measured, not re-armed, on every advance), so a follow started with no cached
 * fix would keep the sentinel — and the wrapped comparison — for the entire walk, permanently
 * silencing the course-correction cue.
 *
 * The fix: `lastOrdinaryGuidanceAtMs` is a nullable `Long?`, matching `lastOrdinaryGuidanceLocation`
 * declared beside it. Null means "nothing spoken yet", so no throttle applies.
 */
class OrdinaryGuidanceThrottleTest {
    private val active =
        TrailFollowerState.Active(
            waypoints = listOf(TrailPoint(1, "A", 0.0, 0.0), TrailPoint(2, "B", 0.001, 0.0)),
            currentIndex = 1,
            thresholdM = 10.0,
        )

    private fun guidance(relativeDeg: Double) =
        TrailGuidanceState(
            targetIndex = 1,
            targetName = "B",
            total = 2,
            distanceToTargetM = 80.0,
            desiredCourseDeg = 0.0,
            relativeDeg = relativeDeg,
            courseIsFresh = true,
        )

    private fun sample(timestampMs: Long) =
        LocationSample(
            lat = 0.0,
            lon = 0.0,
            accuracy = 5.0,
            timestamp = timestampMs,
        )

    @Test
    fun aFreshCoordinatorSpeaksItsFirstMajorCorrection_ratherThanNeverGuiding() {
        // A fresh coordinator, a follow with no prior guidance (no resetThrottle call — exactly the
        // cold-start path S6 exposed), and a fix warranting a major correction. Against the old
        // Long.MIN_VALUE sentinel this always returned null, no matter how large a realistic
        // timestamp was: the wrapped subtraction was always deeply negative.
        val c = TrailGuidanceCoordinator(TestScope())
        c.startFollow(active.waypoints.map { LatLng(it.lat, it.lon) }, TravelDirection.Forward)

        // A realistic epoch-millis timestamp — this is exactly the value the overflow was verified
        // against; a small test timestamp like 1_000 would not have exercised the bug.
        val realisticEpochMs = 1_755_000_000_000L

        val decision = c.evaluateOrdinaryGuidance(active, sample(realisticEpochMs), guidance(relativeDeg = 90.0))

        assertNotNull(
            decision,
            "a fresh coordinator with no prior guidance must be able to speak its first major " +
                "correction; returning null here means the course-correction cue never fires",
        )
    }
}
