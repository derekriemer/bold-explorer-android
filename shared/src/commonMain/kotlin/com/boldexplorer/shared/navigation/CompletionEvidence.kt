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
 */
data class CompletionEvidence(
    val pastTheEnd: Boolean,
    val travelled: Boolean,
) {
    /** Both halves: the user is past the end *and* walked to get there. */
    val completesTheTrail: Boolean get() = pastTheEnd && travelled

    companion object {
        /** No opinion — nothing has been walked, and nothing says the end has been reached. */
        val None = CompletionEvidence(pastTheEnd = false, travelled = false)
    }
}
