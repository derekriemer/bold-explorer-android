package com.boldexplorer.shared.navigation

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Central home for navigation *decision thresholds* — the numbers that decide whether an event
 * fires (reached, off-trail, backtrack, ...), as opposed to how noisy raw signals are filtered
 * (see [GpsHeadingSmoother] for that; it stays where it is).
 *
 * Issue #55: before this file existed, thresholds governing entirely different decisions (a
 * near-trail pickup gate, a trail-end extend gate, an off-trail alert gate, ...) each lived next
 * to the class that consumed them, with nothing stopping two unrelated decisions from
 * accidentally sharing a value, or a change intended for one from silently retuning another.
 * Grouping every threshold here, banner-per-decision, makes that kind of accidental reuse visible
 * at a glance instead of requiring a cross-file audit to notice.
 *
 * **Not all of this file is a relocation, and the difference matters.** It began as one — the
 * proximity, reach, trail-approach, heading-penalty, near-trail and follower-advancement constants
 * are numerically identical to the ones they replaced, and must not be retuned without a separate,
 * deliberate change. Everything under the off-trail, completion, course-baseline, reacquisition and
 * backtrack-noise banners was introduced or changed here, so a reader must not assume unchanged
 * behaviour for those. `OFF_TRAIL_CONSECUTIVE_THRESHOLD = 2` in particular became a
 * `FAST = 2` / `SLOW = 5` pair, which is a behaviour change wearing a relocation's clothes.
 */
object NavigationPolicy {
    // ── Accuracy-aware threshold shapes ──────────────────────────────────────────────────────
    // Two shapes, opposite directions — see each KDoc for which decisions use which.

    /**
     * Sentinel cap meaning "this gate is deliberately still unbounded".
     *
     * Two gates -- the near-trail gate and the trail-extend gate -- were unbounded before the
     * thresholds were centralized here, and remain so. Bounding them would change behaviour, and
     * choosing the bound is a tuning decision that #55 defers until field evidence exists.
     *
     * It has a name rather than an inline `Double.MAX_VALUE` for one reason: a call reading
     * `widenWithAccuracy(..., capM = Double.MAX_VALUE)` *looks* bounded at a glance, which is worse
     * than the honest `max(floor, factor * accuracy)` it replaced. This makes the remaining
     * unbounded gates greppable, so "which thresholds are still unbounded" has a one-command answer.
     */
    const val UNBOUNDED_CAP_M = Double.MAX_VALUE

    /**
     * An accuracy-aware threshold that WIDENS with uncertainty, bounded by [capM].
     *
     * For gates where poor GPS should yield fewer confident decisions (off-trail, near-trail): a
     * wider gate means fewer alerts. The cap is what stops an implausible accuracy report from
     * disabling the check entirely.
     */
    fun widenWithAccuracy(
        baseM: Double,
        factor: Double,
        accuracyM: Double?,
        capM: Double,
    ): Double = min(capM, max(baseM, factor * (accuracyM ?: 0.0)))

    /**
     * An accuracy-aware threshold that TIGHTENS with good GPS, bounded by [ceilingM] and [floorM].
     *
     * For acceptance regions (completion): a good fix should let us be *stricter*, and a poor one
     * must never widen the region past the ceiling. This is the inverse of the
     * `max(floor, factor*accuracy)` pattern that existed throughout the codebase, which expanded
     * acceptance exactly when the fix was least trustworthy — the anti-pattern #55 was filed to
     * remove.
     */
    fun tightenWithAccuracy(
        ceilingM: Double,
        floorM: Double,
        factor: Double,
        accuracyM: Double?,
    ): Double {
        val acc = accuracyM ?: return ceilingM
        return min(ceilingM, max(floorM, factor * acc))
    }

    // ── Recording: refusing a fix no walk could have reached ────────────────────────────────
    //
    // Swept against the 2026-08-12 corpus (2105 consecutive-fix pairs from five sessions) before
    // shipping, which is the only reason these are not guesses. The first version of this rule was
    // a flat 30 m/s and would have refused two real fixes: 34.0 m and 30.2 m of apparent movement
    // in one second, both at 25 m reported accuracy. Jitter at bad GPS, not teleports.

    /**
     * Speed beyond which a step is a moved fix rather than a moving person, in m/s.
     *
     * 50 m/s is 180 km/h. Deliberately far above anything that could lay down a trail, because at a
     * 1 Hz fix rate this is a bar on a *single step* — 50 m between consecutive fixes — and the
     * corpus's fastest honest pair is 34 m/s. A bar tuned close to observed noise would refuse real
     * walking on the day the noise is slightly worse than the day it was measured.
     *
     * Distinct from [TrailGapCheck.MAX_PLAUSIBLE_SPEED_MPS], which classifies gaps in a whole
     * recorded trail after the fact. Same idea, different time scale, and conflating the two is what
     * made the first version of this check wrong.
     */
    const val WILD_FIX_SPEED_MPS = 50.0

