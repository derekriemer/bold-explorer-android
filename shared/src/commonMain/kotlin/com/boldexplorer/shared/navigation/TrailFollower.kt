package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.angleDifferenceDeg
import com.boldexplorer.shared.geo.distance3DMeters
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
    // Emitted when user reaches a waypoint; name/index are the NEW current target.
    data class WaypointReached(
        val index: Int, // 0-based index of NEW target
        val name: String,
        val kind: String, // "waypoint" or "track_point"
        val total: Int, // total waypoint count
        val distanceToNextM: Double, // distance from current loc to next wp (3D when elevation available)
        val absoluteBearingDeg: Double, // bearing from current loc to next wp
    ) : TrailFollowerEvent()

    /**
     * The trail's endpoint has been reached.
     *
     * @property hedged true when GPS accuracy was too poor to assert arrival plainly, so the
     *   caller should phrase this as "you should be at the end" rather than "trail complete".
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

    /**
     * Start from whichever waypoint is nearest to [location].
     *
     * When [bearingDeg] is provided, waypoints more than 90° behind the user are discarded
     * and the winner is the lowest combined score of distance × heading penalty.
     * If all waypoints are behind the user, falls back to nearest by distance.
     */
    fun startNearest(
        waypoints: List<TrailPoint>,
        location: LatLng,
        bearingDeg: Float? = null,
        thresholdM: Double = defaultThresholdM,
    ) {
        if (waypoints.isEmpty()) return
        val nearestIdx =
            if (bearingDeg == null) {
                waypoints.indices.minByOrNull { i ->
                    haversineDistanceMeters(location, LatLng(waypoints[i].lat, waypoints[i].lon))
                } ?: 0
            } else {
                val aheadCandidates =
                    waypoints.indices.filter { i ->
                        val wpBearing = initialBearingDeg(location, LatLng(waypoints[i].lat, waypoints[i].lon))
                        angleDifferenceDeg(bearingDeg.toDouble(), wpBearing) <= 90.0
                    }
                // Fall back to all candidates when every waypoint is behind the user.
                val pool = aheadCandidates.ifEmpty { waypoints.indices.toList() }
                pool.minByOrNull { i ->
                    val dist = haversineDistanceMeters(location, LatLng(waypoints[i].lat, waypoints[i].lon))
                    val wpBearing = initialBearingDeg(location, LatLng(waypoints[i].lat, waypoints[i].lon))
                    val penalty = angleDifferenceDeg(bearingDeg.toDouble(), wpBearing)
                    dist * (1.0 + penalty / 90.0)
                } ?: 0
            }
        closestApproachM = Double.MAX_VALUE
        positionAtLastAdvance = null
        _state.value = TrailFollowerState.Active(waypoints, nearestIdx, thresholdM)
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
     */
    fun onLocationUpdate(
        location: LatLng,
        altitudeM: Double? = null,
        bearingDeg: Float? = null,
        smoothedBearingDeg: Float? = null,
        accuracyM: Double? = null,
        reachedEnd: Boolean = false,
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
        if (reachedEnd) {
            val atEnd = current.copy(currentIndex = current.waypoints.size - 1)
            _state.value = atEnd
            return fireAdvance(atEnd, location, altitudeM, mechanism = "endpoint_alongtrack", accuracyM = accuracyM)
        }

        // 0b. Endpoint completion is a policy of its own, not a side effect of advancing off the
        //    last waypoint. The projection branch is deliberately NOT consulted here: it needs only
        //    t >= 0.9 along the final leg, so on a 300 m final segment it completed the trail 30 m
        //    out (the ~65 ft field report). Completion instead uses a radius that tightens with
        //    good GPS and is capped so poor GPS can never widen it.
        if (current.currentIndex == current.waypoints.size - 1) {
            return if (d <= completionRadiusM(accuracyM)) {
                fireAdvance(current, location, altitudeM, null, null, null, "endpoint", accuracyM = accuracyM)
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
    ): TrailFollowerEvent {
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
            val nextWp = current.waypoints[nextIdx]
            _state.value = current.copy(currentIndex = nextIdx)
            val nextLL = LatLng(nextWp.lat, nextWp.lon)
            val horizDist = haversineDistanceMeters(location, nextLL)
            val distToNext =
                if (altitudeM != null && nextWp.elevationM != null) {
                    distance3DMeters(horizDist, altitudeM - nextWp.elevationM)
                } else {
                    horizDist
                }
            emitCallback()
            TrailFollowerEvent.WaypointReached(
                index = nextIdx,
                name = nextWp.name,
                kind = nextWp.kind,
                total = current.waypoints.size,
                distanceToNextM = distToNext,
                absoluteBearingDeg = initialBearingDeg(location, nextLL),
            )
        } else {
            emitCallback()
            _state.value = TrailFollowerState.Complete
            TrailFollowerEvent.TrailComplete(hedged = shouldHedgeCompletion(accuracyM))
        }
    }

    /**
     * Radius around the trail's end within which completion may fire.
     *
     * `min(ceiling, max(floor, factor × accuracy))`. Good GPS **tightens** the radius below the
     * default; poor GPS clamps at the ceiling and can never widen it. This is the inverse of the
     * `max(floor, factor × accuracy)` pattern used elsewhere in the codebase, which expands the
     * acceptance region exactly when the fix is least trustworthy.
     *
     * Android reports accuracy as a 68% (1σ) horizontal radius, so a factor of 2 is roughly 95%
     * containment rather than an arbitrary multiplier. A null accuracy carries no information, so
     * it falls back to the ceiling — the behaviour before this policy existed.
     */
    private fun completionRadiusM(accuracyM: Double?): Double =
        NavigationPolicy.tightenWithAccuracy(
            ceilingM = NavigationPolicy.COMPLETION_CEILING_M,
            floorM = NavigationPolicy.COMPLETION_FLOOR_M,
            factor = NavigationPolicy.COMPLETION_SIGMA_FACTOR,
            accuracyM = accuracyM,
        )

    /** Whether the fix is too uncertain to assert arrival plainly. */
    private fun shouldHedgeCompletion(accuracyM: Double?): Boolean =
        accuracyM != null && accuracyM > NavigationPolicy.COMPLETION_HEDGE_ABOVE_M
}
