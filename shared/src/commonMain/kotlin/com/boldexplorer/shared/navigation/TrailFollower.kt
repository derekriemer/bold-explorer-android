package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.angleDifferenceDeg
import com.boldexplorer.shared.geo.distanceToSegmentMeters
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.geo.initialBearingDeg
import com.boldexplorer.shared.geo.segmentFraction
import com.boldexplorer.shared.model.Waypoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrailPoint(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val elevationM: Double? = null,
    val kind: String = Waypoint.KIND_WAYPOINT,
)

sealed class TrailFollowerState {
    object Idle : TrailFollowerState()

    data class Active(
        val waypoints: List<TrailPoint>,
        val currentIndex: Int,
        val thresholdM: Double,
    ) : TrailFollowerState()

    object Complete : TrailFollowerState()
}

sealed class TrailFollowerEvent {
    /**
     * The trail's endpoint has been reached.
     *
     * @property hedged true when GPS accuracy was too poor, or the trail match was not
     *   [MatchState.Matched] at the moment of arrival, to assert arrival plainly — so the caller
     *   should phrase this as "you should be at the end" rather than "trail complete". The radial
     *   completion route (`fireAdvance`'s "endpoint" mechanism) checks raw GPS distance to the last
     *   waypoint regardless of match state, so a walker who is off-trail but geometrically close to
     *   the endpoint's coordinates (a shortcut, a dead-reckoned drift) can trigger it with an
     *   otherwise-confident fix; a match confidence weaker than `Matched` needs the same hedge
     *   accuracy alone gives (#67).
     */
    data class TrailComplete(val hedged: Boolean) : TrailFollowerEvent()
}

/** Diagnostic payload emitted via [TrailFollower.onAdvancement] on each waypoint advance. */
data class AdvancementReason(
    val mechanism: String, // "radial" | "projection" | "divergence"
    val distanceToTargetM: Double, // 2D haversine to target at moment of advance
    val segmentFraction: Double?, // null for radial/divergence advances
    val crossTrackM: Double?, // null for radial/divergence advances
    val headingDifferenceDeg: Double?, // null when bearingDeg was not provided
    val closestApproachM: Double, // closest the user got to this target
    val smoothedBearingUsed: Boolean = false, // true when projection check fell back to smoothedBearingDeg
)

