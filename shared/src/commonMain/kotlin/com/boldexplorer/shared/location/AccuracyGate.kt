package com.boldexplorer.shared.location

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters

/**
 * Movement-relative accuracy gate for issue #23. Replaces a flat accuracy ceiling with two
 * complementary checks, either of which admits a fix:
 *
 * 1. **Time-based self-heal.** Tolerance tightens or loosens based on how long it's been since the
 *    last accepted fix: right after an accepted fix the tolerance is small — tighter than a flat
 *    ceiling when the user is stationary, which is the correct direction for waypoint-marking
 *    precision. The longer a fix goes unaccepted, the more the tolerance grows, self-healing back
 *    to live distance/bearing updates instead of waiting indefinitely for a fix at or under
 *    [baseAccuracyM].
 * 2. **Signal-exceeds-noise.** Independent of elapsed time: if the *reported* displacement between
 *    this fix and the last accepted position is bigger than this fix's own accuracy radius, then
 *    even in the worst case (the true position anywhere within that radius) real movement occurred
 *    — e.g. accuracy 15m but the fix is 40m from the last accepted position. That's real signal
 *    worth accepting immediately rather than waiting on the time-based threshold to catch up.
 *
 * Both checks are still capped at [absoluteCeilingM] so a long gap — or a wild single jump — can't
 * wave through an obviously-bad multipath fix.
 *
 * Stateful and single-stream: construct one instance per location stream (not shared across
 * providers), and call [evaluate] once per raw fix in arrival order.
 */
class AccuracyGate(
    private val baseAccuracyM: Float,
    private val absoluteCeilingM: Float,
    private val defaultSpeedMps: Float = DEFAULT_SPEED_MPS,
    private val minSpeedFloorMps: Float = MIN_SPEED_FLOOR_MPS,
    private val speedSafetyFactor: Float = SPEED_SAFETY_FACTOR,
) {
    private var lastAcceptedAtMs: Long? = null
    private var lastAcceptedSpeedMps: Float? = null
    private var lastAcceptedPosition: LatLng? = null

    /** [accuracyM] <= 0 means "unavailable" on some devices; treated as acceptable, as before. */
    fun evaluate(
        accuracyM: Float,
        timestampMs: Long,
        speedMps: Float?,
        lat: Double,
        lon: Double,
    ): Boolean {
        val withinCeiling = accuracyM <= 0f || accuracyM <= absoluteCeilingM
        val accepted =
            accuracyM <= 0f ||
                accuracyM <= timeBasedThreshold(timestampMs) ||
                (withinCeiling && exceedsOwnAccuracy(accuracyM, lat, lon))
        if (accepted) {
            lastAcceptedAtMs = timestampMs
            if (speedMps != null && speedMps >= 0f) lastAcceptedSpeedMps = speedMps
            lastAcceptedPosition = LatLng(lat, lon)
        }
        return accepted
    }

    private fun timeBasedThreshold(timestampMs: Long): Float {
        val lastAt = lastAcceptedAtMs ?: return baseAccuracyM
        val elapsedS = (timestampMs - lastAt).coerceAtLeast(0L) / 1000f
        val speedBoundMps =
            (lastAcceptedSpeedMps?.takeIf { it >= 0f } ?: defaultSpeedMps)
                .coerceAtLeast(minSpeedFloorMps) * speedSafetyFactor
        return (elapsedS * speedBoundMps).coerceAtMost(absoluteCeilingM)
    }

    private fun exceedsOwnAccuracy(
        accuracyM: Float,
        lat: Double,
        lon: Double,
    ): Boolean {
        val last = lastAcceptedPosition ?: return false
        val reportedDistanceM = haversineDistanceMeters(last, LatLng(lat, lon))
        return reportedDistanceM > accuracyM
    }

    companion object {
        /** Brisk-walking default when there's no recent speed sample yet (e.g. right after a cold start). */
        private const val DEFAULT_SPEED_MPS = 2.0f

        /** Guarantees the gate still self-heals even when the last known speed was ~0 (fully stationary). */
        private const val MIN_SPEED_FLOOR_MPS = 0.3f

        /** Headroom over the last observed speed for acceleration + GPS speed-reading noise. */
        private const val SPEED_SAFETY_FACTOR = 3f
    }
}
