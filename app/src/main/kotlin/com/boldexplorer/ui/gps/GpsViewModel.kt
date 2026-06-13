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
import com.boldexplorer.shared.audio.AudioCueScheduler
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.deltaAngle
import com.boldexplorer.shared.geo.distanceToSegmentMeters
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.geo.initialBearingDeg
import com.boldexplorer.shared.geo.segmentFraction
import com.boldexplorer.shared.location.LocationProvider
import com.boldexplorer.shared.model.Collection
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.navigation.CollectionExplorer
import com.boldexplorer.shared.navigation.CollectionExplorerEvent
import com.boldexplorer.shared.navigation.CollectionExplorerState
import com.boldexplorer.shared.navigation.CollectionPoint
import com.boldexplorer.shared.navigation.TrailFollower
import com.boldexplorer.shared.navigation.TrailFollowerEvent
import com.boldexplorer.shared.navigation.TrailFollowerState
import com.boldexplorer.shared.navigation.TrailGuidance
import com.boldexplorer.shared.navigation.TrailGuidanceState
import com.boldexplorer.shared.navigation.TrailPoint
import com.boldexplorer.shared.navigation.TrustedCourse
import com.boldexplorer.shared.repository.CollectionRepository
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

enum class GpsScope { WAYPOINT, TRAIL, COLLECTION }

private data class TargetInputs(
    val scope: GpsScope,
    val selectedWaypointId: Long?,
    val waypoints: List<Waypoint>,
    val trailWaypoints: List<Waypoint>,
    val collectionWaypoints: List<Waypoint>,
    val autoRecording: Boolean,
)

data class GpsUiState(
    val scope: GpsScope = GpsScope.WAYPOINT,
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
    val selectedWaypointId: Long? = null,
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
    val autoRecording: Boolean = false,
    val autoRecordCount: Int = 0,
)

sealed interface GpsAction {
    data class SetScope(
        val scope: GpsScope,
    ) : GpsAction

    data class SelectWaypoint(
        val id: Long,
    ) : GpsAction

    data class SelectTrail(
        val id: Long,
    ) : GpsAction

