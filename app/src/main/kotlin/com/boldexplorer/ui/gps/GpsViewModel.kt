package com.boldexplorer.ui.gps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.BuildConfig
import com.boldexplorer.audio.AudioEventLog
import com.boldexplorer.audio.AudioLogEntry
import com.boldexplorer.audio.SpokenGuidancePlayer
import com.boldexplorer.compass.SensorCompassProvider
import com.boldexplorer.location.BeaconAudioInputs
import com.boldexplorer.location.GpsBackgroundMode
import com.boldexplorer.location.GpsBackgroundSession
import com.boldexplorer.location.LocationForegroundService
import com.boldexplorer.location.SelectedCollectionHolder
import com.boldexplorer.location.TargetingStateHolder
import com.boldexplorer.location.TrailTargetRequest
import com.boldexplorer.shared.audio.AudioCueScheduler
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.deltaAngle
import com.boldexplorer.shared.geo.distanceToSegmentMeters
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.geo.segmentFraction
import com.boldexplorer.shared.location.LocationProvider
import com.boldexplorer.shared.model.Collection
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.TrailEndRow
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.navigation.AdvancementReason
import com.boldexplorer.shared.navigation.CollectionExplorer
import com.boldexplorer.shared.navigation.CollectionExplorerEvent
import com.boldexplorer.shared.navigation.CollectionExplorerState
import com.boldexplorer.shared.navigation.CollectionPoint
import com.boldexplorer.shared.navigation.NavMode
import com.boldexplorer.shared.navigation.NavModeResolver
import com.boldexplorer.shared.navigation.NavigationTargetResolver
import com.boldexplorer.shared.navigation.NearbyTrail
import com.boldexplorer.shared.navigation.NearbyTrailResolver
import com.boldexplorer.shared.navigation.TrailFollower
import com.boldexplorer.shared.navigation.TrailFollowerEvent
import com.boldexplorer.shared.navigation.TrailFollowerState
import com.boldexplorer.shared.navigation.TrailGuidance
import com.boldexplorer.shared.navigation.TrailGuidanceCoordinator
import com.boldexplorer.shared.navigation.TrailGuidanceState
import com.boldexplorer.shared.navigation.TrailPoint
import com.boldexplorer.shared.navigation.TrailRecordingMachine
import com.boldexplorer.shared.navigation.TrailRecordingState
import com.boldexplorer.shared.navigation.collectionNavPoints
import com.boldexplorer.shared.navigation.displayName
import com.boldexplorer.shared.repository.CollectionRepository
import com.boldexplorer.shared.repository.NavPointsRepository
import com.boldexplorer.shared.repository.SettingsRepository
import com.boldexplorer.shared.repository.TrailRepository
import com.boldexplorer.shared.repository.WaypointRepository
import com.boldexplorer.shared.settings.AppSettings
import com.boldexplorer.shared.settings.Units
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

data class GpsUiState(
    val location: LocationSample? = null,
    val headingDeg: Double? = null,
    val accuracyM: Double? = null,
    val compassModeLabel: String = "Magnetic",
    val settings: AppSettings = AppSettings(),
    val waypoints: List<Waypoint> = emptyList(),
    val trails: List<Trail> = emptyList(),
    val collections: List<Collection> = emptyList(),
    val trailWaypoints: List<Waypoint> = emptyList(),
    val collectionWaypoints: List<Waypoint> = emptyList(),
    val collectionTrails: List<Trail> = emptyList(),
    val selectedTrailId: Long? = null,
    val selectedCollectionId: Long? = null,
    val trailFollowState: TrailFollowerState = TrailFollowerState.Idle,
    val collectionExplorerState: CollectionExplorerState = CollectionExplorerState.Idle,
    val targetName: String? = null,
    val bearingDeg: Double? = null,
    val distanceM: Double? = null,
    val relativeDeg: Double? = null,
    val alignmentActive: Boolean = false,
    val alignmentBearingDeg: Double? = null,
    val alignmentRelativeDeg: Double? = null,
    val announcement: String = "",
    val navigationActive: Boolean = false,
    val recordingState: TrailRecordingState = TrailRecordingState.Idle,
    val navMode: NavMode = NavMode.NoCollection,
)

sealed interface GpsAction {
    data class SelectTrail(
        val id: Long,
    ) : GpsAction

    data class SelectCollection(
        val id: Long,
    ) : GpsAction

    data class CreateCollection(
        val name: String,
    ) : GpsAction

    data class SelectCollectionPoint(
        val point: CollectionPoint,
    ) : GpsAction

    data object ClearCollectionTarget : GpsAction

    data object SkipCollectionTarget : GpsAction

    data object ClearCollectionVisited : GpsAction

    data class SetCollectionExploreMode(
        val enabled: Boolean,
    ) : GpsAction

    data class ExtendTrailFromCollectionEnd(
        val trailEnd: CollectionPoint.TrailEnd,
    ) : GpsAction

    /**
     * Follow [trailId] starting from whichever waypoint is nearest the user (mid-trail capable),
     * traveling forward ([reversed] = false) or backward ([reversed] = true). Dispatched by the
     * single "Follow {trail}" affordance / direction chooser for both [NavMode.NearTrail] and
     * [NavMode.AtTrailEnd].
     */
    data class FollowTrail(
        val trailId: Long,
        val reversed: Boolean,
    ) : GpsAction

    data object StopFollowTrail : GpsAction

    data object StartNavigation : GpsAction

    data object StopNavigation : GpsAction

    data object StartAlignment : GpsAction

    data object StopAlignment : GpsAction

    data object ResetAlignment : GpsAction

    data class SetAlignmentBearing(
        val deg: Double,
    ) : GpsAction

    data object AlignToTarget : GpsAction

    data object SpeakAlignmentDelta : GpsAction

    data object MarkWaypoint : GpsAction

    /** Open the waypoint naming dialog with speech-to-text launched immediately. */
    data object MarkWaypointWithSpeech : GpsAction

    data object CopyCoordinates : GpsAction

    data object RecordNewTrail : GpsAction

    /** Open the trail naming dialog with speech-to-text launched immediately. */
    data object RecordNewTrailWithSpeech : GpsAction

    data object StartAutoRecord : GpsAction

    data object StopAutoRecord : GpsAction

    data class AddWaypointsToCollection(
        val ids: Set<Long>,
    ) : GpsAction

    data class AddTrailsToCollection(
        val ids: Set<Long>,
    ) : GpsAction
}

/**
 * A creation the user has initiated but not yet named. Marking a waypoint or starting a trail captures
 * the necessary context and opens the naming dialog; nothing is written to the database until the user
 * confirms a name (see [GpsViewModel.onWaypointNamed] / [GpsViewModel.onTrailNamed]).
 */
sealed interface PendingCreate {
    /** When true, the naming dialog launches speech-to-text immediately on open (entry-point action). */
    val launchStt: Boolean

    data class Waypoint(
        val capturedLocation: LocationSample,
        override val launchStt: Boolean = false,
    ) : PendingCreate

    data class Trail(
        override val launchStt: Boolean = false,
    ) : PendingCreate
}

private data class TelemetryGroup(
    val location: LocationSample?,
    val headingDeg: Double?,
    val accuracyM: Double?,
    val compassModeLabel: String,
    val settings: AppSettings,
)

private data class DataGroup(
    val waypoints: List<Waypoint>,
    val trails: List<Trail>,
    val collections: List<Collection>,
    val trailWaypoints: List<Waypoint>,
    val collectionWaypoints: List<Waypoint>,
    val collectionTrails: List<Trail>,
)