// Port of src/composables/useFollowTrail.ts.
// Call onLocationUpdate() on each GPS fix; it returns an event if the user crossed a threshold.
class TrailFollower(
    private val defaultThresholdM: Double = 15.0,
) {
    private val _state = MutableStateFlow<TrailFollowerState>(TrailFollowerState.Idle)
    val state: StateFlow<TrailFollowerState> = _state.asStateFlow()

    // Closest approach to the current target seen since last advance. Reset on each advance.
    private var closestApproachM = Double.MAX_VALUE

    // Location at the moment of the last advance; null until the first advance in a session.
    // Guards against an instant cascade through closely-spaced checkpoints (issue #9) — see
    // onLocationUpdate().
    private var positionAtLastAdvance: LatLng? = null

    /** Optional callback fired on every waypoint advance with diagnostic information. */
    var onAdvancement: ((AdvancementReason) -> Unit)? = null

    fun start(
        waypoints: List<TrailPoint>,
        fromIndex: Int = 0,
        thresholdM: Double = defaultThresholdM,
    ) {
        if (waypoints.isEmpty()) return
        val idx = fromIndex.coerceIn(0, waypoints.size - 1)
        closestApproachM = Double.MAX_VALUE
        positionAtLastAdvance = null
        _state.value = TrailFollowerState.Active(waypoints, idx, thresholdM)
    }

    fun stop() {
        closestApproachM = Double.MAX_VALUE
        positionAtLastAdvance = null
        _state.value = TrailFollowerState.Idle
    }

    /**
     * Process a GPS fix. Returns a [TrailFollowerEvent] when the user advances past a waypoint,
     * or null when no threshold was crossed.
     *
     * @param location          Current 2D position.
     * @param altitudeM         GPS altitude in metres (used for 3D distance reporting only). Pass
     *                          null when unavailable.
     * @param bearingDeg        Course-over-ground from the GPS fix in degrees [0, 360). Only
     *                          meaningful above ~1 m/s; pass null when speed is insufficient.
     * @param smoothedBearingDeg Smoothed GPS heading from [GpsHeadingSmoother] in degrees [0, 360).
     *                          Used as a fallback for the projection heading check when [bearingDeg]
     *                          is null. No-op for existing callers (defaults to null).
     * @param completion        What the session knows about finishing. Defaults to
     *                          [CompletionEvidence.None], which completes nothing: a caller that
     *                          says nothing about travel does not get a completion by accident.
     * @param matchState        The trail matcher's [MatchState] for this fix, if a match is active.
     *                          Only consulted for [TrailComplete.hedged] — the radial "endpoint"
     *                          completion route checks raw GPS distance, not match confidence, so a
     *                          state other than [MatchState.Matched] needs the same hedge poor
     *                          accuracy gets (#67). Pass null when no matcher is in play.
     */
    fun onLocationUpdate(
        location: LatLng,
        altitudeM: Double? = null,
        bearingDeg: Float? = null,
        smoothedBearingDeg: Float? = null,
        accuracyM: Double? = null,
        completion: CompletionEvidence = CompletionEvidence.None,
        matchState: MatchState? = null,
    ): TrailFollowerEvent? {
        val current = _state.value as? TrailFollowerState.Active ?: return null
        val target = current.waypoints[current.currentIndex]
        val d = haversineDistanceMeters(location, LatLng(target.lat, target.lon))

        if (d < closestApproachM) closestApproachM = d

        // Guard against an instant cascade through several closely-spaced checkpoints (issue #9):
        // require the user to have actually moved since the last advance before another one can
        // fire. Without this, a user standing still near a cluster of checkpoints spaced closer
        // together than thresholdM (e.g. auto-recorded track points, 10 m apart, vs. the default
        // 15 m threshold) could have several consecutive checkpoints simultaneously within radial
        // range of one stationary position — each new GPS fix, even with zero real movement,
        // would then independently satisfy the radial check for whatever the new target is. Does
        // not gate the very first advance in a session (positionAtLastAdvance is null then) —
        // reaching a checkpoint you're already standing at when you start is legitimate.
        positionAtLastAdvance?.let { last ->
            if (haversineDistanceMeters(location, last) < NavigationPolicy.MIN_MOVEMENT_SINCE_ADVANCE_M) return null
        }

        // 0a. Completion by geometry. The matcher says the user has reached or walked past the far
        //     end, which is a stronger claim than anything the index knows: `currentIndex` only
        //     advances when a waypoint check fires, so a walker who passed several checkpoints
        //     without tripping one can be standing beyond the end with the index well short of it.
        //     The radial branch below cannot see that case at all, because it only runs when the
        //     index *is* at the last waypoint — which is how a walk could finish with the follow
        //     still running and guidance still pointing at a waypoint behind the user.
        if (completion.completesTheTrail) {
            val atEnd = current.copy(currentIndex = current.waypoints.size - 1)
            _state.value = atEnd
            return fireAdvance(
                atEnd,
                location,
                altitudeM,
                mechanism = "endpoint_alongtrack",
                accuracyM = accuracyM,
                matchState = matchState,
            )
        }

        // 0b. Endpoint completion is a policy of its own, not a side effect of advancing off the
        //    last waypoint. The projection branch is deliberately NOT consulted here: it needs only
        //    t >= 0.9 along the final leg, so on a 300 m final segment it completed the trail 30 m
        //    out (the ~65 ft field report). Completion instead uses a radius that tightens with
        //    good GPS and is capped so poor GPS can never widen it.
        //
        //    Gated on the *same* travel evidence as 0a, and for the same reason. Arming (ADR 0002)
        //    can anchor at the walker's actual position, so a follow begun at a loop's trailhead —
        //    which is also its final track point — starts with the index already at the end and the
        //    user inside a 5–6 m radius of it. Ungated, that announces the trail complete on the
        //    first fix, before a step has been taken.
        if (current.currentIndex == current.waypoints.size - 1) {
            return if (completion.travelled && d <= NavigationPolicy.completionRadiusM(accuracyM)) {
                fireAdvance(
                    current,
                    location,
                    altitudeM,
                    null,
                    null,
                    null,
                    "endpoint",
                    accuracyM = accuracyM,
                    matchState = matchState,
                )
            } else {
                null
            }
        }

        // 1. Radial threshold — the primary, fast-path check.
        if (d <= current.thresholdM) {
            return fireAdvance(current, location, altitudeM, null, null, null, "radial")
        }

        // 2. Incoming-segment projection: user walked ≥90% of the trail leg toward this target.
        //    Guards on proximity (4× threshold) so a user 1 km past the trailhead on an
        //    open-field trail doesn't auto-advance. Also requires cross-track distance within
        //    3× threshold (user must actually be on the trail) and optional heading agreement.
        if (current.currentIndex > 0 && d <= current.thresholdM * NavigationPolicy.PROJECTION_PROXIMITY_FACTOR) {
            val prev = current.waypoints[current.currentIndex - 1]
            val prevLL = LatLng(prev.lat, prev.lon)
            val targetLL = LatLng(target.lat, target.lon)
            val t = segmentFraction(location, prevLL, targetLL)
            val crossTrack = distanceToSegmentMeters(location, prevLL, targetLL)
            val segBearing = initialBearingDeg(prevLL, targetLL)
            // Use per-fix COG when available; fall back to smoothed heading for slow walkers.
            val effectiveBearing = bearingDeg ?: smoothedBearingDeg
            val usedSmoothed = bearingDeg == null && smoothedBearingDeg != null
            val headingDiffDeg = effectiveBearing?.let { angleDifferenceDeg(it.toDouble(), segBearing) }
            val headingOk = headingDiffDeg == null || headingDiffDeg <= NavigationPolicy.HEADING_TOLERANCE_DEG
            if (t >= NavigationPolicy.INCOMING_ADVANCE_FRACTION &&
                crossTrack <= current.thresholdM * NavigationPolicy.CROSS_TRACK_FACTOR &&
                headingOk
            ) {
                return fireAdvance(current, location, altitudeM, t, crossTrack, headingDiffDeg, "projection", usedSmoothed)
            }
        }

        // 3. Divergence check: user got within 2× threshold of target (close enough to "count"
        //    as a near-miss), is now moving away by ≥5 m, and is heading toward the next point.
        //    Segment-length bound prevents false advances when the next waypoint is far away.
        val nextI = current.currentIndex + 1
        if (nextI < current.waypoints.size &&
            closestApproachM <= current.thresholdM * NavigationPolicy.CLOSENESS_FACTOR &&
            d >= closestApproachM + NavigationPolicy.DIVERGE_M
        ) {
            val nextWpLL = LatLng(current.waypoints[nextI].lat, current.waypoints[nextI].lon)
            val dNext = haversineDistanceMeters(location, nextWpLL)
            val segmentLength = haversineDistanceMeters(LatLng(target.lat, target.lon), nextWpLL)
            if (dNext < d && dNext <= segmentLength * NavigationPolicy.DIVERGE_NEXT_PROXIMITY_FACTOR) {
                return fireAdvance(current, location, altitudeM, null, null, null, "divergence")
            }
        }

        return null
    }

    private fun fireAdvance(
        current: TrailFollowerState.Active,
        location: LatLng,
        altitudeM: Double? = null,
        segFraction: Double? = null,
        crossTrackM: Double? = null,
        headingDiffDeg: Double? = null,
        mechanism: String = "radial",
        smoothedBearingUsed: Boolean = false,
        accuracyM: Double? = null,
        matchState: MatchState? = null,
    ): TrailFollowerEvent? {
        val capturedClosest = closestApproachM
        val dToTarget =
            haversineDistanceMeters(
                location,
                LatLng(current.waypoints[current.currentIndex].lat, current.waypoints[current.currentIndex].lon),
            )
        closestApproachM = Double.MAX_VALUE
        positionAtLastAdvance = location

        fun emitCallback() =
            onAdvancement?.invoke(
                AdvancementReason(mechanism, dToTarget, segFraction, crossTrackM, headingDiffDeg, capturedClosest, smoothedBearingUsed),
            )

        return if (current.currentIndex < current.waypoints.size - 1) {
            val nextIdx = current.currentIndex + 1
            _state.value = current.copy(currentIndex = nextIdx)
            emitCallback()
            // Track points advance the target silently now — the firehose of "Checkpoint N of M"
            // every ~10 m is gone. Dedicated cue producers (progress, annotation) supply what the
            // walker hears instead; onAdvancement above still fires for their diagnostics/telemetry.
            null
        } else {
            emitCallback()
            _state.value = TrailFollowerState.Complete
            TrailFollowerEvent.TrailComplete(hedged = shouldHedgeCompletion(accuracyM, matchState))
        }
    }

    /**
     * Whether the fix is too uncertain to assert arrival plainly — either the GPS fix itself was
     * poor, or the trail match was not [MatchState.Matched] at the moment of arrival (#67). The
     * latter matters because the "endpoint" radial route above completes on raw GPS distance to the
     * last waypoint regardless of match state.
     */
    private fun shouldHedgeCompletion(
        accuracyM: Double?,
        matchState: MatchState?,
    ): Boolean =
        (accuracyM != null && accuracyM > NavigationPolicy.COMPLETION_HEDGE_ABOVE_M) ||
            (matchState != null && matchState != MatchState.Matched)
}

