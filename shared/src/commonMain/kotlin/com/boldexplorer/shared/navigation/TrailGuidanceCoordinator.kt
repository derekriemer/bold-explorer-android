package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.LocationSample
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Owns trail-guidance state and the off-trail / backtrack / ordinary-guidance *detection* state
 * machine, extracted from `GpsViewModel` so that class stays a coordinator rather than a god object.
 *
 * This class is deliberately **effect-free**: it never speaks, beeps, or logs. It computes the
 * current [TrailGuidanceState] and, on each GPS fix, decides *whether* an alert is warranted —
 * returning structured decision objects. The ViewModel performs the side effects (TTS, the audio
 * event log, the wrong-vector beep, the on-screen announcement) and the unit/i18n formatting. That
 * split keeps the throttle/grace/cooldown bookkeeping pure and JVM-testable in `:shared`.
 *
 * The [TrailFollower] itself stays in the ViewModel (it is driven by many follow-start paths); this
 * coordinator only consumes the follower's [TrailFollowerState] as an input.
 */
class TrailGuidanceCoordinator(
    scope: CoroutineScope,
) {
    private val _guidance = MutableStateFlow<TrailGuidanceState?>(null)
    val guidance: StateFlow<TrailGuidanceState?> = _guidance.asStateFlow()

    val relativeDeg: StateFlow<Double?> =
        _guidance
            .map { it?.relativeDeg }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val headingSmoother = GpsHeadingSmoother()
    private val _smoothedHeading = MutableStateFlow<SmoothedHeading?>(null)
    val smoothedHeading: StateFlow<SmoothedHeading?> = _smoothedHeading.asStateFlow()

    private val _navigationHeadingDeg = MutableStateFlow<Double?>(null)
    val navigationHeadingDeg: StateFlow<Double?> = _navigationHeadingDeg.asStateFlow()

    private var lastTrustedCourse: TrustedCourse? = null

    // Ordinary-guidance throttle.
    private var lastOrdinaryGuidanceAtMs = Long.MIN_VALUE
    private var lastOrdinaryGuidanceLocation: LatLng? = null

    // Off-trail detection.
    private var consecutiveOffTrailCount = 0
    private var offTrailAlertFiredAt = 0L
    private var offTrailGraceUntilMs = 0L

    /** |cross-track| at the previous fix, for the divergence rate. */
    private var prevAbsCrossTrackM: Double? = null
    private var prevCrossTrackAtMs = 0L

    /**
     * The followed trail in recorded order, and which way along it the user declared they are going.
     *
     * Set by [startFollow]. The single geometry every detector here measures against, shared with
     * the matcher so `alongTrackM` means one thing app-wide.
     */
    private var followPolyline: TrailPolyline? = null
    private var followDirection: TravelDirection = TravelDirection.Forward

    // Backtrack detection.
    private var consecutiveBacktrackCount = 0
    private var prevDistToTargetM: Double? = null

    /** Projected along-track position at the previous fix — what wrong-way is decided on. */
    private var prevAlongTrackM: Double? = null
    private var backtrackAlertFiredAt = 0L
    private var backtrackGraceUntilMs = 0L

    /**
     * Fold a fresh sample into the trusted-course filter. Returns the confidence-gated smoothed
     * heading so callers driving [TrailFollower] can use it for slow-walker advancement.
     */
    fun updateTrustedCourse(sample: LocationSample): SmoothedHeading? {
        headingSmoother.addFix(sample)
        val smoothed = headingSmoother.smoothedHeading(sample.timestamp)
        _smoothedHeading.value = smoothed
        lastTrustedCourse = TrailGuidance.updateTrustedCourse(lastTrustedCourse, sample, smoothed)
        _navigationHeadingDeg.value = TrailGuidance.freshCourseAt(lastTrustedCourse, sample.timestamp)?.deg
        return smoothed?.takeIf {
            sample.timestamp - it.newestTimestampMs <= TrailGuidance.TRUSTED_COURSE_HOLD_MS
        }
    }

    fun courseIsSmoothed(): Boolean = lastTrustedCourse?.isSmoothed ?: false

    /**
     * Adopt the followed trail's geometry for the session.
     *
     * Takes the polyline in **recorded order** with [direction] carried separately — the same
     * instance the matcher holds, so `alongTrackM` means one thing across the whole app. The
     * follower's own waypoint list is reversed in place for a reverse follow, which makes its
     * along-track session-relative and incomparable with the match's.
     */
    fun startFollow(
        polyline: TrailPolyline,
        direction: TravelDirection,
    ) {
        followPolyline = polyline
        followDirection = direction
    }

    /** Recompute and publish guidance for [sample] against [followState]; returns the new value. */
    fun computeGuidance(
        followState: TrailFollowerState,
        sample: LocationSample,
        match: TrailMatch?,
    ): TrailGuidanceState? {
        // The desired course is a chord over a physical baseline, centred where the *windowed*
        // matcher says the user is. Both halves matter: the chord makes the answer density-
        // invariant, and the windowed centre keeps it on the arm the user is actually walking.
        //
        // `confirmedAlongM` survives `Uncertain` and `Lost` by design, and `Uncertain` is a brief
        // gap where steering by a slightly old position beats going blind on every dropout. Past
        // the reckoning horizon it is a different claim: the value can be minutes old and on
        // another arm, and steering by it reproduces the S5 defect through state instead of through
        // an unwindowed projection. Beyond that, guidance says nothing rather than something stale,
        // and `TrailGuidance` falls back to local geometry. Found in review, 2026-08-15.
        val steerableAlongM =
            match
                ?.takeIf { it.state == MatchState.Matched || it.state == MatchState.Uncertain }
                ?.confirmedAlongM
        val guidance =
            TrailGuidance.compute(
                followState,
                sample,
                lastTrustedCourse,
                followPolyline,
                steerableAlongM,
                followDirection,
            )
        _guidance.value = guidance
        return guidance
    }

    /**
     * Refresh guidance from a known-latest [sample] (used right after a follow starts), optionally
     * arming the ordinary-guidance grace window so a just-started follow doesn't immediately alert.
     */
    fun refreshFromLocation(
        followState: TrailFollowerState,
        sample: LocationSample,
        match: TrailMatch?,
        resetOrdinaryThrottle: Boolean = false,
    ) {
        updateTrustedCourse(sample)
        computeGuidance(followState, sample, match)
        if (resetOrdinaryThrottle) resetThrottle(sample)
    }

    /** Clear all guidance + detection state (trail follow stopped / completed). */
    fun clear() {
        _guidance.value = null
        followPolyline = null
        followDirection = TravelDirection.Forward
        lastOrdinaryGuidanceAtMs = Long.MIN_VALUE
        lastOrdinaryGuidanceLocation = null
        consecutiveOffTrailCount = 0
        offTrailAlertFiredAt = 0L
        prevAbsCrossTrackM = null
        prevCrossTrackAtMs = 0L
        offTrailGraceUntilMs = 0L
        consecutiveBacktrackCount = 0
        prevDistToTargetM = null
        prevAlongTrackM = null
        backtrackAlertFiredAt = 0L
        backtrackGraceUntilMs = 0L
    }

    /** Arm grace windows and reset throttles around [sample] (e.g. on waypoint arrival / follow start). */
    fun resetThrottle(sample: LocationSample) {
        lastOrdinaryGuidanceAtMs = sample.timestamp
        lastOrdinaryGuidanceLocation = LatLng(sample.lat, sample.lon)
        consecutiveOffTrailCount = 0
        offTrailAlertFiredAt = 0L
        prevAbsCrossTrackM = null
        prevCrossTrackAtMs = 0L
        offTrailGraceUntilMs = sample.timestamp + NavigationPolicy.OFF_TRAIL_GRACE_MS
        consecutiveBacktrackCount = 0
        prevDistToTargetM = null
        prevAlongTrackM = null
        backtrackAlertFiredAt = 0L
        backtrackGraceUntilMs = sample.timestamp + NavigationPolicy.BACKTRACK_GRACE_MS
    }

    /**
     * Decide whether a routine "checkpoint N, distance, direction" cue should play. Returns null when
     * suppressed (not active, no major correction, or still inside the time/distance throttle); when
     * non-null, the throttle has been advanced and the caller should speak the cue.
     */
    fun evaluateOrdinaryGuidance(
        followState: TrailFollowerState,
        sample: LocationSample,
        guidance: TrailGuidanceState?,
    ): OrdinaryGuidanceDecision? {
        if (followState !is TrailFollowerState.Active) return null
        val relative = guidance?.relativeDeg ?: return null
        if (!TrailGuidance.isMajorCorrection(relative)) return null
        if (sample.timestamp - lastOrdinaryGuidanceAtMs < NavigationPolicy.ORDINARY_GUIDANCE_INTERVAL_MS) return null

        val current = LatLng(sample.lat, sample.lon)
        val lastLocation = lastOrdinaryGuidanceLocation
        if (lastLocation != null &&
            haversineDistanceMeters(lastLocation, current) < NavigationPolicy.ORDINARY_GUIDANCE_DISTANCE_M
        ) {
            return null
        }

        lastOrdinaryGuidanceAtMs = sample.timestamp
        lastOrdinaryGuidanceLocation = current
        return OrdinaryGuidanceDecision(
            checkpointN = guidance.targetIndex + 1,
            total = guidance.total,
            distanceToTargetM = guidance.distanceToTargetM,
            relativeDeg = relative,
        )
    }

    /**
     * Update off-trail detection state and decide whether to alert. Returns null while inactive or
     * inside the grace window (no diagnostic worth logging); otherwise an [OffTrailEvaluation] whose
     * [OffTrailEvaluation.fired] tells the caller to speak "You may be off trail" + beep, and whose
     * other fields are ready to write to the audio event log.
     */
    fun evaluateOffTrail(
        followState: TrailFollowerState,
        sample: LocationSample,
        guidance: TrailGuidanceState?,
        match: TrailMatch?,
    ): OffTrailEvaluation? {
        if (followState !is TrailFollowerState.Active) return null
        if (sample.timestamp < offTrailGraceUntilMs) return null

        val relative = guidance?.relativeDeg
        // The candidate the matcher chose, whatever state it is in. What that candidate *means*
        // changes with the state, and both readings are the right one for their state:
        //
        // - `Matched`/`Uncertain`: a windowed candidate, near where the user was. This is the one
        //   that matters at a switchback, where the opposite arm is close and an unwindowed
        //   projection would report a small cross-track for someone off their own arm.
        // - `Lost`/`Unconfirmed`: a global candidate — the nearest point on the whole trail, which
        //   is a *lower bound* on how far off the user is. If even that exceeds the gate they are
        //   off trail, so acting on it cannot produce a false positive.
        //
        // Gating this on `Matched`/`Uncertain` silenced the alert permanently for the person most
        // off-trail: leave the trail and keep walking, and the reckoning horizon puts the tracker in
        // `Lost` within 90 s, where it stays. Found in review, 2026-08-15.
        val signedCrossTrackM = match?.chosen?.crossTrackM?.times(followDirection.sign)
        val absCrossTrackM = signedCrossTrackM?.let { abs(it) }
        val rateMps = updateCrossTrackRate(absCrossTrackM, sample.timestamp)

        // The gate *widens* as accuracy degrades, so poor GPS yields fewer confident alerts — the
        // opposite direction from completion, where good GPS tightens the radius. Both are bounded:
        // an implausible accuracy report must not be able to switch off-trail detection off.
        val gateM = offTrailGateM(sample.accuracy)
        val overGate = absCrossTrackM != null && absCrossTrackM > gateM

        if (overGate) {
            consecutiveOffTrailCount++
        } else {
            consecutiveOffTrailCount = 0
            offTrailAlertFiredAt = 0L
        }

        // Corroboration shortens the sustain window; it never gates whether a fix counts. Angle is
        // evidence here, not a veto — demoting it is the whole point, since a stale target is what
        // produced the original defect.
        val diverging = rateMps != null && rateMps > NavigationPolicy.DIVERGENCE_FLOOR_MPS
        val far = absCrossTrackM != null && absCrossTrackM > NavigationPolicy.OFF_TRAIL_FAR_M
        val angleAgrees = relative != null && TrailGuidance.isMajorCorrection(relative)
        val required =
            if (diverging || far || angleAgrees) {
                NavigationPolicy.OFF_TRAIL_CONSECUTIVE_FAST
            } else {
                NavigationPolicy.OFF_TRAIL_CONSECUTIVE_SLOW
            }

        val sinceLastAlertMs = sample.timestamp - offTrailAlertFiredAt
        val xt = absCrossTrackM?.roundToInt()
        val trend = if (diverging) "diverging" else "converging"
        val disposition =
            when {
                absCrossTrackM == null -> "bail:no_window"
                !overGate -> "bail:on_line_xt_${xt}m_gate_${gateM.roundToInt()}m"
                consecutiveOffTrailCount < required ->
                    "hold:xt_${xt}m_${trend}_${consecutiveOffTrailCount}of$required"
                sinceLastAlertMs < NavigationPolicy.OFF_TRAIL_ALERT_INTERVAL_MS -> "bail:cooldown_${sinceLastAlertMs}ms"
                else -> "fire:xt_${xt}m_$trend"
            }
        val fired = disposition.startsWith("fire:")
        if (fired) offTrailAlertFiredAt = sample.timestamp

        return OffTrailEvaluation(
            relativeDeg = relative,
            followerActive = true,
            consecutiveCount = consecutiveOffTrailCount,
            sinceLastAlertMs = sinceLastAlertMs,
            disposition = disposition,
            fired = fired,
            crossTrackM = signedCrossTrackM,
            crossTrackRateMps = rateMps,
            gateM = gateM,
            requiredCount = required,
        )
    }

    /**
     * Signed rate of change of |cross-track|, in m/s. Positive means moving away from the trail.
     *
     * Density-independent and target-free, unlike a bearing against a possibly-stale waypoint —
     * which is why this, not the angle, is the primary divergence signal.
     */
    private fun updateCrossTrackRate(
        absCrossTrackM: Double?,
        nowMs: Long,
    ): Double? {
        val prev = prevAbsCrossTrackM
        val elapsedS = (nowMs - prevCrossTrackAtMs) / 1000.0
        val rate =
            if (prev != null && absCrossTrackM != null && elapsedS > 0.0) {
                (absCrossTrackM - prev) / elapsedS
            } else {
                null
            }
        if (absCrossTrackM != null) {
            prevAbsCrossTrackM = absCrossTrackM
            prevCrossTrackAtMs = nowMs
        }
        return rate
    }

    /** Accuracy-aware off-trail gate, widening with uncertainty but hard-capped. */
    private fun offTrailGateM(accuracyM: Double?): Double =
        NavigationPolicy.widenWithAccuracy(
            baseM = NavigationPolicy.OFF_TRAIL_BASE_M,
            factor = NavigationPolicy.OFF_TRAIL_ACCURACY_FACTOR,
            accuracyM = accuracyM,
            capM = NavigationPolicy.OFF_TRAIL_GATE_CAP_M,
        )

    /**
     * Update backtrack detection state and decide whether to alert. Returns null while inactive or
     * inside the grace window; otherwise a [BacktrackEvaluation] whose [BacktrackEvaluation.fired]
     * tells the caller to speak "You may be going the wrong way", with diagnostics for the log.
     *
     * ## Why this reads [match] instead of projecting (ADR 0001, S5a)
     *
     * The decision is along-track regression, not distance to the current target: distance grows
     * whenever the user walks past *any* turn, or whenever the target is stale and behind them, so
     * keying on it announced "wrong way" to people going the right way — about five times faster
     * than off-trail could report the real problem.
     *
     * But *which* along-track matters just as much. This used to run its own **unwindowed**
     * `polyline.project()`, which on a trail that doubles back snaps to whichever arm the fix
     * happens to be nearest. In the field that read as 113 m of regression in three fixes and
     * announced wrong way to a user walking correctly. Consuming [TrailMatch.confirmedAlongM]
     * inherits the window, the gate and the ladder's states, so the same fixes stay put.
     *
     * @param match the windowed matcher's result for **this** fix, or `null` before it has one.
     * @param direction the declared traversal direction. `alongTrackM` is always in recorded order,
     *   so under [TravelDirection.Reverse] correct progress counts *down* and regression is a rise.
     *   Without this the detector would announce wrong way for an entire reverse follow.
     */
    fun evaluateBacktrack(
        followState: TrailFollowerState,
        sample: LocationSample,
        guidance: TrailGuidanceState?,
        match: TrailMatch?,
        direction: TravelDirection,
    ): BacktrackEvaluation? {
        if (followState !is TrailFollowerState.Active) return null
        if (sample.timestamp < backtrackGraceUntilMs) return null

        val distM = guidance?.distanceToTargetM
        val prevDistM = prevDistToTargetM
        val alongM = match?.confirmedAlongM
        val prev = prevAlongTrackM
        val sinceLastAlertMs = sample.timestamp - backtrackAlertFiredAt

        prevDistToTargetM = distM

        // Only a fresh, contiguous confirmation is evidence. A frozen value under Uncertain/Lost is
        // not movement, and the fix where geometry returns after an absence — the one carrying
        // predictionErrorM — can legitimately land far behind, because a rejoin elsewhere is not a
        // reversal. Both re-baseline instead of comparing.
        val contiguous = match?.state == MatchState.Matched && match.predictionErrorM == null
        val progressM = if (contiguous && prev != null && alongM != null) (alongM - prev) * direction.sign else null
        // The floor widens with reported accuracy, because what it exists to reject is the position
        // noise the fix itself is declaring. A flat 2 m is a walking pace at 1 Hz, and a 25 m fix
        // moves further than that standing still.
        val noiseFloorM = backtrackNoiseFloorM(sample.accuracy)
        val regressed = progressM != null && progressM < -noiseFloorM

        when {
            alongM == null -> {
                consecutiveBacktrackCount = 0
            }

            // Holds the count rather than clearing it: a gap in corroboration is not evidence
            // either way. Re-baselining prev is what stops the gap itself reading as movement.
            !contiguous -> {
                Unit
            }

            regressed -> {
                consecutiveBacktrackCount++
            }

            else -> {
                consecutiveBacktrackCount = 0
                backtrackAlertFiredAt = 0L
            }
        }
        prevAlongTrackM = alongM

        val disposition =
            when {
                alongM == null -> {
                    "bail:no_match"
                }

                !contiguous -> {
                    "hold:${if (match?.state == MatchState.Matched) "reacquired" else "match_${match?.state?.name?.lowercase()}"}"
                }

                consecutiveBacktrackCount < NavigationPolicy.BACKTRACK_CONSECUTIVE_THRESHOLD -> {
                    "bail:count_${consecutiveBacktrackCount}_of_${NavigationPolicy.BACKTRACK_CONSECUTIVE_THRESHOLD}"
                }

                sinceLastAlertMs < NavigationPolicy.BACKTRACK_ALERT_INTERVAL_MS -> {
                    "bail:cooldown_${sinceLastAlertMs}ms"
                }

                else -> {
                    "FIRING"
                }
            }
        val fired = disposition == "FIRING"
        if (fired) backtrackAlertFiredAt = sample.timestamp

        return BacktrackEvaluation(
            alongTrackM = alongM,
            prevAlongTrackM = prev,
            distanceToTargetM = distM,
            prevDistanceToTargetM = prevDistM,
            consecutiveCount = consecutiveBacktrackCount,
            sinceLastAlertMs = sinceLastAlertMs,
            disposition = disposition,
            fired = fired,
            matchState = match?.state,
            noiseFloorM = noiseFloorM,
        )
    }

    /** Accuracy-aware backtrack noise floor, widening with uncertainty but hard-capped. */
    private fun backtrackNoiseFloorM(accuracyM: Double?): Double =
        NavigationPolicy.widenWithAccuracy(
            baseM = NavigationPolicy.BACKTRACK_NOISE_FLOOR_M,
            factor = NavigationPolicy.BACKTRACK_NOISE_ACCURACY_FACTOR,
            accuracyM = accuracyM,
            capM = NavigationPolicy.BACKTRACK_NOISE_FLOOR_CAP_M,
        )
}

