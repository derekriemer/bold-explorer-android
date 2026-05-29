package com.boldexplorer.ui.collections

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.gpx.GpxExporter
import com.boldexplorer.gpx.GpxFileWriter
import com.boldexplorer.gpx.GpxTrail
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.model.Collection as ExplorerCollection
import com.boldexplorer.shared.repository.CollectionRepository
import com.boldexplorer.shared.repository.TrailRepository
import com.boldexplorer.shared.repository.WaypointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionContents(
    val waypoints: List<Waypoint>,
    val trails: List<Trail>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CollectionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val collectionRepo: CollectionRepository,
    private val waypointRepo: WaypointRepository,
    private val trailRepo: TrailRepository,
) : ViewModel() {

    val collections: StateFlow<List<ExplorerCollection>> = collectionRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val allWaypoints: StateFlow<List<Waypoint>> = waypointRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val allTrails: StateFlow<List<Trail>> = trailRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    // Set of collection IDs whose contents are currently expanded/loaded.
    private val _loadedCollectionIds = MutableStateFlow<Set<Long>>(emptySet())

    // Reactive map: collectionId → CollectionContents. Auto-updates on any
    // waypoint, trail, or junction-table write for loaded collections.
    val contents: StateFlow<Map<Long, CollectionContents>> = _loadedCollectionIds
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(ids.map { id ->
                    combine(
                        collectionRepo.observeWaypointsForCollection(id),
                        collectionRepo.observeTrailsForCollection(id),
                    ) { wps, trails -> id to CollectionContents(wps, trails) }
                }) { pairs ->
                    pairs.associate { (id, contents) -> id to contents }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyMap())

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    fun loadContents(collectionId: Long) {
        _loadedCollectionIds.value = _loadedCollectionIds.value + collectionId
    }

    fun create(name: String, description: String?) {
        viewModelScope.launch {
            val id = collectionRepo.create(name, description)
            loadContents(id)
            _toast.value = "Collection created"
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            collectionRepo.remove(id)
            _loadedCollectionIds.value = _loadedCollectionIds.value - id
            _toast.value = "Collection deleted"
        }
    }

    fun addWaypoint(collectionId: Long, waypointId: Long) {
        viewModelScope.launch {
            collectionRepo.attachWaypoint(collectionId, waypointId)
            _toast.value = "Waypoint added"
        }
    }

    fun removeWaypoint(collectionId: Long, waypointId: Long) {
        viewModelScope.launch {
            collectionRepo.detachWaypoint(collectionId, waypointId)
            _toast.value = "Waypoint removed"
        }
    }

    fun addTrail(collectionId: Long, trailId: Long) {
        viewModelScope.launch {
            collectionRepo.attachTrail(collectionId, trailId)
            _toast.value = "Trail added"
        }
    }

    fun removeTrail(collectionId: Long, trailId: Long) {
        viewModelScope.launch {
            collectionRepo.detachTrail(collectionId, trailId)
            _toast.value = "Trail removed"
        }
    }

    fun clearToast() { _toast.value = null }

    fun exportCollection(collectionId: Long) {
        viewModelScope.launch {
            val collection = collectionRepo.getById(collectionId) ?: return@launch
            val waypoints = collectionRepo.waypointsForCollection(collectionId)
            val trails = collectionRepo.trailsForCollection(collectionId).map { trail ->
                GpxTrail(trail.name, trailRepo.waypointsForTrail(trail.id))
            }
            val filename = "collection-${collection.id}.gpx"
            val gpx = GpxExporter.exportCollection(collection.name, waypoints, trails)
            GpxFileWriter.writeToDownloads(context, filename, gpx)
                .onSuccess { _exportStatus.value = "Exported ${collection.name} to Downloads/$filename" }
                .onFailure { _exportStatus.value = "Export failed: ${it.message}" }
        }
    }

    fun clearExportStatus() { _exportStatus.value = null }
}
