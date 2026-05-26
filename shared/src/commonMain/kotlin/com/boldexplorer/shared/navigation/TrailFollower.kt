package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrailPoint(val id: Long, val name: String, val lat: Double, val lon: Double)

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
    data class WaypointReached(val index: Int, val name: String) : TrailFollowerEvent()
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
            _state.value = current.copy(currentIndex = nextIdx)
            TrailFollowerEvent.WaypointReached(nextIdx, current.waypoints[nextIdx].name)
        } else {
            _state.value = TrailFollowerState.Complete
            TrailFollowerEvent.TrailComplete
        }
    }
}
