package com.boldexplorer.ui.waypoints

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.gpx.GpxParser
import com.boldexplorer.location.FusedLocationProviderImpl
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.repository.CollectionRepository
import com.boldexplorer.shared.repository.SettingsRepository
import com.boldexplorer.shared.repository.TrailRepository
import com.boldexplorer.shared.repository.WaypointRepository
import com.boldexplorer.shared.settings.AppSettings
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
import com.boldexplorer.shared.model.Collection as ExplorerCollection

enum class WaypointSortMode { DISTANCE, ALPHA }

/** Radius filter options shown in the UI. Null = no cap. */
val RADIUS_OPTIONS: List<Pair<String, Double?>> =
    listOf(
        "Any distance" to null,
        "1 km" to 1_000.0,
        "2 km" to 2_000.0,
        "5 km" to 5_000.0,
        "10 km" to 10_000.0,
    )

data class WaypointListItem(
    val waypoint: Waypoint,
    val distanceM: Double?,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WaypointsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val waypointRepo: WaypointRepository,
        private val trailRepo: TrailRepository,
        private val collectionRepo: CollectionRepository,
        settingsRepo: SettingsRepository,
        locationProvider: FusedLocationProviderImpl,
    ) : ViewModel() {
        // ── Filter / sort state ───────────────────────────────────────────────────────

        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val _sortMode = MutableStateFlow(WaypointSortMode.DISTANCE)
        val sortMode: StateFlow<WaypointSortMode> = _sortMode.asStateFlow()

        /** Null = no radius cap. */
        private val _radiusFilterM = MutableStateFlow<Double?>(null)
        val radiusFilterM: StateFlow<Double?> = _radiusFilterM.asStateFlow()

        /** Null = all collections. */
        private val _collectionFilter = MutableStateFlow<Long?>(null)
        val collectionFilter: StateFlow<Long?> = _collectionFilter.asStateFlow()

        // ── Source data ───────────────────────────────────────────────────────────────

        private val _allWaypoints =
            waypointRepo
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        val trails: StateFlow<List<Trail>> =
            trailRepo
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        val collections: StateFlow<List<ExplorerCollection>> =
            collectionRepo
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        val settings: StateFlow<AppSettings> =
            settingsRepo
                .observeSettings()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), AppSettings())

        private val location =
            locationProvider.locationFlow
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        // ── "Add to collection" dialog state ─────────────────────────────────────────

        private val _waypointCollectionIds = MutableStateFlow<Set<Long>>(emptySet())
        val waypointCollectionIds: StateFlow<Set<Long>> = _waypointCollectionIds.asStateFlow()

        // ── Derived: waypoint IDs in the selected collection (null = no filter) ───────

        private val _collectionFilterIds: StateFlow<Set<Long>?> =
            _collectionFilter
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(null)
                    } else {
                        collectionRepo.observeWaypointsForCollection(id).map { wps -> wps.map { it.id }.toSet() }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        // ── Filtered + sorted list ────────────────────────────────────────────────────

        val waypoints: StateFlow<List<WaypointListItem>> =
            combine(
                _allWaypoints,
                _query,
                location,
                _sortMode,
                combine(_radiusFilterM, _collectionFilterIds) { r, c -> r to c },
            ) { all, q, loc, sort, (radius, collectionIds) ->

                // 1. Text filter
                var filtered = if (q.isBlank()) all else all.filter { it.name.contains(q, ignoreCase = true) }

                // 2. Collection filter
                if (collectionIds != null) filtered = filtered.filter { it.id in collectionIds }

                // 3. Attach distances
                val center = loc?.let { LatLng(it.lat, it.lon) }
                var items =
                    filtered.map { wp ->
                        WaypointListItem(
                            waypoint = wp,
                            distanceM = center?.let { haversineDistanceMeters(it, LatLng(wp.lat, wp.lon)) },
                        )
                    }

                // 4. Radius cap (only meaningful when we have a location)
                if (radius != null && center != null) {
                    items = items.filter { (it.distanceM ?: Double.MAX_VALUE) <= radius }
                }

                // 5. Sort
                when (sort) {
                    WaypointSortMode.DISTANCE -> {
                        if (center != null) {
                            items.sortedBy { it.distanceM }
                        } else {
                            items.sortedBy { it.waypoint.name.lowercase() }
                        }
                    }

                    WaypointSortMode.ALPHA -> {
                        items.sortedBy { it.waypoint.name.lowercase() }
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        // ── Mutations ─────────────────────────────────────────────────────────────────

        private val _toast = MutableStateFlow<String?>(null)
        val toast: StateFlow<String?> = _toast.asStateFlow()

        fun setQuery(q: String) {
            _query.value = q
        }

        fun setSortMode(mode: WaypointSortMode) {
            _sortMode.value = mode
        }

        fun setRadiusFilter(m: Double?) {
            _radiusFilterM.value = m
        }

        fun setCollectionFilter(id: Long?) {
            _collectionFilter.value = id
        }

        fun create(
            name: String,
            lat: Double,
            lon: Double,
            elevM: Double?,
            tentative: Boolean = false,
        ) {
            val collectionId =
                _collectionFilter.value ?: run {
                    _toast.value = "Select a collection first"
                    return
                }
            viewModelScope.launch {
                waypointRepo.create(collectionId, name, lat, lon, elevM, null, tentative)
                _toast.value = "Waypoint added"
            }
        }

        fun update(
            id: Long,
            name: String,
            lat: Double,
            lon: Double,
            elevM: Double?,
        ) {
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

        fun attach(
            waypointId: Long,
            trailId: Long,
        ) {
            viewModelScope.launch {
                waypointRepo.attach(trailId, waypointId)
                _toast.value = "Attached to trail"
            }
        }

        fun loadWaypointCollections(waypointId: Long) {
            viewModelScope.launch {
                _waypointCollectionIds.value = collectionRepo.collectionsForWaypoint(waypointId).map { it.id }.toSet()
            }
        }

        fun addToCollections(
            waypointId: Long,
            collectionIds: Set<Long>,
        ) {
            viewModelScope.launch {
                collectionIds.forEach { collectionRepo.attachWaypoint(it, waypointId) }
                _toast.value = "${collectionIds.size} collection${if (collectionIds.size == 1) "" else "s"} updated"
            }
        }

        fun clearToast() {
            _toast.value = null
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
