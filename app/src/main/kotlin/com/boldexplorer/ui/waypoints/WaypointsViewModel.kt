package com.boldexplorer.ui.waypoints

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.repository.TrailRepository
import com.boldexplorer.shared.repository.WaypointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WaypointsViewModel @Inject constructor(
    private val waypointRepo: WaypointRepository,
    private val trailRepo: TrailRepository,
) : ViewModel() {

    private val _allWaypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _trails = MutableStateFlow<List<Trail>>(emptyList())
    val trails: StateFlow<List<Trail>> = _trails.asStateFlow()

    val waypoints: StateFlow<List<Waypoint>> = combine(_allWaypoints, _query) { all, q ->
        if (q.isBlank()) all
        else all.filter { it.name.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
    }

    suspend fun refresh() {
        _allWaypoints.value = waypointRepo.getAll()
        _trails.value = trailRepo.getAll()
    }

    fun setQuery(q: String) { _query.value = q }

    fun create(name: String, lat: Double, lon: Double, elevM: Double?) {
        viewModelScope.launch {
            waypointRepo.create(name, lat, lon, elevM, null)
            _allWaypoints.value = waypointRepo.getAll()
            _toast.value = "Waypoint added"
        }
    }

    fun update(id: Long, name: String, lat: Double, lon: Double, elevM: Double?) {
        viewModelScope.launch {
            waypointRepo.update(id, name, lat, lon, elevM)
            _allWaypoints.value = waypointRepo.getAll()
            _toast.value = "Waypoint updated"
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            waypointRepo.remove(id)
            _allWaypoints.value = waypointRepo.getAll()
            _toast.value = "Waypoint deleted"
        }
    }

    fun attach(waypointId: Long, trailId: Long) {
        viewModelScope.launch {
            waypointRepo.attach(trailId, waypointId)
            _toast.value = "Attached to trail"
        }
    }

    fun clearToast() { _toast.value = null }
}
