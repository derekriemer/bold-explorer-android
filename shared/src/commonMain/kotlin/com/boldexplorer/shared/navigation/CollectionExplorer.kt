package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// A point in a collection — either a standalone waypoint or one end of a trail.
sealed class CollectionPoint {
    abstract val id: Long   // unique key: waypointId for Standalone, synthetic for TrailEnd
    abstract val waypoint: Waypoint

    data class Standalone(override val waypoint: Waypoint) : CollectionPoint() {
        override val id: Long get() = waypoint.id
    }

    // isStart=true → first waypoint of trail; isStart=false → last waypoint.
    data class TrailEnd(
        override val waypoint: Waypoint,
        val trail: Trail,
        val isStart: Boolean,
    ) : CollectionPoint() {
        // Synthetic key so start and end of the same trail are distinct entries.
        override val id: Long get() = if (isStart) trail.id * 10_000L + 1 else trail.id * 10_000L + 2
    }
}

sealed class CollectionExplorerState {
    object Idle : CollectionExplorerState()

    data class Active(
        val points: List<CollectionPoint>,          // all points, sorted nearest-first
        val target: CollectionPoint?,               // null = system picks nearest
        val visitedIds: List<Long>,                 // capped at VISITED_CAP; newest last
        val exploreMode: Boolean,
        val nearTrailEndM: Double?,                 // distance to target if it's a TrailEnd, else null
        val proximityAnnouncedIds: Set<Long> = emptySet(), // points already proximity-announced this session
    ) : CollectionExplorerState()
}

sealed class CollectionExplorerEvent {
    /** Target reached; next target already selected (or null if all visited). */
    data class PointReached(val reached: CollectionPoint, val next: CollectionPoint?) : CollectionExplorerEvent()
    /** User is within TRAIL_APPROACH_M of a TrailEnd target — surface Follow button. */
    data class NearTrailEnd(val trailEnd: CollectionPoint.TrailEnd, val distanceM: Double) : CollectionExplorerEvent()
    /** User passed within PROXIMITY_M of an unvisited non-target point. Fires once per point per session. */
    data class NearbyPoint(val point: CollectionPoint, val distanceM: Double) : CollectionExplorerEvent()
}

class CollectionExplorer {
    private val _state = MutableStateFlow<CollectionExplorerState>(CollectionExplorerState.Idle)
    val state: StateFlow<CollectionExplorerState> = _state.asStateFlow()

    /** Load a collection's resolved points and start targeting. */
    fun load(points: List<CollectionPoint>, exploreMode: Boolean = false) {
        if (points.isEmpty()) {
            _state.value = CollectionExplorerState.Idle
            return
        }
        _state.value = CollectionExplorerState.Active(
            points = points,
            target = null,          // null → system will pick nearest on first GPS fix
            visitedIds = emptyList(),
            exploreMode = exploreMode,
            nearTrailEndM = null,
        )
    }

    fun stop() {
        _state.value = CollectionExplorerState.Idle
    }

    fun setExploreMode(enabled: Boolean) {
        val s = _state.value as? CollectionExplorerState.Active ?: return
        _state.value = s.copy(exploreMode = enabled)
    }

    /** User explicitly selected a target from the list. */
    fun selectTarget(point: CollectionPoint) {
        val s = _state.value as? CollectionExplorerState.Active ?: return
        _state.value = s.copy(target = point, nearTrailEndM = null)
    }

    /** Clear manual selection → system picks nearest on next GPS fix. */
    fun clearTarget() {
        val s = _state.value as? CollectionExplorerState.Active ?: return
        _state.value = s.copy(target = null, nearTrailEndM = null)
    }

    /** Reset visited queue (useful to re-tour in explore mode). */
    fun clearVisited() {
        val s = _state.value as? CollectionExplorerState.Active ?: return
        _state.value = s.copy(visitedIds = emptyList(), target = null, nearTrailEndM = null, proximityAnnouncedIds = emptySet())
    }

