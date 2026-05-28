package com.boldexplorer.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.model.Collection as ExplorerCollection
import com.boldexplorer.shared.repository.CollectionRepository
import com.boldexplorer.shared.repository.TrailRepository
import com.boldexplorer.shared.repository.WaypointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionContents(
    val waypoints: List<Waypoint>,
    val trails: List<Trail>,
)

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val collectionRepo: CollectionRepository,
    private val waypointRepo: WaypointRepository,
    private val trailRepo: TrailRepository,
) : ViewModel() {

    private val _collections = MutableStateFlow<List<ExplorerCollection>>(emptyList())
    val collections: StateFlow<List<ExplorerCollection>> = _collections.asStateFlow()

    private val _contents = MutableStateFlow<Map<Long, CollectionContents>>(emptyMap())
    val contents: StateFlow<Map<Long, CollectionContents>> = _contents.asStateFlow()

    private val _allWaypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val allWaypoints: StateFlow<List<Waypoint>> = _allWaypoints.asStateFlow()

    private val _allTrails = MutableStateFlow<List<Trail>>(emptyList())
    val allTrails: StateFlow<List<Trail>> = _allTrails.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
    }

    suspend fun refresh() {
        _collections.value = collectionRepo.getAll()
        _allWaypoints.value = waypointRepo.getAll()
        _allTrails.value = trailRepo.getAll()
    }

    fun loadContents(collectionId: Long) {
        viewModelScope.launch {
            val wps = collectionRepo.waypointsForCollection(collectionId)
            val trails = collectionRepo.trailsForCollection(collectionId)
            _contents.value = _contents.value + (collectionId to CollectionContents(wps, trails))
        }
    }

    fun create(name: String, description: String?) {
        viewModelScope.launch {
            val id = collectionRepo.create(name, description)
            _collections.value = collectionRepo.getAll()
            loadContents(id)
            _toast.value = "Collection created"
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            collectionRepo.remove(id)
            _collections.value = collectionRepo.getAll()
            _contents.value = _contents.value - id
            _toast.value = "Collection deleted"
        }
    }

    fun addWaypoint(collectionId: Long, waypointId: Long) {
        viewModelScope.launch {
            collectionRepo.attachWaypoint(collectionId, waypointId)
            loadContents(collectionId)
            _toast.value = "Waypoint added"
        }
    }

    fun removeWaypoint(collectionId: Long, waypointId: Long) {
        viewModelScope.launch {
            collectionRepo.detachWaypoint(collectionId, waypointId)
            loadContents(collectionId)
            _toast.value = "Waypoint removed"
        }
    }

    fun addTrail(collectionId: Long, trailId: Long) {
        viewModelScope.launch {
            collectionRepo.attachTrail(collectionId, trailId)
            loadContents(collectionId)
            _toast.value = "Trail added"
        }
    }

    fun removeTrail(collectionId: Long, trailId: Long) {
        viewModelScope.launch {
            collectionRepo.detachTrail(collectionId, trailId)
            loadContents(collectionId)
            _toast.value = "Trail removed"
        }
    }

    fun clearToast() { _toast.value = null }
}
