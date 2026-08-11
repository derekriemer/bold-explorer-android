package com.boldexplorer.shared.location

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.deltaAngle
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.geo.initialBearingDeg
import com.boldexplorer.shared.model.LocationSample
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Controlled GPS degradation for field testing.
 *
 * Switchback matching cannot be falsified under good GPS: when the fix is accurate the nearest arm
 * *is* the correct arm, so an unwindowed scan (#62) picks correctly every time. The hazard only
 * appears once positional error approaches the separation between arms, which is not a condition
 * that can be waited for.
 *
 * Note the two knobs test different things and only one of them tests #62. Inflating the *reported*
 * accuracy widens gates but leaves the fix in the right place, so the matcher never faces an
 * ambiguous choice. Moving the position is what creates ambiguity. Real multipath is also not
 * Gaussian — near a cliff it pushes consistently to one side, which is exactly what makes a fix
 * land on the wrong arm — so a constant bias is the harsher and more realistic test.
 */
class LocationDegradationTest {
    private fun sample(
        lat: Double = 40.0,
        lon: Double = -105.0,
        accuracyM: Double? = 4.0,
        timestampMs: Long = 1_000L,
    ) = LocationSample(lat = lat, lon = lon, accuracy = accuracyM, timestamp = timestampMs)

    /** Deterministic "gaussian" so tests are repeatable. */
    private fun fixedGaussian(value: Double): () -> Double = { value }

    @Test
    fun inactiveConfig_leavesTheSampleUntouched() {
        val original = sample()
        val result = DegradationConfig().apply(original, nowMs = 1_000L, nextGaussian = fixedGaussian(0.0))
        assertEquals(original, result, "an inactive config must be a no-op")
    }

    @Test
    fun lateralBias_movesTheFixTheStatedDistanceAndBearing() {
        val original = sample()
        val config = DegradationConfig(lateralBiasM = 25.0, biasBearingDeg = 90.0, expiresAtMs = 10_000L)
        val moved = config.apply(original, nowMs = 1_000L, nextGaussian = fixedGaussian(0.0))

        val from = LatLng(original.lat, original.lon)
        val to = LatLng(moved.lat, moved.lon)
        assertEquals(25.0, haversineDistanceMeters(from, to), 0.2, "bias distance")
        assertEquals(0.0, abs(deltaAngle(90.0, initialBearingDeg(from, to))), 0.5, "bias bearing is due east")
    }

    @Test
    fun accuracyOverride_replacesTheReportedAccuracy() {
        val config = DegradationConfig(accuracyOverrideM = 35.0, expiresAtMs = 10_000L)
        val result = config.apply(sample(accuracyM = 4.0), nowMs = 1_000L, nextGaussian = fixedGaussian(0.0))
        assertEquals(35.0, result.accuracy, "reported accuracy should be overridden")
    }

    @Test
    fun accuracyOverrideAlone_doesNotMoveTheFix() {
        // Stated as a test because it is the trap: inflating accuracy feels like "degrading GPS"
        // but leaves the position correct, so it cannot produce switchback ambiguity.
        val original = sample()
        val config = DegradationConfig(accuracyOverrideM = 35.0, expiresAtMs = 10_000L)
        val result = config.apply(original, nowMs = 1_000L, nextGaussian = fixedGaussian(0.0))
        assertEquals(original.lat, result.lat, 1e-12, "latitude untouched")
        assertEquals(original.lon, result.lon, 1e-12, "longitude untouched")
    }

    @Test
    fun jitter_movesTheFixByRoughlySigma() {
        val original = sample()
        // Both axes draw the same value here, so the offset is sigma * sqrt(2).
        val config = DegradationConfig(jitterSigmaM = 10.0, expiresAtMs = 10_000L)
        val moved = config.apply(original, nowMs = 1_000L, nextGaussian = fixedGaussian(1.0))
        val movedM = haversineDistanceMeters(LatLng(original.lat, original.lon), LatLng(moved.lat, moved.lon))
        assertEquals(14.1, movedM, 0.5, "two axes at 1 sigma each")
    }

    @Test
    fun biasAndJitterCompose() {
        val original = sample()
        val config =
            DegradationConfig(
                lateralBiasM = 20.0,
                biasBearingDeg = 0.0,
                jitterSigmaM = 5.0,
                expiresAtMs = 10_000L,
            )
        val moved = config.apply(original, nowMs = 1_000L, nextGaussian = fixedGaussian(1.0))
        val movedM = haversineDistanceMeters(LatLng(original.lat, original.lon), LatLng(moved.lat, moved.lon))
        assertTrue(movedM > 20.0, "jitter should add to the bias, got $movedM")
    }

    @Test
    fun expiredConfig_stopsDegrading() {
        // The safety interlock. A forgotten toggle would make real behaviour look like a bug, and
        // for a blind user there is no visual cue that it is still on.
        val original = sample()
        val config = DegradationConfig(lateralBiasM = 50.0, expiresAtMs = 5_000L)
        val afterExpiry = config.apply(original, nowMs = 5_001L, nextGaussian = fixedGaussian(0.0))
        assertEquals(original, afterExpiry, "degradation must stop itself once expired")
    }

    @Test
    fun activeReportsWhetherDegradationIsCurrentlyApplying() {
        val config = DegradationConfig(lateralBiasM = 30.0, expiresAtMs = 5_000L)
        assertTrue(config.isActiveAt(4_999L), "active before expiry")
        assertTrue(!config.isActiveAt(5_000L), "inactive at expiry")
        assertTrue(!DegradationConfig().isActiveAt(0L), "a config with no knobs set is never active")
    }
}