    /**
     * Call on every GPS fix.
     * - Re-sorts points by distance.
     * - If no manual target is set, auto-selects nearest unvisited.
     * - Checks reach threshold and trail-approach threshold.
     * Returns an event if something notable happened, null otherwise.
     */
    fun onLocationUpdate(location: LatLng): CollectionExplorerEvent? {
        val s = _state.value as? CollectionExplorerState.Active ?: return null

        // Re-sort all points by current distance.
        val sorted = s.points.sortedBy { p ->
            haversineDistanceMeters(location, LatLng(p.waypoint.lat, p.waypoint.lon))
        }

        // Resolve the current target: manual pick or nearest unvisited.
        val target = s.target ?: sorted.firstOrNull { it.id !in s.visitedIds }

        if (target == null) {
            _state.value = s.copy(points = sorted, target = null, nearTrailEndM = null)
            return null
        }

        val distToTarget = haversineDistanceMeters(
            location, LatLng(target.waypoint.lat, target.waypoint.lon),
        )

        // Trail endpoints: show Follow button when within TRAIL_APPROACH_M.
        // Never auto-reach a trail end — user must explicitly Follow or Clear to move on.
        if (target is CollectionPoint.TrailEnd) {
            val near = distToTarget <= TRAIL_APPROACH_M
            val nearTrailEndM: Double? = if (near) distToTarget else null
            val nearby = proximityCheck(sorted, target, s, location)
            _state.value = s.copy(
                points = sorted,
                target = target,
                nearTrailEndM = nearTrailEndM,
                proximityAnnouncedIds = if (nearby != null) s.proximityAnnouncedIds + nearby.id else s.proximityAnnouncedIds,
            )
            return when {
                near && nearTrailEndM != s.nearTrailEndM -> CollectionExplorerEvent.NearTrailEnd(target, distToTarget)
                nearby != null -> CollectionExplorerEvent.NearbyPoint(nearby,
                    haversineDistanceMeters(location, LatLng(nearby.waypoint.lat, nearby.waypoint.lon)))
                else -> null
            }
        }

        // Standalone waypoint: check reach threshold.
        val nearTrailEndM: Double? = null
        if (distToTarget <= REACH_THRESHOLD_M) {
            val newVisited = (s.visitedIds + target.id).takeLast(VISITED_CAP)

            // In explore mode, auto-advance to next nearest unvisited.
            val nextTarget = if (s.exploreMode) {
                sorted.firstOrNull { it.id !in newVisited }
            } else {
                null     // hold; user must clear or pick manually
            }

            _state.value = s.copy(
                points = sorted,
                target = nextTarget,
                visitedIds = newVisited,
                nearTrailEndM = null,
            )
            return CollectionExplorerEvent.PointReached(target, nextTarget)
        }

        // Proximity scan: announce unvisited non-target points within PROXIMITY_M once per session.
        val nearby = proximityCheck(sorted, target, s, location)
        _state.value = s.copy(
            points = sorted,
            target = target,
            nearTrailEndM = nearTrailEndM,
            proximityAnnouncedIds = if (nearby != null) s.proximityAnnouncedIds + nearby.id else s.proximityAnnouncedIds,
        )
        return if (nearby != null) {
            val dist = haversineDistanceMeters(location, LatLng(nearby.waypoint.lat, nearby.waypoint.lon))
            CollectionExplorerEvent.NearbyPoint(nearby, dist)
        } else null
    }

    private fun proximityCheck(
        sorted: List<CollectionPoint>,
        target: CollectionPoint,
        s: CollectionExplorerState.Active,
        location: LatLng,
    ): CollectionPoint? = sorted.firstOrNull { p ->
        p.id != target.id &&
        p.id !in s.visitedIds &&
        p.id !in s.proximityAnnouncedIds &&
        haversineDistanceMeters(location, LatLng(p.waypoint.lat, p.waypoint.lon)) <= PROXIMITY_M
    }

    companion object {
        const val REACH_THRESHOLD_M = 15.0
        const val TRAIL_APPROACH_M = 10.0
        const val PROXIMITY_M = 30.0
        const val VISITED_CAP = 3
    }
}
