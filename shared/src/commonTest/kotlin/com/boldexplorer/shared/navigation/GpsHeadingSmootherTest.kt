package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.model.LocationSample
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpsHeadingSmootherTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun smoother() = GpsHeadingSmoother(windowSize = 5)

    private fun fix(
        headingDeg: Double,
        speedMps: Double = 0.6,
        timestampMs: Long = 1_000L,
    ) = LocationSample(lat = 0.0, lon = 0.0, heading = headingDeg, speed = speedMps, timestamp = timestampMs)

    /** Assert that two bearings are within [toleranceDeg] of each other (handles wrap). */
    private fun assertNearBearing(expected: Double, actual: Double, toleranceDeg: Double = 2.0) {
        val diff = abs(((actual - expected + 540.0) % 360.0) - 180.0)
        assertTrue(diff <= toleranceDeg, "Expected bearing near $expected° but got $actual° (diff=$diff°)")
    }

    // ── Recording gate ────────────────────────────────────────────────────────

    @Test
    fun noHeading_ignored() {
        val s = smoother()
        s.addFix(LocationSample(0.0, 0.0, heading = null, speed = 1.0, timestamp = 1_000))
        assertNull(s.smoothedHeading(1_000))
    }

    @Test
    fun noSpeed_ignored() {
        val s = smoother()
        s.addFix(LocationSample(0.0, 0.0, heading = 90.0, speed = null, timestamp = 1_000))
        assertNull(s.smoothedHeading(1_000))
    }

    @Test
    fun belowMinSpeed_ignored() {
        val s = smoother()
        s.addFix(fix(headingDeg = 90.0, speedMps = 0.3))
        assertNull(s.smoothedHeading(1_000))
    }

    // ── MIN_FIXES gate ────────────────────────────────────────────────────────

    @Test
    fun fewerThanMinFixes_returnsNull() {
        val s = smoother()
        s.addFix(fix(90.0, timestampMs = 1_000))
        s.addFix(fix(90.0, timestampMs = 2_000))
        assertNull(s.smoothedHeading(2_000)) // only 2 < MIN_FIXES=3
    }

    @Test
    fun exactlyMinFixes_returnsResult() {
        val s = smoother()
        s.addFix(fix(90.0, timestampMs = 1_000))
        s.addFix(fix(90.0, timestampMs = 2_000))
        s.addFix(fix(90.0, timestampMs = 3_000))
        assertNotNull(s.smoothedHeading(3_000))
    }

    // ── Wrap-around ───────────────────────────────────────────────────────────

    @Test
    fun wrapAround_355and5_averagesNearNorth() {
        val s = smoother()
        repeat(3) { i ->
            s.addFix(fix(355.0, timestampMs = 1_000L + i * 1_000))
        }
        s.addFix(fix(5.0, timestampMs = 4_000))
        s.addFix(fix(5.0, timestampMs = 5_000))
        val result = s.smoothedHeading(5_000)
        assertNotNull(result)
        // Expected ≈ 0° (due North), not 180°
        assertNearBearing(0.0, result.deg, toleranceDeg = 10.0)
    }

    @Test
    fun north_eastAverage_correctQuadrant() {
        val s = smoother()
        repeat(3) { i -> s.addFix(fix(0.0, timestampMs = 1_000L + i * 1_000)) }
        repeat(3) { i -> s.addFix(fix(90.0, timestampMs = 4_000L + i * 1_000)) }
        val result = s.smoothedHeading(6_000)
        assertNotNull(result)
        // Window=5 drops the oldest North fix, leaving 2 North + 3 East → weighted ≈ 57°.
        // Use 20° tolerance to verify the result is in the correct NE quadrant.
        assertNearBearing(45.0, result.deg, toleranceDeg = 20.0)
    }

    // ── Opposing headings → low confidence → null ─────────────────────────────

    @Test
    fun opposingHeadings_returnsNull() {
        val s = smoother()
        s.addFix(fix(0.0,   speedMps = 0.6, timestampMs = 1_000))
        s.addFix(fix(180.0, speedMps = 0.6, timestampMs = 2_000))
        s.addFix(fix(0.0,   speedMps = 0.6, timestampMs = 3_000))
        s.addFix(fix(180.0, speedMps = 0.6, timestampMs = 4_000))
        s.addFix(fix(0.0,   speedMps = 0.6, timestampMs = 5_000))
        val result = s.smoothedHeading(5_000)
        assertNull(result)
        // Rejected confidence should be recorded for logging
        assertNotNull(s.lastRejectedConfidence)
        assertTrue(s.lastRejectedConfidence!! < GpsHeadingSmoother.MIN_VECTOR_CONFIDENCE)
    }

    // ── Speed dominance ───────────────────────────────────────────────────────

    @Test
    fun fasterFixesDominate() {
        val s = smoother()
        // Three fast fixes at 90° (East), two slow fixes at 180° (South)
        s.addFix(fix(90.0,  speedMps = 0.9, timestampMs = 1_000))
        s.addFix(fix(90.0,  speedMps = 0.9, timestampMs = 2_000))
        s.addFix(fix(90.0,  speedMps = 0.9, timestampMs = 3_000))
        s.addFix(fix(180.0, speedMps = 0.45, timestampMs = 4_000))
        s.addFix(fix(180.0, speedMps = 0.45, timestampMs = 5_000))
        val result = s.smoothedHeading(5_000)
        assertNotNull(result)
        // Result should be significantly closer to 90° than 180°
        val diffTo90  = abs(((result.deg - 90.0  + 540.0) % 360.0) - 180.0)
        val diffTo180 = abs(((result.deg - 180.0 + 540.0) % 360.0) - 180.0)
        assertTrue(diffTo90 < diffTo180, "Expected result closer to 90° than 180°, got ${result.deg}°")
    }

    // ── Staleness eviction ────────────────────────────────────────────────────

    @Test
    fun staleFixesEvicted_beforeMinFixesCheck() {
        val s = smoother()
        val t0 = 1_000L
        s.addFix(fix(90.0, timestampMs = t0))
        s.addFix(fix(90.0, timestampMs = t0 + 1_000))
        s.addFix(fix(90.0, timestampMs = t0 + 2_000))
        s.addFix(fix(90.0, timestampMs = t0 + 3_000))
        // Advance clock so even the newest old fix (t0+3000) is past MAX_STALENESS_MS.
        // Need staleNow - (t0 + 3_000) > MAX_STALENESS_MS, i.e. staleNow > t0 + 93_000.
        val staleNow = t0 + GpsHeadingSmoother.MAX_STALENESS_MS + 5_000
        // Add 1 fresh fix — effective count = 1 < MIN_FIXES=3 → null
        s.addFix(fix(90.0, timestampMs = staleNow))
        assertNull(s.smoothedHeading(staleNow))
    }

    @Test
    fun freshFixesAfterStaleness_returnsResultWhenEnough() {
        val s = smoother()
        val t0 = GpsHeadingSmoother.MAX_STALENESS_MS + 10_000L
        s.addFix(fix(45.0, timestampMs = t0))
        s.addFix(fix(45.0, timestampMs = t0 + 1_000))
        s.addFix(fix(45.0, timestampMs = t0 + 2_000))
        val result = s.smoothedHeading(t0 + 2_000)
        assertNotNull(result)
        assertNearBearing(45.0, result.deg)
    }

    // ── Recency weighting ─────────────────────────────────────────────────────

    @Test
    fun recentFixesOutweighOldOnes() {
        val s = smoother()
        val t0 = 1_000L
        val halfStale = GpsHeadingSmoother.MAX_STALENESS_MS / 2
        // Three old (half-stale) fixes heading East
        s.addFix(fix(90.0, speedMps = 0.6, timestampMs = t0))
        s.addFix(fix(90.0, speedMps = 0.6, timestampMs = t0 + 100))
        s.addFix(fix(90.0, speedMps = 0.6, timestampMs = t0 + 200))
        val nowMs = t0 + halfStale + 1_000
        // Three fresh fixes heading South — same speed but more recent → heavier recency weight
        s.addFix(fix(180.0, speedMps = 0.6, timestampMs = nowMs - 2_000))
        s.addFix(fix(180.0, speedMps = 0.6, timestampMs = nowMs - 1_000))
        s.addFix(fix(180.0, speedMps = 0.6, timestampMs = nowMs))
        val result = s.smoothedHeading(nowMs)
        assertNotNull(result)
        // Fresh South fixes should pull result closer to 180° than 90°
        val diffTo90  = abs(((result.deg - 90.0  + 540.0) % 360.0) - 180.0)
        val diffTo180 = abs(((result.deg - 180.0 + 540.0) % 360.0) - 180.0)
        assertTrue(diffTo180 < diffTo90, "Expected result closer to 180°, got ${result.deg}°")
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    @Test
    fun smoothedHeading_reportsCorrectSampleCount() {
        val s = smoother()
        s.addFix(fix(45.0, timestampMs = 1_000))
        s.addFix(fix(45.0, timestampMs = 2_000))
        s.addFix(fix(45.0, timestampMs = 3_000))
        val result = s.smoothedHeading(3_000)
        assertNotNull(result)
        assertEquals(3, result.sampleCount)
    }

    @Test
    fun smoothedHeading_reportsNewestTimestamp() {
        val s = smoother()
        s.addFix(fix(45.0, timestampMs = 1_000))
        s.addFix(fix(45.0, timestampMs = 5_000))
        s.addFix(fix(45.0, timestampMs = 3_000))
        val result = s.smoothedHeading(5_000)
        assertNotNull(result)
        assertEquals(5_000L, result.newestTimestampMs)
    }

    // ── Window cap ────────────────────────────────────────────────────────────

    @Test
    fun windowCap_oldestDroppedWhenFull() {
        val s = GpsHeadingSmoother(windowSize = 3)
        // Fill with South fixes
        s.addFix(fix(180.0, speedMps = 0.9, timestampMs = 1_000))
        s.addFix(fix(180.0, speedMps = 0.9, timestampMs = 2_000))
        s.addFix(fix(180.0, speedMps = 0.9, timestampMs = 3_000))
        // Push in East fixes — oldest South fix is evicted per window
        s.addFix(fix(90.0, speedMps = 0.9, timestampMs = 4_000))
        s.addFix(fix(90.0, speedMps = 0.9, timestampMs = 5_000))
        s.addFix(fix(90.0, speedMps = 0.9, timestampMs = 6_000))
        val result = s.smoothedHeading(6_000)
        assertNotNull(result)
        // All 3 buffer slots are now East fixes
        assertNearBearing(90.0, result.deg)
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test
    fun reset_clearsBuffer() {
        val s = smoother()
        s.addFix(fix(90.0, timestampMs = 1_000))
        s.addFix(fix(90.0, timestampMs = 2_000))
        s.addFix(fix(90.0, timestampMs = 3_000))
        assertNotNull(s.smoothedHeading(3_000))
        s.reset()
        assertNull(s.smoothedHeading(3_000))
    }
}
