package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units
import kotlin.math.abs

/**
 * An annotation placed on the trail being followed (ADR 0001, S5b/S6).
 *
 * [alongTrackM] comes from `TrailPolyline.alongTrackFor` over the stored `(segment, offset)` pair;
 * [signedCrossTrackM] is positive to the right of recorded order, so the *walker's* left and right
 * follow from the travel direction rather than from the recording.
 */
data class RouteAnnotation(
    val id: Long,
    val name: String,
    val alongTrackM: Double,
    val signedCrossTrackM: Double,
)

/**
 * Announces annotations as the walker comes up on them.
 *
 * Announced on approach rather than on arrival: these are benches, gates and parking areas — things
 * a walker may want to stop at — and a cue that arrives as you draw level has already cost you the
 * chance. The lead is a *time* budget because lead time is what the user experiences, and walking
 * speed varies with terrain and tiredness far more than the geometry does.
 */
class AnnotationCueProducer(
    private val annotations: List<RouteAnnotation>,
    private val direction: TravelDirection,
) {
    private val announced = mutableSetOf<Long>()

    fun onFix(
        alongTrackM: Double,
        speedMps: Double?,
        units: Units,
    ): List<String> {
        val leadM =
            ((speedMps ?: 0.0) * NavigationPolicy.ANNOTATION_LEAD_SECONDS)
                .coerceIn(NavigationPolicy.ANNOTATION_LEAD_MIN_M, NavigationPolicy.ANNOTATION_LEAD_MAX_M)

        return annotations
            .filter { it.id !in announced }
            .filter { withinLead(it, alongTrackM, leadM) }
            .sortedBy { aheadM(it, alongTrackM) }
            .map { annotation ->
                announced += annotation.id
                phrase(annotation, units)
            }
    }

    /** Distance still to walk before drawing level, negative once past. Direction-signed. */
    private fun aheadM(
        annotation: RouteAnnotation,
        alongTrackM: Double,
    ): Double = (annotation.alongTrackM - alongTrackM) * direction.sign

    private fun withinLead(
        annotation: RouteAnnotation,
        alongTrackM: Double,
        leadM: Double,
    ): Boolean {
        val ahead = aheadM(annotation, alongTrackM)
        // Fires from the lead distance right through to level. Past that it is Task 6's problem.
        return ahead in 0.0..leadM
    }

    /**
     * Marks crossed while the match was lost, inferred from the along-track jump at reacquisition.
     *
     * Hedged, because the app did not see the crossing — it is reading it off the jump. Gated on the
     * rejoin being believable: [predictionErrorM] is non-null exactly when the reacquisition landed
     * somewhere unpredicted, and a rejoin elsewhere on the trail would make these claims false rather
     * than merely late.
     */
    fun onReacquired(
        fromAlongTrackM: Double,
        toAlongTrackM: Double,
        predictionErrorM: Double?,
        units: Units,
    ): List<String> {
        if (predictionErrorM != null && predictionErrorM > NavigationPolicy.REJOIN_TRUSTED_ERROR_M) {
            return emptyList()
        }
        val lo = minOf(fromAlongTrackM, toAlongTrackM)
        val hi = maxOf(fromAlongTrackM, toAlongTrackM)

        return annotations
            .filter { it.id !in announced && it.alongTrackM in lo..hi }
            .sortedBy { aheadM(it, toAlongTrackM) }
            .map { annotation ->
                announced += annotation.id
                val behindM = abs(toAlongTrackM - annotation.alongTrackM)
                val distance = formatSpokenDistance(behindM, units)
                "You passed ${annotation.name}, $distance back, on your ${side(annotation)}"
            }
    }

    /** Right of recorded order is the walker's left on a reverse follow. */
    private fun side(annotation: RouteAnnotation): String =
        if (annotation.signedCrossTrackM * direction.sign >= 0.0) "right" else "left"

    private fun phrase(
        annotation: RouteAnnotation,
        units: Units,
    ): String {
        val distance = formatSpokenDistance(abs(annotation.signedCrossTrackM), units)
        val side = side(annotation)
        return if (abs(annotation.signedCrossTrackM) > NavigationPolicy.ANNOTATION_ASIDE_M) {
            "Off to your $side, $distance: ${annotation.name}"
        } else {
            "${annotation.name} ahead, $distance, on your $side"
        }
    }
}
