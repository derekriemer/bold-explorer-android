package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.geo.initialBearingDeg
import com.boldexplorer.shared.model.Waypoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrailPoint(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
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
        val index: Int,               // 0-based index of NEW target
        val name: String,
        val kind: String,             // "waypoint" or "track_point"
        val total: Int,               // total waypoint count
        val distanceToNextM: Double,  // haversine from current loc to next wp
        val absoluteBearingDeg: Double, // bearing from current loc to next wp
    ) : TrailFollowerEvent()
    object TrailComplete : TrailFollowerEvent()
}

// Port of src/composables/useFollowTrail.ts.
// Call onLocationUpdate() on each GPS fix; it returns an event if the user crossed a threshold.
class TrailFollower(private val defaultThresholdM: Double = 15.0) {
    private val _state = MutableStateFlow<TrailFollowerState>(TrailFollowerState.Idle)
    val state: StateFlow<TrailFollowerState> = _state.asStateFlow()

    fun start(waypoints: List<TrailPoint>, fromIndex: Int = 0, thresholdM: Double = defaultThresholdM) {
        if (waypoints.isEmpty()) return
        val idx = fromIndex.coerceIn(0, waypoints.size - 1)
        _state.value = TrailFollowerState.Active(waypoints, idx, thresholdM)
    }

    /** Start from whichever waypoint is nearest to [location]. */
    fun startNearest(waypoints: List<TrailPoint>, location: LatLng, thresholdM: Double = defaultThresholdM) {
        if (waypoints.isEmpty()) return
        val nearestIdx = waypoints.indices.minByOrNull { i ->
            haversineDistanceMeters(location, LatLng(waypoints[i].lat, waypoints[i].lon))
        } ?: 0
        _state.value = TrailFollowerState.Active(waypoints, nearestIdx, thresholdM)
    }

    fun stop() {
        _state.value = TrailFollowerState.Idle
    }

    fun onLocationUpdate(location: LatLng): TrailFollowerEvent? {
        val current = _state.value as? TrailFollowerState.Active ?: return null
        val target = current.waypoints[current.currentIndex]
        val d = haversineDistanceMeters(location, LatLng(target.lat, target.lon))
        if (d > current.thresholdM) return null

        return if (current.currentIndex < current.waypoints.size - 1) {
            val nextIdx = current.currentIndex + 1
            val nextWp = current.waypoints[nextIdx]
            _state.value = current.copy(currentIndex = nextIdx)
            TrailFollowerEvent.WaypointReached(
                index = nextIdx,
                name = nextWp.name,
                kind = nextWp.kind,
                total = current.waypoints.size,
                distanceToNextM = haversineDistanceMeters(location, LatLng(nextWp.lat, nextWp.lon)),
                absoluteBearingDeg = initialBearingDeg(location, LatLng(nextWp.lat, nextWp.lon)),
            )
        } else {
            _state.value = TrailFollowerState.Complete
            TrailFollowerEvent.TrailComplete
        }
    }
}
