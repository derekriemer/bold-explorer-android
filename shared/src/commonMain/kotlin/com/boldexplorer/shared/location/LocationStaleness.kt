package com.boldexplorer.shared.location

/**
 * Pure staleness check for issue #23: distance/bearing derived from the last accepted fix can look
 * perfectly live (a beacon still pans/pitches off current heading) even though the fix underneath
 * it is old. This must be re-evaluated against a ticking clock, not just on new-fix arrival, or
 * "stale" would only ever flip retroactively once a fresh fix finally shows up — by which point
 * it's no longer stale anyway.
 */
fun isLocationStale(
    nowMs: Long,
    fixTimestampMs: Long?,
    thresholdMs: Long = DEFAULT_STALE_THRESHOLD_MS,
): Boolean = fixTimestampMs == null || nowMs - fixTimestampMs > thresholdMs

/** Long enough to not flag normal ~1s fix jitter; short enough to catch a genuine multi-fix freeze. */
const val DEFAULT_STALE_THRESHOLD_MS = 8_000L
