package com.boldexplorer.shared.location

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccuracyGateTest {
    private fun gate() = AccuracyGate(baseAccuracyM = 10f, absoluteCeilingM = 50f)

    // Default position for tests that aren't exercising the signal-exceeds-noise path — a fixed
    // lat/lon means reported displacement is always 0, so it never accidentally satisfies
    // "displacement > accuracy" and interferes with the time-based assertions below.
    private fun AccuracyGate.evaluateAt(
        accuracyM: Float,
        timestampMs: Long,
        speedMps: Float?,
        lat: Double = 0.0,
        lon: Double = 0.0,
    ) = evaluate(accuracyM, timestampMs, speedMps, lat, lon)

    @Test
    fun firstFix_usesBaseAccuracyAsThreshold() {
        val g = gate()
        assertTrue(g.evaluateAt(accuracyM = 10f, timestampMs = 0L, speedMps = null))
    }

    @Test
    fun firstFix_worseThanBase_rejected() {
        val g = gate()
        assertFalse(g.evaluateAt(accuracyM = 11f, timestampMs = 0L, speedMps = null))
    }

    @Test
    fun unavailableAccuracy_alwaysAccepted() {
        val g = gate()
        assertTrue(g.evaluateAt(accuracyM = 0f, timestampMs = 0L, speedMps = null))
        assertTrue(g.evaluateAt(accuracyM = -1f, timestampMs = 1_000L, speedMps = null))
    }

    @Test
    fun stationaryUser_stricterThanFlatGateOnNextFix() {
        val g = gate()
        // Accept a good first fix while stationary (speed 0).
        assertTrue(g.evaluateAt(accuracyM = 5f, timestampMs = 0L, speedMps = 0f))
        // 1s later, a fix worse than the old flat 10m ceiling — but still much better than 10m —
        // should now be rejected: while stationary, tolerance is tighter than the old flat gate.
        assertFalse(g.evaluateAt(accuracyM = 9f, timestampMs = 1_000L, speedMps = null))
    }

    @Test
    fun selfHeals_toleranceGrowsTheLongerAGoodFixIsMissing() {
        val g = gate()
        assertTrue(g.evaluateAt(accuracyM = 5f, timestampMs = 0L, speedMps = 0f))
        // A 40m-accuracy fix is rejected immediately after the good fix...
        assertFalse(g.evaluateAt(accuracyM = 40f, timestampMs = 500L, speedMps = null))
        // ...but the same accuracy is eventually accepted once enough time has passed without any
        // accepted fix, rather than waiting forever for another ≤10m fix.
        assertTrue(g.evaluateAt(accuracyM = 40f, timestampMs = 60_000L, speedMps = null))
    }

    @Test
    fun toleranceNeverExceedsAbsoluteCeiling() {
        val g = gate()
        assertTrue(g.evaluateAt(accuracyM = 5f, timestampMs = 0L, speedMps = 0f))
        // A very long gap (minutes) must still cap at absoluteCeilingM, not grow unbounded.
        assertFalse(g.evaluateAt(accuracyM = 51f, timestampMs = 10 * 60 * 1_000L, speedMps = null))
        assertTrue(g.evaluateAt(accuracyM = 50f, timestampMs = 10 * 60 * 1_000L, speedMps = null))
    }

    @Test
    fun fasterRecentSpeed_widensToleranceFaster() {
        val stationary = gate()
        val moving = gate()
        assertTrue(stationary.evaluateAt(accuracyM = 5f, timestampMs = 0L, speedMps = 0f))
        assertTrue(moving.evaluateAt(accuracyM = 5f, timestampMs = 0L, speedMps = 5f))

        // Same elapsed time, same degraded accuracy fix: the faster-moving stream should have
        // widened its tolerance more, since a larger displacement is plausible.
        val stationaryAccepted = stationary.evaluateAt(accuracyM = 20f, timestampMs = 2_000L, speedMps = null)
        val movingAccepted = moving.evaluateAt(accuracyM = 20f, timestampMs = 2_000L, speedMps = null)
        assertFalse(stationaryAccepted)
        assertTrue(movingAccepted)
    }

    // ── Signal-exceeds-noise ──────────────────────────────────────────────────

    @Test
    fun reportedDisplacementBeyondAccuracy_acceptedImmediately() {
        val g = gate()
        assertTrue(g.evaluate(accuracyM = 5f, timestampMs = 0L, speedMps = 0f, lat = 0.0, lon = 0.0))
        // 1s later: accuracy 15m is worse than both the base ceiling and the tight stationary
        // time-based threshold, but the fix is ~40m from the last accepted position — farther than
        // its own accuracy radius can explain away, so it must reflect real movement.
        val farLat = 0.0 + metersToLatDegrees(40.0)
        assertTrue(g.evaluate(accuracyM = 15f, timestampMs = 1_000L, speedMps = null, lat = farLat, lon = 0.0))
    }

    @Test
    fun reportedDisplacementWithinAccuracy_stillRejected() {
        val g = gate()
        assertTrue(g.evaluate(accuracyM = 5f, timestampMs = 0L, speedMps = 0f, lat = 0.0, lon = 0.0))
        // Displacement (5m) is smaller than this fix's own accuracy (15m) — could easily be noise
        // around a stationary position, not evidence of real movement.
        val nearLat = 0.0 + metersToLatDegrees(5.0)
        assertFalse(g.evaluate(accuracyM = 15f, timestampMs = 1_000L, speedMps = null, lat = nearLat, lon = 0.0))
    }

    @Test
    fun signalExceedsNoise_stillCappedAtAbsoluteCeiling() {
        val g = gate()
        assertTrue(g.evaluate(accuracyM = 5f, timestampMs = 0L, speedMps = 0f, lat = 0.0, lon = 0.0))
        // Displacement (200m) exceeds accuracy (100m), but accuracy itself is worse than the
        // absolute ceiling (50m) — must still be rejected as an obviously-bad fix.
        val farLat = 0.0 + metersToLatDegrees(200.0)
        assertFalse(g.evaluate(accuracyM = 100f, timestampMs = 1_000L, speedMps = null, lat = farLat, lon = 0.0))
    }

    @Test
    fun noPriorAcceptedFix_signalExceedsNoiseNeverTriggers() {
        val g = gate()
        // First-ever fix has nothing to compare displacement against — falls back to baseAccuracyM.
        assertFalse(g.evaluate(accuracyM = 40f, timestampMs = 0L, speedMps = null, lat = 0.0, lon = 0.0))
    }

    /** Rough meters-to-degrees-latitude conversion, precise enough for these fixed test distances. */
    private fun metersToLatDegrees(meters: Double): Double = meters / 111_320.0
}
