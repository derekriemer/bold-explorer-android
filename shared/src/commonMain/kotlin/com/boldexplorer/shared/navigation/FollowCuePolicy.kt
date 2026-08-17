package com.boldexplorer.shared.navigation

/**
 * Whether a cue should be offered at all, as against what it should say (ADR 0001, S6).
 *
 * The straightness test is suppression only, which is why it needs none of S8's apparatus — no
 * lookahead derived from a time budget, no anchoring to the curvature maximum, no already-announced
 * set. Being wrong on one fix costs one skipped beep, and skipping is idempotent. S8 will answer a
 * harder question with the same primitive.
 */
object FollowCuePolicy {
    /**
     * Whether the trail ahead of [alongTrackM] is straight enough for a progress cue.
     *
     * Sagitta rather than accumulated turn: summing per-vertex `|Δbearing|` grows without bound
     * with recording density, and signed net turn reads zero across an S-bend that is plainly not
     * straight.
     */
    fun isStraightAhead(
        polyline: TrailPolyline,
        alongTrackM: Double,
    ): Boolean =
        polyline.sagittaOver(alongTrackM, NavigationPolicy.STRAIGHT_LOOKAHEAD_M) <=
            NavigationPolicy.STRAIGHT_SAGITTA_M
}
