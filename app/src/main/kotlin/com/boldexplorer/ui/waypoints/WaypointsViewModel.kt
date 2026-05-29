package com.boldexplorer.ui.waypoints

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.location.FusedLocationProviderImpl
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.repository.SettingsRepository
import com.boldexplorer.shared.repository.TrailRepository
import com.boldexplorer.shared.repository.WaypointRepository
import com.boldexplorer.shared.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WaypointListItem(
    val waypoint: Waypoint,
    val distanceM: Double?,
)

@HiltViewModel
class WaypointsViewModel @Inject constructor(
    private val waypointRepo: WaypointRepository,
    private val trailRepo: TrailRepository,
    settingsRepo: SettingsRepository,
    locationProvider: FusedLocationProviderImpl,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _allWaypoints = waypointRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val trails: StateFlow<List<Trail>> = trailRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepo.observeSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), AppSettings())

    private val location = locationProvider.locationFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val waypoints: StateFlow<List<WaypointListItem>> = combine(_allWaypoints, _query, location) { all, q, loc ->
        val filtered = if (q.isBlank()) all
        else all.filter { it.name.contains(q, ignoreCase = true) }

        if (loc == null) {
            filtered.map { WaypointListItem(it, null) }
        } else {
            val center = LatLng(loc.lat, loc.lon)
            filtered
                .map { wp ->
                    WaypointListItem(
                        waypoint = wp,
                        distanceM = haversineDistanceMeters(center, LatLng(wp.lat, wp.lon)),
                    )
                }
                .sortedBy { it.distanceM }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun setQuery(q: String) { _query.value = q }

    fun create(name: String, lat: Double, lon: Double, elevM: Double?) {
        viewModelScope.launch {
            waypointRepo.create(name, lat, lon, elevM, null)
            _toast.value = "Waypoint added"
        }
    }

    fun update(id: Long, name: String, lat: Double, lon: Double, elevM: Double?) {
        viewModelScope.launch {
            waypointRepo.update(id, name, lat, lon, elevM)
            _toast.value = "Waypoint updated"
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            waypointRepo.remove(id)
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
