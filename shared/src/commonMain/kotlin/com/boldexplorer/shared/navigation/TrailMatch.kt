package com.boldexplorer.shared.navigation

/**
 * Which end of the trail the user is walking toward, chosen once per session.
 *
 * A [TrailPolyline] is **always** in recorded order and is never reversed, so `alongTrackM` means
 * the same thing for every walk of a trail and two field logs of opposite traversals can be
 * overlaid. Direction is carried here instead, and under [Reverse] `alongTrackM` counts down.
 *
 * This is a session *parameter*, not an inference. Detecting a mid-walk turnaround and re-deriving
 * direction from motion is explicitly out of scope: a user who physically turns around sees
 * `alongTrackM` move against the declared direction, distance-remaining grow, and completion not
 * fire. For a blind user who may have turned around unintentionally that is useful information
 * rather than a bug, and an intentional reversal is handled by re-selecting direction.
 */
enum class TravelDirection {
    Forward,
    Reverse,
}

/**
 * +1 when `alongTrackM` grows with correct progress, −1 when it shrinks.
 *
 * The one fact everything reverse-related depends on. It was written out three times — in
 * `ProgressTracker`, in the guidance coordinator and inline in `TrailGuidance` — which is three
 * chances for the convention to drift and no single place to document or test it.
 */
val TravelDirection.sign: Double
    get() = if (this == TravelDirection.Reverse) -1.0 else 1.0

/**
 * How much the tracker currently trusts its own position, and therefore how hard it is searching.
 *
 * These are **internal** states. The public navigation surface does not expose them yet — that
 * split is deferred — but all four exist because the reacquisition ladder reads all four to decide
 * how wide to search and whether the result may be trusted.
 */
enum class MatchState {
    /** A fix projected onto the trail within gate. Normal operation. */
    Matched,

    /**
     * No acceptable candidate inside the window. Progress is frozen and the window widens around
     * the last known-good position; only prediction moves. Off-trail alerts and completion are
     * suppressed here.
     */
    Uncertain,

    /**
     * The reckoning horizon expired, in metres or in seconds. Prior progress no longer constrains
     * the search at all.
     *
     * The *value* of `confirmedAlongM` survives this state and consumers keep reading it — only its
     * role in bounding the window ends.
     */
    Lost,

    /**
     * A global scan produced a candidate, which constrains nothing until it earns it. Drives no
     * guidance, no completion and no off-trail alerting; promotes to [Matched] only after
     * corroborating displacement.
     */
    Unconfirmed,
}

/** Whether a fix was matched against a bounded window or against the whole trail. */
enum class ScanKind {
    /** No scan ran — the tracker was in cooldown after a failed reacquisition. */
    None,

    /** Bounded by `confirmedAlongM ± budget`. The normal, and the safe, case. */
    Windowed,

    /** The whole polyline, with no prior to constrain it. Rare by construction. */
    Global,
}

/**
 * The result of matching one GPS fix against a trail, including the evidence behind the decision.
 *
 * Everything needed to replay a field log and ask "how often did it go global, was it right, and
 * would the prediction have misled us" is here. [chosen] and [bestRejected] are recorded together
 * deliberately: a decision recorded without its runner-up cannot be second-guessed afterwards, and
 * the promote-or-drop question for course evidence is answerable only from the pair.
 *
 * @property state how much the tracker trusts this result.
 * @property confirmedAlongM the last geometry-corroborated position, in metres along the trail, or
 *   `null` before the first successful acquisition. **This is the only progress value any consumer
 *   should read** — distance remaining, completion, telemetry and speech all come from here.
 * @property predictedAlongM 1-D extrapolation from the last confirmed position. Ranks tied
 *   candidates and nothing else; never persisted as progress, never read by a consumer, and ignored
 *   entirely past the reckoning horizon.
 * @property position the confirmed position itself, or `null` before acquisition. Frozen while
 *   [state] is not [MatchState.Matched].
 * @property chosen the candidate this fix selected, whether or not it was accepted. Under
 *   [MatchState.Unconfirmed] this is the unpromoted candidate.
 * @property bestRejected the nearest candidate that was *not* chosen — the rival. Null when the
 *   scan found only one place the user could be.
 * @property unmatchedCount consecutive fixes that produced no accepted match.
 * @property uncertainSec seconds since the last confirmed match.
 * @property travelledM confirmed along-track distance accumulated this session. Reckoned and
 *   unconfirmed movement never contribute, and neither does a reacquisition jump.
 * @property windowM the along-track window searched, or `null` for a global or skipped scan.
 * @property budgetM half-width of that window in metres.
 * @property predictionErrorM `predictedAlongM − confirmedAlongM` at the moment geometry returned,
 *   set only on the fix that reacquires. The single field that says whether prediction deserves to
 *   remain a tiebreaker at all.
 * @property disposition short machine-greppable reason, in the established `verb:detail` style.
 */
data class TrailMatch(
    val state: MatchState,
    val confirmedAlongM: Double?,
    val predictedAlongM: Double?,
    val position: TrailPosition?,
    val chosen: TrailPosition?,
    val bestRejected: TrailPosition?,
    val unmatchedCount: Int,
    val uncertainSec: Double,
    val travelledM: Double,
    val scanKind: ScanKind,
    val windowM: ClosedFloatingPointRange<Double>?,
    val budgetM: Double,
    val predictionErrorM: Double?,
    val disposition: String,
)
