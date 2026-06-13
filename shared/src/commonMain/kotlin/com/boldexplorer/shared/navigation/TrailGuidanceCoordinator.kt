package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.LocationSample
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

    // Backtrack detection.
    private var consecutiveBacktrackCount = 0
    private var prevDistToTargetM: Double? = null
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
        val guidance = TrailGuidance.compute(followState, sample, lastTrustedCourse)
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
        offTrailGraceUntilMs = 0L
        consecutiveBacktrackCount = 0
        prevDistToTargetM = null
        backtrackAlertFiredAt = 0L
        backtrackGraceUntilMs = 0L
    }

    /** Arm grace windows and reset throttles around [sample] (e.g. on waypoint arrival / follow start). */
    fun resetThrottle(sample: LocationSample) {
        lastOrdinaryGuidanceAtMs = sample.timestamp
        lastOrdinaryGuidanceLocation = LatLng(sample.lat, sample.lon)
        consecutiveOffTrailCount = 0
        offTrailAlertFiredAt = 0L
        offTrailGraceUntilMs = sample.timestamp + OFF_TRAIL_GRACE_MS
        consecutiveBacktrackCount = 0
        prevDistToTargetM = null
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
        val followerActive = followState is TrailFollowerState.Active
        if (!followerActive) return null
        if (sample.timestamp < offTrailGraceUntilMs) return null

        val relative = guidance?.relativeDeg
        val sinceLastAlertMs = sample.timestamp - offTrailAlertFiredAt

        if (relative != null && TrailGuidance.isMajorCorrection(relative)) {
            consecutiveOffTrailCount++
        } else {
            consecutiveOffTrailCount = 0
            offTrailAlertFiredAt = 0L
        }

        val disposition =
            when {
                relative == null -> {
                    "bail:no_relative_deg"
                }

                consecutiveOffTrailCount < OFF_TRAIL_CONSECUTIVE_THRESHOLD -> {
                    "bail:count_${consecutiveOffTrailCount}_of_$OFF_TRAIL_CONSECUTIVE_THRESHOLD"
                }

                sinceLastAlertMs < OFF_TRAIL_ALERT_INTERVAL_MS -> {
                    "bail:cooldown_${sinceLastAlertMs}ms"
                }

                else -> {
                    "FIRING"
                }
            }
        val fired = disposition == "FIRING"
        if (fired) offTrailAlertFiredAt = sample.timestamp

        return OffTrailEvaluation(
            relativeDeg = relative,
            followerActive = followerActive,
            consecutiveCount = consecutiveOffTrailCount,
            sinceLastAlertMs = sinceLastAlertMs,
            disposition = disposition,
            fired = fired,
        )
    }

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
        if (followState !is TrailFollowerState.Active) return null
        if (sample.timestamp < backtrackGraceUntilMs) return null

        val distM = guidance?.distanceToTargetM
        val prev = prevDistToTargetM
        val sinceLastAlertMs = sample.timestamp - backtrackAlertFiredAt

        if (distM == null) {
            consecutiveBacktrackCount = 0
            prevDistToTargetM = null
        } else {
            if (prev != null && distM > prev + BACKTRACK_NOISE_FLOOR_M) {
                consecutiveBacktrackCount++
            } else {
                consecutiveBacktrackCount = 0
                backtrackAlertFiredAt = 0L
            }
            prevDistToTargetM = distM
        }

        val disposition =
            when {
                distM == null -> {
                    "bail:no_guidance"
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
            distanceToTargetM = distM,
            prevDistanceToTargetM = prev,
            consecutiveCount = consecutiveBacktrackCount,
            sinceLastAlertMs = sinceLastAlertMs,
            disposition = disposition,
            fired = fired,
        )
    }

    companion object {
        const val ORDINARY_GUIDANCE_INTERVAL_MS = 30_000L
        const val ORDINARY_GUIDANCE_DISTANCE_M = 25.0
        const val OFF_TRAIL_CONSECUTIVE_THRESHOLD = 2
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
)

/** Backtrack detection outcome for one GPS fix; effect-free, ready for log + optional alert. */
data class BacktrackEvaluation(
    val distanceToTargetM: Double?,
    val prevDistanceToTargetM: Double?,
    val consecutiveCount: Int,
    val sinceLastAlertMs: Long,
    val disposition: String,
    val fired: Boolean,
)
