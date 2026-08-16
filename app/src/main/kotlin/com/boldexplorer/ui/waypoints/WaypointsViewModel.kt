package com.boldexplorer.ui.waypoints

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.location.SelectedCollectionHolder
import com.boldexplorer.location.TargetingStateHolder
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.location.LocationProvider
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.navigation.BearingComputer
import com.boldexplorer.shared.repository.CollectionRepository
import com.boldexplorer.shared.repository.SettingsRepository
import com.boldexplorer.shared.repository.TrailAttachment
import com.boldexplorer.shared.repository.TrailRepository
import com.boldexplorer.shared.repository.WaypointRepository
import com.boldexplorer.shared.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
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
        private val waypointRepo: WaypointRepository,
        private val trailRepo: TrailRepository,
        private val collectionRepo: CollectionRepository,
        private val targetingStateHolder: TargetingStateHolder,
        private val selectedCollectionHolder: SelectedCollectionHolder,
        settingsRepo: SettingsRepository,
        locationProvider: LocationProvider,
    ) : ViewModel() {
        // ── Filter / sort state ───────────────────────────────────────────────────────

        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val _sortMode = MutableStateFlow(WaypointSortMode.DISTANCE)
        val sortMode: StateFlow<WaypointSortMode> = _sortMode.asStateFlow()

        /** Null = no radius cap. */
        private val _radiusFilterM = MutableStateFlow<Double?>(null)
        val radiusFilterM: StateFlow<Double?> = _radiusFilterM.asStateFlow()

        val collectionFilter: StateFlow<Long?> = selectedCollectionHolder.selectedCollectionId

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
            selectedCollectionHolder.selectedCollectionId
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

                // 2. Collection scoping: a collection MUST be selected. Null ids = nothing selected yet,
                //    so show nothing and let the UI prompt for a selection. A selected-but-empty
                //    collection yields an empty set and correctly shows no waypoints.
                if (collectionIds == null) return@combine emptyList()
                filtered = filtered.filter { it.id in collectionIds }

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
            selectedCollectionHolder.select(id)
        }

        fun create(
            name: String,
            lat: Double,
            lon: Double,
            elevM: Double?,
            tentative: Boolean = false,
        ) {
            val collectionId =
                selectedCollectionHolder.selectedCollectionId.value ?: run {
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

        /**
         * The current GPS position, isolated so a future N-sample averaging pass can replace this
         * single read without touching callers (e.g. [fixPoint]). Null when there is no fix yet.
         */
        private fun captureCurrentPosition(): LatLng? = location.value?.let { LatLng(it.lat, it.lon) }

        /**
         * Overwrite a waypoint's coordinates with the current GPS position, leaving name/elevation
         * untouched. Used to correct a point recorded from the wrong spot (e.g. a doorway logged from
         * across the street). One-shot today; see [captureCurrentPosition] for the averaging seam.
         */
        fun fixPoint(id: Long) {
            val pos =
                captureCurrentPosition() ?: run {
                    _toast.value = "No GPS fix yet"
                    return
                }
            viewModelScope.launch {
                waypointRepo.update(id, lat = pos.lat, lon = pos.lon)
                _toast.value = "Point corrected"
            }
        }

        /**
         * Set this waypoint as the GPS navigation target without leaving the Waypoints screen. Writes
         * to the shared [TargetingStateHolder]; the GPS ViewModel observes it and re-points the explorer.
         */
        fun setAsTarget(id: Long) {
            val name = _allWaypoints.value.firstOrNull { it.id == id }?.name
            targetingStateHolder.requestWaypointTarget(id)
            _toast.value = name?.let { "$it set as GPS target" } ?: "Set as GPS target"
        }

        /**
         * Move a waypoint out of the currently-viewed collection and into [targetCollectionId]. A no-op
         * if no collection is currently selected (the screen requires one before any waypoint shows).
         */
        fun moveToCollection(
            id: Long,
            targetCollectionId: Long,
        ) {
            val from =
                selectedCollectionHolder.selectedCollectionId.value ?: run {
                    _toast.value = "Select a collection first"
                    return
                }
            if (from == targetCollectionId) return
            viewModelScope.launch {
                collectionRepo.attachWaypoint(targetCollectionId, id)
                collectionRepo.detachWaypoint(from, id)
                _toast.value = "Waypoint moved"
            }
        }

        /** Create a new collection and immediately scope the screen to it. */
        fun createCollection(name: String) {
            viewModelScope.launch {
                val id = collectionRepo.create(name, null)
                selectedCollectionHolder.select(id)
                _toast.value = "Collection created"
            }
        }

        fun attach(
            waypointId: Long,
            trailId: Long,
        ) {
            viewModelScope.launch {
                val outcome = waypointRepo.attach(trailId, waypointId)
                // A point attached to a recorded trail annotates it rather than becoming part of it,
                // and one that lands a long way off is said out loud rather than placed quietly —
                // the distance is the useful fact, and the user is the only one who can judge it.
                _toast.value =
                    when {
                        outcome is TrailAttachment.Annotation && outcome.fix.farFromTrail ->
                            "Attached, but it is ${
                                BearingComputer.formatDistance(outcome.fix.distanceFromTrailM, settings.value.units)
                            } from the trail"

                        else -> "Attached to trail"
                    }
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
    }
