package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

// Port of src/composables/useBearingDistance.ts pure functions.
object BearingComputer {
    private val CARDINALS =
        listOf(
            "N",
            "NNE",
            "NE",
            "ENE",
            "E",
            "ESE",
            "SE",
            "SSE",
            "S",
            "SSW",
            "SW",
            "WSW",
            "W",
            "WNW",
            "NW",
            "NNW",
        )

    fun toCardinal(deg: Double): String {
        val normalized = ((deg % 360) + 360) % 360
        val idx = ((normalized + 11.25) / 22.5).toInt() % 16
        return CARDINALS[idx]
    }

    // Fuzzy relative direction label for display and TTS — trail/waypoint targeting.
    // relativeDeg is signed: positive = right, negative = left.
    fun toRelative(relativeDeg: Double): String =
        when {
            abs(relativeDeg) < 20 -> "straight ahead"
            relativeDeg in 20.0..60.0 -> "slight right"
            relativeDeg in 60.0..120.0 -> "right"
            relativeDeg in 120.0..180.0 -> "sharp right"
            relativeDeg in -60.0..-20.0 -> "slight left"
            relativeDeg in -120.0..-60.0 -> "left"
            else -> "sharp left"
        }

    // Alignment-mode direction label — always says left or right explicitly.
    // Never says "straight ahead"; uses "aligned" only within the deadband.
    // relativeDeg signed: positive = target is to the right, so turn right.
    fun toAlignmentRelative(
        relativeDeg: Double,
        deadbandDeg: Double = 5.0,
    ): String {
        val absDeg = abs(relativeDeg).toInt()
        return when {
            abs(relativeDeg) <= deadbandDeg -> "aligned"
            relativeDeg > 0 -> "$absDeg degrees right"
            else -> "$absDeg degrees left"
        }
    }

    // Alignment ping pitch: 880 Hz when perfectly aligned, falls to 220 Hz at ±180°.
    // Uses the same cos-based octave mapping as computePitchHz so the ear gets
    // a continuous "closing in" sensation — higher = closer to aligned.
    fun computeAlignmentPitchHz(relativeDeg: Double): Double = 220.0 * 2.0.pow(1.0 + cos(relativeDeg * PI / 180.0))

    // relativeDeg is the signed delta in [-180, 180].
    // 0 = 12 o'clock, 90 = 3 o'clock, -90 = 9 o'clock, ±180 = 6 o'clock.
    fun toClock(relativeDeg: Double): String {
        val normalized = ((relativeDeg % 360) + 360) % 360
        val hour = ((normalized + 15) / 30).toInt() % 12
        return "${if (hour == 0) 12 else hour} o'clock"
    }

    fun formatDistance(
        meters: Double,
        units: Units,
    ): String =
        when (units) {
            Units.METRIC -> {
                if (meters >= 1000) "${"%.1f".format(meters / 1000)} km" else "${meters.roundToInt()} m"
            }

            Units.IMPERIAL -> {
                val feet = meters * 3.28084
                if (feet >= 5280) "${"%.1f".format(feet / 5280)} mi" else "${feet.roundToInt()} ft"
            }
        }

    // Maps relativeDeg to a stereo pan value via sin:
    // 0°→0.0 (centre), ±90°→±1.0 (full R/L), ±180°→0.0 (behind, centre).
    // sin is bounded to [-1, 1] so no coercion needed.
    fun computePan(relativeDeg: Double): Float = sin(relativeDeg * PI / 180.0).toFloat()

    // Alignment pan, continuous across the full ±180°: warps the angle through a sub-linear power
    // curve (ALIGNMENT_PAN_CURVE < 1) before mapping through sin, instead of the old design's sine
    // scaled to fill ±45° and then hard-clamped flat beyond it. That old design had two real
    // problems, both field-confirmed: (1) a sine's own slope shrinks to near-zero approaching its
    // ±1 peak, and the scaled mapping hit that peak exactly at the 45° edge — so pan was already
    // going flat over roughly the outer half of the "high resolution" cone, well before the edge,
    // while pitch (unscaled cosine over the full 180°) kept moving; (2) the hard clamp pinned pan at
    // exactly +1 approaching 180° from one side and -1 approaching -180° from the other, so slowly
    // turning past directly-away-from-target snapped pan hard left/right in one instant.
    // This mapping fixes both: it's steepest near 0° (so centering still has plenty of resolution),
    // never truly flattens in the middle of the range, and — critically — returns to the same value
    // (0.0) approaching ±180° from both directions, exactly like the ordinary [computePan], so
    // crossing that point is inaudible instead of a jump. Lower ALIGNMENT_PAN_CURVE = more
    // resolution weighted toward dead-ahead; 1.0 would reduce this to plain sin(relativeDeg).
    private const val ALIGNMENT_PAN_CURVE = 0.6

    fun computeAlignmentPan(relativeDeg: Double): Float {
        val theta = relativeDeg.coerceIn(-180.0, 180.0)
        val warpedDeg = sign(theta) * 180.0 * (abs(theta) / 180.0).pow(ALIGNMENT_PAN_CURVE)
        return sin(warpedDeg * PI / 180.0).toFloat()
    }

    // Returns the beacon pitch in Hz — logarithmic / musical mapping:
    // 0°→880 Hz (A5, ahead), ±90°→440 Hz (A4, side), ±180°→220 Hz (A3, behind).
    // Each 90° step is exactly one octave — perceptually equal intervals.
    fun computePitchHz(relativeDeg: Double): Double = 220.0 * 2.0.pow(1.0 + cos(relativeDeg * PI / 180.0))

    fun differenceAbs(relativeDeg: Double): Double = abs(relativeDeg)
}
