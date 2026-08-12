package com.boldexplorer.shared.navigation

/**
 * The S4 continuous-matching constants, as a value rather than as compile-time constants.
 *
 * ## Why these are injectable when the rest of [NavigationPolicy] is not
 *
 * The other thresholds in [NavigationPolicy] were tuned against lived experience of the app. These
 * thirteen were invented — ADR 0001 specifies the *shape* of every rule here (a gate that widens
 * with accuracy, a budget bounded in both metres and seconds, a corroboration distance) and the
 * numeric value of none of them. The ADR is explicit that nothing may depend on them until field
 * evidence exists.
 *
 * Field evidence now exists: one walk, logged per-fix with the raw fixes attached, is a corpus that
 * candidate values can be swept against offline. That sweep is only possible if the values are
 * parameters. As `const val` reads inside `ProgressTracker`, evaluating a different
 * `RECKONING_HORIZON_S` meant editing the source and rebuilding — which makes comparing thirteen
 * interacting constants across a 1886-fix walk impractical, and comparing *combinations* of them
 * hopeless.
 *
 * The defaults are exactly the [NavigationPolicy] values, so production behaviour is unchanged and
 * `NavigationPolicy` stays the single place a tuned value gets written down. This type is the seam
 * for the replay harness, not a second source of truth.
 *
 * @property matchGateBaseM cross-track distance inside which a candidate is accepted at good
 *   accuracy.
 * @property matchGateAccuracyFactor how much reported accuracy widens that gate.
 * @property matchGateCapM ceiling on the widened gate. **Suspect**: the field walk showed the
 *   matcher reporting `Matched` at 56 m cross-track under degraded accuracy, while the live follower
 *   was simultaneously announcing off-trail at [NavigationPolicy.OFF_TRAIL_FAR_M] = 60 m.
 * @property vertexAcceptBaseM how close to a vertex a vertex-clamped projection must be to confirm
 *   progress, before accuracy widening. See [NavigationPolicy.VERTEX_ACCEPT_BASE_M].
 * @property vertexAcceptAccuracyFactor how much reported accuracy widens that radius.
 * @property vertexAcceptCapM ceiling on it.
 * @property budgetAccuracyFactor how much reported accuracy widens the search window.
 * @property budgetElapsedCapS ceiling on the elapsed term, so a long gap cannot make the window
 *   unbounded.
 * @property minMaxSpeedMps floor on the assumed speed bound, so a stationary user still gets a
 *   usable window.
 * @property speedMarginFactor headroom over observed speed.
 * @property speedEmaAlpha smoothing on the speed estimate.
 * @property candidateTieM how close two candidates' cross-track distances must be before direction
 *   breaks the tie rather than distance.
 * @property reckoningHorizonM distance half of the Uncertain→Lost horizon.
 * @property reckoningHorizonS time half of the same horizon.
 * @property corroborationM along-track displacement an Unconfirmed candidate must survive before it
 *   is believed.
 * @property rescanCooldownS minimum gap between global scans while Lost.
 */
data class MatchTuning(
    val matchGateBaseM: Double = NavigationPolicy.MATCH_GATE_BASE_M,
    val matchGateAccuracyFactor: Double = NavigationPolicy.MATCH_GATE_ACCURACY_FACTOR,
    val matchGateCapM: Double = NavigationPolicy.MATCH_GATE_CAP_M,
    val vertexAcceptBaseM: Double = NavigationPolicy.VERTEX_ACCEPT_BASE_M,
    val vertexAcceptAccuracyFactor: Double = NavigationPolicy.VERTEX_ACCEPT_ACCURACY_FACTOR,
    val vertexAcceptCapM: Double = NavigationPolicy.VERTEX_ACCEPT_CAP_M,
    val budgetAccuracyFactor: Double = NavigationPolicy.BUDGET_ACCURACY_FACTOR,
    val budgetElapsedCapS: Double = NavigationPolicy.BUDGET_ELAPSED_CAP_S,
    val minMaxSpeedMps: Double = NavigationPolicy.MIN_MAX_SPEED_MPS,
    val speedMarginFactor: Double = NavigationPolicy.SPEED_MARGIN_FACTOR,
    val speedEmaAlpha: Double = NavigationPolicy.SPEED_EMA_ALPHA,
    val candidateTieM: Double = NavigationPolicy.CANDIDATE_TIE_M,
    val reckoningHorizonM: Double = NavigationPolicy.RECKONING_HORIZON_M,
    val reckoningHorizonS: Double = NavigationPolicy.RECKONING_HORIZON_S,
    val corroborationM: Double = NavigationPolicy.CORROBORATION_M,
    val rescanCooldownS: Double = NavigationPolicy.RESCAN_COOLDOWN_S,
) {
    companion object {
        /** The shipping values. Named so a replay report can say what it was comparing against. */
        val DEFAULT = MatchTuning()
    }
}
