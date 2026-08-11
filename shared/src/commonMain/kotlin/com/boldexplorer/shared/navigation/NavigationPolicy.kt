package com.boldexplorer.shared.navigation

import kotlin.math.max
import kotlin.math.min

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
 * This file is a pure by-reference relocation: every value below is numerically identical to the
 * constant it replaced. Do not retune values here without a separate, deliberate change.
 */
object NavigationPolicy {
    // ── Accuracy-aware threshold shapes ──────────────────────────────────────────────────────
    // Two shapes, opposite directions — see each KDoc for which decisions use which.

    /**
     * An accuracy-aware threshold that WIDENS with uncertainty, bounded by [capM].
     *
     * For gates where poor GPS should yield fewer confident decisions (off-trail, near-trail): a
     * wider gate means fewer alerts. The cap is what stops an implausible accuracy report from
     * disabling the check entirely.
     */
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

    // ── Backtrack detection ───────────────────────────────────────────────────────────────────

    const val BACKTRACK_CONSECUTIVE_THRESHOLD = 3
    const val BACKTRACK_NOISE_FLOOR_M = 2.0
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

    // ── Trail extend ──────────────────────────────────────────────────────────────────────────

    /** Minimum distance within which a trail end may be extended, regardless of GPS accuracy. */
    const val EXTEND_FLOOR_M = 15.0

    /** Multiplier on reported GPS accuracy when it exceeds [EXTEND_FLOOR_M], to scale the extend threshold. */
    const val EXTEND_ACCURACY_FACTOR = 2.0
}
