package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.location.isLocationStale
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
private const val END_EPSILON_M = 0.5

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
    //
    // Null means "nothing spoken yet this session" — not a sentinel timestamp. A `Long.MIN_VALUE`
    // sentinel here overflowed: `sample.timestamp - Long.MIN_VALUE` wraps a realistic epoch-millis
    // timestamp around to a large *negative* number, which is always less than the 30 s interval, so
    // the throttle check always short-circuited and ordinary guidance could never fire from a cold
    // start. This used to be masked because `resetThrottle` ran on every track-point advance, seconds
    // into any walk — with that call gone (S6), a follow with no cached fix would keep the sentinel,
    // and the wrapped comparison, for the entire walk. Found in review, 2026-08-17.
    //
    // See also `ProgressCue.elapsedSinceMs`, which hits the same `Long.MIN_VALUE` subtraction-overflow
    // hazard and handles it with a sentinel guard instead of a nullable — a reasonable alternative,
    // kept there rather than adopted here because it would have meant re-deriving the null-vs-sentinel
    // choice a second way in the same codebase.
    private var lastOrdinaryGuidanceAtMs: Long? = null
    private var lastOrdinaryGuidanceLocation: LatLng? = null

    // Off-trail detection.
    private var offTrailGraceUntilMs = 0L

    // Two parallel copies of the same bookkeeping (count, cooldown, cross-track baseline) — see
    // [OffTrailDetectorState]'s doc for why there are two and not one. `live` drives what the app
    // actually says; `shadow` drives only the `shadow:` disposition and never freezes.
    private val offTrailLive = OffTrailDetectorState()
    private val offTrailShadow = OffTrailDetectorState()

    /**
     * The trail being followed and its matcher, or null when no follow is active.
     *
     * Owned rather than injected: see [FollowSession] for what went wrong when the caller assembled
     * these pieces itself. Everything here that needs geometry, direction or a match reads it from
     * this one place.
     */
    private var session: FollowSession? = null

    /**
     * The active follow's session, read-only, or null when no follow is active.
     *
     * `session` itself stays private — [startFollow]/[adopt] remain the only way to create or
     * replace one, which is the whole point of owning it here rather than in the ViewModel (see
     * the doc on [session]). But S6's cue producers need [FollowSession.polyline],
     * [FollowSession.direction], [FollowSession.speedMps] and [FollowSession.remainingM] directly,
     * and none of those are per-fix decisions this coordinator makes on their behalf — reconstructing
     * an equivalent [TrailPolyline] from the same points a second time is exactly the kind of drift
     * risk [OffTrailDetectorState]'s doc warns about, just one call away from here instead of inside
     * it. Exposing the session read-only is cheaper than duplicating it.
     */
    val followSession: FollowSession? get() = session

    private val followDirection: TravelDirection
        get() = session?.direction ?: TravelDirection.Forward

    // Backtrack detection.
    private var prevDistToTargetM: Double? = null
    private var backtrackGraceUntilMs = 0L

    // Same live/shadow split as off-trail, same reason — see [BacktrackDetectorState].
    private val backtrackLive = BacktrackDetectorState()
    private val backtrackShadow = BacktrackDetectorState()

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
     * Fold one GPS fix into the session: trusted course first, then the match.
     *
     * The single per-fix entry point, and the reason the detectors below can read the match rather
     * than be handed one. Ordering used to be a comment at the call site — match before the
     * follower, follower before the detectors — and a caller who got it wrong decided this fix on
     * the previous fix's position, silently.
     *
     * A **stale** sample is folded into the course but not matched. `location` in the app is a
     * StateFlow that keeps its last value indefinitely, so a caller refreshing guidance from the
     * cached fix can hand over one that is minutes old; the tracker would take that timestamp as
     * its last-confirmed time and declare itself Lost on the next real fix. That invariant belongs
     * here, where elapsed time is already the unit of thought, rather than at each call site.
     */
    fun onFix(
        sample: LocationSample,
        nowMs: Long = sample.timestamp,
    ): FixOutcome {
        val smoothed = updateTrustedCourse(sample)
        val active = session?.takeIf { !isLocationStale(nowMs, sample.timestamp) }
        active?.onFix(sample)
        return FixOutcome(smoothed, session?.lastMatch, session?.lastEvidence)
    }

    /**
     * Begin following [points] in [direction] — the one way to start a follow.
     *
     * Takes the trail in **recorded order**, with direction carried separately, and builds the
     * matcher itself. The follower's own waypoint list is reversed in place for a reverse follow,
     * which makes its along-track session-relative and incomparable with the match's, so it is not
     * what to pass here.
     *
     * @param seedAlongM where the walk was armed — [FollowArming]'s chosen anchor. `null` defaults
     *   to the traversal start. See [FollowSession].
     */
    fun startFollow(
        points: List<LatLng>,
        direction: TravelDirection,
        tuning: MatchTuning = MatchTuning.DEFAULT,
        isRecorded: Boolean = true,
        seedAlongM: Double? = null,
    ) = adopt(FollowSession(points, direction, tuning, isRecorded, seedAlongM))

    /** Adopt a prepared session. Test seam: production goes through [startFollow]. */
    internal fun adopt(followSession: FollowSession) {
        session = followSession
        // Adopting a trail is the start of a session, so the detectors start over with it. This
        // used to be left to `resetThrottle`, which follow-start only reaches when there is a
        // cached fix to refresh guidance from — start a follow cold and the new trail inherited the
        // previous one's `prevAlongTrackM` and counters, so the first fix on the new trail was
        // differenced against a position on the old one. With the count already at two, that fires
        // "you may be going the wrong way" on the first fix of a walk. Found in review, 2026-08-15.
        resetDetectors()
    }

    /**
     * Forget what the detectors had accumulated, without touching the grace windows.
     *
     * Grace is armed from a timestamp and so belongs to [resetThrottle]; this is only the evidence
     * that has to refer to one trail to mean anything.
     */
    private fun resetDetectors() {
        offTrailLive.reset()
        offTrailShadow.reset()
        prevDistToTargetM = null
        backtrackLive.reset()
        backtrackShadow.reset()
    }

    /**
     * What the session knows about finishing — see [CompletionEvidence]. The single derivation of
     * the completion rules; both of the follower's routes to `TrailComplete` read this one value.
     *
     * Completion asks geometry, not `currentIndex`. The index only advances when a waypoint check
     * fires, so a user who passed several checkpoints without tripping one can reach the end with
     * the index still well short of it — and the endpoint branch that owns completion today only
     * runs when the index *is* at the last waypoint.
     *
     * "Past the end" shows up as an [ProjectionKind.EndpointClamped] projection, because
     * `alongTrackM` is clamped to the polyline and so can never exceed its length. Which end counts
     * depends on the declared direction: a reverse walk finishes at 0.
     */
    fun completionEvidence(): CompletionEvidence {
        val followSession = session ?: return CompletionEvidence.None
        val match = followSession.lastMatch ?: return CompletionEvidence.None
        val totalLengthM = followSession.polyline.totalLengthM

        // Travel is a session accumulator, so it is readable whatever this fix did. Requiring a
        // confirmed match here would withdraw the radial completion at a terminus — the one place
        // a match is most likely to wobble, and the last place the walk can afford to lose it.
        val travelled = match.travelledM >= NavigationPolicy.completionTravelGuardM(totalLengthM)

        // Being past the end, by contrast, is a claim about where this fix is, so it needs one.
        val position = match.position?.takeIf { match.state == MatchState.Matched }
        val pastTheEnd =
            position != null &&
                position.kind == ProjectionKind.EndpointClamped &&
                when (followSession.direction) {
                    TravelDirection.Forward -> position.alongTrackM >= totalLengthM - END_EPSILON_M
                    TravelDirection.Reverse -> position.alongTrackM <= END_EPSILON_M
                }

        return CompletionEvidence(pastTheEnd = pastTheEnd, travelled = travelled)
    }

    /** Recompute and publish guidance for [sample] against [followState]; returns the new value. */
    fun computeGuidance(
        followState: TrailFollowerState,
        sample: LocationSample,
        match: TrailMatch? = session?.lastMatch,
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
        // and `TrailGuidance` remains silent. Found in review, 2026-08-15.
        val steerableAlongM =
            match
                ?.takeIf { it.state == MatchState.Matched || it.state == MatchState.Uncertain }
                ?.confirmedAlongM
        val guidance =
            TrailGuidance.compute(
                followState,
                sample,
                lastTrustedCourse,
                session?.polyline,
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
        resetOrdinaryThrottle: Boolean = false,
        nowMs: Long = sample.timestamp,
    ) {
        onFix(sample, nowMs)
        computeGuidance(followState, sample)
        if (resetOrdinaryThrottle) resetThrottle(sample)
    }

    /** Clear all guidance + detection state (trail follow stopped / completed). */
    fun clear() {
        _guidance.value = null
        session = null
        lastOrdinaryGuidanceAtMs = null
        lastOrdinaryGuidanceLocation = null
        offTrailGraceUntilMs = 0L
        backtrackGraceUntilMs = 0L
        resetDetectors()
    }

    /** Arm grace windows and reset throttles around [sample] (e.g. on waypoint arrival / follow start). */
    fun resetThrottle(sample: LocationSample) {
        lastOrdinaryGuidanceAtMs = sample.timestamp
        lastOrdinaryGuidanceLocation = LatLng(sample.lat, sample.lon)
        offTrailGraceUntilMs = sample.timestamp + NavigationPolicy.OFF_TRAIL_GRACE_MS
        backtrackGraceUntilMs = sample.timestamp + NavigationPolicy.BACKTRACK_GRACE_MS
        resetDetectors()
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
        // Null means nothing has been spoken this session, so no throttle applies — the first
        // qualifying fix may always speak. See the field comment on the property for why this is a
        // nullable "unset" rather than a sentinel timestamp.
        val lastAt = lastOrdinaryGuidanceAtMs
        if (lastAt != null && sample.timestamp - lastAt < NavigationPolicy.ORDINARY_GUIDANCE_INTERVAL_MS) return null

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
     * Update off-trail detection state and decide whether to alert. Returns null only while
     * inactive; otherwise an [OffTrailEvaluation] whose [OffTrailEvaluation.fired] tells the caller
     * to speak "You may be off trail" + beep, and whose other fields are ready to write to the audio
     * event log.
     *
     * Inside the grace window the evaluation still runs and [OffTrailEvaluation.suppressedByGrace]
     * is set, while `fired` stays false. The grace-free result remains available in
     * [OffTrailEvaluation.shadowDisposition] for field-log analysis.
     *
     * On a hand-built route (`session?.isRecorded == false`, ADR 0001, Task 10) this returns a
     * genuine early exit instead — `disposition = "bail:hand_built_route"`, `fired = false`, and
     * neither the grace nor the shadow tracks advance — because the route's polyline is invented and
     * cross-track from it is not evidence the walker left the path.
     */
    fun evaluateOffTrail(
        followState: TrailFollowerState,
        sample: LocationSample,
        guidance: TrailGuidanceState?,
        match: TrailMatch? = session?.lastMatch,
    ): OffTrailEvaluation? {
        if (followState !is TrailFollowerState.Active) return null

        // A hand-built route's polyline is invented — straight lines between waypoints nobody
        // walked — so cross-track from it is not evidence of leaving the path; it is evidence the
        // path is not a line. This is a genuine early return, above the grace/shadow machinery, not
        // another suppression flag: there is nothing meaningful to shadow here, so running the
        // detectors would pollute the field logs the shadow exists to produce with dispositions
        // about geometry no one ever walked. Backtrack is unaffected — going the wrong way along a
        // hand-built route is still going the wrong way; only cross-track is meaningless.
        if (session?.isRecorded == false) {
            return OffTrailEvaluation(
                relativeDeg = guidance?.relativeDeg,
                consecutiveCount = 0,
                sinceLastAlertMs = 0L,
                disposition = "bail:hand_built_route",
                fired = false,
            )
        }

        // Grace used to be a hard mute here: return null, and nothing below ever ran, so the
        // consecutive-fix counters could not advance during the window either. That is now a flag
        // instead of an early exit — the evaluator runs every fix and emits a disposition either
        // way, so a walk's log says what dropping grace would have done. Only the *firing* stays
        // gated, at the very end of this function. ADR 0001, S6.
        val suppressedByGrace = sample.timestamp < offTrailGraceUntilMs

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

        // The gate *widens* as accuracy degrades, so poor GPS yields fewer confident alerts — the
        // opposite direction from completion, where good GPS tightens the radius. Both are bounded:
        // an implausible accuracy report must not be able to switch off-trail detection off.
        val gateM = offTrailGateM(sample.accuracy)
        val overGate = absCrossTrackM != null && absCrossTrackM > gateM
        val far = absCrossTrackM != null && absCrossTrackM > NavigationPolicy.OFF_TRAIL_FAR_M
        // A global candidate beyond the gate is already conclusive while Lost/Unconfirmed: it is a
        // lower bound on the distance from every trail segment. Losing the directional course must
        // not demote that evidence from the two-fix ladder to the five-fix ladder.
        val rapidCorroboration =
            relative?.let(TrailGuidance::isMajorCorrection)
                ?: (match?.state == MatchState.Lost || match?.state == MatchState.Unconfirmed)

        // `shadow` always advances: it answers "what would this fix do if grace did not exist at
        // all", so it has to see every fix, in-grace or not, to mean anything. This is the fix for
        // the Critical the 2026-08-17 review found: a single counter set that ran unconditionally
        // once grace stopped being an early return let evidence gathered *during* grace decide
        // whether the *first post-grace* fix fired — a real alert that the pre-S6 code
        // would not have spoken. `live` below is what stops that from happening again.
        val shadow = advanceOffTrail(offTrailShadow, absCrossTrackM, overGate, far, rapidCorroboration, sample.timestamp)

        // `live` only *persists* what it learns outside grace — `mutate = false` during grace makes
        // this call a read-only peek, so `offTrailLive` itself is frozen exactly as the pre-S6 early
        // return froze it (it sat before every mutation). The peek still returns a same-shaped
        // result — "if this one fix were folded onto whatever live had already accumulated, without
        // saving it" — which is what lets `disposition` (below) describe something coherent even
        // during grace, without ever letting that peek reach the two-fix-minimum required to fire:
        // every grace window starts both tracks at 0 (`resetThrottle` always resets both), so a
        // single un-persisted fix can only ever reach a peeked count of 1. The switch deliberately
        // does not change this — see `fired` below for why unfreezing `live` on the switch would be
        // wrong.
        val live =
            advanceOffTrail(
                offTrailLive,
                absCrossTrackM,
                overGate,
                far,
                rapidCorroboration,
                sample.timestamp,
                mutate = !suppressedByGrace,
            )

        // `live` drives production alerts — bit-for-bit the pre-S6 behaviour. A peeked `live` can
        // never reach `required` on its own (see above), so `!suppressedByGrace &&` is
        // belt-and-suspenders rather than load-bearing. `shadow` remains measurement only.
        val fired = !suppressedByGrace && live.wouldFire

        val xt = absCrossTrackM?.roundToInt()
        // Two questions, two fields — deliberately not one string trying to answer both (ADR 0001,
        // S6, spec correction found in re-review, 2026-08-17).
        //
        // `disposition` answers "what actually happened, and why" — always from `live`, which
        // decides `fired`, so it can never contradict it: a spoken alert can never be logged as a
        // cooldown bail, and a held fix can never be logged as fired. The first version of this split
        // had `disposition` always read `shadow`, which could log `bail:cooldown_30000ms` on the
        // exact fix that spoke "You may be off trail." — a spoken alert with the *dying* shadow's
        // cooldown attached to it, because the shadow can fire (and start cooling down) tens of
        // seconds before `live` catches up past grace. Found in re-review.
        //
        // `shadowDisposition` answers the counterfactual, always from `shadow`, regardless of the
        // production alert path — this is the field a walk's log is actually for: counting `fire:` + `shadow:` in
        // `disposition` conflated "what was said" with "what removing grace would produce"; counting
        // `shadow:would_fire` in `shadowDisposition` instead keeps the two questions apart.
        val disposition = offTrailDisposition(live, absCrossTrackM, overGate, gateM, xt) { "fire:xt_${xt}m_${live.trend}" }
        val shadowDisposition =
            offTrailDisposition(shadow, absCrossTrackM, overGate, gateM, xt) { "shadow:would_fire_xt_${xt}m_${shadow.trend}" }

        return OffTrailEvaluation(
            relativeDeg = relative,
            consecutiveCount = shadow.consecutiveCount,
            sinceLastAlertMs = shadow.sinceLastAlertMs,
            disposition = disposition,
            shadowDisposition = shadowDisposition,
            fired = fired,
            crossTrackM = signedCrossTrackM,
            crossTrackRateMps = shadow.rateMps,
            gateM = gateM,
            requiredCount = shadow.required,
            suppressedByGrace = suppressedByGrace,
        )
    }

    /**
     * The shared ladder shape for both [OffTrailEvaluation.disposition] (called on whichever track
     * decided `fired`) and [OffTrailEvaluation.shadowDisposition] (called on `shadow` always) — same
     * bail/hold logic, only the terminal "this track would fire" spelling differs, via [onFire].
     */
    private fun offTrailDisposition(
        track: OffTrailTrackResult,
        absCrossTrackM: Double?,
        overGate: Boolean,
        gateM: Double,
        xt: Int?,
        onFire: () -> String,
    ): String =
        when {
            absCrossTrackM == null -> "bail:no_window"
            !overGate -> "bail:on_line_xt_${xt}m_gate_${gateM.roundToInt()}m"
            track.consecutiveCount < track.required ->
                "hold:xt_${xt}m_${track.trend}_${track.consecutiveCount}of${track.required}"
            !track.wouldFire -> "bail:cooldown_${track.sinceLastAlertMs}ms"
            // The decision, then its description — not the description parsed back into a decision.
            // `fired` used to be `disposition.startsWith("fire:")`, which made a user-facing alert
            // depend on the spelling of a log string: renaming one would have silently disabled or
            // enabled it, with nothing failing at the edit site.
            else -> onFire()
        }

    /**
     * Folds one fix into [state] and decides whether *that track* would fire. Mutates [state] only
     * when [mutate] is true; called once for `shadow` (always, `mutate = true`) and once for `live`
     * (`mutate = true` outside grace, `mutate = false` — a read-only peek — during it). See
     * [OffTrailDetectorState] for why there are two states.
     */
    private fun advanceOffTrail(
        state: OffTrailDetectorState,
        absCrossTrackM: Double?,
        overGate: Boolean,
        far: Boolean,
        rapidCorroboration: Boolean,
        nowMs: Long,
        mutate: Boolean = true,
    ): OffTrailTrackResult {
        // Signed rate of change of |cross-track|, m/s. Positive means moving away from the trail.
        // Density-independent and target-free, unlike a bearing against a possibly-stale waypoint —
        // which is why this, not the angle, is the primary divergence signal.
        val prevAbs = state.prevAbsCrossTrackM
        val elapsedS = (nowMs - state.prevCrossTrackAtMs) / 1000.0
        val rateMps =
            if (prevAbs != null && absCrossTrackM != null && elapsedS > 0.0) {
                (absCrossTrackM - prevAbs) / elapsedS
            } else {
                null
            }

        // The count resets, the cooldown does not. They answer different questions: the count asks
        // "is this sustained", the cooldown asks "did we just say this". Clearing the cooldown here
        // let a single interrupting fix re-arm the alert — and this branch is taken not only when
        // the user is back on the line but whenever cross-track is unavailable at all, which under
        // degraded GPS is often. Found in review, 2026-08-15.
        val consecutiveCount = if (overGate) state.consecutiveCount + 1 else 0

        // Corroboration shortens the sustain window; it never gates whether a fix counts. Angle is
        // evidence here, not a veto — demoting it is the whole point, since a stale target is what
        // produced the original defect.
        val diverging = rateMps != null && rateMps > NavigationPolicy.DIVERGENCE_FLOOR_MPS
        val trend = if (diverging) "diverging" else "converging"
        val required =
            if (diverging || far || rapidCorroboration) {
                NavigationPolicy.OFF_TRAIL_CONSECUTIVE_FAST
            } else {
                NavigationPolicy.OFF_TRAIL_CONSECUTIVE_SLOW
            }

        val sinceLastAlertMs = nowMs - state.alertFiredAt
        val wouldFire =
            absCrossTrackM != null &&
                overGate &&
                consecutiveCount >= required &&
                sinceLastAlertMs >= NavigationPolicy.OFF_TRAIL_ALERT_INTERVAL_MS

        if (mutate) {
            if (absCrossTrackM != null) {
                state.prevAbsCrossTrackM = absCrossTrackM
                state.prevCrossTrackAtMs = nowMs
            }
            state.consecutiveCount = consecutiveCount
            if (wouldFire) state.alertFiredAt = nowMs
        }

        return OffTrailTrackResult(consecutiveCount, required, rateMps, trend, sinceLastAlertMs, wouldFire)
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
     * Update backtrack detection state and decide whether to alert. Returns null only while
     * inactive; otherwise a [BacktrackEvaluation] whose [BacktrackEvaluation.fired] tells the caller
     * to speak "You may be going the wrong way", with diagnostics for the log.
     *
     * Inside the grace window the evaluation still runs and [BacktrackEvaluation.suppressedByGrace]
     * is set, while `fired` stays false. The grace-free result remains available in
     * [BacktrackEvaluation.shadowDisposition] for field-log analysis.
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

     * Direction comes from the session ([startFollow]), not from the caller. It was a parameter,
     * which meant the same fact had two sources: a caller that had adopted a reverse trail but
     * passed `Forward` would get sign-inverted wrong-way detection — "you may be going the wrong
     * way", continuously, to someone walking correctly.
     */
    fun evaluateBacktrack(
        followState: TrailFollowerState,
        sample: LocationSample,
        guidance: TrailGuidanceState?,
        match: TrailMatch? = session?.lastMatch,
    ): BacktrackEvaluation? {
        if (followState !is TrailFollowerState.Active) return null
        // See the matching comment in evaluateOffTrail: grace is a flag now, not an early exit, so
        // the sustain count still advances during the window and the log shows what dropping grace
        // would have done. ADR 0001, S6.
        val suppressedByGrace = sample.timestamp < backtrackGraceUntilMs

        val distM = guidance?.distanceToTargetM
        val prevDistM = prevDistToTargetM
        val alongM = match?.confirmedAlongM
        prevDistToTargetM = distM

        val contiguous = match?.state == MatchState.Matched && match.predictionErrorM == null
        // The floor widens with reported accuracy, because what it exists to reject is the position
        // noise the fix itself is declaring. A flat 2 m is a walking pace at 1 Hz, and a 25 m fix
        // moves further than that standing still.
        val noiseFloorM = backtrackNoiseFloorM(sample.accuracy)

        // `shadow` always advances; `live` only *persists* what it learns outside grace (see the
        // matching comment in evaluateOffTrail — `mutate = false` during grace makes this a
        // read-only peek, so `backtrackLive` itself stays frozen). Critical, review 2026-08-17.
        val shadow = advanceBacktrack(backtrackShadow, alongM, contiguous, match?.state, noiseFloorM, sample.timestamp)
        val live =
            advanceBacktrack(backtrackLive, alongM, contiguous, match?.state, noiseFloorM, sample.timestamp, mutate = !suppressedByGrace)

        // See evaluateOffTrail: `live` drives production alerts; `shadow` remains measurement only.
        val fired = !suppressedByGrace && live.wouldFire

        // Two fields, not one — see the matching comment in evaluateOffTrail. `disposition` is built
        // from `live`, which decides `fired`, so it can never contradict it; `shadowDisposition` is
        // always the counterfactual.
        val disposition = backtrackDisposition(live) { "FIRING" }
        val shadowDisposition = backtrackDisposition(shadow) { "shadow:would_fire" }

        return BacktrackEvaluation(
            alongTrackM = alongM,
            prevAlongTrackM = shadow.prevAlongTrackM,
            distanceToTargetM = distM,
            prevDistanceToTargetM = prevDistM,
            consecutiveCount = shadow.consecutiveCount,
            sinceLastAlertMs = shadow.sinceLastAlertMs,
            disposition = disposition,
            shadowDisposition = shadowDisposition,
            fired = fired,
            matchState = match?.state,
            noiseFloorM = noiseFloorM,
            suppressedByGrace = suppressedByGrace,
        )
    }

    /**
     * The shared ladder shape for both [BacktrackEvaluation.disposition] (called on whichever track
     * decided `fired`) and [BacktrackEvaluation.shadowDisposition] (called on `shadow` always) — same
     * bail/hold logic, only the terminal "this track would fire" spelling differs, via [onFire].
     */
    private fun backtrackDisposition(
        track: BacktrackTrackResult,
        onFire: () -> String,
    ): String =
        when {
            track.hold != null -> track.hold
            track.consecutiveCount < NavigationPolicy.BACKTRACK_CONSECUTIVE_THRESHOLD ->
                "bail:count_${track.consecutiveCount}_of_${NavigationPolicy.BACKTRACK_CONSECUTIVE_THRESHOLD}"
            !track.wouldFire -> "bail:cooldown_${track.sinceLastAlertMs}ms"
            else -> onFire()
        }

    /**
     * Folds one fix into [state] and decides whether *that track* would fire. Mutates [state] only
     * when [mutate] is true; same calling shape as [advanceOffTrail]. See [BacktrackDetectorState]
     * for why there are two instances.
     */
    private fun advanceBacktrack(
        state: BacktrackDetectorState,
        alongM: Double?,
        contiguous: Boolean,
        matchState: MatchState?,
        noiseFloorM: Double,
        nowMs: Long,
        mutate: Boolean = true,
    ): BacktrackTrackResult {
        val prev = state.prevAlongTrackM

        // Only a fresh, contiguous confirmation is evidence. A frozen value under Uncertain/Lost is
        // not movement, and the fix where geometry returns after an absence — the one carrying
        // predictionErrorM — can legitimately land far behind, because a rejoin elsewhere is not a
        // reversal. Both re-baseline instead of comparing.
        val progressM = if (contiguous && prev != null && alongM != null) (alongM - prev) * followDirection.sign else null
        val regressed = progressM != null && progressM < -noiseFloorM

        // One pass: update the count and say why, instead of two `when` ladders over the same
        // predicates that had to be kept in the same order by hand. The old shape needed a
        // do-nothing branch purely to stay aligned with its twin.
        var consecutiveCount = state.consecutiveCount
        val hold =
            when {
                alongM == null -> {
                    consecutiveCount = 0
                    "bail:no_match"
                }

                // Holds the count rather than clearing it: a gap in corroboration is not evidence
                // either way. Re-baselining prev is what stops the gap itself reading as movement.
                !contiguous -> {
                    if (matchState == MatchState.Matched) {
                        "hold:reacquired"
                    } else {
                        "hold:match_${matchState?.name?.lowercase()}"
                    }
                }

                regressed -> {
                    consecutiveCount++
                    null
                }

                // As with off-trail: the count resets, the cooldown does not.
                else -> {
                    consecutiveCount = 0
                    null
                }
            }

        val sustained = consecutiveCount >= NavigationPolicy.BACKTRACK_CONSECUTIVE_THRESHOLD
        val sinceLastAlertMs = nowMs - state.alertFiredAt
        val wouldFire = hold == null && sustained && sinceLastAlertMs >= NavigationPolicy.BACKTRACK_ALERT_INTERVAL_MS

        if (mutate) {
            state.consecutiveCount = consecutiveCount
            state.prevAlongTrackM = alongM
            if (wouldFire) state.alertFiredAt = nowMs
        }

        return BacktrackTrackResult(consecutiveCount, prev, hold, sinceLastAlertMs, wouldFire)
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

/**
 * Off-trail's per-fix bookkeeping — the consecutive-fix count, the cross-track rate baseline, and
 * this track's own alert cooldown.
 *
 * [TrailGuidanceCoordinator] holds two instances, not one: `live`, which drives what the app
 * actually says and freezes during grace exactly as the pre-S6 early return did (grace sat before
 * every mutation, so nothing moved while it was armed), and `shadow`, which advances on every fix
 * regardless of grace so it can answer "what would removing grace produce". A single shared instance
 * was tried first — mutated on every in-grace fix per the original brief's Step 3 — and that let
 * evidence gathered *during* grace decide whether the fix right after grace expired would fire: a
 * real alert that the old code would not have spoken. Found in review, 2026-08-17.
 *
 * Keep these as two instances of one class rather than prefixing each field `shadow` on a single
 * one: six near-identical fields drift when only one copy gets touched by the next edit, which is
 * exactly the class of bug this fixes.
 */
private class OffTrailDetectorState {
    var consecutiveCount = 0
    var alertFiredAt = 0L
    var prevAbsCrossTrackM: Double? = null
    var prevCrossTrackAtMs = 0L

    fun reset() {
        consecutiveCount = 0
        alertFiredAt = 0L
        prevAbsCrossTrackM = null
        prevCrossTrackAtMs = 0L
    }
}

/** One [OffTrailDetectorState] track's answer for one fix. */
private data class OffTrailTrackResult(
    val consecutiveCount: Int,
    val required: Int,
    val rateMps: Double?,
    val trend: String,
    val sinceLastAlertMs: Long,
    val wouldFire: Boolean,
)

/**
 * Backtrack's per-fix bookkeeping — the consecutive-regression count, the along-track baseline, and
 * this track's own alert cooldown. Held twice on [TrailGuidanceCoordinator] for the same reason as
 * [OffTrailDetectorState]: `live` freezes during grace exactly as the pre-S6 code did, `shadow`
 * advances on every fix so the log can say what removing grace would have produced.
 */
private class BacktrackDetectorState {
    var consecutiveCount = 0
    var alertFiredAt = 0L
    var prevAlongTrackM: Double? = null

    fun reset() {
        consecutiveCount = 0
        alertFiredAt = 0L
        prevAlongTrackM = null
    }
}

/** One [BacktrackDetectorState] track's answer for one fix. */
private data class BacktrackTrackResult(
    val consecutiveCount: Int,
    /** The along-track position [BacktrackDetectorState] held *before* this fix. */
    val prevAlongTrackM: Double?,
    val hold: String?,
    val sinceLastAlertMs: Long,
    val wouldFire: Boolean,
)

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
    val consecutiveCount: Int,
    val sinceLastAlertMs: Long,
    /** What actually happened — built from the production track that decided [fired], so never contradicts it. */
    val disposition: String,
    /**
     * What the shadow (grace-free) track would have decided, always — regardless of [fired].
     * Counting `shadow:would_fire` here is how a walk answers "what would removing
     * grace produce"; `disposition` answers "what did this walk actually do" (ADR 0001, S6, spec
     * correction found in re-review, 2026-08-17).
     */
    val shadowDisposition: String = disposition,
    val fired: Boolean,
    /** Signed distance from the trail; positive means the user is to the right of travel. */
    val crossTrackM: Double? = null,
    /** Rate of change of |cross-track|, m/s. Positive means diverging from the trail. */
    val crossTrackRateMps: Double? = null,
    /** The accuracy-aware gate |cross-track| had to exceed for this fix to count. */
    val gateM: Double? = null,
    /** Consecutive qualifying fixes required before alerting, given the corroborating evidence. */
    val requiredCount: Int = 0,
    /** Whether the off-trail grace window would have muted this fix (ADR 0001, S6). See [fired]. */
    val suppressedByGrace: Boolean = false,
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
    /** What actually happened — built from the production track that decided [fired], so never contradicts it. */
    val disposition: String,
    /**
     * What the shadow (grace-free) track would have decided, always — regardless of [fired]. See
     * [OffTrailEvaluation.shadowDisposition] for the full reasoning.
     */
    val shadowDisposition: String = disposition,
    val fired: Boolean,
    /** The ladder state that gated this decision, or `null` when there was no match at all. */
    val matchState: MatchState? = null,
    /** The accuracy-aware regression this fix had to exceed to count. */
    val noiseFloorM: Double = NavigationPolicy.BACKTRACK_NOISE_FLOOR_M,
    /** Whether the backtrack grace window would have muted this fix (ADR 0001, S6). See [fired]. */
    val suppressedByGrace: Boolean = false,
)
