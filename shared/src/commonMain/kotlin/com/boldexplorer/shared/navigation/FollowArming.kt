package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import kotlin.math.abs

/** Why an anchor is where it is, so the caller can phrase the start announcement. */
enum class ArmReason {
    /** The walker's fix projected onto the trail, and geometry chose from there. */
    OnTrail,

    /** No fix was available at arming time; the anchor is the traversal's start. */
    NoFix,

    /** A fix was available but no part of the trail was within the match gate of it. */
    OffTrail,
}

/** One continuation offered to the walker when the trail passes their position more than once. */
data class AnchorOption(
    val anchor: TrailPosition,
    val remainingM: Double,
    val revisitsStartPoint: Boolean,
)

sealed interface ArmingResult {
    data class Resolved(val anchor: TrailPosition, val reason: ArmReason) : ArmingResult

    data class Fork(val options: List<AnchorOption>) : ArmingResult {
        init {
            require(options.size >= 2) { "a fork with ${options.size} option(s) is a Resolved" }
        }
    }
}

/** The arming constants, as a parameter so a test can vary one. Mirrors [MatchTuning]'s reason for existing. */
data class ArmingTuning(
    val samePathBaseM: Double = NavigationPolicy.SAME_PATH_BASE_M,
    val samePathAccuracyFactor: Double = NavigationPolicy.SAME_PATH_ACCURACY_FACTOR,
    val samePathCapM: Double = NavigationPolicy.SAME_PATH_CAP_M,
) {
    /** The same-ground radius for a fix of this quality. Widens with poor accuracy; never narrows. */
    fun samePathM(accuracyM: Double?): Double =
        NavigationPolicy.widenWithAccuracy(samePathBaseM, samePathAccuracyFactor, accuracyM, samePathCapM)

    companion object {
        val DEFAULT = ArmingTuning()
    }
}

object FollowArming {
    /**
     * Where a follow of [polyline] in [direction] begins, given the walker is at [location].
     *
     * The single arming decision (ADR 0002 §1). Both the matcher and the follower are seeded from
     * what this returns, which is what stops them anchoring a walk differently.
     */
    fun resolve(
        polyline: TrailPolyline,
        direction: TravelDirection,
        location: LatLng?,
        accuracyM: Double?,
        tuning: ArmingTuning = ArmingTuning.DEFAULT,
    ): ArmingResult {
        val traversalStart = traversalStart(polyline, direction)

        // 1. No fix: the traversal's own start is the only defensible answer.
        if (location == null) return ArmingResult.Resolved(traversalStart, ArmReason.NoFix)

        // 2 + 3. Every local minimum, gated by what the matcher itself would accept.
        val gateM =
            NavigationPolicy.widenWithAccuracy(
                baseM = NavigationPolicy.MATCH_GATE_BASE_M,
                factor = NavigationPolicy.MATCH_GATE_ACCURACY_FACTOR,
                accuracyM = accuracyM,
                capM = NavigationPolicy.MATCH_GATE_CAP_M,
            )
        val gated = polyline.candidates(location, window = null).filter { abs(it.crossTrackM) <= gateM }
        if (gated.isEmpty()) return ArmingResult.Resolved(traversalStart, ArmReason.OffTrail)

        // 4. Same ground: strands near enough to `best` to be one path recorded twice. Measured
        //    between the candidates themselves — the question is whether two pieces of stored
        //    geometry are the same path, not how far either is from the walker.
        val best = gated.minByOrNull { abs(it.crossTrackM) }!!
        val samePathM = tuning.samePathM(accuracyM)
        val samePath = gated.filter { haversineDistanceMeters(best.snapped, it.snapped) <= samePathM }

        // 5. A candidate with nothing left ahead of it would complete the follow on its first fix.
        //    Where that empties the list the walker is standing at the finish, which is an answer.
        val completionRadiusM = NavigationPolicy.completionRadiusM(accuracyM)
        val live = samePath.filter { remainingM(polyline, direction, it.alongTrackM) > completionRadiusM }
        val survivors = live.ifEmpty { listOf(best) }

        // 6. One continuation: nothing to ask.
        if (survivors.size == 1) return ArmingResult.Resolved(survivors.single(), ArmReason.OnTrail)

        // 7. More than one: the walker chooses, always.
        val ordered = survivors.sortedBy { it.alongTrackM }
        return ArmingResult.Fork(
            ordered.map { candidate ->
                AnchorOption(
                    anchor = candidate,
                    remainingM = remainingM(polyline, direction, candidate.alongTrackM),
                    revisitsStartPoint = ordered.any { isAheadOf(direction, it.alongTrackM, candidate.alongTrackM) },
                )
            },
        )
    }

    /** Trail remaining ahead of [alongTrackM] toward the end being walked to. Mirrors [FollowSession.remainingM]. */
    private fun remainingM(
        polyline: TrailPolyline,
        direction: TravelDirection,
        alongTrackM: Double,
    ): Double =
        when (direction) {
            TravelDirection.Forward -> polyline.totalLengthM - alongTrackM
            TravelDirection.Reverse -> alongTrackM
        }.coerceAtLeast(0.0)

    private fun isAheadOf(
        direction: TravelDirection,
        candidateM: Double,
        ofM: Double,
    ): Boolean =
        when (direction) {
            TravelDirection.Forward -> candidateM > ofM
            TravelDirection.Reverse -> candidateM < ofM
        }

    /**
     * The traversal's own start, as a projected position.
     *
     * Windowed to the end being asked for, because on a loop the two ends are the *same ground*:
     * projecting the last vertex of a closed trail without a window returns along-track 0, so a
     * reverse follow with no fix would arm at the trail's start and be complete before it began.
     *
     * `project` cannot return null for a window that contains a whole segment, and a [TrailPolyline]
     * cannot be built from an empty point list.
     */
    private fun traversalStart(
        polyline: TrailPolyline,
        direction: TravelDirection,
    ): TrailPosition {
        val totalM = polyline.totalLengthM
        val endM = if (direction == TravelDirection.Forward) 0.0 else totalM
        val window =
            when (direction) {
                TravelDirection.Forward -> 0.0..(totalM / 2.0)
                TravelDirection.Reverse -> (totalM / 2.0)..totalM
            }
        return polyline.project(polyline.positionAt(endM), window)!!
    }
}
