package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.PI

// Port of src/composables/useBearingDistance.ts pure functions.
object BearingComputer {
    private val CARDINALS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    fun toCardinal(deg: Double): String {
        val normalized = ((deg % 360) + 360) % 360
        val idx = ((normalized + 11.25) / 22.5).toInt() % 16
        return CARDINALS[idx]
    }

    // Fuzzy relative direction label for display and TTS — trail/waypoint targeting.
    // relativeDeg is signed: positive = right, negative = left.
    fun toRelative(relativeDeg: Double): String = when {
        abs(relativeDeg) < 20   -> "straight ahead"
        relativeDeg in  20.0.. 60.0  -> "slight right"
        relativeDeg in  60.0..120.0  -> "right"
        relativeDeg in 120.0..180.0  -> "sharp right"
        relativeDeg in -60.0..-20.0  -> "slight left"
        relativeDeg in -120.0..-60.0 -> "left"
        else                         -> "sharp left"
    }

    // Alignment-mode direction label — always says left or right explicitly.
    // Never says "straight ahead"; uses "aligned" only within the deadband.
    // relativeDeg signed: positive = target is to the right, so turn right.
    fun toAlignmentRelative(relativeDeg: Double, deadbandDeg: Double = 5.0): String {
        val absDeg = abs(relativeDeg).toInt()
        return when {
            abs(relativeDeg) <= deadbandDeg -> "aligned"
            relativeDeg > 0 -> "$absDeg degrees right"
            else            -> "$absDeg degrees left"
        }
    }

    // Alignment ping pitch: 880 Hz when perfectly aligned, falls to 220 Hz at ±180°.
    // Uses the same cos-based octave mapping as computePitchHz so the ear gets
    // a continuous "closing in" sensation — higher = closer to aligned.
    fun computeAlignmentPitchHz(relativeDeg: Double): Double =
        220.0 * 2.0.pow(1.0 + cos(relativeDeg * PI / 180.0))

    // relativeDeg is the signed delta in [-180, 180].
    // 0 = 12 o'clock, 90 = 3 o'clock, -90 = 9 o'clock, ±180 = 6 o'clock.
    fun toClock(relativeDeg: Double): String {
        val normalized = ((relativeDeg % 360) + 360) % 360
        val hour = ((normalized + 15) / 30).toInt() % 12
        return "${if (hour == 0) 12 else hour} o'clock"
    }

    fun formatDistance(meters: Double, units: Units): String = when (units) {
        Units.METRIC -> if (meters >= 1000) "${"%.1f".format(meters / 1000)} km" else "${meters.roundToInt()} m"
        Units.IMPERIAL -> {
            val feet = meters * 3.28084
            if (feet >= 5280) "${"%.1f".format(feet / 5280)} mi" else "${feet.roundToInt()} ft"
        }
    }

    // Maps relativeDeg to a stereo pan value via sin:
    // 0°→0.0 (centre), ±90°→±1.0 (full R/L), ±180°→0.0 (behind, centre).
    // sin is bounded to [-1, 1] so no coercion needed.
    fun computePan(relativeDeg: Double): Float =
        sin(relativeDeg * PI / 180.0).toFloat()

    // Returns the beacon pitch in Hz — logarithmic / musical mapping:
    // 0°→880 Hz (A5, ahead), ±90°→440 Hz (A4, side), ±180°→220 Hz (A3, behind).
    // Each 90° step is exactly one octave — perceptually equal intervals.
    fun computePitchHz(relativeDeg: Double): Double =
        220.0 * 2.0.pow(1.0 + cos(relativeDeg * PI / 180.0))

    fun differenceAbs(relativeDeg: Double): Double = abs(relativeDeg)
}