    /**
     * How many combined standard deviations of reported accuracy a step may cover before the fix,
     * rather than the walker, is what moved.
     *
     * Android reports accuracy as a 68% (1σ) radius, so two fixes admit a combined
     * `sqrt(a² + b²)`. A jump inside a few of those is a jump the fix already told you it might
     * invent. At 25 m accuracy this makes the bar 141 m, against a worst observed jitter of 34 m —
     * four times the noise the corpus actually contains.
     *
     * This is the widen-with-uncertainty direction, and deliberately so: unlike an *acceptance*
     * region (see [tightenWithAccuracy]), a poor fix here must make the app slower to call something
     * impossible, not quicker. The cost of a false refusal is a hole in someone's recorded walk.
     */
    const val WILD_FIX_SIGMA_FACTOR = 4.0

    /**
     * How far a fix may legitimately be from the previous believed one.
     *
     * The looser of two budgets: what a walk could cover in [elapsedMs] at [WILD_FIX_SPEED_MPS], and
     * what the two fixes' own reported accuracies could invent. Both are needed — the speed term
     * alone refuses the far side of a GPS dropout, and the accuracy term alone refuses ordinary
     * walking once the fix rate drops.
     *
     * A null accuracy carries no information, so it contributes no budget rather than a default one.
     */
    fun wildJumpBudgetM(
        elapsedMs: Long,
        accuracyM: Double?,
        previousAccuracyM: Double?,
    ): Double {
        val travelBudget = WILD_FIX_SPEED_MPS * (elapsedMs / 1000.0)
        val a = accuracyM ?: 0.0
        val b = previousAccuracyM ?: 0.0
        val noiseBudget = WILD_FIX_SIGMA_FACTOR * sqrt(a * a + b * b)
        return max(travelBudget, noiseBudget)
    }

    // ── Nearby: proximity announcements & mid-trail pickup ──────────────────────────────────

    /** CollectionExplorer: distance within which an unvisited non-target point gets a one-time NearbyPoint announcement. */
    const val PROXIMITY_M = 30.0

    /** NearbyTrailResolver: floor of the accuracy-scaled gate for "close enough to a trail to follow it mid-trail". */
    const val NEAR_TRAIL_FLOOR_M = 20.0

    /** NearbyTrailResolver: multiplier on accuracy when it exceeds [NEAR_TRAIL_FLOOR_M]. */
    const val NEAR_TRAIL_ACCURACY_FACTOR = 2.0

    // ── Waypoint acceptance: reaching / approaching a target ─────────────────────────────────

    /** CollectionExplorer: distance within which a standalone waypoint target counts as reached. */
    const val REACH_THRESHOLD_M = 15.0

    /** CollectionExplorer: distance within which a trail-end target shows the Follow action. */
    const val TRAIL_APPROACH_M = 10.0

    /** CollectionExplorer: per-degree penalty (metres-equivalent) applied to off-heading candidates when auto-selecting the next target. */
    const val HEADING_DEGREE_PENALTY_M = 1.5

    // ── Advancement: TrailFollower's radial / projection / divergence checks ─────────────────

    /** Incoming projection: ≥90% along the trail leg → auto-advance. */
    const val INCOMING_ADVANCE_FRACTION = 0.9

    /** Incoming projection only fires within this factor × threshold, to avoid false advances on open terrain. */
    const val PROJECTION_PROXIMITY_FACTOR = 4.0

    /** Incoming projection: cross-track distance must be within this factor × threshold. */
    const val CROSS_TRACK_FACTOR = 3.0

    /** Incoming projection: course-over-ground must agree with segment direction within this many degrees. */
    const val HEADING_TOLERANCE_DEG = 60.0

    /** Divergence: user must have gotten within this factor × threshold of the target (a near-miss). */
    const val CLOSENESS_FACTOR = 2.0

    /** Divergence: user must have moved this many further metres away since closest approach. */
    const val DIVERGE_M = 5.0

    /** Divergence: next waypoint must be within this factor of the current segment length. */
    const val DIVERGE_NEXT_PROXIMITY_FACTOR = 1.5

