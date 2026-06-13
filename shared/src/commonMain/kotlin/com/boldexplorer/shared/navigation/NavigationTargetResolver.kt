package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.deltaAngle
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.geo.initialBearingDeg
import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Resolves the *active navigation target* and the geometry to it, extracted from `GpsViewModel` so
 * that class stays a coordinator rather than a god object.
 *
 * It owns one precedence rule, applied in a single place: while a trail is being followed, the
 * target is the follower's current waypoint; otherwise it is the collection explorer's target.
 * (Trail follow wins so audio/telemetry track the segment being walked rather than the nearest
 * collection point.) From that target it derives the bearing, distance, and the signed delta to the
 * GPS course.
 *
 * Pure flow math over shared inputs — no Android dependencies — so it is JVM-testable in `:shared`.
 *
 * @param navHeadingDeg GPS course-over-ground (not compass heading): the user is travelling toward
 *   the target, so [relativeDeg] is course-relative. Trail-follow beacons/speech use [TrailGuidance]
 *   instead so they point along the trail segment, not necessarily direct-to-target.
 */
class NavigationTargetResolver(
    scope: CoroutineScope,
    trailFollowState: StateFlow<TrailFollowerState>,
    explorerState: StateFlow<CollectionExplorerState>,
    location: StateFlow<LocationSample?>,
    navHeadingDeg: StateFlow<Double?>,
) {
    val targetCoord: StateFlow<LatLng?> =
        combine(trailFollowState, explorerState) { fs, explorer ->
            val trailWp = (fs as? TrailFollowerState.Active)?.waypoints?.getOrNull(fs.currentIndex)
            if (trailWp != null) {
                LatLng(trailWp.lat, trailWp.lon)
            } else {
                (explorer as? CollectionExplorerState.Active)?.target?.waypoint?.let { LatLng(it.lat, it.lon) }
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), null)

    val targetName: StateFlow<String?> =
        combine(trailFollowState, explorerState) { fs, explorer ->
            val trailWp = (fs as? TrailFollowerState.Active)?.waypoints?.getOrNull(fs.currentIndex)
            if (trailWp != null) {
                trailWp.name
            } else {
                (explorer as? CollectionExplorerState.Active)?.target?.displayName()
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), null)

    val bearingDeg: StateFlow<Double?> =
        combine(location, targetCoord) { loc, target ->
            if (loc == null || target == null) {
                null
            } else {
                initialBearingDeg(LatLng(loc.lat, loc.lon), target)
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), null)

    val distanceM: StateFlow<Double?> =
        combine(location, targetCoord) { loc, target ->
            if (loc == null || target == null) {
                null
            } else {
                haversineDistanceMeters(LatLng(loc.lat, loc.lon), target)
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), null)

    val relativeDeg: StateFlow<Double?> =
        combine(navHeadingDeg, bearingDeg) { heading, bearing ->
            if (heading == null || bearing == null) {
                null
            } else {
                deltaAngle(heading, bearing)
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), null)
}