/** +1 when `alongTrackM` should grow with correct progress, −1 when it should shrink. */
private val TravelDirection.sign: Double
    get() = if (this == TravelDirection.Reverse) -1.0 else 1.0

/** A routine trail-guidance cue is due; the caller formats + speaks it. */
data class OrdinaryGuidanceDecision(
    val checkpointN: Int,
    val total: Int,
    val distanceToTargetM: Double,
    val relativeDeg: Double,
)

/** Off-trail detection outcome for one GPS fix; effect-free, ready for log + optional alert. */
data class OffTrailEvaluation(
    val relativeDeg: Double?,
    val followerActive: Boolean,
    val consecutiveCount: Int,
    val sinceLastAlertMs: Long,
    val disposition: String,
    val fired: Boolean,
    /** Signed distance from the trail; positive means the user is to the right of travel. */
    val crossTrackM: Double? = null,
    /** Rate of change of |cross-track|, m/s. Positive means diverging from the trail. */
    val crossTrackRateMps: Double? = null,
    /** The accuracy-aware gate |cross-track| had to exceed for this fix to count. */
    val gateM: Double? = null,
    /** Consecutive qualifying fixes required before alerting, given the corroborating evidence. */
    val requiredCount: Int = 0,
)

/** Backtrack detection outcome for one GPS fix; effect-free, ready for log + optional alert. */
data class BacktrackEvaluation(
    /** Projected position along the trail; the value the decision is actually made on. */
    val alongTrackM: Double? = null,
    /** The previous fix's along-track position. */
    val prevAlongTrackM: Double? = null,
    val distanceToTargetM: Double?,
    val prevDistanceToTargetM: Double?,
    val consecutiveCount: Int,
    val sinceLastAlertMs: Long,
    val disposition: String,
    val fired: Boolean,
    /** The ladder state that gated this decision, or `null` when there was no match at all. */
    val matchState: MatchState? = null,
    /** The accuracy-aware regression this fix had to exceed to count. */
    val noiseFloorM: Double = NavigationPolicy.BACKTRACK_NOISE_FLOOR_M,
)
