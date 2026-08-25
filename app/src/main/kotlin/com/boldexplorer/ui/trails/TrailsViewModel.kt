package com.boldexplorer.ui.trails

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.gpx.GpxFileWriter
import com.boldexplorer.location.SelectedCollectionHolder
import com.boldexplorer.location.TargetingStateHolder
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.gpx.GpxExporter
import com.boldexplorer.shared.location.LocationProvider
import com.boldexplorer.shared.model.Collection
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.TrailNamedPoint
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.navigation.BearingComputer
import com.boldexplorer.shared.repository.CollectionRepository
import com.boldexplorer.shared.repository.NavPointsRepository
import com.boldexplorer.shared.repository.SettingsRepository
import com.boldexplorer.shared.repository.TrailAnnotationRepository
import com.boldexplorer.shared.repository.TrailAttachment
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrailsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val trailRepo: TrailRepository,
        private val waypointRepo: WaypointRepository,
        private val collectionRepo: CollectionRepository,
        private val annotationRepo: TrailAnnotationRepository,
        private val navPointsRepo: NavPointsRepository,
        private val targetingStateHolder: TargetingStateHolder,
        private val selectedCollectionHolder: SelectedCollectionHolder,
        settingsRepo: SettingsRepository,
        locationProvider: LocationProvider,
    ) : ViewModel() {
        /** Units for the distances this screen reports; see [attachExisting]. */
        val settings: StateFlow<AppSettings> =
            settingsRepo
                .observeSettings()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), AppSettings())

        // Current fix, used to decide which trail end is nearest for the end-proximity actions.
        val currentLocation: StateFlow<LatLng?> =
            locationProvider.locationFlow
                .map { it?.let { s -> LatLng(s.lat, s.lon) } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        val selectedCollectionId: StateFlow<Long?> = selectedCollectionHolder.selectedCollectionId

        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val _collectionTrails: StateFlow<List<Trail>> =
            selectedCollectionHolder.selectedCollectionId
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyList()) else collectionRepo.observeTrailsForCollection(id)
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        val trails: StateFlow<List<Trail>> =
            combine(_collectionTrails, _query) { all, q ->
                if (q.isBlank()) all else all.filter { it.name.contains(q, ignoreCase = true) }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        val collections: StateFlow<List<Collection>> =
            collectionRepo
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        // User-created waypoints only — used for "attach existing" candidates.
        val allWaypoints: StateFlow<List<Waypoint>> =
            waypointRepo
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        // Where each trail actually begins and ends, from its geometry (ADR 0001, S5b).
        //
        // The same lean per-collection query the GPS screen follows from — ≤2 rows per trail, no
        // track-point body, live as recording extends a trail. Deliberately not the editor's
        // named-waypoint list: that list is what the user should *see*, and since S5b it can be
        // entirely annotations, which are named things beside the trail rather than its ends.
        //
        // Per collection rather than per expanded card, because the collapsed card offers Follow as
        // a TalkBack action and the card is the whole control for a blind user; making the ends
        // arrive only after a card had been expanded once would hide it exactly when it is wanted.
        val trailEnds: StateFlow<Map<Long, TrailEnds>> =
            selectedCollectionHolder.selectedCollectionId
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyList()) else navPointsRepo.observeTrailEndsForCollection(id)
                }.map { rows ->
                    rows
                        .groupBy { it.trail.id }
                        .mapNotNull { (trailId, ends) ->
                            val start = ends.firstOrNull { it.isStart }?.waypoint ?: return@mapNotNull null
                            // A one-point trail reports a single row, and it is both ends at once.
                            trailId to TrailEnds(start = start, end = ends.lastOrNull { !it.isStart }?.waypoint ?: start)
                        }.toMap()
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyMap())

        // Trail IDs whose named waypoints + track point count have ever been requested (the
        // screen only asks once a card is expanded); grows only, mirrors CollectionsViewModel's
        // loadContents/_loadedCollectionIds — expand/collapse itself is UI-local state.
        private val _loadedTrailIds = MutableStateFlow<Set<Long>>(emptySet())

        // Trail IDs whose full track point list has ever been requested. Grows only.
        private val _loadedTrackPointIds = MutableStateFlow<Set<Long>>(emptySet())

        // Named waypoints (kind='waypoint') per loaded trail. Reactive.
        val namedWaypoints: StateFlow<Map<Long, List<TrailNamedPoint>>> =
            _loadedTrailIds
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

        // Track point counts per loaded trail. Reactive.
        val trackPointCounts: StateFlow<Map<Long, Long>> =
            _loadedTrailIds
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

        // Full track point lists per loaded trail. Reactive.
        val trackPoints: StateFlow<Map<Long, List<Waypoint>>> =
            _loadedTrackPointIds
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

        private val _toast = MutableStateFlow<String?>(null)
        val toast: StateFlow<String?> = _toast.asStateFlow()

        private val _exportStatus = MutableStateFlow<String?>(null)
        val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

        /** Request named waypoints + track point count for [trailId] (called when its card expands). */
        fun loadNamedWaypoints(trailId: Long) {
            _loadedTrailIds.value = _loadedTrailIds.value + trailId
        }

        /** Request the full track point list for [trailId] (called when that sub-section expands). */
        fun loadTrackPoints(trailId: Long) {
            _loadedTrackPointIds.value = _loadedTrackPointIds.value + trailId
        }

        fun setQuery(q: String) {
            _query.value = q
        }

        fun setCollectionFilter(id: Long?) {
            selectedCollectionHolder.select(id)
        }

        fun create(
            name: String,
            description: String?,
            tentative: Boolean = false,
        ) {
            val collectionId =
                selectedCollectionHolder.selectedCollectionId.value ?: run {
                    _toast.value = "Select a collection first"
                    return
                }
            viewModelScope.launch {
                val id = trailRepo.create(collectionId, name, description, tentative)
                loadNamedWaypoints(id)
                _toast.value = "Trail created"
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
                _loadedTrailIds.value = _loadedTrailIds.value - id
                _loadedTrackPointIds.value = _loadedTrackPointIds.value - id
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

        /**
         * Set one end of [trailId] as the GPS target (point navigation, not a trail follow).
         *
         * The toast waits for the outcome rather than announcing at the moment of asking. It used to
         * announce immediately, which meant "Navigating to the start of X" was said whether or not
         * anything happened — and for a recorded trail nothing did (issue #78).
         */
        fun navigateToTrailEnd(
            trailId: Long,
            isStart: Boolean,
            label: String,
        ) {
            targetingStateHolder.requestTrailEndTarget(trailId, isStart, label)
        }

        /**
         * Create a real waypoint and attach it to [trailId] — never a track point. A manually typed
         * coordinate has no kinematics gate behind it the way the auto-recorder does, so it must
         * never be allowed to fabricate geometry directly; routing through [WaypointRepository.attach]
         * means a recorded trail can only ever gain an annotation from this, never a vertex (#102).
         */
        fun addWaypointToTrail(
            trailId: Long,
            name: String,
            lat: Double,
            lon: Double,
            elevM: Double?,
        ) {
            val collectionId =
                selectedCollectionHolder.selectedCollectionId.value ?: run {
                    _toast.value = "Select a collection first"
                    return
                }
            viewModelScope.launch {
                val id = waypointRepo.create(collectionId, name, lat, lon, elevM, null)
                val outcome = waypointRepo.attach(trailId, id)
                _toast.value =
                    when {
                        outcome is TrailAttachment.Annotation && outcome.fix.farFromTrail -> {
                            "Waypoint added, but it is ${
                                BearingComputer.formatDistance(outcome.fix.distanceFromTrailM, settings.value.units)
                            } from the trail"
                        }

                        else -> {
                            "Waypoint added"
                        }
                    }
            }
        }

        fun attachExisting(
            trailId: Long,
            waypointIds: Set<Long>,
        ) {
            viewModelScope.launch {
                val distant =
                    waypointIds.mapNotNull { id ->
                        val outcome = waypointRepo.attach(trailId, id)
                        // Attaching to a recorded trail annotates it; one that lands far off the
                        // trail is worth naming, since only the user can say whether it belongs.
                        (outcome as? TrailAttachment.Annotation)
                            ?.takeIf { it.fix.farFromTrail }
                            ?.let { waypointRepo.getById(id)?.name to it.fix.distanceFromTrailM }
                    }
                val attached = "${waypointIds.size} waypoint${if (waypointIds.size == 1) "" else "s"} attached"
                _toast.value =
                    if (distant.isEmpty()) {
                        attached
                    } else {
                        val units = settings.value.units
                        val far =
                            distant.joinToString("; ") { (name, m) ->
                                "${name ?: "one"} is ${BearingComputer.formatDistance(m, units)} from the trail"
                            }
                        "$attached — $far"
                    }
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
                // Annotations are not geometry, so they are not in `waypointsForTrail` — but they
                // are things the user marked, and an export that dropped them would lose them.
                val annotated = annotationRepo.forTrail(trailId).map { it.waypoint }
                val filename = "trail-${trail.id}.gpx"
                GpxFileWriter
                    .writeToDownloads(context, filename, GpxExporter.exportTrail(trail.name, waypoints, annotated))
                    .onSuccess { _exportStatus.value = "Exported ${trail.name} to Downloads/$filename" }
                    .onFailure { _exportStatus.value = "Export failed: ${it.message}" }
            }
        }

        fun clearExportStatus() {
            _exportStatus.value = null
        }
    }

/**
 * Where a trail begins and ends, taken from its geometry (ADR 0001, S5b).
 *
 * Not the first and last of the editor's named-waypoint list. That list is the union of vertices and
 * annotations, and on a recorded trail every named point in it is an annotation — a bench beside the
 * path, not an end of it. Reading it for ends would offer "Follow from this end" while standing
 * halfway along, and choose the travel direction from whichever annotation happened to be nearest.
 *
 * [start] and [end] are the same waypoint on a one-point trail, which is how the screen knows not to
 * offer a reverse of it.
 */
data class TrailEnds(
    val start: Waypoint,
    val end: Waypoint,
)
