package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units
import kotlin.math.abs
import kotlin.math.roundToInt

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

    // Returns -1 (left), 0 (aligned), or 1 (right).
    fun computePan(relativeDeg: Double): Int = when {
        relativeDeg > 0 -> 1
        relativeDeg < 0 -> -1
        else -> 0
    }

    fun differenceAbs(relativeDeg: Double): Double = abs(relativeDeg)
}