    /**
     * Minimum real movement required since the last advance before another one can fire (issue
     * #9). Larger than typical GPS jitter for a stationary user (a few metres), smaller than both
     * the default reach threshold (15 m) and the auto-record track-point spacing (10 m), so
     * normal sequential advances while actually walking are unaffected.
     */
    const val MIN_MOVEMENT_SINCE_ADVANCE_M = 5.0

    // ── Off-trail detection ───────────────────────────────────────────────────────────────────

    /** Consecutive over-gate fixes required when divergence, distance, or angle corroborates. */
    const val OFF_TRAIL_CONSECUTIVE_FAST = 2

    /** Required when the user is parallel or converging — the ambiguous case, so alert later. */
    const val OFF_TRAIL_CONSECUTIVE_SLOW = 5

    /** Cross-track gate floor, before accuracy widening. */
    const val OFF_TRAIL_BASE_M = 20.0

    /** Accuracy multiplier for the gate; bounded by [OFF_TRAIL_GATE_CAP_M]. */
    const val OFF_TRAIL_ACCURACY_FACTOR = 2.0

    /** Hard ceiling on the gate, so an implausible accuracy cannot disable detection. */
    const val OFF_TRAIL_GATE_CAP_M = 40.0

    /** Beyond this, alert on the fast path regardless of trend — worth one utterance. */
    const val OFF_TRAIL_FAR_M = 60.0

    /** Minimum |cross-track| growth to count as diverging rather than GPS jitter. */
    const val DIVERGENCE_FLOOR_MPS = 0.2

    const val OFF_TRAIL_ALERT_INTERVAL_MS = 45_000L
    const val OFF_TRAIL_GRACE_MS = 30_000L

    /**
     * Confirmed along-track travel required before a trail may be completed, on a long trail.
     *
     * A loop's start is also its end, and a follow may legitimately begin standing there — so
     * "you are at the end" is not on its own evidence of having walked the trail. ADR 0001
     * revision 15 specified this guard; it was never implemented, so until now nothing stopped a
     * completion firing on the first fix of a loop.
     */
    const val MIN_TRAVEL_FOR_COMPLETION_M = 50.0

    /**
     * The same guard as a fraction of the trail, for trails shorter than the flat minimum.
     *
     * The effective guard is `min(MIN_TRAVEL_FOR_COMPLETION_M, totalLengthM × this)`. A flat
     * minimum alone would make any trail shorter than 50 m impossible to finish — the same
     * "constant that does not scale to the geometry" failure this ADR exists to remove, and the one
     * revision 15 was itself written to correct.
     */
    const val COMPLETION_TRAVEL_FRACTION = 0.25

    /** Travel a trail of [totalLengthM] must show before completion is believable. */
    fun completionTravelGuardM(totalLengthM: Double): Double =
        min(MIN_TRAVEL_FOR_COMPLETION_M, totalLengthM * COMPLETION_TRAVEL_FRACTION)

    // ── Backtrack detection ───────────────────────────────────────────────────────────────────

    const val BACKTRACK_CONSECUTIVE_THRESHOLD = 3

    /**
     * Along-track regression that counts as movement rather than noise, at good accuracy.
     *
     * Widened by [BACKTRACK_NOISE_ACCURACY_FACTOR] because the noise this floor exists to reject is
     * the position noise the fix itself reports. Replaying the 2026-08-12 corpus: at 25 m reported
     * accuracy the *windowed* confirmed position wandered 4–12 m per fix while the owner stood
     * still, which cleared a flat 2 m three times running and would have announced "wrong way" to
     * someone who had not moved.
     */
    const val BACKTRACK_NOISE_FLOOR_M = 2.0

    /**
     * How much reported accuracy widens the noise floor.
     *
     * Half, not the whole: two fixes at accuracy *a* can disagree by more than *a* between them, so
     * a factor of 1 would be generous enough to swallow a slow genuine reversal. Swept over 2110
     * corpus fixes, 0.5 removes the 16:07:35 false positive and changes no other outcome.
     */
    const val BACKTRACK_NOISE_ACCURACY_FACTOR = 0.5

    /**
     * Ceiling on the widened floor.
     *
     * An implausible accuracy report must not be able to switch wrong-way detection off — the same
     * bound, for the same reason, as [OFF_TRAIL_GATE_CAP_M] and [MATCH_GATE_CAP_M].
     */
    const val BACKTRACK_NOISE_FLOOR_CAP_M = 15.0

    const val BACKTRACK_ALERT_INTERVAL_MS = 45_000L
    const val BACKTRACK_GRACE_MS = 20_000L

    // ── Ordinary guidance throttle ────────────────────────────────────────────────────────────

