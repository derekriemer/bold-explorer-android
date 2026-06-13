package com.boldexplorer.ui.trails

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.gpx.GpxFileWriter
import com.boldexplorer.gpx.GpxParser
import com.boldexplorer.location.FusedLocationProviderImpl
import com.boldexplorer.location.TargetingStateHolder
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.gpx.GpxExporter
import com.boldexplorer.shared.model.Collection
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrailsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val trailRepo: TrailRepository,
        private val waypointRepo: WaypointRepository,
        private val collectionRepo: CollectionRepository,
        private val targetingStateHolder: TargetingStateHolder,
        locationProvider: FusedLocationProviderImpl,
    ) : ViewModel() {
        // Current fix, used to decide which trail end is nearest for the end-proximity actions.
        val currentLocation: StateFlow<LatLng?> =
            locationProvider.locationFlow
                .map { it?.let { s -> LatLng(s.lat, s.lon) } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        val trails: StateFlow<List<Trail>> =
            trailRepo
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        val collections: StateFlow<List<Collection>> =
            collectionRepo
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        // User-created waypoints only — used for "attach existing" candidates.
        val allWaypoints: StateFlow<List<Waypoint>> =
            waypointRepo
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        // Level-1 expanded trail IDs: show named waypoints + track point count.
        private val _expandedTrailIds = MutableStateFlow<Set<Long>>(emptySet())

        // Level-2 expanded trail IDs: also show the full track point list.
        private val _trackExpandedTrailIds = MutableStateFlow<Set<Long>>(emptySet())

        // Named waypoints (kind='waypoint') per expanded trail. Reactive.
        val namedWaypoints: StateFlow<Map<Long, List<Waypoint>>> =
            _expandedTrailIds
                .flatMapLatest { ids ->
                    if (ids.isEmpty()) {
                        flowOf(emptyMap())
                    } else {
                        combine(
                            ids.map { id ->
                                trailRepo.observeNamedWaypointsForTrail(id).map { wps -> id to wps }
                            },
                        ) { pairs ->
                            pairs.associate { (id, wps) -> id to wps }
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyMap())

        // Track point counts per expanded trail. Reactive.
        val trackPointCounts: StateFlow<Map<Long, Long>> =
            _expandedTrailIds
                .flatMapLatest { ids ->
                    if (ids.isEmpty()) {
                        flowOf(emptyMap())
                    } else {
                        combine(
                            ids.map { id ->
                                trailRepo.observeTrackPointCountForTrail(id).map { count -> id to count }
                            },
                        ) { pairs ->
                            pairs.associate { (id, count) -> id to count }
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyMap())

        // Full track point lists per level-2 expanded trail. Reactive.
        val trackPoints: StateFlow<Map<Long, List<Waypoint>>> =
            _trackExpandedTrailIds
                .flatMapLatest { ids ->
                    if (ids.isEmpty()) {
                        flowOf(emptyMap())
                    } else {
                        combine(
                            ids.map { id ->
                                trailRepo.observeTrackPointsForTrail(id).map { pts -> id to pts }
                            },
                        ) { pairs ->
                            pairs.associate { (id, pts) -> id to pts }
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyMap())

        val expandedTrailIds: StateFlow<Set<Long>> = _expandedTrailIds.asStateFlow()
        val trackExpandedTrailIds: StateFlow<Set<Long>> = _trackExpandedTrailIds.asStateFlow()

        private val _toast = MutableStateFlow<String?>(null)
        val toast: StateFlow<String?> = _toast.asStateFlow()

        private val _exportStatus = MutableStateFlow<String?>(null)
        val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

        /** Toggle level-1 expand (named waypoints + count). */
        fun toggleExpand(trailId: Long) {
            val current = _expandedTrailIds.value
            if (trailId in current) {
                _expandedTrailIds.value = current - trailId
                _trackExpandedTrailIds.value = _trackExpandedTrailIds.value - trailId
            } else {
                _expandedTrailIds.value = current + trailId
            }
        }

        /** Toggle level-2 expand (full track point list). */
        fun toggleTrackExpand(trailId: Long) {
            val current = _trackExpandedTrailIds.value
            _trackExpandedTrailIds.value = if (trailId in current) current - trailId else current + trailId
        }

        fun create(
            collectionId: Long,
            name: String,
            description: String?,
            tentative: Boolean = false,
        ) {
            viewModelScope.launch {
                val id = trailRepo.create(collectionId, name, description, tentative)
                _expandedTrailIds.value = _expandedTrailIds.value + id
                _toast.value = "Trail created"
            }
        }

        fun rename(
            id: Long,
            name: String,
        ) {
            viewModelScope.launch {
                trailRepo.update(id, name = name)
                _toast.value = "Trail renamed"
            }
        }

        fun delete(id: Long) {
            viewModelScope.launch {
                trailRepo.remove(id)
                _expandedTrailIds.value = _expandedTrailIds.value - id
                _trackExpandedTrailIds.value = _trackExpandedTrailIds.value - id
                _toast.value = "Trail deleted"
            }
        }

        // ── GPS navigation requests (routed to the GPS screen via TargetingStateHolder) ──

        /** Follow [trailId] from its start ([reversed] = false) or its end ([reversed] = true). */
        fun followTrail(
            trailId: Long,
            reversed: Boolean,
        ) {
            targetingStateHolder.requestTrailFollow(trailId, reversed)
            _toast.value = if (reversed) "Following trail in reverse" else "Following trail"
        }

        /** Begin auto-recording track points onto [trailId]. */
        fun recordTrail(trailId: Long) {
            targetingStateHolder.requestTrailRecord(trailId)
            _toast.value = "Recording trail"
        }

        /** Set an endpoint waypoint as the GPS target (point navigation, not trail follow). */
        fun navigateToWaypoint(
            waypointId: Long,
            label: String,
        ) {
            targetingStateHolder.requestWaypointTarget(waypointId)
            _toast.value = "Navigating to $label"
        }

        fun addWaypointToTrail(
            trailId: Long,
            name: String,
            lat: Double,
            lon: Double,
            elevM: Double?,
        ) {
            viewModelScope.launch {
                waypointRepo.createTrackPoint(trailId, name, lat, lon, elevM)
                _toast.value = "Waypoint added"
            }
        }

        fun attachExisting(
            trailId: Long,
            waypointId: Long,
        ) {
            viewModelScope.launch {
                waypointRepo.attach(trailId, waypointId)
                _toast.value = "Waypoint attached"
            }
        }

        fun detachWaypoint(
            trailId: Long,
            waypointId: Long,
        ) {
            viewModelScope.launch {
                waypointRepo.detach(trailId, waypointId)
                _toast.value = "Waypoint detached"
            }
        }

        // currentIndex is 0-based; setPosition takes 1-based position.
        fun moveUp(
            trailId: Long,
            waypointId: Long,
            currentIndex: Int,
        ) {
            if (currentIndex <= 0) return
            viewModelScope.launch {
                waypointRepo.setPosition(trailId, waypointId, currentIndex)
            }
        }

        fun moveDown(
            trailId: Long,
            waypointId: Long,
            currentIndex: Int,
            listSize: Int,
        ) {
            if (currentIndex >= listSize - 1) return
            viewModelScope.launch {
                waypointRepo.setPosition(trailId, waypointId, currentIndex + 2)
            }
        }

        fun clearToast() {
            _toast.value = null
        }

        fun exportTrail(trailId: Long) {
            viewModelScope.launch {
                val trail = trailRepo.getById(trailId) ?: return@launch
                val waypoints = trailRepo.waypointsForTrail(trailId)
                val filename = "trail-${trail.id}.gpx"
                GpxFileWriter
                    .writeToDownloads(context, filename, GpxExporter.exportTrail(trail.name, waypoints))
                    .onSuccess { _exportStatus.value = "Exported ${trail.name} to Downloads/$filename" }
                    .onFailure { _exportStatus.value = "Export failed: ${it.message}" }
            }
        }

        fun clearExportStatus() {
            _exportStatus.value = null
        }

        fun importGpx(uri: Uri) {
            viewModelScope.launch {
                try {
                    val stream =
                        context.contentResolver.openInputStream(uri) ?: run {
                            _toast.value = "Could not open file"
                            return@launch
                        }
                    val result = stream.use { GpxParser.parse(it) }
                    if (result.isEmpty) {
                        _toast.value = "No waypoints or trails found in file"
                        return@launch
                    }
                    val collectionId = collectionRepo.create(result.collectionName ?: "Imported", null)
                    result.waypoints.forEach { p ->
                        waypointRepo.create(collectionId, p.name, p.lat, p.lon, p.elevM, p.description)
                    }
                    result.trails.forEach { trail ->
                        val trailId = trailRepo.create(collectionId, trail.name, null)
                        trail.points.forEach { p ->
                            if (trail.isRoute) {
                                val wpId = waypointRepo.create(collectionId, p.name, p.lat, p.lon, p.elevM, p.description)
                                waypointRepo.attach(trailId, wpId)
                            } else {
                                waypointRepo.createTrackPoint(trailId, p.name, p.lat, p.lon, p.elevM)
                            }
                        }
                    }
                    _toast.value = "Imported ${result.summary}"
                } catch (e: Exception) {
                    _toast.value = "Import failed: ${e.message}"
                }
            }
        }
    }
