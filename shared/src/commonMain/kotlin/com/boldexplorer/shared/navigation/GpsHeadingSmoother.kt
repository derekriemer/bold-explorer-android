package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.model.LocationSample
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Rolling buffer of GPS course-over-ground samples that produces a
 * speed-and-recency-weighted circular mean heading.
 *
 * GPS/Android bearing is clockwise from North, so the correct unit-vector
 * decomposition is xEast = sin(rad), yNorth = cos(rad), and the inverse is
 * atan2(sumX, sumY) — not the standard math convention.
 *
 * Only fixes where both heading and speed are present and speed ≥
 * [MIN_RECORD_SPEED_MPS] are recorded. [smoothedHeading] returns null when
 * fewer than [MIN_FIXES] fresh samples exist or the vector confidence is below
 * [MIN_VECTOR_CONFIDENCE] (i.e. samples are opposing or random).
 */
class GpsHeadingSmoother(
    val windowSize: Int = DEFAULT_WINDOW_SIZE,
) {
    data class HeadingFix(
        val headingDeg: Double,
        val speedMps: Double,
        val timestampMs: Long,
    )

    private val buffer = ArrayDeque<HeadingFix>(windowSize)

    /** Last confidence that caused a rejection (low-confidence null return), for log tuning. */
    var lastRejectedConfidence: Double? = null
        private set

    /** Add a GPS fix. Ignored if heading or speed is absent or speed < [MIN_RECORD_SPEED_MPS]. */
    fun addFix(sample: LocationSample) {
        val heading = sample.heading ?: return
        val speed = sample.speed ?: return
        if (speed < MIN_RECORD_SPEED_MPS) return
        if (buffer.size >= windowSize) buffer.removeFirst()
        buffer.addLast(HeadingFix(heading, speed, sample.timestamp))
    }

    /**
     * Compute the smoothed heading as of [nowMs].
     *
     * Returns null when:
     * - Fewer than [MIN_FIXES] buffer entries are within [MAX_STALENESS_MS] of [nowMs]
     * - Computed confidence < [MIN_VECTOR_CONFIDENCE] (opposing/divergent samples)
     *
     * When returning null due to low confidence, [lastRejectedConfidence] is set for logging.
     */
    fun smoothedHeading(nowMs: Long): SmoothedHeading? {
        lastRejectedConfidence = null

        val fresh = buffer.filter { nowMs - it.timestampMs <= MAX_STALENESS_MS }
        if (fresh.size < MIN_FIXES) return null

        var sumX = 0.0   // East
        var sumY = 0.0   // North
        var totalWeight = 0.0
        var newestTimestampMs = Long.MIN_VALUE

        for (fix in fresh) {
            val recency = (1.0 - (nowMs - fix.timestampMs).toDouble() / MAX_STALENESS_MS).coerceIn(0.0, 1.0)
            val weight = fix.speedMps * recency
            if (weight <= 0.0) continue
            val rad = Math.toRadians(fix.headingDeg)
            sumX += weight * sin(rad)   // East component (CW-from-North convention)
            sumY += weight * cos(rad)   // North component
            totalWeight += weight
            if (fix.timestampMs > newestTimestampMs) newestTimestampMs = fix.timestampMs
        }

        if (totalWeight <= 0.0) return null

        val confidence = hypot(sumX, sumY) / totalWeight
        if (confidence < MIN_VECTOR_CONFIDENCE) {
            lastRejectedConfidence = confidence
            return null
        }

        val deg = normalize360(Math.toDegrees(atan2(sumX, sumY)))
        return SmoothedHeading(
            deg = deg,
            confidence = confidence,
            sampleCount = fresh.size,
            newestTimestampMs = newestTimestampMs,
        )
    }

    /** Reset the buffer. */
    fun reset() {
        buffer.clear()
        lastRejectedConfidence = null
    }

    companion object {
        const val MIN_RECORD_SPEED_MPS = 0.4
        const val MAX_STALENESS_MS = 90_000L
        const val MIN_FIXES = 3
        const val MIN_VECTOR_CONFIDENCE = 0.55
        const val DEFAULT_WINDOW_SIZE = 5

        private fun normalize360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
    }
}

/**
 * Result of [GpsHeadingSmoother.smoothedHeading].
 *
 * @param deg               Smoothed bearing in degrees [0, 360), clockwise from North.
 * @param confidence        Vector strength [0, 1]. Near 1 = consistent direction;
 *                          near 0 = opposing or random samples.
 * @param sampleCount       Number of fresh (non-stale) buffer entries that contributed.
 * @param newestTimestampMs Timestamp of the most recent contributing fix.
 */
data class SmoothedHeading(
    val deg: Double,
    val confidence: Double,
    val sampleCount: Int,
    val newestTimestampMs: Long,
)