/**
 * Maps [anchor] — a recorded along-track position, [FollowArming]'s chosen anchor — to the
 * traversal-order index [TrailFollower.start] should arm from.
 *
 * [TrailFollower.start] takes `waypoints` in **traversal order** (recorded order for `Forward`,
 * reversed for `Reverse`), while `anchor.alongTrackM` is always in **recorded** along-track — the
 * same disagreement [FollowSession] exists to keep straight for the matcher. This is that
 * conversion for the follower: find the recorded vertex the anchor sits at or just ahead of (in the
 * requested direction), then translate that recorded index into a traversal one.
 *
 * ADR 0002 §3. `poly` must be the same polyline `waypoints` was built from, in the same recorded
 * order — [FollowSession.polyline], not a second one reconstructed from `waypoints` itself, which
 * for a reverse follow is already traversal-ordered and would misread `cumulativeM`.
 */
fun followerIndexFor(
    poly: TrailPolyline,
    anchor: TrailPosition,
    direction: TravelDirection,
): Int {
    val recordedIndex =
        when (direction) {
            TravelDirection.Forward ->
                (0 until poly.size).firstOrNull { poly.cumulativeM[it] >= anchor.alongTrackM } ?: poly.size - 1
            TravelDirection.Reverse ->
                (poly.size - 1 downTo 0).firstOrNull { poly.cumulativeM[it] <= anchor.alongTrackM } ?: 0
        }
    return if (direction == TravelDirection.Forward) recordedIndex else poly.size - 1 - recordedIndex
}
