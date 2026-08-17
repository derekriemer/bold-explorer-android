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
     * Whether the trail ahead of [alongTrackM], in [direction] of travel, is straight enough for a
     * progress cue.
     *
     * Sagitta rather than accumulated turn: summing per-vertex `|Δbearing|` grows without bound
     * with recording density, and signed net turn reads zero across an S-bend that is plainly not
     * straight.
     *
     * `sagittaOver` only ever looks toward increasing along-track, so "ahead" has to be translated
     * before calling it: forward keeps today's `[alongTrackM, alongTrackM + LOOKAHEAD]`; reverse
     * walks toward decreasing along-track, so its lookahead window is the metres *behind*
     * `alongTrackM` in the trail's recorded order, `[alongTrackM - LOOKAHEAD, alongTrackM]`.
     *
     * Forward needs no symmetric shortening near its end: `sagittaOver` already clamps its own
     * `endM` to the trail's `totalLengthM`, so a forward walker close to the finish is naturally
     * handed a short window there. Reverse gets no such help from clamping the *start* alone —
     * `alongTrackM` is still the window's untouched far edge, so if only the start were clamped to
     * zero, a walker at `alongTrackM = 20` with a 40 m lookahead would still ask about `[0, 40]`,
     * 20 m of which is trail already behind them. The window has to *shrink*, not just stop at
     * zero: this close to the start, there simply isn't 40 m of "behind" left to examine, so the
     * lookahead itself is capped at whatever `alongTrackM` actually is.
     */
    fun isStraightAhead(
        polyline: TrailPolyline,
        alongTrackM: Double,
        direction: TravelDirection,
    ): Boolean {
        val (startM, lookaheadM) =
            when (direction) {
                TravelDirection.Forward -> alongTrackM to NavigationPolicy.STRAIGHT_LOOKAHEAD_M
                TravelDirection.Reverse -> {
                    val lookahead = minOf(NavigationPolicy.STRAIGHT_LOOKAHEAD_M, alongTrackM)
                    (alongTrackM - NavigationPolicy.STRAIGHT_LOOKAHEAD_M).coerceAtLeast(0.0) to lookahead
                }
            }
        return polyline.sagittaOver(startM, lookaheadM) <= NavigationPolicy.STRAIGHT_SAGITTA_M
    }
}
