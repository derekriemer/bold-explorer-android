package com.boldexplorer.shared.navigation

/** A change in whether the follow knows where the walker is (ADR 0001, S6). */
sealed interface MatchStateCue {
    object Lost : MatchStateCue

    object Reacquired : MatchStateCue
}

/**
 * Turns per-fix match states into the two transitions worth saying out loud.
 *
 * [isLost] is the continuous state the earcon reads, so sound says "I do not know where you are"
 * for as long as that is true. Speech marks only the change. Both halves matter: an unchanged
 * earcon during a lost match reproduces a failure already met in the field, where audio implied
 * everything was fine while the app had stopped knowing anything.
 *
 * Loss and reacquisition share the same [NavigationPolicy.MATCH_LOST_SUSTAIN] sustain requirement
 * (#82): a marginal `Matched` fix right at the acceptance gate is exactly as flap-prone as a
 * marginal loss, so announcing "back on the trail" on a single fix chattered the same way an
 * unsustained loss would have — ADR 0001 S6 called for the sustain requirement without saying it
 * was one-directional.
 */
class MatchStateCueProducer {
    private var consecutiveNotMatched = 0
    private var consecutiveMatched = 0

    /** Whether the match is currently considered lost — the state the earcon renders. */
    var isLost: Boolean = false
        private set

    fun onFix(state: MatchState): MatchStateCue? {
        if (state == MatchState.Matched) {
            consecutiveNotMatched = 0
            if (!isLost) {
                return null
            }
            consecutiveMatched++
            if (consecutiveMatched >= NavigationPolicy.MATCH_LOST_SUSTAIN) {
                isLost = false
                consecutiveMatched = 0
                return MatchStateCue.Reacquired
            }
            return null
        }

        consecutiveMatched = 0
        consecutiveNotMatched++
        if (!isLost && consecutiveNotMatched >= NavigationPolicy.MATCH_LOST_SUSTAIN) {
            isLost = true
            return MatchStateCue.Lost
        }
        return null
    }
}