    const val ORDINARY_GUIDANCE_INTERVAL_MS = 30_000L
    const val ORDINARY_GUIDANCE_DISTANCE_M = 25.0

    /**
     * Physical baseline for the desired-course chord, in metres, used by [TrailGuidance].
     *
     * Long enough to average out recording noise at walking density, short enough that a real
     * bend still moves the course promptly. Deliberately *not* shared with any matching window —
     * that one exists to exclude a switchback's other arm, which is the opposite goal.
     */
    const val COURSE_BASELINE_M = 20.0

    // ── Completion: trail-endpoint acceptance ─────────────────────────────────────────────────

    /** Upper bound on the completion radius; poor GPS never widens past this. */
    const val COMPLETION_CEILING_M = 15.0

    /** Lower bound, so an optimistic accuracy report cannot make the trail uncompletable. */
    const val COMPLETION_FLOOR_M = 5.0

    /** Android accuracy is a 1σ radius; 2σ is roughly 95% containment. */
    const val COMPLETION_SIGMA_FACTOR = 2.0

    /** Above this accuracy the arrival announcement hedges rather than asserts. */
    const val COMPLETION_HEDGE_ABOVE_M = 10.0

    // ── Continuous matching: the reacquisition ladder (ProgressTracker) ──────────────────────
    //
    // Unlike everything above, these are NOT relocated values — they are new, and they are
    // deliberately untuned. S4 runs the tracker in shadow mode precisely so they can be set from
    // field logs rather than from guesswork. Treat the numbers here as starting points with the
    // right *shape*, not as answers.

    /** Cross-track floor for accepting a candidate as a match, before accuracy widening. */
    const val MATCH_GATE_BASE_M = 25.0

    /** Accuracy multiplier for the match gate. */
    const val MATCH_GATE_ACCURACY_FACTOR = 3.0

    /**
     * Hard ceiling on the match gate, so an implausible accuracy cannot make anything match.
     *
     * **Must stay at or below [OFF_TRAIL_FAR_M].** Above it the tracker reports `Matched` while the
     * app is simultaneously telling the user they may be off trail, which is not a tuning question
     * but a contradiction. Pinned by `NavigationPolicyTest`.
     *
     * Lowered from 60 m after the 2026-08-12 field walk, where under a 25 m reported accuracy the
     * gate pinned at its cap and the matcher held `Matched` at 49, 54 and 56 m cross-track while the
     * live follower announced off-trail. Swept over the recorded walk with the replay harness:
     *
     * ```
     * cap     max cross-track while Matched   vertex-pinned fixes
     *  60 m               57.1 m                      45
     *  50 m               49.6 m                      40
     *  40 m               38.9 m                      30
     *  30 m               29.0 m                      16
     * ```
     *
     * 40 m rather than 30 m because the cap only binds under degraded accuracy, and a fix genuinely
     * displaced 20 m with 8 m of jitter *should* still match — 30 m starts rejecting fixes that were
     * right. Under good GPS the cap does not bind at all: at 3 m accuracy the gate is
     * 25 + 3 × 3 = 34 m, and the walk's undegraded session replayed identically at every cap from 30
     * to 60 m. This change therefore costs nothing in normal conditions.
     *
     * It reduces vertex pinning as a side effect (45 → 30 fixes) by rejecting the far end of the
     * apex wedge. That is filtering, not a fix — see the vertex-projection amendment in ADR 0001.
     */
    const val MATCH_GATE_CAP_M = 40.0

    /**
     * How close to a vertex a **vertex-clamped** projection must be before it may confirm progress.
     *
     * ADR 0001 Amendment 1 / S4c. Where the trail turns, the exterior wedge behind the corner
     * projects entirely onto the corner itself: an entire region of ground shares one `alongTrackM`,
     * and the reported cross-track becomes radial distance to the vertex rather than perpendicular
     * offset from a path. Inside this radius that answer is still usable, because the ambiguity is
     * smaller than the GPS error and no better answer exists. Outside it the tracker does not know
     * where along the trail the walker is, and says so by staying `Uncertain`.
     *
     * Scaled by accuracy rather than fixed, because the question is whether the degeneracy is
     * distinguishable from noise — a property of the fix, not of the trail.
     *
     * **Untuned.** Sweep against a recorded walk with the replay harness before trusting these.
     */
    const val VERTEX_ACCEPT_BASE_M = 5.0

    /** Accuracy multiplier for [VERTEX_ACCEPT_BASE_M]. */
    const val VERTEX_ACCEPT_ACCURACY_FACTOR = 2.0

    /** Ceiling on the vertex accept radius; past this a wedge position is never a known position. */
    const val VERTEX_ACCEPT_CAP_M = 25.0