    data class SelectCollection(
        val id: Long,
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

    data class FollowTrailFromCollectionEnd(
        val trailEnd: CollectionPoint.TrailEnd,
    ) : GpsAction

    data object StartFollowTrail : GpsAction

    data object StartFollowTrailReversed : GpsAction

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

    data object MarkWaypoint : GpsAction

    data object RecordNewTrail : GpsAction

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
    data class Waypoint(
        val capturedLocation: LocationSample,
    ) : PendingCreate

    data object Trail : PendingCreate
}

private data class TelemetryGroup(
    val location: LocationSample?,
    val headingDeg: Double?,
    val accuracyM: Double?,
    val compassModeLabel: String,
    val settings: AppSettings,
)

private data class DataGroup(
    val scope: GpsScope,
    val waypoints: List<Waypoint>,
    val trails: List<Trail>,
    val collections: List<Collection>,
    val trailWaypoints: List<Waypoint>,
    val collectionWaypoints: List<Waypoint>,
    val collectionTrails: List<Trail>,
)

private data class SelectionGroup(
    val selectedWaypointId: Long?,
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
    val scope: GpsScope,
    val followState: TrailFollowerState,
)

private data class InteractionGroup(
    val alignmentBearingDeg: Double?,
    val alignmentRelativeDeg: Double?,
    val announcement: String,
    val navigationActive: Boolean,
    val autoRecording: Boolean,
    val autoRecordCount: Int,
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

        // Heading used for bearing-to-waypoint and clock/relative display.
        // GPS course-over-ground only — null when stationary or no fix. Matches Vue useBearingDistance.
        // No compass fallback: compass bearing is unreliable for targeting (phone orientation unknown,
        // pocket mode). A future "wand" mode will expose compass-based pointing explicitly.
        // Speed gate: Android FusedLocation keeps reporting the last bearing even when stationary,
        // so we null it out below MIN_TRUSTED_SPEED_MPS to suppress the directional beacon at rest.
        private val navHeadingDeg: StateFlow<Double?> =
            location
                .map { sample -> sample?.heading?.takeIf { (sample.speed ?: 0.0) >= TrailGuidance.MIN_TRUSTED_SPEED_MPS } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        val accuracyM: StateFlow<Double?> =
            location
                .map { it?.accuracy }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        // ── Scope + selection ─────────────────────────────────────────────────────────

        private val _scope = MutableStateFlow(GpsScope.WAYPOINT)
        val scope: StateFlow<GpsScope> = _scope.asStateFlow()

        private val _selectedWaypointId = MutableStateFlow<Long?>(null)
        val selectedWaypointId: StateFlow<Long?> = _selectedWaypointId.asStateFlow()

        private val _selectedTrailId = MutableStateFlow<Long?>(null)
        val selectedTrailId: StateFlow<Long?> = _selectedTrailId.asStateFlow()

        private val _selectedCollectionId = MutableStateFlow<Long?>(null)
        val selectedCollectionId: StateFlow<Long?> = _selectedCollectionId.asStateFlow()

        // ── Pending creation (drives the naming dialog; no DB write until named) ──────

        private val _pendingCreate = MutableStateFlow<PendingCreate?>(null)
        val pendingCreate: StateFlow<PendingCreate?> = _pendingCreate.asStateFlow()

        // ── Trail recording state (declared early; used in targetInputs below) ────────

        private val _autoRecording = MutableStateFlow(false)
        val autoRecording: StateFlow<Boolean> = _autoRecording.asStateFlow()

        private val _autoRecordCount = MutableStateFlow(0)
        val autoRecordCount: StateFlow<Int> = _autoRecordCount.asStateFlow()

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
            _selectedCollectionId
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(emptyList())
                    } else {
                        collectionRepo.observeWaypointsForCollection(id)
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        private val collectionTrails: StateFlow<List<Trail>> =
            _selectedCollectionId
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(emptyList())
                    } else {
                        collectionRepo.observeTrailsForCollection(id)
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

        // ── Trail follower ────────────────────────────────────────────────────────────

        private val trailFollower = TrailFollower()
        val trailFollowState: StateFlow<TrailFollowerState> = trailFollower.state
        private var lastTrustedCourse: TrustedCourse? = null
        private val _trailGuidance = MutableStateFlow<TrailGuidanceState?>(null)
        private val trailGuidance: StateFlow<TrailGuidanceState?> = _trailGuidance.asStateFlow()
        private val trailGuidanceRelativeDeg: StateFlow<Double?> =
            _trailGuidance
                .map { it?.relativeDeg }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)
        private var lastOrdinaryGuidanceAtMs = Long.MIN_VALUE
        private var lastOrdinaryGuidanceLocation: LatLng? = null
        private var consecutiveOffTrailCount = 0
        private var offTrailAlertFiredAt = 0L
        private var offTrailGraceUntilMs = 0L
        private var consecutiveBacktrackCount = 0
        private var prevDistToTargetM: Double? = null
        private var backtrackAlertFiredAt = 0L
        private var backtrackGraceUntilMs = 0L

        // ── Collection explorer ───────────────────────────────────────────────────────

        private val collectionExplorer = CollectionExplorer()
        val collectionExplorerState: StateFlow<CollectionExplorerState> = collectionExplorer.state

        // ── Derived navigation values ─────────────────────────────────────────────────

        private val targetInputs =
            combine(
                combine(_scope, _selectedWaypointId, waypoints) { sc, wpId, wps -> Triple(sc, wpId, wps) },
                combine(trailWaypoints, collectionWaypoints, _autoRecording) { tw, cw, ar -> Triple(tw, cw, ar) },
            ) { (sc, wpId, wps), (tw, cw, ar) ->
                TargetInputs(sc, wpId, wps, tw, cw, ar)
            }

        val targetCoord: StateFlow<LatLng?> =
            combine(
                targetInputs,
                trailFollowState,
                collectionExplorer.state,
            ) { inputs, fs, explorer ->
                when (inputs.scope) {
                    GpsScope.WAYPOINT -> {
                        inputs.waypoints.find { it.id == inputs.selectedWaypointId }?.let { LatLng(it.lat, it.lon) }
                    }

                    GpsScope.TRAIL -> {
                        (fs as? TrailFollowerState.Active)
                            ?.waypoints
                            ?.getOrNull(fs.currentIndex)
                            ?.let { LatLng(it.lat, it.lon) }
                            ?: if (inputs.autoRecording) {
                                null
                            } else {
                                inputs.trailWaypoints.firstOrNull()?.let { LatLng(it.lat, it.lon) }
                            }
                    }

                    GpsScope.COLLECTION -> {
                        (explorer as? CollectionExplorerState.Active)
                            ?.target
                            ?.waypoint
                            ?.let { LatLng(it.lat, it.lon) }
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        val targetName: StateFlow<String?> =
            combine(
                targetInputs,
                trailFollowState,
                collectionExplorer.state,
            ) { inputs, fs, explorer ->
                when (inputs.scope) {
                    GpsScope.WAYPOINT -> {
                        inputs.waypoints.find { it.id == inputs.selectedWaypointId }?.name
                    }

                    GpsScope.TRAIL -> {
                        (fs as? TrailFollowerState.Active)
                            ?.waypoints
                            ?.getOrNull(fs.currentIndex)
                            ?.name
                            ?: if (inputs.autoRecording) {
                                null
                            } else {
                                inputs.trailWaypoints.firstOrNull()?.name
                            }
                    }

                    GpsScope.COLLECTION -> {
                        (explorer as? CollectionExplorerState.Active)
                            ?.target
                            ?.let { p ->
                                when (p) {
                                    is CollectionPoint.Standalone -> p.waypoint.name
                                    is CollectionPoint.TrailEnd -> "${p.trail.name} (${if (p.isStart) "start" else "end"})"
                                }
                            }
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        val bearingDeg: StateFlow<Double?> =
            combine(location, targetCoord) { loc, target ->
                if (loc == null || target == null) {
                    null
                } else {
                    initialBearingDeg(LatLng(loc.lat, loc.lon), target)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        val distanceM: StateFlow<Double?> =
            combine(location, targetCoord) { loc, target ->
                if (loc == null || target == null) {
                    null
                } else {
                    haversineDistanceMeters(LatLng(loc.lat, loc.lon), target)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        // Uses GPS course-over-ground for target-relative display. Trail-follow beacons/speech use
        // TrailGuidance instead so they point along the trail segment, not necessarily direct-to-target.
        val relativeDeg: StateFlow<Double?> =
            combine(navHeadingDeg, bearingDeg) { heading, bearing ->
                if (heading == null || bearing == null) {
                    null
                } else {
                    deltaAngle(heading, bearing)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        // ── Alignment ─────────────────────────────────────────────────────────────────

        private val _alignmentActive = MutableStateFlow(false)
        val alignmentActive: StateFlow<Boolean> = _alignmentActive.asStateFlow()

        private val _alignmentBearingDeg = MutableStateFlow<Double?>(null)
        val alignmentBearingDeg: StateFlow<Double?> = _alignmentBearingDeg.asStateFlow()

        // When alignment is active, audio uses compass heading vs the stored alignment bearing.
        // Waypoint targeting uses GPS course (navHeadingDeg); alignment uses compass (headingDeg)
        // because the user is physically pointing the phone at a target, not moving toward it.
        private val audioAlignmentGroup =
            combine(
                headingDeg,
                _alignmentBearingDeg,
                _alignmentActive,
            ) { heading, alignBearing, alignActive ->
                AudioAlignmentGroup(heading, alignBearing, alignActive)
            }

        private val audioNavigationGroup =
            combine(
                combine(relativeDeg, trailGuidanceRelativeDeg) { wpRelative, trailRelative -> wpRelative to trailRelative },
                combine(_scope, trailFollowState) { scope, followState -> scope to followState },
            ) { (wpRelative, trailRelative), (scope, followState) ->
                AudioNavigationGroup(wpRelative, trailRelative, scope, followState)
            }

        val audioRelativeDeg: StateFlow<Double?> =
            combine(
                audioAlignmentGroup,
                audioNavigationGroup,
            ) { alignment, navigation ->
                if (alignment.alignmentActive && alignment.headingDeg != null && alignment.alignmentBearingDeg != null) {
                    deltaAngle(alignment.headingDeg, alignment.alignmentBearingDeg)
                } else if (navigation.scope == GpsScope.TRAIL && navigation.followState is TrailFollowerState.Active) {
                    navigation.trailRelativeDeg
                } else {
                    navigation.waypointRelativeDeg
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        // Signed delta between current compass heading and alignment target.
        val alignmentRelativeDeg: StateFlow<Double?> =
            combine(
                headingDeg,
                _alignmentBearingDeg,
                _alignmentActive,
            ) { heading, alignBearing, alignActive ->
                if (alignActive && heading != null && alignBearing != null) {
                    deltaAngle(heading, alignBearing)
                } else {
                    null
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

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
                combine(scope, waypoints, trails, collections, trailWaypoints) { sc, w, tr, col, tw ->
                    DataGroup(sc, w, tr, col, tw, emptyList(), emptyList())
                },
                combine(collectionWaypoints, collectionTrails) { cw, ct -> cw to ct },
            ) { dg, (cw, ct) -> dg.copy(collectionWaypoints = cw, collectionTrails = ct) }
        private val selectionGroup =
            combine(
                selectedWaypointId,
                selectedTrailId,
                selectedCollectionId,
                trailFollowState,
                collectionExplorerState,
            ) { wp, tr, col, fs, ce -> SelectionGroup(wp, tr, col, fs, ce) }
        private val bearingGroup =
            combine(
                combine(targetName, bearingDeg, distanceM, relativeDeg) { tn, bd, dm, rd ->
                    BearingGroup(tn, bd, dm, rd, alignmentActive = false)
                },
                combine(alignmentActive, _scope, trailFollowState, trailGuidance) { aa, scope, fs, guidance ->
                    Triple(aa, scope == GpsScope.TRAIL && fs is TrailFollowerState.Active, guidance)
                },
            ) { group, (aa, trailActive, guidance) ->
                group.copy(
                    relativeDeg = if (trailActive) guidance?.relativeDeg else group.relativeDeg,
                    alignmentActive = aa,
                )
            }
        private val interactionGroup =
            combine(
                combine(alignmentBearingDeg, alignmentRelativeDeg, announcement, navigationActive, autoRecording) { ab, ar, ann, na, rec ->
                    InteractionGroup(ab, ar, ann, na, rec, 0)
                },
                autoRecordCount,
            ) { i, c -> i.copy(autoRecordCount = c) }

        val uiState: StateFlow<GpsUiState> =
            combine(
                telemetryGroup,
                dataGroup,
                selectionGroup,
                bearingGroup,
                interactionGroup,
            ) { tel, data, sel, bear, inter ->
                GpsUiState(
                    scope = data.scope,
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
                    selectedWaypointId = sel.selectedWaypointId,
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
                    autoRecording = inter.autoRecording,
                    autoRecordCount = inter.autoRecordCount,
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
            // Reload collection explorer whenever the selected collection's waypoints or trails change.
            viewModelScope.launch {
                combine(collectionWaypoints, collectionTrails) { wps, trails -> wps to trails }
                    .collect { (wps, trails) ->
                        if (_scope.value != GpsScope.COLLECTION || _selectedCollectionId.value == null) return@collect
                        val standalones = wps.map { CollectionPoint.Standalone(it) }
                        val trailEnds =
                            trails.flatMap { trail ->
                                val ordered = trailRepo.waypointsForTrail(trail.id)
                                listOfNotNull(
                                    ordered.firstOrNull()?.let { CollectionPoint.TrailEnd(it, trail, isStart = true) },
                                    ordered
                                        .lastOrNull()
                                        ?.takeIf { ordered.size > 1 }
                                        ?.let { CollectionPoint.TrailEnd(it, trail, isStart = false) },
                                )
                            }
                        val currentExploreMode =
                            (collectionExplorer.state.value as? CollectionExplorerState.Active)
                                ?.exploreMode ?: false
                        collectionExplorer.load(standalones + trailEnds, currentExploreMode)
                    }
            }
            // Drive TrailFollower and CollectionExplorer on every GPS fix; surface events as announcements + audio.
            // Also handle auto-recording.
            viewModelScope.launch {
                location.filterNotNull().collect { sample ->
                    lastTrustedCourse = TrailGuidance.updateTrustedCourse(lastTrustedCourse, sample)
                    when (
                        val event =
                            trailFollower.onLocationUpdate(
                                location = LatLng(sample.lat, sample.lon),
                                altitudeM = sample.altitude,
                                bearingDeg =
                                    sample.heading
                                        ?.takeIf { (sample.speed ?: 0.0) >= TrailGuidance.MIN_TRUSTED_SPEED_MPS }
                                        ?.toFloat(),
                            )
                    ) {
                        is TrailFollowerEvent.WaypointReached -> {
                            val guidance = updateTrailGuidance(sample)
                            val text = buildTrailAdvanceAnnouncement(event, guidance)
                            announce(text, speakInBackground = true, trigger = "WaypointReached", sample = sample, guidance = guidance)
                            resetOrdinaryGuidanceThrottle(sample)
                        }

                        is TrailFollowerEvent.TrailComplete -> {
                            clearTrailGuidance()
                            announce("Trail complete", speakInBackground = true, trigger = "TrailComplete", sample = sample)
                            trailFollower.stop()
                        }

                        null -> {
                            val guidance = updateTrailGuidance(sample)
                            maybeAnnounceOrdinaryTrailGuidance(sample, guidance)
                            maybeAnnounceOffTrail(sample, guidance)
                            maybeAnnounceBacktrack(sample, guidance)
                        }
                    }

                    // Drive CollectionExplorer when in collection scope.
                    if (_scope.value == GpsScope.COLLECTION) {
                        val travelHeadingDeg = sample.heading?.takeIf { (sample.speed ?: 0.0) >= MIN_TRAVEL_HEADING_SPEED_MPS }
                        when (val event = collectionExplorer.onLocationUpdate(LatLng(sample.lat, sample.lon), travelHeadingDeg)) {
                            is CollectionExplorerEvent.PointReached -> {
                                val name =
                                    when (val p = event.reached) {
                                        is CollectionPoint.Standalone -> p.waypoint.name
                                        is CollectionPoint.TrailEnd -> "${p.trail.name} (${if (p.isStart) "start" else "end"})"
                                    }
                                val nextName =
                                    event.next?.let { p ->
                                        when (p) {
                                            is CollectionPoint.Standalone -> p.waypoint.name
                                            is CollectionPoint.TrailEnd -> "${p.trail.name} (${if (p.isStart) "start" else "end"})"
                                        }
                                    }
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
                                val name =
                                    when (val p = event.point) {
                                        is CollectionPoint.Standalone -> p.waypoint.name
                                        is CollectionPoint.TrailEnd -> "${p.trail.name} (${if (p.isStart) "start" else "end"})"
                                    }
                                val dist = formatDistanceM(event.distanceM, settings.value.units)
                                announce("Nearby: $name, $dist", speakInBackground = true, trigger = "NearbyPoint")
                            }

                            null -> {
                                Unit
                            }
                        }
                    }

                    // Auto-record: attach a GPS point to the active trail every AUTO_RECORD_DISTANCE_M.
                    if (_autoRecording.value) {
                        val trailId = _selectedTrailId.value ?: return@collect
                        val current = LatLng(sample.lat, sample.lon)
                        val last = _lastAutoRecordLoc
                        if (last == null || haversineDistanceMeters(last, current) >= AUTO_RECORD_DISTANCE_M) {
                            _lastAutoRecordLoc = current
                            val name = "Track ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"
                            launch {
                                waypointRepo.createTrackPoint(trailId, name, sample.lat, sample.lon, sample.altitude)
                                val count = _autoRecordCount.value + 1
                                _autoRecordCount.value = count
                                if (count % AUTO_RECORD_TTS_INTERVAL == 0) {
                                    spokenGuidancePlayer.speak("$count track points recorded")
                                }
                                // trailWaypoints updates automatically via observeWaypointsForTrail
                            }
                        }
                    }
                }
            }
        }

        // ── Scope + selection ─────────────────────────────────────────────────────────

        fun setScope(scope: GpsScope) {
            _scope.value = scope
        }

        fun selectWaypoint(id: Long) {
            _selectedWaypointId.value = id
        }

        fun selectTrail(id: Long) {
            _selectedTrailId.value = id
        }

        fun selectCollection(id: Long) {
            _selectedCollectionId.value = id
            collectionExplorer.stop() // will reload via collectionWaypoints/collectionTrails combine
        }

        fun selectCollectionPoint(point: CollectionPoint) {
            collectionExplorer.selectTarget(point)
            backgroundSession.setModeActive(GpsBackgroundMode.CollectionFollow, true)
            startLocationService()
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
                        append(" Next: ${collectionPointName(next)}")
                        if (distanceText != null) append(", $distanceText")
                        append(".")
                    }
                } ?: " No more unvisited points."
            announce("Skipping ${collectionPointName(event.skipped)}.$nextText", speakInBackground = true, trigger = "CollectionSkip")
        }

        fun clearCollectionVisited() {
            collectionExplorer.clearVisited()
        }

        fun setCollectionExploreMode(enabled: Boolean) {
            collectionExplorer.setExploreMode(enabled)
            backgroundSession.setModeActive(GpsBackgroundMode.CollectionFollow, enabled)
            if (enabled) startLocationService() else stopLocationServiceIfIdle()
        }

        fun startFollowTrailFromCollectionEnd(trailEnd: CollectionPoint.TrailEnd) {
            val reversed = !trailEnd.isStart
            viewModelScope.launch {
                val wps = trailRepo.waypointsForTrail(trailEnd.trail.id)
                val ordered = if (reversed) wps.reversed() else wps
                if (ordered.isEmpty()) return@launch
                val points = ordered.map { TrailPoint(it.id, it.name, it.lat, it.lon, elevationM = it.elevM, kind = it.kind) }
                val loc = location.value?.let { LatLng(it.lat, it.lon) }
                val bearing =
                    location.value?.let { s ->
                        s.heading?.takeIf { (s.speed ?: 0.0) >= TrailGuidance.MIN_TRUSTED_SPEED_MPS }?.toFloat()
                    }
                _selectedTrailId.value = trailEnd.trail.id
                setScope(GpsScope.TRAIL)
                if (loc != null) trailFollower.startNearest(points, loc, bearing) else trailFollower.start(points)
                refreshTrailGuidanceFromLatestLocation(resetOrdinaryThrottle = true)
                backgroundSession.setModeActive(GpsBackgroundMode.TrailFollow, true)
                startLocationService()
                announce(
                    buildTrailStartAnnouncement(
                        "Following ${trailEnd.trail.name}${if (reversed) " in reverse" else ""}",
                        points,
                        loc,
                    ),
                    speakInBackground = true,
                    trigger = "TrailStartedFromCollection",
                )
            }
        }

        // ── Trail following ───────────────────────────────────────────────────────────

        fun startFollowTrail() {
            val wps = trailWaypoints.value
            if (wps.isEmpty()) return
            val points = wps.map { TrailPoint(it.id, it.name, it.lat, it.lon, elevationM = it.elevM, kind = it.kind) }
            val loc = location.value?.let { LatLng(it.lat, it.lon) }
            val bearing =
                location.value?.let { s ->
                    s.heading?.takeIf { (s.speed ?: 0.0) >= TrailGuidance.MIN_TRUSTED_SPEED_MPS }?.toFloat()
                }
            if (loc != null) trailFollower.startNearest(points, loc, bearing) else trailFollower.start(points)
            refreshTrailGuidanceFromLatestLocation(resetOrdinaryThrottle = true)
            backgroundSession.setModeActive(GpsBackgroundMode.TrailFollow, true)
            startLocationService()
            announce(buildTrailStartAnnouncement("Trail started", points, loc), speakInBackground = true, trigger = "TrailStarted")
        }

        fun startFollowTrailReversed() {
            val wps = trailWaypoints.value
            if (wps.isEmpty()) return
            val points = wps.reversed().map { TrailPoint(it.id, it.name, it.lat, it.lon, elevationM = it.elevM, kind = it.kind) }
            val loc = location.value?.let { LatLng(it.lat, it.lon) }
            val bearing =
                location.value?.let { s ->
                    s.heading?.takeIf { (s.speed ?: 0.0) >= TrailGuidance.MIN_TRUSTED_SPEED_MPS }?.toFloat()
                }
            if (loc != null) trailFollower.startNearest(points, loc, bearing) else trailFollower.start(points)
            refreshTrailGuidanceFromLatestLocation(resetOrdinaryThrottle = true)
            backgroundSession.setModeActive(GpsBackgroundMode.TrailFollow, true)
            startLocationService()
            announce(
                buildTrailStartAnnouncement("Trail started in reverse", points, loc),
                speakInBackground = true,
                trigger = "TrailStartedReversed",
            )
        }

        private fun buildTrailStartAnnouncement(
            prefix: String,
            points: List<TrailPoint>,
            loc: LatLng?,
        ): String {
            val active = trailFollower.state.value as? TrailFollowerState.Active
            val firstWp = active?.waypoints?.getOrNull(active.currentIndex)
            val total = active?.waypoints?.size ?: points.size
            val guidance = _trailGuidance.value
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
            clearTrailGuidance()
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
            _alignmentBearingDeg.value = headingDeg.value ?: _alignmentBearingDeg.value ?: 0.0
            _alignmentActive.value = true
            if (!_navigationActive.value) {
                backgroundSession.startAlignment(beaconAudioInputs())
                _audioStartedForAlignment.value = true
            } else {
                _audioStartedForAlignment.value = false
            }
        }

        fun stopAlignment() {
            _alignmentActive.value = false
            if (!_navigationActive.value) {
                backgroundSession.stopAlignment()
            }
            _audioStartedForAlignment.value = false
        }

        fun resetAlignmentToCurrent() {
            headingDeg.value?.let { setAlignmentBearing(it) }
        }

        fun setAlignmentBearing(deg: Double) {
            _alignmentBearingDeg.value = ((deg % 360) + 360) % 360
        }

        fun setAlignmentToBearing() {
            bearingDeg.value?.let { setAlignmentBearing(it) }
        }

        // ── Collection editing from GPS screen ───────────────────────────────────────

        fun addWaypointsToCollection(ids: Set<Long>) {
            val collId = _selectedCollectionId.value ?: return
            viewModelScope.launch { ids.forEach { collectionRepo.attachWaypoint(collId, it) } }
        }

        fun addTrailsToCollection(ids: Set<Long>) {
            val collId = _selectedCollectionId.value ?: return
            viewModelScope.launch { ids.forEach { collectionRepo.attachTrail(collId, it) } }
        }

        // ── Waypoint marking ──────────────────────────────────────────────────────────

        /**
         * Capture the current location and open the naming dialog. No DB write happens here — the
         * waypoint is created only once the user confirms a name in [onWaypointNamed]. Guarded on a
         * selected collection (the FAB is also greyed out when none is selected).
         */
        fun markWaypoint() {
            val loc = location.value ?: return
            if (_selectedCollectionId.value == null) return
            _pendingCreate.value = PendingCreate.Waypoint(loc)
        }

        fun onWaypointNamed(
            name: String,
            tentative: Boolean,
        ) {
            val pending = _pendingCreate.value as? PendingCreate.Waypoint ?: return
            val collectionId = _selectedCollectionId.value ?: return
            val loc = pending.capturedLocation
            _pendingCreate.value = null
            viewModelScope.launch {
                val waypointId =
                    waypointRepo.create(collectionId, name, loc.lat, loc.lon, loc.altitude, null, tentative)
                // While recording, also link this named waypoint to the trail. It stays a user
                // waypoint (collection-owned); only auto-captured points are kind=track_point.
                if (_autoRecording.value) {
                    _selectedTrailId.value?.let { trailId -> waypointRepo.attach(trailId, waypointId) }
                }
                _announcement.value = "Waypoint marked: $name"
                // waypoints StateFlow updates automatically via observeAll()
            }
        }

        fun cancelPendingCreate() {
            _pendingCreate.value = null
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
                alignmentActive = _alignmentActive,
                beaconCuesEnabled = beaconCuesEnabled,
                location = location,
                trailGuidance = trailGuidance,
            )

        private fun updateTrailGuidance(sample: LocationSample): TrailGuidanceState? {
            val guidance = TrailGuidance.compute(trailFollower.state.value, sample, lastTrustedCourse)
            _trailGuidance.value = guidance
            return guidance
        }

        private fun refreshTrailGuidanceFromLatestLocation(resetOrdinaryThrottle: Boolean = false) {
            val sample = location.value ?: return
            lastTrustedCourse = TrailGuidance.updateTrustedCourse(lastTrustedCourse, sample)
            updateTrailGuidance(sample)
            if (resetOrdinaryThrottle) resetOrdinaryGuidanceThrottle(sample)
        }

        private fun clearTrailGuidance() {
            _trailGuidance.value = null
            lastOrdinaryGuidanceAtMs = Long.MIN_VALUE
            lastOrdinaryGuidanceLocation = null
            consecutiveOffTrailCount = 0
            offTrailAlertFiredAt = 0L
            offTrailGraceUntilMs = 0L
            consecutiveBacktrackCount = 0
            prevDistToTargetM = null
            backtrackAlertFiredAt = 0L
            backtrackGraceUntilMs = 0L
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

        private fun maybeAnnounceOrdinaryTrailGuidance(
            sample: LocationSample,
            guidance: TrailGuidanceState?,
        ) {
            if (_scope.value != GpsScope.TRAIL) return
            if (trailFollower.state.value !is TrailFollowerState.Active) return
            val relative = guidance?.relativeDeg ?: return
            if (!TrailGuidance.isMajorCorrection(relative)) return
            if (sample.timestamp - lastOrdinaryGuidanceAtMs < ORDINARY_GUIDANCE_INTERVAL_MS) return

            val current = LatLng(sample.lat, sample.lon)
            val lastLocation = lastOrdinaryGuidanceLocation
            if (lastLocation != null &&
                haversineDistanceMeters(lastLocation, current) < ORDINARY_GUIDANCE_DISTANCE_M
            ) {
                return
            }

            lastOrdinaryGuidanceAtMs = sample.timestamp
            lastOrdinaryGuidanceLocation = current
            val checkpointN = guidance.targetIndex + 1
            val distLabel = formatDistanceM(guidance.distanceToTargetM, settings.value.units)
            announce(
                "Checkpoint $checkpointN of ${guidance.total}. $distLabel, ${directionHint(relative)}.",
                speakInBackground = true,
                trigger = "OrdinaryTrailGuidance",
                sample = sample,
                guidance = guidance,
            )
        }

        // BUG-3: alert when consecutive GPS fixes show |relativeDeg| >= 60° (user is off trail).
        private fun maybeAnnounceOffTrail(
            sample: LocationSample,
            guidance: TrailGuidanceState?,
        ) {
            if (_scope.value != GpsScope.TRAIL) return
            if (trailFollower.state.value !is TrailFollowerState.Active) return
            if (sample.timestamp < offTrailGraceUntilMs) return

            val relative = guidance?.relativeDeg
            val sinceLastAlertMs = sample.timestamp - offTrailAlertFiredAt

            if (relative != null && TrailGuidance.isMajorCorrection(relative)) {
                consecutiveOffTrailCount++
            } else {
                consecutiveOffTrailCount = 0
                offTrailAlertFiredAt = 0L
            }

            viewModelScope.launch {
                audioEventLog.append(
                    AudioLogEntry(
                        timestampMs = sample.timestamp,
                        kind = AudioLogEntry.Kind.DETECTION_STATE,
                        trigger = "OffTrailCheck",
                        inputs =
                            "relativeDeg=${relative?.let { "%.1f°".format(it) } ?: "null"}" +
                                ", scope=${_scope.value}, followerActive=${trailFollower.state.value is TrailFollowerState.Active}",
                        outputs = "consecutiveOffTrail=$consecutiveOffTrailCount, sinceLastAlertMs=$sinceLastAlertMs",
                        played =
                            when {
                                relative == null -> {
                                    "bail:no_relative_deg"
                                }

                                consecutiveOffTrailCount < OFF_TRAIL_CONSECUTIVE_THRESHOLD -> {
                                    "bail:count_${consecutiveOffTrailCount}_of_$OFF_TRAIL_CONSECUTIVE_THRESHOLD"
                                }

                                sinceLastAlertMs < OFF_TRAIL_ALERT_INTERVAL_MS -> {
                                    "bail:cooldown_${sinceLastAlertMs}ms"
                                }

                                else -> {
                                    "FIRING"
                                }
                            },
                    ),
                )
            }

            if (relative == null) return
            if (consecutiveOffTrailCount < OFF_TRAIL_CONSECUTIVE_THRESHOLD) return
            if (sinceLastAlertMs < OFF_TRAIL_ALERT_INTERVAL_MS) return

            offTrailAlertFiredAt = sample.timestamp
            announce("You may be off trail.", speakInBackground = true, trigger = "OffTrailAlert", sample = sample, guidance = guidance)
            viewModelScope.launch { scheduler.emitWrongVector() }
        }

        // BUG-9: alert when consecutive GPS fixes show distance to target steadily increasing (backtracking).
        private fun maybeAnnounceBacktrack(
            sample: LocationSample,
            guidance: TrailGuidanceState?,
        ) {
            if (_scope.value != GpsScope.TRAIL) return
            if (trailFollower.state.value !is TrailFollowerState.Active) return
            if (sample.timestamp < backtrackGraceUntilMs) return

            val distM = guidance?.distanceToTargetM
            val prev = prevDistToTargetM
            val sinceLastAlertMs = sample.timestamp - backtrackAlertFiredAt

            if (distM == null) {
                consecutiveBacktrackCount = 0
                prevDistToTargetM = null
            } else {
                if (prev != null && distM > prev + BACKTRACK_NOISE_FLOOR_M) {
                    consecutiveBacktrackCount++
                } else {
                    consecutiveBacktrackCount = 0
                    backtrackAlertFiredAt = 0L
                }
                prevDistToTargetM = distM
            }

            viewModelScope.launch {
                audioEventLog.append(
                    AudioLogEntry(
                        timestampMs = sample.timestamp,
                        kind = AudioLogEntry.Kind.DETECTION_STATE,
                        trigger = "BacktrackCheck",
                        inputs =
                            "distToTarget=${distM?.let { "%.1fm".format(it) } ?: "null"}" +
                                ", prevDist=${prev?.let { "%.1fm".format(it) } ?: "null"}",
                        outputs = "consecutiveBacktrack=$consecutiveBacktrackCount, sinceLastAlertMs=$sinceLastAlertMs",
                        played =
                            when {
                                distM == null -> {
                                    "bail:no_guidance"
                                }

                                consecutiveBacktrackCount < BACKTRACK_CONSECUTIVE_THRESHOLD -> {
                                    "bail:count_${consecutiveBacktrackCount}_of_$BACKTRACK_CONSECUTIVE_THRESHOLD"
                                }

                                sinceLastAlertMs < BACKTRACK_ALERT_INTERVAL_MS -> {
                                    "bail:cooldown_${sinceLastAlertMs}ms"
                                }

                                else -> {
                                    "FIRING"
                                }
                            },
                    ),
                )
            }

            if (distM == null) return
            if (consecutiveBacktrackCount < BACKTRACK_CONSECUTIVE_THRESHOLD) return
            if (sinceLastAlertMs < BACKTRACK_ALERT_INTERVAL_MS) return

            backtrackAlertFiredAt = sample.timestamp
            announce(
                "You may be going the wrong way.",
                speakInBackground = true,
                trigger = "BacktrackAlert",
                sample = sample,
                guidance = guidance,
            )
        }

        private fun resetOrdinaryGuidanceThrottle(sample: LocationSample) {
            lastOrdinaryGuidanceAtMs = sample.timestamp
            lastOrdinaryGuidanceLocation = LatLng(sample.lat, sample.lon)
            consecutiveOffTrailCount = 0
            offTrailAlertFiredAt = 0L
            offTrailGraceUntilMs = sample.timestamp + OFF_TRAIL_GRACE_MS
            consecutiveBacktrackCount = 0
            prevDistToTargetM = null
            backtrackAlertFiredAt = 0L
            backtrackGraceUntilMs = sample.timestamp + BACKTRACK_GRACE_MS
        }

        private fun announce(
            text: String,
            speakInBackground: Boolean = false,
            trigger: String = "unknown",
            sample: LocationSample? = null,
            guidance: TrailGuidanceState? = null,
        ) {
            _announcement.value = text
            val ttsDelivered = speakInBackground && spokenGuidancePlayer.speak(text)
            viewModelScope.launch {
                val extra =
                    buildMap<String, Any?> {
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

        fun recordNewTrail() {
            if (_selectedCollectionId.value == null) return
            _pendingCreate.value = PendingCreate.Trail
        }

        fun onTrailNamed(
            name: String,
            tentative: Boolean,
        ) {
            if (_pendingCreate.value !is PendingCreate.Trail) return
            val collectionId = _selectedCollectionId.value ?: return
            _pendingCreate.value = null
            viewModelScope.launch {
                val id = trailRepo.create(collectionId, name, null, tentative)
                _selectedTrailId.value = id
                // trails and trailWaypoints update automatically via observe flows
                _announcement.value = "New trail: $name. Tap mark to add waypoints, or use auto-record."
            }
        }

        fun startAutoRecord() {
            _selectedTrailId.value ?: return
            _lastAutoRecordLoc = location.value?.let { LatLng(it.lat, it.lon) }
            _autoRecordCount.value = 0
            _autoRecording.value = true
            backgroundSession.setModeActive(GpsBackgroundMode.AutoRecord, true)
            startLocationService() // keep process alive when screen is off
            announce("Auto-recording started. Move to capture track points.", speakInBackground = true, trigger = "AutoRecordStart")
        }

        fun stopAutoRecord() {
            _autoRecording.value = false
            _lastAutoRecordLoc = null
            backgroundSession.setModeActive(GpsBackgroundMode.AutoRecord, false)
            stopLocationServiceIfIdle()
            announce(
                "Auto-recording stopped. ${_autoRecordCount.value} points recorded.",
                speakInBackground = true,
                trigger = "AutoRecordStop",
            )
        }

        // ── Action dispatcher ─────────────────────────────────────────────────────────

        fun onAction(action: GpsAction) {
            when (action) {
                is GpsAction.SetScope -> {
                    setScope(action.scope)
                }

                is GpsAction.SelectWaypoint -> {
                    selectWaypoint(action.id)
                }

                is GpsAction.SelectTrail -> {
                    selectTrail(action.id)
                }

                is GpsAction.SelectCollection -> {
                    selectCollection(action.id)
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

                is GpsAction.FollowTrailFromCollectionEnd -> {
                    startFollowTrailFromCollectionEnd(action.trailEnd)
                }

                GpsAction.StartFollowTrail -> {
                    startFollowTrail()
                }

                GpsAction.StartFollowTrailReversed -> {
                    startFollowTrailReversed()
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

                GpsAction.MarkWaypoint -> {
                    markWaypoint()
                }

                GpsAction.RecordNewTrail -> {
                    recordNewTrail()
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

        private fun collectionPointName(point: CollectionPoint): String =
            when (point) {
                is CollectionPoint.Standalone -> point.waypoint.name
                is CollectionPoint.TrailEnd -> "${point.trail.name} (${if (point.isStart) "start" else "end"})"
            }

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
            private const val ORDINARY_GUIDANCE_INTERVAL_MS = 30_000L
            private const val ORDINARY_GUIDANCE_DISTANCE_M = 25.0
            private const val OFF_TRAIL_CONSECUTIVE_THRESHOLD = 2
            private const val OFF_TRAIL_ALERT_INTERVAL_MS = 45_000L
            private const val OFF_TRAIL_GRACE_MS = 30_000L
            private const val BACKTRACK_CONSECUTIVE_THRESHOLD = 3
            private const val BACKTRACK_NOISE_FLOOR_M = 2.0
            private const val BACKTRACK_ALERT_INTERVAL_MS = 45_000L
            private const val BACKTRACK_GRACE_MS = 20_000L
        }
    }
