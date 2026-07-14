package com.boldexplorer.ui.collections

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.gpx.GpxFileWriter
import com.boldexplorer.gpx.GpxParseResult
import com.boldexplorer.gpx.GpxParser
import com.boldexplorer.location.FusedLocationProviderImpl
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.gpx.GpxExporter
import com.boldexplorer.shared.gpx.GpxTrail
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.repository.CollectionRepository
import com.boldexplorer.shared.repository.TrailRepository
import com.boldexplorer.shared.repository.WaypointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.boldexplorer.shared.model.Collection as ExplorerCollection

data class CollectionContents(
    val waypoints: List<Waypoint>,
    val trails: List<Trail>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CollectionsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val collectionRepo: CollectionRepository,
        private val waypointRepo: WaypointRepository,
        private val trailRepo: TrailRepository,
        locationProvider: FusedLocationProviderImpl,
    ) : ViewModel() {
        // Eagerly so we always have a cached location when the add-waypoints dialog opens,
        // even if the user navigates here without the GPS screen active.
        private val location =
            locationProvider.locationFlow
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val _allCollections: StateFlow<List<ExplorerCollection>> =
            collectionRepo
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        val collections: StateFlow<List<ExplorerCollection>> =
            combine(_allCollections, _query) { all, q ->
                if (q.isBlank()) all else all.filter { it.name.contains(q, ignoreCase = true) }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        fun setQuery(q: String) {
            _query.value = q
        }

        val allWaypoints: StateFlow<List<Waypoint>> =
            combine(waypointRepo.observeAll(), location) { wps, loc ->
                if (loc == null) {
                    wps
                } else {
                    val center = LatLng(loc.lat, loc.lon)
                    wps.sortedBy { haversineDistanceMeters(center, LatLng(it.lat, it.lon)) }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        val allTrails: StateFlow<List<Trail>> =
            trailRepo
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        // Set of collection IDs whose contents are currently expanded/loaded.
        private val _loadedCollectionIds = MutableStateFlow<Set<Long>>(emptySet())

        // Reactive map: collectionId → CollectionContents. Auto-updates on any
        // waypoint, trail, or junction-table write for loaded collections.
        val contents: StateFlow<Map<Long, CollectionContents>> =
            _loadedCollectionIds
                .flatMapLatest { ids ->
                    if (ids.isEmpty()) {
                        flowOf(emptyMap())
                    } else {
                        combine(
                            ids.map { id ->
                                combine(
                                    collectionRepo.observeWaypointsForCollection(id),
                                    collectionRepo.observeTrailsForCollection(id),
                                ) { wps, trails -> id to CollectionContents(wps, trails) }
                            },
                        ) { pairs ->
                            pairs.associate { (id, contents) -> id to contents }
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyMap())

        private val _toast = MutableStateFlow<String?>(null)
        val toast: StateFlow<String?> = _toast.asStateFlow()

        private val _exportStatus = MutableStateFlow<String?>(null)
        val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

        fun loadContents(collectionId: Long) {
            _loadedCollectionIds.value = _loadedCollectionIds.value + collectionId
        }

        fun create(
            name: String,
            description: String?,
        ) {
            viewModelScope.launch {
                val id = collectionRepo.create(name, description)
                loadContents(id)
                _toast.value = "Collection created"
            }
        }

        fun rename(
            id: Long,
            name: String,
        ) {
            viewModelScope.launch {
                collectionRepo.rename(id, name)
                _toast.value = "Collection renamed"
            }
        }

        fun delete(id: Long) {
            viewModelScope.launch {
                collectionRepo.remove(id)
                _loadedCollectionIds.value = _loadedCollectionIds.value - id
                _toast.value = "Collection deleted"
            }
        }

        fun addWaypoints(
            collectionId: Long,
            ids: Set<Long>,
        ) {
            viewModelScope.launch {
                ids.forEach { collectionRepo.attachWaypoint(collectionId, it) }
                _toast.value = "${ids.size} waypoint${if (ids.size == 1) "" else "s"} added"
            }
        }

        fun removeWaypoint(
            collectionId: Long,
            waypointId: Long,
        ) {
            viewModelScope.launch {
                collectionRepo.detachWaypoint(collectionId, waypointId)
                _toast.value = "Waypoint removed"
            }
        }

        fun addTrails(
            collectionId: Long,
            ids: Set<Long>,
        ) {
            viewModelScope.launch {
                ids.forEach { collectionRepo.attachTrail(collectionId, it) }
                _toast.value = "${ids.size} trail${if (ids.size == 1) "" else "s"} added"
            }
        }

        fun removeTrail(
            collectionId: Long,
            trailId: Long,
        ) {
            viewModelScope.launch {
                collectionRepo.detachTrail(collectionId, trailId)
                _toast.value = "Trail removed"
            }
        }

        fun clearToast() {
            _toast.value = null
        }

        fun exportCollection(collectionId: Long) {
            viewModelScope.launch {
                val collection = collectionRepo.getById(collectionId) ?: return@launch
                val waypoints = collectionRepo.waypointsForCollection(collectionId)
                val trails =
                    collectionRepo.trailsForCollection(collectionId).map { trail ->
                        GpxTrail(trail.name, trailRepo.waypointsForTrail(trail.id))
                    }
                val filename = "collection-${collection.id}.gpx"
                val gpx = GpxExporter.exportCollection(collection.name, waypoints, trails)
                GpxFileWriter
                    .writeToDownloads(context, filename, gpx)
                    .onSuccess { _exportStatus.value = "Exported ${collection.name} to Downloads/$filename" }
                    .onFailure { _exportStatus.value = "Export failed: ${it.message}" }
            }
        }

        fun clearExportStatus() {
            _exportStatus.value = null
        }

        /** Creates a new collection (named from the GPX file) and imports its contents into it. */
        fun importGpx(
            uri: Uri,
            fallbackName: String,
        ) {
            viewModelScope.launch {
                readGpx(uri)?.let { result ->
                    val collectionName = result.collectionName ?: fallbackName
                    val collectionId = collectionRepo.create(collectionName, null)
                    loadContents(collectionId)
                    applyGpxImport(collectionId, result)
                    _toast.value = "Imported \"$collectionName\" — ${result.summary}"
                }
            }
        }

        /** Imports a GPX file's waypoints/trails directly into an existing [collectionId]. */
        fun importGpxIntoCollection(
            collectionId: Long,
            uri: Uri,
        ) {
            viewModelScope.launch {
                readGpx(uri)?.let { result ->
                    loadContents(collectionId)
                    applyGpxImport(collectionId, result)
                    val name = collectionRepo.getById(collectionId)?.name ?: "collection"
                    _toast.value = "Imported ${result.summary} into $name"
                }
            }
        }

        /** Opens and parses [uri], reporting failures via [_toast]; returns null if nothing to import. */
        private suspend fun readGpx(uri: Uri): GpxParseResult? {
            try {
                val stream =
                    context.contentResolver.openInputStream(uri) ?: run {
                        _toast.value = "Could not open file"
                        return null
                    }
                val result = stream.use { GpxParser.parse(it) }
                if (result.isEmpty) {
                    _toast.value = "No waypoints or trails found in file"
                    return null
                }
                return result
            } catch (e: Exception) {
                _toast.value = "Import failed: ${e.message}"
                return null
            }
        }

        private suspend fun applyGpxImport(
            collectionId: Long,
            result: GpxParseResult,
        ) {
            result.waypoints.forEach { p ->
                waypointRepo.create(collectionId, p.name, p.lat, p.lon, p.elevM, p.description)
            }
            result.trails.forEach { trail ->
                val trailId = trailRepo.create(collectionId, trail.name, null)
                trail.points.forEach { p ->
                    if (trail.isRoute) {
                        // Route points are named, collection-owned waypoints linked to the trail.
                        val wpId = waypointRepo.create(collectionId, p.name, p.lat, p.lon, p.elevM, p.description)
                        waypointRepo.attach(trailId, wpId)
                    } else {
                        // Track points derive their collection through the trail.
                        waypointRepo.createTrackPoint(trailId, p.name, p.lat, p.lon, p.elevM)
                    }
                }
            }
        }
    }