    /** Accuracy multiplier (K) in `budget = maxSpeed × elapsed + K × accuracy`. */
    const val BUDGET_ACCURACY_FACTOR = 2.0

    /**
     * Cap (T_CAP) on the elapsed term of the budget, in seconds.
     *
     * Without it the window grows without bound and silently becomes a global scan while still
     * claiming the continuity that makes it safe. The reckoning horizon is the honest way to give
     * up; this cap is what stops the window pretending otherwise in the meantime.
     */
    const val BUDGET_ELAPSED_CAP_S = 20.0

    /**
     * Floor on the speed used to size the window, in m/s.
     *
     * The window must admit a walker who has stopped and drifted, so it cannot collapse to zero
     * when observed speed does.
     */
    const val MIN_MAX_SPEED_MPS = 2.5

    /**
     * Safety margin applied to observed speed when sizing the window.
     *
     * Window size **must** derive from observed speed rather than being a global constant. A
     * walking-tuned constant strands a cyclist outside the window on every fix — permanent
     * `Uncertain → Lost → global scan → cooldown` — while a vehicle-tuned one admits candidates
     * 300 m away for a walker who has moved four metres, destroying the switchback protection that
     * window size exists to provide. See the high-speed traversal fixture.
     */
    const val SPEED_MARGIN_FACTOR = 2.0

    /** Smoothing factor for the observed-speed EMA. Higher reacts faster and is noisier. */
    const val SPEED_EMA_ALPHA = 0.3

    /**
     * Cross-track spread within which two candidates count as tied, in metres.
     *
     * Only tied candidates are ranked by prediction. A candidate that is plainly nearer wins on
     * geometry alone — prediction ranks, it does not constrain.
     */
    const val CANDIDATE_TIE_M = 5.0

    /** Reckoned distance past which continuity is declared gone, in metres. */
    const val RECKONING_HORIZON_M = 120.0

    /**
     * Elapsed time past which continuity is declared gone, in seconds.
     *
     * Both bounds are required and they trade places with speed: a stationary user with bad GPS
     * burns seconds without metres, a fast-moving one burns metres without seconds.
     */
    const val RECKONING_HORIZON_S = 90.0

    /** Displacement an `Unconfirmed` candidate must survive before being promoted, in metres. */
    const val CORROBORATION_M = 25.0

    /**
     * Minimum gap between global rescans, in seconds.
     *
     * The failure mode is frequency, not cost: a user genuinely off the trail fails corroboration
     * every time, and without this the tracker would scan globally on every fix at 1 Hz forever.
     * Implemented as a timestamp on the state rather than a counter — see [ProgressTracker].
     */
    const val RESCAN_COOLDOWN_S = 30.0

    // ── Trail extend ──────────────────────────────────────────────────────────────────────────

    /** Minimum distance within which a trail end may be extended, regardless of GPS accuracy. */
    const val EXTEND_FLOOR_M = 15.0

    /** Multiplier on reported GPS accuracy when it exceeds [EXTEND_FLOOR_M], to scale the extend threshold. */
    const val EXTEND_ACCURACY_FACTOR = 2.0

    // ── S6: follow cues ───────────────────────────────────────────────────────────────────────

    /** Progress earcon cadence — frequent enough to read as continuous presence. */
    const val PROGRESS_EARCON_INTERVAL_MS = 5_000L

    /** Progress speech cadence — one short fact, twice the rate of ordinary guidance. */
    const val PROGRESS_SPEECH_INTERVAL_MS = 15_000L

    /** Skip the progress cue when anything else spoke this recently. */
    const val PROGRESS_YIELD_MS = 5_000L

    /** Reaction budget for an annotation approach: hear it, decide, and stop. */
    const val ANNOTATION_LEAD_SECONDS = 8.0

    /** Floors the lead when stationary. */
    const val ANNOTATION_LEAD_MIN_M = 10.0

    /** Caps the lead when moving fast. */
    const val ANNOTATION_LEAD_MAX_M = 40.0

    /** Beyond this from the trail, an annotation is phrased as an aside rather than plainly. */
    const val ANNOTATION_ASIDE_M = 25.0

    /** Window `sagittaOver` is asked about when deciding whether the trail ahead is straight. */
    const val STRAIGHT_LOOKAHEAD_M = 40.0

    /** Bulge over that window above which the trail ahead is not called straight. */
    const val STRAIGHT_SAGITTA_M = 4.0

    /** Non-`Matched` fixes before a lost match is announced; stops flap chatter. */
    const val MATCH_LOST_SUSTAIN = 3
}