private data class SelectionGroup(
    val selectedTrailId: Long?,
    val selectedCollectionId: Long?,
    val trailFollowState: TrailFollowerState,
    val collectionExplorerState: CollectionExplorerState,
)

private data class BearingGroup(
    val targetName: String?,
    val bearingDeg: Double?,
    val distanceM: Double?,
    val relativeDeg: Double?,
    val alignmentActive: Boolean,
)

private data class AudioAlignmentGroup(
    val headingDeg: Double?,
    val alignmentBearingDeg: Double?,
    val alignmentActive: Boolean,
)

private data class AudioNavigationGroup(
    val waypointRelativeDeg: Double?,
    val trailRelativeDeg: Double?,
    val followState: TrailFollowerState,
)

private data class InteractionGroup(
    val alignmentBearingDeg: Double?,
    val alignmentRelativeDeg: Double?,
    val announcement: String,
    val navigationActive: Boolean,
    val recordingState: TrailRecordingState,
    val navMode: NavMode,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GpsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val locationProvider: LocationProvider,
        private val compassProvider: SensorCompassProvider,
        private val waypointRepo: WaypointRepository,
        private val trailRepo: TrailRepository,
        private val collectionRepo: CollectionRepository,
        private val navPointsRepo: NavPointsRepository,
        private val targetingStateHolder: TargetingStateHolder,
        private val selectedCollectionHolder: SelectedCollectionHolder,
        private val backgroundSession: GpsBackgroundSession,
        private val spokenGuidancePlayer: SpokenGuidancePlayer,
        private val settingsRepo: SettingsRepository,
        private val audioEventLog: AudioEventLog,
        private val scheduler: AudioCueScheduler,
    ) : ViewModel() {
        // ── Settings ──────────────────────────────────────────────────────────────────

        val settings: StateFlow<AppSettings> =
            settingsRepo
                .observeSettings()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), AppSettings())

        val beaconCuesEnabled: StateFlow<Boolean> =
            settings
                .map { it.beaconCuesEnabled }
                .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings().beaconCuesEnabled)

        // ── Location ──────────────────────────────────────────────────────────────────

        val location =
            locationProvider.locationFlow
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        // Compass heading — always driven by the sensor, never GPS course.
        val headingDeg: StateFlow<Double?> =
            combine(
                compassProvider.headingFlow,
                settings,
            ) { reading, s ->
                when (s.compassMode) {
                    com.boldexplorer.shared.settings.CompassMode.TRUE -> reading.trueNorth ?: reading.magnetic
                    com.boldexplorer.shared.settings.CompassMode.MAGNETIC -> reading.magnetic
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        val compassModeLabel: StateFlow<String> =
            combine(
                compassProvider.headingFlow,
                settings,
            ) { reading, s ->
                when (s.compassMode) {
                    com.boldexplorer.shared.settings.CompassMode.TRUE -> {
                        if (reading.trueNorth != null) "True North" else "True North (no fix)"
                    }

                    com.boldexplorer.shared.settings.CompassMode.MAGNETIC -> {
                        "Magnetic"
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), "Magnetic")

        // Shared trusted-course state drives both trail guidance and direct-to-waypoint beacons.
        // It accepts confidence-gated slow-walker samples, then expires shortly after movement stops.
        private val guidanceCoordinator = TrailGuidanceCoordinator(viewModelScope)
        private val navHeadingDeg: StateFlow<Double?> = guidanceCoordinator.navigationHeadingDeg

        val accuracyM: StateFlow<Double?> =
            location
                .map { it?.accuracy }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        // ── Selection ───────────────────────────────────────────────────────────────

        private val _selectedTrailId = MutableStateFlow<Long?>(null)
        val selectedTrailId: StateFlow<Long?> = _selectedTrailId.asStateFlow()

        val selectedCollectionId: StateFlow<Long?> = selectedCollectionHolder.selectedCollectionId

        // ── Pending creation (drives the naming dialog; no DB write until named) ──────

        private val _pendingCreate = MutableStateFlow<PendingCreate?>(null)
        val pendingCreate: StateFlow<PendingCreate?> = _pendingCreate.asStateFlow()

        // ── Trail recording state machine ────────────────────────────────────────────
        // Single source of truth that guarantees follow and record are never offered together.

        private val recordingMachine = TrailRecordingMachine()
        val recordingState: StateFlow<TrailRecordingState> = recordingMachine.state

        // Distance-throttle geometry for auto-record (not part of the state machine).
        @Volatile private var _lastAutoRecordLoc: LatLng? = null

        // ── Reactive data ─────────────────────────────────────────────────────────────

        val waypoints: StateFlow<List<Waypoint>> =
            combine(waypointRepo.observeAll(), location) { wps, loc ->
                if (loc == null) {
                    wps
                } else {
                    wps.sortedBy { haversineDistanceMeters(LatLng(loc.lat, loc.lon), LatLng(it.lat, it.lon)) }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        val trails: StateFlow<List<Trail>> =
            trailRepo
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        val collections: StateFlow<List<com.boldexplorer.shared.model.Collection>> =
            collectionRepo
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        // Automatically tracks the selected trail's waypoints. Re-emits on any write
        // to the waypoint or trail_waypoint tables for that trail.
        val trailWaypoints: StateFlow<List<Waypoint>> =
            _selectedTrailId
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(emptyList())
                    } else {
                        trailRepo.observeWaypointsForTrail(id)
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        val collectionWaypoints: StateFlow<List<Waypoint>> =
            selectedCollectionHolder.selectedCollectionId
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(emptyList())
                    } else {
                        collectionRepo.observeWaypointsForCollection(id)
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        private val collectionTrails: StateFlow<List<Trail>> =
            selectedCollectionHolder.selectedCollectionId
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(emptyList())
                    } else {
                        collectionRepo.observeTrailsForCollection(id)
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        // Lean reactive start/end of each trail in the selected collection. Backed by the
        // trailEndsForCollection SQL query (≤2 rows/trail, no track-point body) — re-emits when a
        // track point shifts a trail's MAX position, so ends stay live without loading dense data.
        private val collectionTrailEnds: StateFlow<List<TrailEndRow>> =
            selectedCollectionHolder.selectedCollectionId
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(emptyList())
                    } else {
                        navPointsRepo.observeTrailEndsForCollection(id)
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        // ── Trail follower ────────────────────────────────────────────────────────────

        private val trailFollower =
            TrailFollower().also { follower ->
                follower.onAdvancement = { reason -> lastAdvancementReason = reason }
            }
        val trailFollowState: StateFlow<TrailFollowerState> = trailFollower.state
        private var lastAdvancementReason: AdvancementReason? = null

        // Guidance state + the off-trail/backtrack/ordinary-guidance detection state machine live in a
        // dedicated coordinator (pure, JVM-tested in :shared). It decides *whether* to alert; this
        // ViewModel performs the effects (TTS, audio event log, wrong-vector beep, on-screen text).
        private val trailGuidance: StateFlow<TrailGuidanceState?> = guidanceCoordinator.guidance
        private val trailGuidanceRelativeDeg: StateFlow<Double?> = guidanceCoordinator.relativeDeg

        // ── Collection explorer ───────────────────────────────────────────────────────

        private val collectionExplorer = CollectionExplorer()
        val collectionExplorerState: StateFlow<CollectionExplorerState> = collectionExplorer.state

        // ── Derived navigation values ─────────────────────────────────────────────────

        // The active-target precedence rule (trail-follow waypoint wins over the explorer target) and
        // the bearing/distance/relative geometry live in a dedicated, JVM-tested resolver. The public
        // flows below are thin facades so existing consumers (uiState, audio, alignment) are unchanged.
        private val targetResolver =
            NavigationTargetResolver(
                scope = viewModelScope,
                trailFollowState = trailFollowState,
                explorerState = collectionExplorer.state,
                location = location,
                navHeadingDeg = navHeadingDeg,
            )
        val targetCoord: StateFlow<LatLng?> = targetResolver.targetCoord
        val targetName: StateFlow<String?> = targetResolver.targetName
        val bearingDeg: StateFlow<Double?> = targetResolver.bearingDeg
        val distanceM: StateFlow<Double?> = targetResolver.distanceM
        val relativeDeg: StateFlow<Double?> = targetResolver.relativeDeg

        // Per-fix snapshot of the nearest follow-able trail (mid-trail pickup). Unlike the reactive
        // nav-point flows this is *pulled* once per GPS fix — conflated so a backlog of fixes can't
        // queue DB work — by bbox-querying the selected collection's trail points and ranking them
        // purely in NearbyTrailResolver. The bbox is sized to the resolver's accuracy-scaled gate plus
        // a margin so it always covers whatever the resolver may admit. Only place dense trail data is
        // touched, and only as a bounded snapshot.
        private val nearbyTrail: StateFlow<List<NearbyTrail>> =
            combine(selectedCollectionHolder.selectedCollectionId, location) { id, loc -> id to loc }
                .conflate()
                .mapLatest { (id, loc) ->
                    if (id == null || loc == null) {
                        emptyList()
                    } else {
                        val acc = loc.accuracy ?: 0.0
                        val gate = max(NearbyTrailResolver.NEAR_TRAIL_FLOOR_M, NearbyTrailResolver.NEAR_TRAIL_ACCURACY_FACTOR * acc)
                        val center = LatLng(loc.lat, loc.lon)
                        val snapshot = navPointsRepo.trailPointsInBbox(id, center, radiusM = gate + NEARBY_TRAIL_BBOX_MARGIN_M)
                        NearbyTrailResolver.resolve(center, loc.accuracy, snapshot)
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        // The single derived mode the GPS screen renders its contextual trail controls from. Folds the
        // three state holders with an explicit precedence (active session beats explorer target) and
        // never reads the unreliable TrailRecordingState.Selected.hasPoints flag — see NavModeResolver.
        private val navModeResolver =
            NavModeResolver(
                scope = viewModelScope,
                recordingState = recordingMachine.state,
                explorerState = collectionExplorer.state,
                selectedCollectionId = selectedCollectionHolder.selectedCollectionId,
                location = location,
                accuracyM = accuracyM,
                nearbyTrail = nearbyTrail,
            )
        val navMode: StateFlow<NavMode> = navModeResolver.navMode

        // ── Alignment ─────────────────────────────────────────────────────────────────

        // Bearing-alignment state + math + speech live in a dedicated delegate; the ViewModel keeps
        // only the audio-session wiring (start/stop the directional beacon), since that depends on
        // navigation state. The public flows below are thin facades over the controller so existing
        // consumers (uiState, AlignmentDialog) are unchanged.
        private val alignmentController =
            AlignmentController(
                scope = viewModelScope,
                headingDeg = headingDeg,
                targetBearingDeg = bearingDeg,
                spokenGuidancePlayer = spokenGuidancePlayer,
            )
        val alignmentActive: StateFlow<Boolean> = alignmentController.active
        val alignmentBearingDeg: StateFlow<Double?> = alignmentController.bearingDeg

        // When alignment is active, audio uses compass heading vs the stored alignment bearing.
        // Waypoint targeting uses GPS course (navHeadingDeg); alignment uses compass (headingDeg)
        // because the user is physically pointing the phone at a target, not moving toward it.
        private val audioAlignmentGroup =
            combine(
                headingDeg,
                alignmentController.bearingDeg,
                alignmentController.active,
            ) { heading, alignBearing, alignActive ->
                AudioAlignmentGroup(heading, alignBearing, alignActive)
            }

        private val audioNavigationGroup =
            combine(
                combine(relativeDeg, trailGuidanceRelativeDeg) { wpRelative, trailRelative -> wpRelative to trailRelative },
                trailFollowState,
            ) { (wpRelative, trailRelative), followState ->
                AudioNavigationGroup(wpRelative, trailRelative, followState)
            }

        val audioRelativeDeg: StateFlow<Double?> =
            combine(
                audioAlignmentGroup,
                audioNavigationGroup,
            ) { alignment, navigation ->
                if (alignment.alignmentActive && alignment.headingDeg != null && alignment.alignmentBearingDeg != null) {
                    deltaAngle(alignment.headingDeg, alignment.alignmentBearingDeg)
                } else if (navigation.followState is TrailFollowerState.Active) {
                    navigation.trailRelativeDeg
                } else {
                    navigation.waypointRelativeDeg
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        // Signed delta between current compass heading and alignment target.
        val alignmentRelativeDeg: StateFlow<Double?> = alignmentController.relativeDeg

        // ── TalkBack announcements ────────────────────────────────────────────────────

        private val _announcement = MutableStateFlow("")
        val announcement: StateFlow<String> = _announcement.asStateFlow()

        // ── Navigation active ─────────────────────────────────────────────────────────

        private val _navigationActive = MutableStateFlow(false)
        val navigationActive: StateFlow<Boolean> = _navigationActive.asStateFlow()

        // True when audio was started solely to serve alignment (so we can stop it on stopAlignment).
        private val _audioStartedForAlignment = MutableStateFlow(false)

        // ── Combined UI state ─────────────────────────────────────────────────────────

        private val telemetryGroup =
            combine(location, headingDeg, accuracyM, compassModeLabel, settings) { l, h, a, cm, s ->
                TelemetryGroup(l, h, a, cm, s)
            }
        private val dataGroup =
            combine(
                combine(waypoints, trails, collections, trailWaypoints) { w, tr, col, tw ->
                    DataGroup(w, tr, col, tw, emptyList(), emptyList())
                },
                combine(collectionWaypoints, collectionTrails) { cw, ct -> cw to ct },
            ) { dg, (cw, ct) -> dg.copy(collectionWaypoints = cw, collectionTrails = ct) }
        private val selectionGroup =
            combine(
                selectedTrailId,
                selectedCollectionId,
                trailFollowState,
                collectionExplorerState,
            ) { tr, col, fs, ce -> SelectionGroup(tr, col, fs, ce) }
        private val bearingGroup =
            combine(
                combine(targetName, bearingDeg, distanceM, relativeDeg) { tn, bd, dm, rd ->
                    BearingGroup(tn, bd, dm, rd, alignmentActive = false)
                },
                combine(alignmentActive, trailFollowState, trailGuidance) { aa, fs, guidance ->
                    Triple(aa, fs is TrailFollowerState.Active, guidance)
                },
            ) { group, (aa, trailActive, guidance) ->
                group.copy(
                    relativeDeg = if (trailActive) guidance?.relativeDeg else group.relativeDeg,
                    alignmentActive = aa,
                )
            }
        private val interactionGroup =
            combine(
                combine(alignmentBearingDeg, alignmentRelativeDeg, announcement, navigationActive) { ab, ar, ann, na ->
                    InteractionGroup(ab, ar, ann, na, TrailRecordingState.Idle, NavMode.NoCollection)
                },
                combine(recordingState, navMode) { rec, nm -> rec to nm },
            ) { group, (rec, nm) -> group.copy(recordingState = rec, navMode = nm) }

        val uiState: StateFlow<GpsUiState> =
            combine(
                telemetryGroup,
                dataGroup,
                selectionGroup,
                bearingGroup,
                interactionGroup,
            ) { tel, data, sel, bear, inter ->
                GpsUiState(
                    location = tel.location,
                    headingDeg = tel.headingDeg,
                    accuracyM = tel.accuracyM,
                    compassModeLabel = tel.compassModeLabel,
                    settings = tel.settings,
                    waypoints = data.waypoints,
                    trails = data.trails,
                    collections = data.collections,
                    trailWaypoints = data.trailWaypoints,
                    collectionWaypoints = data.collectionWaypoints,
                    collectionTrails = data.collectionTrails,
                    selectedTrailId = sel.selectedTrailId,
                    selectedCollectionId = sel.selectedCollectionId,
                    trailFollowState = sel.trailFollowState,
                    collectionExplorerState = sel.collectionExplorerState,
                    targetName = bear.targetName,
                    bearingDeg = bear.bearingDeg,
                    distanceM = bear.distanceM,
                    relativeDeg = bear.relativeDeg,
                    alignmentActive = bear.alignmentActive,
                    alignmentBearingDeg = inter.alignmentBearingDeg,
                    alignmentRelativeDeg = inter.alignmentRelativeDeg,
                    announcement = inter.announcement,
                    navigationActive = inter.navigationActive,
                    recordingState = inter.recordingState,
                    navMode = inter.navMode,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), GpsUiState())

        // ── Init ──────────────────────────────────────────────────────────────────────

        init {
            // Feed GPS fixes to the compass so it can compute magnetic declination.
            viewModelScope.launch {
                location.filterNotNull().collect { s ->
                    compassProvider.setLocation(s.lat, s.lon, s.altitude ?: 0.0)
                }
            }
            // Stop the explorer immediately on any collection change so GPS fixes that arrive before
            // the reactive DB queries re-emit don't fire announcements against stale points. The
            // explorer reloads once collectionWaypoints/collectionTrailEnds settle (below).
            viewModelScope.launch {
                selectedCollectionHolder.selectedCollectionId.collect {
                    collectionExplorer.stop()
                }
            }
            // Reload collection explorer from a fully reactive composition: standalone waypoints plus each
            // trail's ends (from the lean trailEndsForCollection query). Adding a track point (which
            // touches only trail_waypoint) shifts a trail's MAX position, so its end refreshes live —
            // no app restart, and without loading the trail's dense track-point body.
            viewModelScope.launch {
                collectionNavPoints(
                    standalones = collectionWaypoints,
                    trailEnds = collectionTrailEnds,
                ).collect { points -> reloadCollectionExplorer(points) }
            }
            // Bridge: honour "set as GPS target" requests made from the Waypoints/Trails screens so the
            // user can re-point navigation without leaving the screen they are on.
            viewModelScope.launch {
                targetingStateHolder.waypointTargetId.filterNotNull().collect { wpId ->
                    applyExternalWaypointTarget(wpId)
                    targetingStateHolder.clear()
                }
            }
            viewModelScope.launch {
                targetingStateHolder.trailRequest.filterNotNull().collect { req ->
                    when (req) {
                        is TrailTargetRequest.Follow -> followTrailById(req.trailId, req.reversed)
                        is TrailTargetRequest.Record -> recordTrailById(req.trailId)
                    }
                    targetingStateHolder.clearTrail()
                }
            }
            // Drive TrailFollower and CollectionExplorer on every GPS fix; surface events as announcements + audio.
            // Also handle auto-recording.
            viewModelScope.launch {
                location.filterNotNull().collect { sample ->
                    val smoothedHeading = guidanceCoordinator.updateTrustedCourse(sample)
                    announceTrailFollowerEvent(sample, smoothedHeading?.deg?.toFloat())
                    // Drive CollectionExplorer on every fix; it is the primary navigator. When no
                    // collection is loaded its state is Idle and onLocationUpdate is a no-op.
                    val travelHeadingDeg = sample.heading?.takeIf { (sample.speed ?: 0.0) >= MIN_TRAVEL_HEADING_SPEED_MPS }
                    announceCollectionEvent(collectionExplorer.onLocationUpdate(LatLng(sample.lat, sample.lon), travelHeadingDeg))
                    maybeRecordTrackPoint(sample)
                }
            }
        }

        // ── Selection ───────────────────────────────────────────────────────────────

        fun selectTrail(id: Long) {
            _selectedTrailId.value = id
            // Drive the state machine to Selected so the UI offers Follow (has points) or Record (empty).
            // Only while idle/selected — never disrupt an active follow/record session.
            viewModelScope.launch {
                val hasPoints = trailRepo.waypointsForTrail(id).isNotEmpty()
                val current = recordingMachine.state.value
                if (current is TrailRecordingState.Idle || current is TrailRecordingState.Selected) {
                    recordingMachine.selectTrail(id, hasPoints)
                }
            }
        }

        fun selectCollection(id: Long) {
            selectedCollectionHolder.select(id)
        }

        fun selectCollectionPoint(point: CollectionPoint) {
            collectionExplorer.selectTarget(point)
            backgroundSession.setModeActive(GpsBackgroundMode.CollectionFollow, true)
            startLocationService()
        }

        /**
         * Apply a cross-screen "set as GPS target" request: ensure a collection containing [waypointId]
         * is selected (so the explorer loads it), then wait for the matching standalone point to appear
         * and select it. Bounded wait so a missing/slow load never hangs the bridge.
         */
        private suspend fun applyExternalWaypointTarget(waypointId: Long) {
            val collections = collectionRepo.collectionsForWaypoint(waypointId)
            if (collections.isEmpty()) return
            val current = selectedCollectionHolder.selectedCollectionId.value
            if (current == null || collections.none { it.id == current }) {
                selectCollection(collections.first().id)
            }
            val point =
                withTimeoutOrNull(2_000L) {
                    collectionExplorer.state
                        .mapNotNull { st ->
                            (st as? CollectionExplorerState.Active)
                                ?.points
                                ?.firstOrNull { it is CollectionPoint.Standalone && it.waypoint.id == waypointId }
                        }.first()
                }
            point?.let { selectCollectionPoint(it) }
        }

        fun clearCollectionTarget() {
            collectionExplorer.clearTarget()
            backgroundSession.setModeActive(GpsBackgroundMode.CollectionFollow, false)
            stopLocationServiceIfIdle()
        }

        fun skipCollectionTarget() {
            val event = collectionExplorer.skipTarget() ?: return
            backgroundSession.setModeActive(GpsBackgroundMode.CollectionFollow, event.next != null)
            if (event.next != null) startLocationService() else stopLocationServiceIfIdle()

            val nextText =
                event.next?.let { next ->
                    val loc = location.value?.let { LatLng(it.lat, it.lon) }
                    val distanceText =
                        loc?.let {
                            formatDistanceM(
                                haversineDistanceMeters(it, LatLng(next.waypoint.lat, next.waypoint.lon)),
                                settings.value.units,
                            )
                        }
                    buildString {
                        append(" Next: ${next.displayName()}")
                        if (distanceText != null) append(", $distanceText")
                        append(".")
                    }
                } ?: " No more unvisited points."
            announce("Skipping ${event.skipped.displayName()}.$nextText", speakInBackground = true, trigger = "CollectionSkip")
        }

        fun clearCollectionVisited() {
            collectionExplorer.clearVisited()
        }

        fun setCollectionExploreMode(enabled: Boolean) {
            collectionExplorer.setExploreMode(enabled)
            backgroundSession.setModeActive(GpsBackgroundMode.CollectionFollow, enabled)
            if (enabled) startLocationService() else stopLocationServiceIfIdle()
        }

        /**
         * Follow [trailId] from its start ([reversed] = false) or end ([reversed] = true), fetching the
         * ordered waypoints directly from the repository. Used both by the collection explore "follow from
         * end" path and by trail-follow requests raised on the Trails screen (via [TargetingStateHolder]),
         * so the caller need not have the trail selected first.
         */
        private fun followTrailById(
            trailId: Long,
            reversed: Boolean,
        ) {
            viewModelScope.launch {
                // Reconcile collection: mirror applyExternalWaypointTarget so the nav list includes
                // this trail's endpoints. Every trail belongs to at least one collection (General is
                // the sentinel), so an empty result here is a data-integrity failure.
                val trailCollections = collectionRepo.collectionsForTrail(trailId)
                val current = selectedCollectionHolder.selectedCollectionId.value
                if (trailCollections.isNotEmpty() && (current == null || trailCollections.none { it.id == current })) {
                    selectCollection(trailCollections.first().id)
                }
                val wps = trailRepo.waypointsForTrail(trailId)
                val ordered = if (reversed) wps.reversed() else wps
                if (ordered.isEmpty()) return@launch
                val name = trailRepo.getById(trailId)?.name ?: "trail"
                val points = ordered.map { TrailPoint(it.id, it.name, it.lat, it.lon, elevationM = it.elevM, kind = it.kind) }
                val loc = location.value?.let { LatLng(it.lat, it.lon) }
                val bearing =
                    location.value?.let { s ->
                        s.heading?.takeIf { (s.speed ?: 0.0) >= TrailGuidance.MIN_TRUSTED_SPEED_MPS }?.toFloat()
                    }
                _selectedTrailId.value = trailId
                enterFollowing(trailId)
                if (loc != null) trailFollower.startNearest(points, loc, bearing) else trailFollower.start(points)
                refreshTrailGuidanceFromLatestLocation(resetOrdinaryThrottle = true)
                backgroundSession.setModeActive(GpsBackgroundMode.TrailFollow, true)
                startLocationService()
                announce(
                    buildTrailStartAnnouncement(
                        "Following $name${if (reversed) " in reverse" else ""}",
                        points,
                        loc,
                    ),
                    speakInBackground = true,
                    trigger = "TrailStartedFromCollection",
                )
            }
        }

        /** Select [trailId] and begin auto-recording onto it; used by Trails-screen record requests. */
        private fun recordTrailById(trailId: Long) {
            viewModelScope.launch {
                val trailCollections = collectionRepo.collectionsForTrail(trailId)
                val current = selectedCollectionHolder.selectedCollectionId.value
                if (trailCollections.isNotEmpty() && (current == null || trailCollections.none { it.id == current })) {
                    selectCollection(trailCollections.first().id)
                }
                val hasPoints = trailRepo.waypointsForTrail(trailId).isNotEmpty()
                _selectedTrailId.value = trailId
                recordingMachine.stop()
                recordingMachine.selectTrail(trailId, hasPoints)
                startAutoRecord()
            }
        }

        // ── Trail following ───────────────────────────────────────────────────────────

        /**
         * Drive the recording state machine into [TrailRecordingState.Following] for [trailId],
         * stopping any active session first so follow and record are never active together.
         */
        private fun enterFollowing(trailId: Long) {
            val current = recordingMachine.state.value
            if (current is TrailRecordingState.Following || current is TrailRecordingState.Recording) {
                recordingMachine.stop()
            }
            val now = recordingMachine.state.value
            if (now !is TrailRecordingState.Selected || now.trailId != trailId) {
                recordingMachine.selectTrail(trailId, hasPoints = true)
            }
            recordingMachine.startFollowing()
        }

        private fun buildTrailStartAnnouncement(
            prefix: String,
            points: List<TrailPoint>,
            loc: LatLng?,
        ): String {
            val active = trailFollower.state.value as? TrailFollowerState.Active
            val firstWp = active?.waypoints?.getOrNull(active.currentIndex)
            val total = active?.waypoints?.size ?: points.size
            val guidance = guidanceCoordinator.guidance.value
            return buildString {
                val checkpointN = active?.currentIndex?.plus(1) ?: 1
                append("$prefix. Checkpoint $checkpointN of $total.")
                if (firstWp != null && loc != null) {
                    val dist = haversineDistanceMeters(loc, LatLng(firstWp.lat, firstWp.lon))
                    val distLabel = formatDistanceM(dist, settings.value.units)
                    val relDir = guidance?.relativeDeg?.let { directionHint(it) }
                    if (relDir != null) {
                        append(" $distLabel, $relDir.")
                    } else {
                        append(" $distLabel.")
                    }
                }
            }
        }

        fun stopFollowTrail() {
            trailFollower.stop()
            recordingMachine.stop()
            guidanceCoordinator.clear()
            backgroundSession.setModeActive(GpsBackgroundMode.TrailFollow, false)
            stopLocationServiceIfIdle()
            announce("Trail navigation stopped", speakInBackground = true, trigger = "TrailStopped")
        }

        // ── Audio navigation ──────────────────────────────────────────────────────────

        fun startNavigation() {
            if (_navigationActive.value) return
            _navigationActive.value = true
            backgroundSession.startBeaconNavigation(beaconAudioInputs())
            _audioStartedForAlignment.value = false
            startLocationService()
        }

        fun stopNavigation() {
            if (!_navigationActive.value) return
            _navigationActive.value = false
            backgroundSession.stopBeaconNavigation()
            stopLocationServiceIfIdle()
        }

        // ── Alignment ─────────────────────────────────────────────────────────────────

        fun startAlignment() {
            alignmentController.start()
            if (!_navigationActive.value) {
                backgroundSession.startAlignment(beaconAudioInputs())
                _audioStartedForAlignment.value = true
            } else {
                _audioStartedForAlignment.value = false
            }
        }

        fun stopAlignment() {
            alignmentController.stop()
            if (!_navigationActive.value) {
                backgroundSession.stopAlignment()
            }
            _audioStartedForAlignment.value = false
        }

        fun resetAlignmentToCurrent() = alignmentController.resetToCurrentHeading()

        fun setAlignmentBearing(deg: Double) = alignmentController.setBearing(deg)

        fun setAlignmentToBearing() = alignmentController.alignToTarget()

        /**
         * Speak the current alignment delta on demand. Driven by the alignment modal's opt-in ticker:
         * the modal only requests this while auto-read is on, so the app never talks over the rest of
         * the UI uninvited.
         */
        fun speakAlignmentDelta() = alignmentController.speakDelta()

        // ── Collection editing from GPS screen ───────────────────────────────────────

        fun addWaypointsToCollection(ids: Set<Long>) {
            val collId = selectedCollectionHolder.selectedCollectionId.value ?: return
            viewModelScope.launch { ids.forEach { collectionRepo.attachWaypoint(collId, it) } }
        }

        fun addTrailsToCollection(ids: Set<Long>) {
            val collId = selectedCollectionHolder.selectedCollectionId.value ?: return
            viewModelScope.launch { ids.forEach { collectionRepo.attachTrail(collId, it) } }
        }

        // ── Waypoint marking ──────────────────────────────────────────────────────────

        /**
         * Capture the current location and open the naming dialog. No DB write happens here — the
         * waypoint is created only once the user confirms a name in [onWaypointNamed]. Guarded on a
         * selected collection (the FAB is also greyed out when none is selected).
         */
        fun markWaypoint(launchStt: Boolean = false) {
            val loc = location.value ?: return
            if (selectedCollectionHolder.selectedCollectionId.value == null) return
            _pendingCreate.value = PendingCreate.Waypoint(loc, launchStt)
        }

        fun onWaypointNamed(
            name: String,
            tentative: Boolean,
        ) {
            val pending = _pendingCreate.value as? PendingCreate.Waypoint ?: return
            val collectionId = selectedCollectionHolder.selectedCollectionId.value ?: return
            val loc = pending.capturedLocation
            _pendingCreate.value = null
            viewModelScope.launch {
                val waypointId =
                    waypointRepo.create(collectionId, name, loc.lat, loc.lon, loc.altitude, null, tentative)
                // While recording, also link this named waypoint to the trail. It stays a user
                // waypoint (collection-owned); only auto-captured points are kind=track_point.
                (recordingMachine.state.value as? TrailRecordingState.Recording)?.let { rec ->
                    waypointRepo.attach(rec.trailId, waypointId)
                }
                _announcement.value = "Waypoint marked: $name"
                // waypoints StateFlow updates automatically via observeAll()
            }
        }

        fun cancelPendingCreate() {
            _pendingCreate.value = null
        }

        /** Copy the current GPS coordinates to the clipboard and confirm aloud (SAR / sharing). */
        fun copyCoordinates() {
            val loc =
                location.value ?: run {
                    announce("No GPS fix to copy.", speakInBackground = true, trigger = "CopyCoordinates")
                    return
                }
            val text = "${"%.6f".format(loc.lat)}, ${"%.6f".format(loc.lon)}"
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("coordinates", text))
            announce("Coordinates copied: $text", speakInBackground = true, trigger = "CopyCoordinates")
        }

        // ── Permissions ───────────────────────────────────────────────────────────────

        fun hasForegroundLocationPermission(): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        // ── Service control ───────────────────────────────────────────────────────────

        private fun startLocationService() {
            context.startForegroundService(
                Intent(context, LocationForegroundService::class.java).apply {
                    action = LocationForegroundService.ACTION_START
                },
            )
        }

        private fun stopLocationService() {
            context.startService(
                Intent(context, LocationForegroundService::class.java).apply {
                    action = LocationForegroundService.ACTION_STOP
                },
            )
        }

        private fun stopLocationServiceIfIdle() {
            if (!backgroundSession.state.value.needsForegroundService) stopLocationService()
        }

        private fun beaconAudioInputs(): BeaconAudioInputs =
            BeaconAudioInputs(
                accuracyM = accuracyM,
                relativeDeg = audioRelativeDeg,
                alignmentActive = alignmentController.active,
                beaconCuesEnabled = beaconCuesEnabled,
                location = location,
                trailGuidance = trailGuidance,
                smoothedHeading = guidanceCoordinator.smoothedHeading,
            )

        private fun refreshTrailGuidanceFromLatestLocation(resetOrdinaryThrottle: Boolean = false) {
            val sample = location.value ?: return
            guidanceCoordinator.refreshFromLocation(trailFollower.state.value, sample, resetOrdinaryThrottle)
        }

        /**
         * Drive the TrailFollower with a fresh fix and speak the resulting event: an advance cue on
         * arrival, "Trail complete" on completion, or — when no event fires — the ordinary-guidance,
         * off-trail, and backtrack checks.
         */
        private fun announceTrailFollowerEvent(
            sample: LocationSample,
            smoothedBearingDeg: Float?,
        ) {
            when (
                val event =
                    trailFollower.onLocationUpdate(
                        location = LatLng(sample.lat, sample.lon),
                        altitudeM = sample.altitude,
                        bearingDeg =
                            sample.heading
                                ?.takeIf { (sample.speed ?: 0.0) >= TrailGuidance.MIN_TRUSTED_SPEED_MPS }
                                ?.toFloat(),
                        smoothedBearingDeg = smoothedBearingDeg,
                    )
            ) {
                is TrailFollowerEvent.WaypointReached -> {
                    val guidance = guidanceCoordinator.computeGuidance(trailFollower.state.value, sample)
                    val text = buildTrailAdvanceAnnouncement(event, guidance)
                    val reason = lastAdvancementReason
                    lastAdvancementReason = null
                    announce(
                        text,
                        speakInBackground = true,
                        trigger = "WaypointReached",
                        sample = sample,
                        guidance = guidance,
                        extraOverride =
                            if (reason == null) {
                                emptyMap()
                            } else {
                                buildMap {
                                    put("mechanism", reason.mechanism)
                                    put("closestApproachM", reason.closestApproachM)
                                    reason.headingDifferenceDeg?.let { put("headingDifferenceDeg", it) }
                                    put("smoothedBearingUsed", reason.smoothedBearingUsed)
                                }
                            },
                    )
                    guidanceCoordinator.resetThrottle(sample)
                }

                is TrailFollowerEvent.TrailComplete -> {
                    guidanceCoordinator.clear()
                    announce("Trail complete", speakInBackground = true, trigger = "TrailComplete", sample = sample)
                    trailFollower.stop()
                }

                null -> {
                    val followState = trailFollower.state.value
                    val guidance = guidanceCoordinator.computeGuidance(followState, sample)
                    announceOrdinaryTrailGuidance(followState, sample, guidance)
                    announceOffTrail(followState, sample, guidance)
                    announceBacktrack(followState, sample, guidance)
                }
            }
        }

        private fun buildTrailAdvanceAnnouncement(
            event: TrailFollowerEvent.WaypointReached,
            guidance: TrailGuidanceState?,
        ): String {
            val oneBasedN = event.index + 1
            val distance = guidance?.distanceToTargetM ?: event.distanceToNextM
            val distLabel = formatDistanceM(distance, settings.value.units)
            val relDir = guidance?.relativeDeg?.let { directionHint(it) }

            return if (event.kind == Waypoint.KIND_TRACK_POINT) {
                buildString {
                    append("Checkpoint $oneBasedN of ${event.total}.")
                    append(" $distLabel.")
                    if (relDir != null) append(" $relDir.")
                }
            } else {
                buildString {
                    append("${event.name}.")
                    append(" $distLabel.")
                    if (relDir != null) append(" $relDir.")
                }
            }
        }

        // The detection logic (counts, grace/cooldown windows, noise floor) lives in
        // [TrailGuidanceCoordinator]; these wrappers apply the effects — speak, beep, and write the
        // audio event log — when it decides an alert is warranted.

        private fun announceOrdinaryTrailGuidance(
            followState: TrailFollowerState,
            sample: LocationSample,
            guidance: TrailGuidanceState?,
        ) {
            val decision = guidanceCoordinator.evaluateOrdinaryGuidance(followState, sample, guidance) ?: return
            val distLabel = formatDistanceM(decision.distanceToTargetM, settings.value.units)
            announce(
                "Checkpoint ${decision.checkpointN} of ${decision.total}. $distLabel, ${directionHint(decision.relativeDeg)}.",
                speakInBackground = true,
                trigger = "OrdinaryTrailGuidance",
                sample = sample,
                guidance = guidance,
            )
        }

        // BUG-3: alert when consecutive GPS fixes show |relativeDeg| >= 60° (user is off trail).
        private fun announceOffTrail(
            followState: TrailFollowerState,
            sample: LocationSample,
            guidance: TrailGuidanceState?,
        ) {
            val eval = guidanceCoordinator.evaluateOffTrail(followState, sample, guidance) ?: return
            viewModelScope.launch {
                audioEventLog.append(
                    AudioLogEntry(
                        timestampMs = sample.timestamp,
                        kind = AudioLogEntry.Kind.DETECTION_STATE,
                        trigger = "OffTrailCheck",
                        inputs =
                            "relativeDeg=${eval.relativeDeg?.let { "%.1f°".format(it) } ?: "null"}" +
                                ", followerActive=${eval.followerActive}" +
                                ", smoothed=${guidanceCoordinator.courseIsSmoothed()}",
                        outputs = "consecutiveOffTrail=${eval.consecutiveCount}, sinceLastAlertMs=${eval.sinceLastAlertMs}",
                        played = eval.disposition,
                    ),
                )
            }
            if (!eval.fired) return
            announce("You may be off trail.", speakInBackground = true, trigger = "OffTrailAlert", sample = sample, guidance = guidance)
            viewModelScope.launch { scheduler.emitWrongVector() }
        }

        // BUG-9: alert when consecutive GPS fixes show distance to target steadily increasing (backtracking).
        private fun announceBacktrack(
            followState: TrailFollowerState,
            sample: LocationSample,
            guidance: TrailGuidanceState?,
        ) {
            val eval = guidanceCoordinator.evaluateBacktrack(followState, sample, guidance) ?: return
            viewModelScope.launch {
                audioEventLog.append(
                    AudioLogEntry(
                        timestampMs = sample.timestamp,
                        kind = AudioLogEntry.Kind.DETECTION_STATE,
                        trigger = "BacktrackCheck",
                        inputs =
                            "distToTarget=${eval.distanceToTargetM?.let { "%.1fm".format(it) } ?: "null"}" +
                                ", prevDist=${eval.prevDistanceToTargetM?.let { "%.1fm".format(it) } ?: "null"}" +
                                ", smoothed=${guidanceCoordinator.courseIsSmoothed()}",
                        outputs = "consecutiveBacktrack=${eval.consecutiveCount}, sinceLastAlertMs=${eval.sinceLastAlertMs}",
                        played = eval.disposition,
                    ),
                )
            }
            if (!eval.fired) return
            announce(
                "You may be going the wrong way.",
                speakInBackground = true,
                trigger = "BacktrackAlert",
                sample = sample,
                guidance = guidance,
            )
        }

        /**
         * Load the CollectionExplorer with an already-composed point list (standalones + trail ends,
         * derived reactively by [collectionNavPoints]), preserving the current explore-mode flag.
         * No-op when no collection is selected.
         */
        private fun reloadCollectionExplorer(points: List<CollectionPoint>) {
            if (selectedCollectionHolder.selectedCollectionId.value == null) return
            val currentExploreMode =
                (collectionExplorer.state.value as? CollectionExplorerState.Active)
                    ?.exploreMode ?: false
            collectionExplorer.load(points, currentExploreMode)
        }

        /**
         * Speak the cue for a CollectionExplorer event from a GPS fix (arrival, nearby point).
         * [CollectionExplorerEvent.NearTrailEnd] and [CollectionExplorerEvent.TargetSkipped] are
         * intentionally silent here: the UI reacts to the state change, and skips are user-driven.
         */
        private fun announceCollectionEvent(event: CollectionExplorerEvent?) {
            when (event) {
                is CollectionExplorerEvent.PointReached -> {
                    val name = event.reached.displayName()
                    val nextName = event.next?.displayName()
                    val text =
                        buildString {
                            append("Reached $name.")
                            if (nextName != null) {
                                append(" Next: $nextName.")
                            } else if ((collectionExplorer.state.value as? CollectionExplorerState.Active)?.exploreMode ==
                                false
                            ) {
                                append(" Choose another target or tap Nearest.")
                            } else {
                                append(" No more unvisited points.")
                            }
                        }
                    announce(text, speakInBackground = true, trigger = "CollectionPointReached")
                }

                is CollectionExplorerEvent.NearTrailEnd -> {
                    // UI reacts to state change; no audio needed here.
                }

                is CollectionExplorerEvent.TargetSkipped -> {
                    // Skip is emitted only by the user action path, not by GPS updates.
                }

                is CollectionExplorerEvent.NearbyPoint -> {
                    val dist = formatDistanceM(event.distanceM, settings.value.units)
                    announce("Nearby: ${event.point.displayName()}, $dist", speakInBackground = true, trigger = "NearbyPoint")
                }

                null -> {
                    Unit
                }
            }
        }

        /**
         * While recording, attach a track point to the active trail once the user has moved
         * [AUTO_RECORD_DISTANCE_M] from the last recorded point. No-op in any other state. The
         * recording machine is the single source of truth for whether recording is active.
         */
        private fun maybeRecordTrackPoint(sample: LocationSample) {
            val recording = recordingMachine.state.value as? TrailRecordingState.Recording ?: return
            val current = LatLng(sample.lat, sample.lon)
            val last = _lastAutoRecordLoc
            if (last != null && haversineDistanceMeters(last, current) < AUTO_RECORD_DISTANCE_M) return
            _lastAutoRecordLoc = current
            val trailId = recording.trailId
            val name = "Track ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"
            viewModelScope.launch {
                waypointRepo.createTrackPoint(trailId, name, sample.lat, sample.lon, sample.altitude)
                recordingMachine.addPoint()
                val count = (recordingMachine.state.value as? TrailRecordingState.Recording)?.pointCount ?: 0
                if (count % AUTO_RECORD_TTS_INTERVAL == 0) {
                    spokenGuidancePlayer.speak("$count track points recorded")
                }
                // trailWaypoints updates automatically via observeWaypointsForTrail
            }
        }

        private fun announce(
            text: String,
            speakInBackground: Boolean = false,
            trigger: String = "unknown",
            sample: LocationSample? = null,
            guidance: TrailGuidanceState? = null,
            extraOverride: Map<String, Any?> = emptyMap(),
        ) {
            _announcement.value = text
            val ttsDelivered = speakInBackground && spokenGuidancePlayer.speak(text)
            viewModelScope.launch {
                val extra =
                    buildMap<String, Any?> {
                        putAll(extraOverride)
                        put("ttsDelivered", ttsDelivered)
                        sample?.let {
                            if (BuildConfig.SHOW_DEBUG_FEATURES) {
                                put("userLat", it.lat)
                                put("userLng", it.lon)
                                it.altitude?.let { v -> put("userElev_m", v) }
                                it.heading?.let { v -> put("userHeading", v) }
                                it.speed?.let { v -> put("userSpeed_ms", v) }
                            }
                            it.accuracy?.let { v -> put("userAccuracy_m", v) }
                        }
                        guidance?.let {
                            put("targetIndex", it.targetIndex)
                            put("distToTarget_m", it.distanceToTargetM)
                            put("trailProgress_pct", (it.targetIndex.toDouble() / it.total) * 100.0)
                            val active = trailFollower.state.value as? TrailFollowerState.Active
                            val target = active?.waypoints?.getOrNull(it.targetIndex)
                            if (BuildConfig.SHOW_DEBUG_FEATURES) {
                                target?.let { wp ->
                                    put("targetLat", wp.lat)
                                    put("targetLng", wp.lon)
                                    wp.elevationM?.let { e -> put("targetElev_m", e) }
                                }
                            }
                            if (active != null && it.targetIndex > 0 && target != null) {
                                val prev = active.waypoints[it.targetIndex - 1]
                                val loc = sample?.let { s -> LatLng(s.lat, s.lon) }
                                if (loc != null) {
                                    val prevLL = LatLng(prev.lat, prev.lon)
                                    val targetLL = LatLng(target.lat, target.lon)
                                    put("crossTrackErr_m", distanceToSegmentMeters(loc, prevLL, targetLL))
                                    val t = segmentFraction(loc, prevLL, targetLL).coerceIn(0.0, 1.0)
                                    put("alongTrackDist_m", t * haversineDistanceMeters(prevLL, targetLL))
                                }
                            }
                        }
                    }
                audioEventLog.append(
                    AudioLogEntry(
                        timestampMs = System.currentTimeMillis(),
                        kind = AudioLogEntry.Kind.TTS_ANNOUNCEMENT,
                        trigger = trigger,
                        inputs = "text=\"$text\"",
                        outputs = "",
                        played = if (ttsDelivered) "Spoke: '$text'" else "Suppressed: '$text'",
                        extra = extra,
                    ),
                )
            }
        }

        // ── Trail recording ───────────────────────────────────────────────────────────

        fun recordNewTrail(launchStt: Boolean = false) {
            if (selectedCollectionHolder.selectedCollectionId.value == null) return
            _pendingCreate.value = PendingCreate.Trail(launchStt)
        }

        fun onTrailNamed(
            name: String,
            tentative: Boolean,
        ) {
            if (_pendingCreate.value !is PendingCreate.Trail) return
            val collectionId = selectedCollectionHolder.selectedCollectionId.value ?: return
            _pendingCreate.value = null
            viewModelScope.launch {
                val id = trailRepo.create(collectionId, name, null, tentative)
                _selectedTrailId.value = id
                recordingMachine.stop()
                recordingMachine.selectTrail(id, hasPoints = false)
                // trails and trailWaypoints update automatically via observe flows
                _announcement.value = "New trail: $name. Tap mark to add waypoints, or use auto-record."
            }
        }

        fun startAutoRecord() {
            val trailId = _selectedTrailId.value ?: return
            val current = recordingMachine.state.value
            if (current is TrailRecordingState.Recording || current is TrailRecordingState.Following) return
            if (current !is TrailRecordingState.Selected || current.trailId != trailId) {
                recordingMachine.selectTrail(trailId, hasPoints = trailWaypoints.value.isNotEmpty())
            }
            recordingMachine.startRecording()
            _lastAutoRecordLoc = location.value?.let { LatLng(it.lat, it.lon) }
            backgroundSession.setModeActive(GpsBackgroundMode.AutoRecord, true)
            startLocationService() // keep process alive when screen is off
            announce("Auto-recording started. Move to capture track points.", speakInBackground = true, trigger = "AutoRecordStart")
        }

        fun stopAutoRecord() {
            val count = (recordingMachine.state.value as? TrailRecordingState.Recording)?.pointCount ?: 0
            recordingMachine.stop()
            _lastAutoRecordLoc = null
            backgroundSession.setModeActive(GpsBackgroundMode.AutoRecord, false)
            stopLocationServiceIfIdle()
            announce(
                "Auto-recording stopped. $count points recorded.",
                speakInBackground = true,
                trigger = "AutoRecordStop",
            )
        }

        /**
         * Resume recording onto a trail from one of its ends. Offered only when the explore target is a
         * [CollectionPoint.TrailEnd] within the accuracy-scaled extension threshold (see [NavModeResolver]).
         */
        fun extendTrailFromCollectionEnd(trailEnd: CollectionPoint.TrailEnd) {
            _selectedTrailId.value = trailEnd.trail.id
            recordingMachine.stop()
            recordingMachine.selectTrail(trailEnd.trail.id, hasPoints = true)
            startAutoRecord()
        }

        // ── Action dispatcher ─────────────────────────────────────────────────────────

        fun onAction(action: GpsAction) {
            when (action) {
                is GpsAction.SelectTrail -> {
                    selectTrail(action.id)
                }

                is GpsAction.SelectCollection -> {
                    selectCollection(action.id)
                }

                is GpsAction.CreateCollection -> {
                    viewModelScope.launch {
                        val id = collectionRepo.create(action.name, null)
                        selectCollection(id)
                    }
                }

                is GpsAction.SelectCollectionPoint -> {
                    selectCollectionPoint(action.point)
                }

                GpsAction.ClearCollectionTarget -> {
                    clearCollectionTarget()
                }

                GpsAction.SkipCollectionTarget -> {
                    skipCollectionTarget()
                }

                GpsAction.ClearCollectionVisited -> {
                    clearCollectionVisited()
                }

                is GpsAction.SetCollectionExploreMode -> {
                    setCollectionExploreMode(action.enabled)
                }

                is GpsAction.ExtendTrailFromCollectionEnd -> {
                    extendTrailFromCollectionEnd(action.trailEnd)
                }

                is GpsAction.FollowTrail -> {
                    followTrailById(action.trailId, action.reversed)
                }

                GpsAction.StopFollowTrail -> {
                    stopFollowTrail()
                }

                GpsAction.StartNavigation -> {
                    startNavigation()
                }

                GpsAction.StopNavigation -> {
                    stopNavigation()
                }

                GpsAction.StartAlignment -> {
                    startAlignment()
                }

                GpsAction.StopAlignment -> {
                    stopAlignment()
                }

                GpsAction.ResetAlignment -> {
                    resetAlignmentToCurrent()
                }

                is GpsAction.SetAlignmentBearing -> {
                    setAlignmentBearing(action.deg)
                }

                GpsAction.AlignToTarget -> {
                    setAlignmentToBearing()
                    startAlignment()
                }

                GpsAction.SpeakAlignmentDelta -> {
                    speakAlignmentDelta()
                }

                GpsAction.MarkWaypoint -> {
                    markWaypoint()
                }

                GpsAction.MarkWaypointWithSpeech -> {
                    markWaypoint(launchStt = true)
                }

                GpsAction.CopyCoordinates -> {
                    copyCoordinates()
                }

                GpsAction.RecordNewTrail -> {
                    recordNewTrail()
                }

                GpsAction.RecordNewTrailWithSpeech -> {
                    recordNewTrail(launchStt = true)
                }

                GpsAction.StartAutoRecord -> {
                    startAutoRecord()
                }

                GpsAction.StopAutoRecord -> {
                    stopAutoRecord()
                }

                is GpsAction.AddWaypointsToCollection -> {
                    addWaypointsToCollection(action.ids)
                }

                is GpsAction.AddTrailsToCollection -> {
                    addTrailsToCollection(action.ids)
                }
            }
        }

        private fun directionHint(relativeDeg: Double): String =
            com.boldexplorer.shared.navigation.BearingComputer
                .toRelative(relativeDeg)

        private fun formatDistanceM(
            meters: Double,
            units: Units,
        ): String =
            if (units == Units.METRIC) {
                if (meters < 1000) {
                    "${meters.roundToInt()} meters"
                } else {
                    "${"%.1f".format(meters / 1000)} km"
                }
            } else {
                val feet = meters * 3.28084
                if (feet < 1000) {
                    "${feet.roundToInt()} feet"
                } else {
                    "${"%.1f".format(feet / 5280)} miles"
                }
            }

        override fun onCleared() {
            super.onCleared()
            if (_navigationActive.value) backgroundSession.stopBeaconNavigation()
        }

        companion object {
            private const val AUTO_RECORD_DISTANCE_M = 10.0
            private const val AUTO_RECORD_TTS_INTERVAL = 5

            // Minimum speed (m/s) before GPS course-over-ground is trusted for direction-aware selection.
            private const val MIN_TRAVEL_HEADING_SPEED_MPS = 1.0

            // Padding added to the near-trail gate when sizing the mid-trail snapshot bbox, so the
            // query window always covers whatever distance NearbyTrailResolver may admit.
            private const val NEARBY_TRAIL_BBOX_MARGIN_M = 30.0
        }
    }
