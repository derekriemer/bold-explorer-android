package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.LocationSample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
     * Cached polyline for the followed trail.
     *
     * Keyed on the waypoint list's *identity*, not its contents: [TrailFollower] copies its state
     * on every advance but carries the same list instance forward, so identity holds for a session
     * and changes exactly when a different trail is followed. Rebuilding a 10k-point polyline on
     * every fix would not be viable.
     */
    private var cachedPolylineFor: List<TrailPoint>? = null
    private var cachedPolyline: TrailPolyline? = null

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

    /** Recompute and publish guidance for [sample] against [followState]; returns the new value. */
    fun computeGuidance(
        followState: TrailFollowerState,
        sample: LocationSample,
    ): TrailGuidanceState? {
        // Pass the cached polyline so the desired course comes from a chord over a physical
        // baseline rather than one noisy recorded segment. Without this the new path is dead code.
        val polyline = (followState as? TrailFollowerState.Active)?.let { polylineFor(it) }
        val guidance = TrailGuidance.compute(followState, sample, lastTrustedCourse, polyline)
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
    ) {
        updateTrustedCourse(sample)
        computeGuidance(followState, sample)
        if (resetOrdinaryThrottle) resetThrottle(sample)
    }

    /** Clear all guidance + detection state (trail follow stopped / completed). */
    fun clear() {
        _guidance.value = null
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
        offTrailGraceUntilMs = sample.timestamp + OFF_TRAIL_GRACE_MS
        consecutiveBacktrackCount = 0
        prevDistToTargetM = null
        prevAlongTrackM = null
        backtrackAlertFiredAt = 0L
        backtrackGraceUntilMs = sample.timestamp + BACKTRACK_GRACE_MS
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
        if (sample.timestamp - lastOrdinaryGuidanceAtMs < ORDINARY_GUIDANCE_INTERVAL_MS) return null

        val current = LatLng(sample.lat, sample.lon)
        val lastLocation = lastOrdinaryGuidanceLocation
        if (lastLocation != null &&
            haversineDistanceMeters(lastLocation, current) < ORDINARY_GUIDANCE_DISTANCE_M
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
    ): OffTrailEvaluation? {
        val active = followState as? TrailFollowerState.Active ?: return null
        if (sample.timestamp < offTrailGraceUntilMs) return null

        val relative = guidance?.relativeDeg
        val signedCrossTrackM =
            polylineFor(active)?.project(LatLng(sample.lat, sample.lon))?.crossTrackM
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
        val diverging = rateMps != null && rateMps > DIVERGENCE_FLOOR_MPS
        val far = absCrossTrackM != null && absCrossTrackM > OFF_TRAIL_FAR_M
        val angleAgrees = relative != null && TrailGuidance.isMajorCorrection(relative)
        val required =
            if (diverging || far || angleAgrees) OFF_TRAIL_CONSECUTIVE_FAST else OFF_TRAIL_CONSECUTIVE_SLOW

        val sinceLastAlertMs = sample.timestamp - offTrailAlertFiredAt
        val xt = absCrossTrackM?.roundToInt()
        val trend = if (diverging) "diverging" else "converging"
        val disposition =
            when {
                absCrossTrackM == null -> "bail:no_geometry"
                !overGate -> "bail:on_line_xt_${xt}m_gate_${gateM.roundToInt()}m"
                consecutiveOffTrailCount < required ->
                    "hold:xt_${xt}m_${trend}_${consecutiveOffTrailCount}of$required"
                sinceLastAlertMs < OFF_TRAIL_ALERT_INTERVAL_MS -> "bail:cooldown_${sinceLastAlertMs}ms"
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

    /** Builds or reuses the polyline for [active]'s geometry. Null when there is no segment yet. */
    private fun polylineFor(active: TrailFollowerState.Active): TrailPolyline? {
        if (active.waypoints.size < 2) return null
        if (cachedPolylineFor !== active.waypoints) {
            cachedPolyline = TrailPolyline(active.waypoints.map { LatLng(it.lat, it.lon) })
            cachedPolylineFor = active.waypoints
        }
        return cachedPolyline
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
        min(OFF_TRAIL_GATE_CAP_M, max(OFF_TRAIL_BASE_M, OFF_TRAIL_ACCURACY_FACTOR * (accuracyM ?: 0.0)))

    /**
     * Update backtrack detection state and decide whether to alert. Returns null while inactive or
     * inside the grace window; otherwise a [BacktrackEvaluation] whose [BacktrackEvaluation.fired]
     * tells the caller to speak "You may be going the wrong way", with diagnostics for the log.
     */
    fun evaluateBacktrack(
        followState: TrailFollowerState,
        sample: LocationSample,
        guidance: TrailGuidanceState?,
    ): BacktrackEvaluation? {
        val active = followState as? TrailFollowerState.Active ?: return null
        if (sample.timestamp < backtrackGraceUntilMs) return null

        // Decided on along-track regression, not on distance to the current target. Distance grows
        // whenever the user walks past *any* turn, or whenever the target is stale and behind them,
        // so keying on it announced "wrong way" to people going the right way — and did so about
        // five times faster than off-trail could report the real problem.
        val distM = guidance?.distanceToTargetM
        val alongM = polylineFor(active)?.project(LatLng(sample.lat, sample.lon))?.alongTrackM
        val prev = prevAlongTrackM
        val sinceLastAlertMs = sample.timestamp - backtrackAlertFiredAt

        prevDistToTargetM = distM
        if (alongM == null) {
            consecutiveBacktrackCount = 0
            prevAlongTrackM = null
        } else {
            if (prev != null && alongM < prev - BACKTRACK_NOISE_FLOOR_M) {
                consecutiveBacktrackCount++
            } else {
                consecutiveBacktrackCount = 0
                backtrackAlertFiredAt = 0L
            }
            prevAlongTrackM = alongM
        }

        val disposition =
            when {
                alongM == null -> {
                    "bail:no_geometry"
                }

                consecutiveBacktrackCount < BACKTRACK_CONSECUTIVE_THRESHOLD -> {
                    "bail:count_${consecutiveBacktrackCount}_of_$BACKTRACK_CONSECUTIVE_THRESHOLD"
                }

                sinceLastAlertMs < BACKTRACK_ALERT_INTERVAL_MS -> {
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
            prevDistanceToTargetM = prevDistToTargetM,
            consecutiveCount = consecutiveBacktrackCount,
            sinceLastAlertMs = sinceLastAlertMs,
            disposition = disposition,
            fired = fired,
        )
    }

    companion object {
        const val ORDINARY_GUIDANCE_INTERVAL_MS = 30_000L
        const val ORDINARY_GUIDANCE_DISTANCE_M = 25.0
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
        const val BACKTRACK_CONSECUTIVE_THRESHOLD = 3
        const val BACKTRACK_NOISE_FLOOR_M = 2.0
        const val BACKTRACK_ALERT_INTERVAL_MS = 45_000L
        const val BACKTRACK_GRACE_MS = 20_000L
    }
}

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
)
