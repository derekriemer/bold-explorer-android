package com.boldexplorer.ui.trails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.repository.TrailRepository
import com.boldexplorer.shared.repository.WaypointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrailsViewModel @Inject constructor(
    private val trailRepo: TrailRepository,
    private val waypointRepo: WaypointRepository,
) : ViewModel() {

    private val _trails = MutableStateFlow<List<Trail>>(emptyList())
    val trails: StateFlow<List<Trail>> = _trails.asStateFlow()

    // trailId → ordered waypoints
    private val _trailWaypoints = MutableStateFlow<Map<Long, List<Waypoint>>>(emptyMap())
    val trailWaypoints: StateFlow<Map<Long, List<Waypoint>>> = _trailWaypoints.asStateFlow()

    private val _allWaypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val allWaypoints: StateFlow<List<Waypoint>> = _allWaypoints.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
    }

    suspend fun refresh() {
        _trails.value = trailRepo.getAll()
        _allWaypoints.value = waypointRepo.getAll()
    }

    fun loadWaypoints(trailId: Long) {
        viewModelScope.launch {
            val wps = trailRepo.waypointsForTrail(trailId)
            _trailWaypoints.value = _trailWaypoints.value + (trailId to wps)
        }
    }

    fun create(name: String, description: String?) {
        viewModelScope.launch {
            val id = trailRepo.create(name, description)
            _trails.value = trailRepo.getAll()
            loadWaypoints(id)
            _toast.value = "Trail created"
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            trailRepo.update(id, name = name)
            _trails.value = trailRepo.getAll()
            _toast.value = "Trail renamed"
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            trailRepo.remove(id)
            _trails.value = trailRepo.getAll()
            _trailWaypoints.value = _trailWaypoints.value - id
            _toast.value = "Trail deleted"
        }
    }

    fun addWaypointToTrail(trailId: Long, name: String, lat: Double, lon: Double, elevM: Double?) {
        viewModelScope.launch {
            val wpId = waypointRepo.create(name, lat, lon, elevM, null)
            waypointRepo.attach(trailId, wpId)
            loadWaypoints(trailId)
            _allWaypoints.value = waypointRepo.getAll()
            _toast.value = "Waypoint added"
        }
    }

    fun attachExisting(trailId: Long, waypointId: Long) {
        viewModelScope.launch {
            waypointRepo.attach(trailId, waypointId)
            loadWaypoints(trailId)
            _toast.value = "Waypoint attached"
        }
    }

    fun detachWaypoint(trailId: Long, waypointId: Long) {
        viewModelScope.launch {
            waypointRepo.detach(trailId, waypointId)
            loadWaypoints(trailId)
            _toast.value = "Waypoint detached"
        }
    }

    // currentIndex is 0-based; setPosition takes 1-based position.
    fun moveUp(trailId: Long, waypointId: Long, currentIndex: Int) {
        if (currentIndex <= 0) return
        viewModelScope.launch {
            waypointRepo.setPosition(trailId, waypointId, currentIndex)
            loadWaypoints(trailId)
        }
    }

    fun moveDown(trailId: Long, waypointId: Long, currentIndex: Int, listSize: Int) {
        if (currentIndex >= listSize - 1) return
        viewModelScope.launch {
            waypointRepo.setPosition(trailId, waypointId, currentIndex + 2)
            loadWaypoints(trailId)
        }
    }

    fun clearToast() { _toast.value = null }
}
