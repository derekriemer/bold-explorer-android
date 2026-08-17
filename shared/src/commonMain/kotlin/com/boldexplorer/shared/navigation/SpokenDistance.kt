package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units
import kotlin.math.roundToInt

private const val FEET_PER_METRE = 3.28084
private const val FEET_PER_MILE = 5280.0
private const val METRES_PER_KM = 1000.0

/**
 * A distance as it should be *spoken*, in the user's units.
 *
 * Distinct from [BearingComputer.formatDistance], which writes "500 m" for a label. These are heard,
 * so units are words: a TTS engine's rendering of "km" is not something to leave to chance.
 *
 * Metres are the internal unit everywhere in this codebase and are spoken nowhere; this is the only
 * place the conversion happens, so iOS inherits it rather than reimplementing it.
 */
fun formatSpokenDistance(
    meters: Double,
    units: Units,
): String =
    when (units) {
        Units.METRIC ->
            if (meters < METRES_PER_KM) {
                "${meters.roundToInt()} meters"
            } else {
                "${formatOneDecimal(meters / METRES_PER_KM)} kilometers"
            }

        Units.IMPERIAL -> {
            val feet = meters * FEET_PER_METRE
            // Switches at a mile, not at a thousand feet: the threshold and the divisor have to be
            // the same quantity or the first mile speaks as a fraction of itself.
            if (feet < FEET_PER_MILE) {
                "${feet.roundToInt()} feet"
            } else {
                "${formatOneDecimal(feet / FEET_PER_MILE)} miles"
            }
        }
    }

/** One decimal place without `String.format`, which is JVM-only and this is commonMain. */
private fun formatOneDecimal(value: Double): String {
    val tenths = (value * 10.0).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}
