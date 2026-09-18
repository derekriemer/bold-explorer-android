package com.boldexplorer.shared.navigation

/**
 * What the session knows about finishing, as [TrailFollower] needs it.
 *
 * There are two routes to a `TrailComplete` — the match says the user is at or past the far end,
 * and the radius around the last waypoint — and both ask the same question first: *has this follow
 * actually walked anywhere?* A loop's start is also its end and a follow may begin standing there,
 * so being at the end is not by itself evidence of having walked the trail.
 *
 * Carrying both halves in one value is what keeps the guard from being applied to one route and
 * forgotten on the other, which is exactly the hole this type was introduced to close.
 *
 * @property pastTheEnd the match places the user at or past the far end of the trail, where "far"
 *   depends on the declared direction. A claim about *this fix*, so it requires a confirmed match.
 * @property travelled confirmed along-track travel this session clears
 *   [NavigationPolicy.completionTravelGuardM]. A session accumulator rather than a claim about this
 *   fix, so it survives a match that has gone momentarily uncertain — which a terminus is a likely
 *   place for.
 * @property matchConfidentlyElsewhere a confirmed match places the walker along-track somewhere
 *   that clearly isn't the end — more than [NavigationPolicy.COMPLETION_CEILING_M] from it. Vetoes
 *   `TrailFollower`'s radial ("0b") completion route, which checks only raw GPS distance to the
 *   endpoint's *coordinates* and so cannot on its own distinguish genuine arrival from a
 *   self-intersecting route (a loop or lollipop whose stick crosses back near its own trailhead)
 *   physically passing near the endpoint's coordinates somewhere else along the walk. Deliberately
 *   *not* the same claim as [pastTheEnd]: this is a veto derived from weaker, more available
 *   evidence (any confirmed along-track position, not specifically an
 *   [ProjectionKind.EndpointClamped] one) — 0b exists as 0a's fallback precisely for fixes where
 *   [pastTheEnd]'s stronger claim isn't available, so requiring it here would just collapse 0b into
 *   0a. False whenever there is no confirmed match to check against, which leaves 0b's existing
 *   radial-only behaviour as the answer for a genuinely poor-confidence fix at the terminus — the
 *   scenario 0b was built for (review finding, PR #144).
 */
data class CompletionEvidence(
    val pastTheEnd: Boolean,
    val travelled: Boolean,
    val matchConfidentlyElsewhere: Boolean = false,
) {
    /** Both halves: the user is past the end *and* walked to get there. */
    val completesTheTrail: Boolean get() = pastTheEnd && travelled

    companion object {
        /** No opinion — nothing has been walked, and nothing says the end has been reached. */
        val None = CompletionEvidence(pastTheEnd = false, travelled = false)
    }
}
