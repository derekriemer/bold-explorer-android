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
import com.boldexplorer.audio.ShadowMatchMonitor
import com.boldexplorer.audio.AudioLogEntry
import com.boldexplorer.audio.LastOutput
import com.boldexplorer.audio.LiveRegionAnnouncement
import com.boldexplorer.audio.OutputRouter
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
import com.boldexplorer.shared.location.isLocationStale
import com.boldexplorer.shared.model.Collection
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.TrailEndRow
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.navigation.AnchorOption
import com.boldexplorer.shared.navigation.AnnotationCueProducer
import com.boldexplorer.shared.navigation.ArmingResult
import com.boldexplorer.shared.navigation.CollectionExplorer
import com.boldexplorer.shared.navigation.CollectionExplorerEvent
import com.boldexplorer.shared.navigation.CollectionExplorerState
import com.boldexplorer.shared.navigation.CollectionPoint
import com.boldexplorer.shared.navigation.NavMode
import com.boldexplorer.shared.navigation.NavModeResolver
import com.boldexplorer.shared.navigation.NavigationPolicy
import com.boldexplorer.shared.navigation.NavigationTargetResolver
import com.boldexplorer.shared.navigation.ExternalTargetRequest
import com.boldexplorer.shared.navigation.FollowArming
import com.boldexplorer.shared.navigation.NearbyTrail
import com.boldexplorer.shared.navigation.resolveIn
import com.boldexplorer.shared.navigation.NearbyTrailResolver
import com.boldexplorer.shared.navigation.MatchState
import com.boldexplorer.shared.navigation.MatchStateCue
import com.boldexplorer.shared.navigation.MatchStateCueProducer
import com.boldexplorer.shared.navigation.ProgressCueProducer
import com.boldexplorer.shared.navigation.RouteAnnotation
import com.boldexplorer.shared.navigation.TrailFollower
import com.boldexplorer.shared.navigation.FixOutcome
import com.boldexplorer.audio.trailMatchLogEntry
import com.boldexplorer.shared.navigation.TravelDirection
import com.boldexplorer.shared.navigation.TrailFollowerEvent
import com.boldexplorer.shared.navigation.TrailFollowerState
import com.boldexplorer.shared.navigation.TrackPointDecision
import com.boldexplorer.shared.navigation.TrackPointGate
import com.boldexplorer.shared.navigation.TrailGuidance
import com.boldexplorer.shared.navigation.TrailGuidanceCoordinator
import com.boldexplorer.shared.navigation.TrailGuidanceState
import com.boldexplorer.shared.navigation.TrailMatch
import com.boldexplorer.shared.navigation.TrailPoint
import com.boldexplorer.shared.navigation.TrailPolyline
import com.boldexplorer.shared.navigation.TrailPosition
import com.boldexplorer.shared.navigation.TrailRecordingMachine
import com.boldexplorer.shared.navigation.TrailRecordingState
import com.boldexplorer.shared.navigation.collectionNavPoints
import com.boldexplorer.shared.navigation.displayName
import com.boldexplorer.shared.navigation.followerIndexFor
import com.boldexplorer.shared.navigation.formatSpokenDistance
import com.boldexplorer.shared.output.OutputCategory
import com.boldexplorer.shared.output.OutputEvent
import com.boldexplorer.shared.output.OutputKind
import com.boldexplorer.shared.output.OutputManager
import com.boldexplorer.shared.output.OutputOrigin
import com.boldexplorer.shared.repository.CollectionRepository
import com.boldexplorer.shared.repository.NavPointsRepository
import com.boldexplorer.shared.repository.SettingsRepository
import com.boldexplorer.shared.repository.TrailAnnotationRepository
import com.boldexplorer.shared.repository.TrailRepository
import com.boldexplorer.shared.repository.WaypointRepository
import com.boldexplorer.shared.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
    val locationStale: Boolean = false,
    val alignmentActive: Boolean = false,
    val alignmentBearingDeg: Double? = null,
    val alignmentRelativeDeg: Double? = null,
    // Most recent output, whether or not it was actually spoken (#18) — surfaced as plain
    // telemetry (not a live region), so silence mode doesn't defeat itself by auto-announcing this.
    val lastOutput: LastOutput? = null,
    val navigationActive: Boolean = false,
    val recordingState: TrailRecordingState = TrailRecordingState.Idle,
    val navMode: NavMode = NavMode.NoCollection,
)

/**
 * A follow blocked on "which pass did you mean" (ADR 0002 §2/§4) — the trail carries the walker's
 * position more than once, and nothing starts until they pick one.
 *
 * Labels are whole spoken phrases, built here rather than in `:shared` so the geometric facts
 * ([com.boldexplorer.shared.navigation.AnchorOption]) stay separate from their English phrasing —
 * see [GpsViewModel.forkOptionLabel]. `options[i]`'s label corresponds to
 * [GpsViewModel.resolveFollowFork]'s `optionIndex = i`.
 */
data class FollowForkPrompt(
    val options: List<FollowForkOption>,
)

data class FollowForkOption(
    val label: String,
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

    data class SetCollectionAutoAdvance(
        val enabled: Boolean,
    ) : GpsAction

    /** Toggle absolute silence mode (#14) — primary control lives in the HUD controls row (#15). */
    data class SetAbsoluteSilence(
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
    val locationStale: Boolean = false,
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
    val navigationActive: Boolean,
    val recordingState: TrailRecordingState,
    val navMode: NavMode,
    val lastOutput: LastOutput? = null,
)

/**
 * The S6 follow cue producers, held for the lifetime of one trail follow (ADR 0001, S6).
 *
 * [alongTrackMBeforeThisFix] trails the producers by one fix on purpose: `TrailMatch.confirmedAlongM`
 * freezes for the whole Uncertain/Lost span (it only advances again on a fix that reconfirms), so
 * "whatever it was on the previous fix" is exactly "where the dropout began" — right up to and
 * including the fix that reacquires, which is the one moment [AnnotationCueProducer.onReacquired]
 * needs that value for. Captured every fix regardless of match state so it is always one fix stale,
 * never more.
 *
 * [previousMatch] is an identity marker, not a value to read: `TrailGuidanceCoordinator.onFix`
 * returns the *same* `TrailMatch` instance unchanged when a fix was too stale to match, rather than
 * null, so comparing by `===` is what lets `announceFollowCues` tell "a genuinely new fix" from "the
 * same evidence delivered twice" and avoid double-counting the latter.
 */
private class FollowCueProducers(
    val progress: ProgressCueProducer,
    val annotation: AnnotationCueProducer,
    val matchState: MatchStateCueProducer,
) {
    var alongTrackMBeforeThisFix: Double? = null
    var previousMatch: TrailMatch? = null
}

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
        private val annotationRepo: TrailAnnotationRepository,
        private val collectionRepo: CollectionRepository,
        private val navPointsRepo: NavPointsRepository,
        private val targetingStateHolder: TargetingStateHolder,
        private val selectedCollectionHolder: SelectedCollectionHolder,
        private val backgroundSession: GpsBackgroundSession,
        private val outputManager: OutputManager,
        private val outputRouter: OutputRouter,
        private val settingsRepo: SettingsRepository,
        private val audioEventLog: AudioEventLog,
        private val shadowMatchMonitor: ShadowMatchMonitor,
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

        // Issue #23: distance/bearing derived from `location` freeze when fixes stop arriving, but
        // nothing re-checks the clock on its own — a ticker is required so staleness can flip true
        // even without a new fix, instead of only ever being noticed retroactively once one arrives.
        val locationStale: StateFlow<Boolean> =
            combine(
                location,
                flow {
                    while (true) {
                        emit(Unit)
                        delay(1_000L)
                    }
                },
            ) { loc, _ -> isLocationStale(System.currentTimeMillis(), loc?.timestamp) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

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

        // ── Follow arming (ADR 0002) ────────────────────────────────────────────────

        private val _followPrompt = MutableStateFlow<FollowForkPrompt?>(null)

        /**
         * Non-null while a follow is blocked on "which pass did you mean" (ADR 0002 §4). Hoisted to
         * [com.boldexplorer.ui.NavGraph] rather than owned by the GPS screen, because one entry point
         * to [followTrailById] — the Trails-screen follow action — never navigates there.
         */
        val followPrompt: StateFlow<FollowForkPrompt?> = _followPrompt.asStateFlow()

        /** The follow [followPrompt] is blocked on; `null` whenever [followPrompt] is. */
        private var pendingFollow: PendingFollow? = null

        val selectedCollectionId: StateFlow<Long?> = selectedCollectionHolder.selectedCollectionId

        // ── Pending creation (drives the naming dialog; no DB write until named) ──────

        private val _pendingCreate = MutableStateFlow<PendingCreate?>(null)
        val pendingCreate: StateFlow<PendingCreate?> = _pendingCreate.asStateFlow()

        // ── Trail recording state machine ────────────────────────────────────────────
        // Single source of truth that guarantees follow and record are never offered together.

        private val recordingMachine = TrailRecordingMachine()
        val recordingState: StateFlow<TrailRecordingState> = recordingMachine.state

        // Distance throttle + plausibility for auto-record (not part of the state machine). One
        // object because the two anchors it holds have to be kept apart deliberately.
        private val trackPointGate = TrackPointGate(minSpacingM = AUTO_RECORD_DISTANCE_M)

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

        private val trailFollower = TrailFollower()
        val trailFollowState: StateFlow<TrailFollowerState> = trailFollower.state

        // ── Follow cue producers (ADR 0001, S6) ──────────────────────────────────────
        // Constructed in followTrailById when a follow starts, dropped in stopFollowTrail and on
        // TrailComplete. Null exactly when no follow is active — same lifetime as the trail-follow
        // session held inside guidanceCoordinator, but tracked separately because these are pure
        // :shared decision objects driven from the fix handler, not part of the follower state
        // machine itself.
        private var followCues: FollowCueProducers? = null

        // The last time *anything* was spoken via announce() — not scoped to the follow cues.
        // ProgressCueProducer.onFix reads this to decide whether to yield, and the doc on
        // ProgressCue is explicit that the progress beep/speech "must never talk over an alert";
        // scoping this to only the S6 cues would still let the progress cue interrupt an off-trail
        // or backtrack alert spoken the same fix. Long.MIN_VALUE is the same "nothing yet" sentinel
        // ProgressCueProducer's own elapsedSinceMs already guards against overflowing.
        private var lastSpokeAtMs: Long = Long.MIN_VALUE

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
                        // The same gate the resolver will filter by — asked for, not re-derived.
                        val gate = NearbyTrailResolver.gateM(loc.accuracy)
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
                outputManager = outputManager,
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

        // Now sourced from OutputRouter (single centralized live-region sink) instead of a
        // ViewModel-local MutableStateFlow — see issue #11.
        val announcement: StateFlow<LiveRegionAnnouncement> = outputRouter.liveRegion

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
                locationStale,
            ) { group, (aa, trailActive, guidance), stale ->
                group.copy(
                    relativeDeg = if (trailActive) guidance?.relativeDeg else group.relativeDeg,
                    alignmentActive = aa,
                    locationStale = stale,
                )
            }
        private val interactionGroup =
            combine(
                combine(
                    alignmentBearingDeg,
                    alignmentRelativeDeg,
                    navigationActive,
                    outputRouter.lastOutput,
                ) { ab, ar, na, lastOut ->
                    InteractionGroup(ab, ar, na, TrailRecordingState.Idle, NavMode.NoCollection, lastOut)
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
                    locationStale = bear.locationStale,
                    alignmentActive = bear.alignmentActive,
                    alignmentBearingDeg = inter.alignmentBearingDeg,
                    alignmentRelativeDeg = inter.alignmentRelativeDeg,
                    lastOutput = inter.lastOutput,
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
                targetingStateHolder.externalTarget.filterNotNull().collect { request ->
                    val applied = applyExternalTarget(request.request)
                    announce(
                        text = if (applied) request.successMessage else request.failureMessage,
                        kind = OutputKind.GPS_TARGET_RESULT,
                        category = OutputCategory.NAVIGATION,
                        origin = OutputOrigin.USER_REQUESTED,
                    )
                    targetingStateHolder.clear(request)
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
                    // One call folds the trusted course and the match. Ordering used to be a
                    // comment here; it is now inside the coordinator, where it cannot be skipped.
                    val outcome = guidanceCoordinator.onFix(sample)
                    recordMatch(sample, outcome)
                    announceTrailFollowerEvent(sample, outcome.smoothedHeading?.deg?.toFloat(), outcome.match)
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
            // Gate Follow/Extend on explicit selection — same as NearTrail flow.
            if (point is CollectionPoint.TrailEnd) selectTrail(point.trail.id)
            backgroundSession.setModeActive(GpsBackgroundMode.CollectionFollow, true)
            startLocationService()
        }

        /**
         * Apply a cross-screen "set as GPS target" request: ensure the collection holding it is
         * selected (so the explorer loads it), then wait for the matching point to appear and select
         * it. Bounded wait so a missing/slow load never hangs the bridge.
         *
         * @return whether a target was actually set. **The caller must report this.** Every exit
         *   here used to be a silent `return`, so a request that could not be honoured left the
         *   raising screen's "Navigating to …" standing as the only evidence — which for a blind
         *   user is indistinguishable from success (issue #78).
         */
        private suspend fun applyExternalTarget(request: ExternalTargetRequest): Boolean {
            if (!ensureCollectionSelectedFor(request)) return false
            val point =
                withTimeoutOrNull(EXTERNAL_TARGET_TIMEOUT_MS) {
                    collectionExplorer.state
                        .mapNotNull { st -> (st as? CollectionExplorerState.Active)?.points?.let(request::resolveIn) }
                        .first()
                }
            point?.let { selectCollectionPoint(it) }
            return point != null
        }

        /**
         * Select a collection that contains the requested target, if one is not already selected.
         *
         * The two kinds are found differently, and conflating them is what broke trail endpoints: a
         * waypoint's collections come from its own membership, while a trail endpoint belongs to no
         * collection at all — it is a track point — so the collection has to be found through the
         * *trail*.
         */
        private suspend fun ensureCollectionSelectedFor(request: ExternalTargetRequest): Boolean {
            val collections =
                when (request) {
                    is ExternalTargetRequest.Waypoint -> collectionRepo.collectionsForWaypoint(request.waypointId)
                    is ExternalTargetRequest.TrailEnd -> collectionRepo.collectionsForTrail(request.trailId)
                }
            if (collections.isEmpty()) return false
            val current = selectedCollectionHolder.selectedCollectionId.value
            if (current == null || collections.none { it.id == current }) {
                selectCollection(collections.first().id)
            }
            return true
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
                            formatSpokenDistance(
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
            announce(
                "Skipping ${event.skipped.displayName()}.$nextText",
                kind = OutputKind.COLLECTION_SKIP,
                category = OutputCategory.NAVIGATION,
                origin = OutputOrigin.INTERACTION_CONFIRMATION,
            )
        }

        fun clearCollectionVisited() {
            collectionExplorer.clearVisited()
        }

        fun setCollectionAutoAdvance(enabled: Boolean) {
            collectionExplorer.setAutoAdvance(enabled)
            backgroundSession.setModeActive(GpsBackgroundMode.CollectionFollow, enabled)
            if (enabled) startLocationService() else stopLocationServiceIfIdle()
        }

        fun setAbsoluteSilence(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepo.save(settings.value.copy(absoluteSilenceEnabled = enabled))
            }
        }

        /** A follow whose anchor forked (ADR 0002 §2), waiting on [followPrompt]'s answer. */
        private data class PendingFollow(
            val trailId: Long,
            val reversed: Boolean,
            val name: String,
            val isRecorded: Boolean,
            val recordedPoints: List<LatLng>,
            val points: List<TrailPoint>,
            val direction: TravelDirection,
            val options: List<AnchorOption>,
            val loc: LatLng?,
        )

        /**
         * Follow [trailId] from its start ([reversed] = false) or end ([reversed] = true), fetching the
         * ordered waypoints directly from the repository. Used both by the collection explore "follow from
         * end" path and by trail-follow requests raised on the Trails screen (via [TargetingStateHolder]),
         * so the caller need not have the trail selected first.
         *
         * Resolves the walk's anchor once, via [FollowArming], before anything starts (ADR 0002 §1). On
         * [ArmingResult.Fork] nothing starts at all — no session, no follower, no location service —
         * [followPrompt] is set instead, and [resolveFollowFork]/[cancelFollowFork] pick it back up.
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
                // Same predicate `WaypointRepositoryImpl.attach` uses for vertex-vs-annotation (ADR
                // 0001, S5b): a trail with track points of its own was walked, so its polyline is the
                // path. One with none is hand-built from waypoints, and its straight-line segments are
                // invented — the off-trail detector must not treat departing from them as evidence.
                val isRecorded = trailRepo.observeTrackPointCountForTrail(trailId).first() > 0L
                val name = trailRepo.getById(trailId)?.name ?: "trail"
                val points = ordered.map { TrailPoint(it.id, it.name, it.lat, it.lon, elevationM = it.elevM, kind = it.kind) }
                // RECORDED order, with the traversal direction carried separately — deliberately not
                // `ordered`, which is reversed in place. Reversing the point list would make
                // alongTrackM session-relative and stop two walks of the same trail being comparable
                // in the field logs. Arming reads this same list, so its anchor is in the same frame
                // `startFollow` below builds its polyline from.
                val recordedPoints = wps.map { LatLng(it.lat, it.lon) }
                val loc = location.value?.let { LatLng(it.lat, it.lon) }
                val accuracyM = location.value?.accuracy
                val direction = if (reversed) TravelDirection.Reverse else TravelDirection.Forward

                when (val armed = FollowArming.resolve(TrailPolyline(recordedPoints), direction, loc, accuracyM)) {
                    is ArmingResult.Fork -> {
                        pendingFollow =
                            PendingFollow(trailId, reversed, name, isRecorded, recordedPoints, points, direction, armed.options, loc)
                        _followPrompt.value =
                            FollowForkPrompt(armed.options.map { FollowForkOption(forkOptionLabel(it, direction)) })
                    }

                    is ArmingResult.Resolved ->
                        beginFollow(trailId, reversed, name, isRecorded, recordedPoints, points, direction, armed.anchor, loc)
                }
            }
        }

        /** Answers [followPrompt] with the option at [optionIndex] and resumes the follow it forked from. */
        fun resolveFollowFork(optionIndex: Int) {
            val pending = pendingFollow ?: return
            val option = pending.options.getOrNull(optionIndex) ?: return
            pendingFollow = null
            _followPrompt.value = null
            viewModelScope.launch {
                beginFollow(
                    pending.trailId,
                    pending.reversed,
                    pending.name,
                    pending.isRecorded,
                    pending.recordedPoints,
                    pending.points,
                    pending.direction,
                    option.anchor,
                    pending.loc,
                )
            }
        }

        /** Answers [followPrompt] by starting nothing at all. */
        fun cancelFollowFork() {
            pendingFollow = null
            _followPrompt.value = null
            announce(
                "Follow cancelled",
                kind = OutputKind.TRAIL_STOPPED,
                category = OutputCategory.NAVIGATION,
                origin = OutputOrigin.INTERACTION_CONFIRMATION,
            )
        }

        /**
         * A fork option's spoken label, built from its geometry (ADR 0002 §4) — `shared` never learns
         * about phrasing. Direction is named rather than left implicit because the two options can
         * point opposite ways along the same ground.
         */
        private fun forkOptionLabel(
            option: AnchorOption,
            direction: TravelDirection,
        ): String {
            val distance = formatSpokenDistance(option.remainingM, settings.value.units)
            return when {
                option.revisitsStartPoint -> "The loop — $distance, comes back past here"
                direction == TravelDirection.Forward -> "On to the end — $distance"
                else -> "Back to the start — $distance"
            }
        }

        /** Seeds and starts both consumers from a resolved [anchor] (ADR 0002 §3), then announces the start. */
        private suspend fun beginFollow(
            trailId: Long,
            reversed: Boolean,
            name: String,
            isRecorded: Boolean,
            recordedPoints: List<LatLng>,
            points: List<TrailPoint>,
            direction: TravelDirection,
            anchor: TrailPosition,
            loc: LatLng?,
        ) {
            _selectedTrailId.value = trailId
            enterFollowing(trailId)
            // Drop the outgoing follow's cues before installing the new session, not after the
            // (suspend) annotation query below. Nothing yields between here and the assignment
            // today — TrailAnnotationRepositoryImpl.forTrail never actually suspends — but that
            // is an implementation detail of one repository this call does not own, and the day
            // it (or an iOS implementation) does suspend, a fix for a *different* trail landing
            // mid-window must not be answered with a still-active previous trail's producers.
            followCues = null
            guidanceCoordinator.startFollow(
                points = recordedPoints,
                direction = direction,
                isRecorded = isRecorded,
                seedAlongM = anchor.alongTrackM,
            )
            // S6 follow cues (ADR 0001). Built from guidanceCoordinator's own polyline — just
            // constructed by startFollow above from these same points — rather than a second
            // TrailPolyline built here from `wps`, so an annotation's alongTrackM is guaranteed
            // comparable with TrailMatch.confirmedAlongM instead of merely expected to match a
            // separately-built geometry. The same polyline is what `followerIndexFor` below needs
            // too — see its doc for why an independently-built one would misread `cumulativeM`.
            val polyline = guidanceCoordinator.followSession!!.polyline
            val annotations =
                annotationRepo.forTrail(trailId).map { a ->
                    RouteAnnotation(
                        id = a.id,
                        name = a.waypoint.name,
                        alongTrackM = polyline.alongTrackFor(a.segmentIndex, a.offsetM),
                        // project(point, window = null) always finds a position on a non-empty
                        // polyline; the fallback exists only to satisfy the type system.
                        signedCrossTrackM =
                            polyline.project(LatLng(a.waypoint.lat, a.waypoint.lon))?.crossTrackM ?: 0.0,
                    )
                }
            followCues =
                FollowCueProducers(
                    progress = ProgressCueProducer(),
                    annotation = AnnotationCueProducer(annotations, direction),
                    matchState = MatchStateCueProducer(),
                )
            trailFollower.start(points, fromIndex = followerIndexFor(polyline, anchor, direction))
            refreshTrailGuidanceFromLatestLocation(resetOrdinaryThrottle = true)
            backgroundSession.setModeActive(GpsBackgroundMode.TrailFollow, true)
            startLocationService()
            announce(
                buildTrailStartAnnouncement(
                    "Following $name${if (reversed) " in reverse" else ""}",
                    points,
                    loc,
                ),
                kind = OutputKind.TRAIL_STARTED,
                category = OutputCategory.NAVIGATION,
                origin = OutputOrigin.INTERACTION_CONFIRMATION,
            )
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
                    val distLabel = formatSpokenDistance(dist, settings.value.units)
                    val relDir = guidance?.relativeDeg?.let { directionHint(it) }
                    if (relDir != null) {
                        append(" $distLabel, $relDir.")
                    } else {
                        append(" $distLabel. Trail direction unavailable until the trail is acquired.")
                    }
                }
            }
        }

        /**
         * Match this fix against the followed trail, and record what the matcher decided.
         *
         * Gated on the live follower being active so a matcher left over from a finished follow
         * cannot keep running. The Debug switch gates the **logging** only: since S5a wrong-way
         * detection reads the match, switching off a per-fix log line must not quietly switch off an
         * alert. TRAIL_MATCH is still the only per-fix kind in the log.
         *
         * With the switch off the record is not built at all, only the match — which is what the
         * switch was always documented to do and, until review caught it, did not: `onFix` used to
         * return the finished `AudioLogEntry`, so a 27-key map and three formatted strings were
         * assembled every fix and dropped on the next line.
         */
        private fun recordMatch(
            sample: LocationSample,
            outcome: FixOutcome,
        ) {
            val match = outcome.match ?: return
            val evidence = outcome.evidence ?: return
            if (trailFollower.state.value !is TrailFollowerState.Active) return
            if (!shadowMatchMonitor.enabled.value) return
            audioEventLog.append(trailMatchLogEntry(sample, match, evidence))
            shadowMatchMonitor.record(match)
        }

        fun stopFollowTrail() {
            shadowMatchMonitor.clear()
            trailFollower.stop()
            recordingMachine.stop()
            guidanceCoordinator.clear()
            followCues = null
            backgroundSession.setModeActive(GpsBackgroundMode.TrailFollow, false)
            stopLocationServiceIfIdle()
            announce(
                "Trail navigation stopped",
                kind = OutputKind.TRAIL_STOPPED,
                category = OutputCategory.NAVIGATION,
                origin = OutputOrigin.INTERACTION_CONFIRMATION,
            )
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
                // Previously set _announcement directly, bypassing TTS eligibility + the audio
                // log entirely — now routed through announce() like every other output (issue #11).
                announce(
                    "Waypoint marked: $name",
                    kind = OutputKind.WAYPOINT_MARKED,
                    category = OutputCategory.SYSTEM,
                    origin = OutputOrigin.INTERACTION_CONFIRMATION,
                )
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
                    announce(
                        "No GPS fix to copy.",
                        kind = OutputKind.COPY_COORDINATES,
                        category = OutputCategory.SYSTEM,
                        origin = OutputOrigin.INTERACTION_CONFIRMATION,
                    )
                    return
                }
            val text = "${"%.6f".format(loc.lat)}, ${"%.6f".format(loc.lon)}"
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("coordinates", text))
            announce(
                "Coordinates copied: $text",
                kind = OutputKind.COPY_COORDINATES,
                category = OutputCategory.SYSTEM,
                origin = OutputOrigin.INTERACTION_CONFIRMATION,
            )
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
            // `location` keeps its last value indefinitely, so this fix can be minutes old; the
            // coordinator declines to match a stale one rather than letting it set the tracker's
            // clock. Passing the real clock is what lets it tell.
            guidanceCoordinator.refreshFromLocation(
                trailFollower.state.value,
                sample,
                resetOrdinaryThrottle,
                nowMs = System.currentTimeMillis(),
            )
        }

        /**
         * Drive the TrailFollower with a fresh fix and speak the resulting event: an advance cue on
         * arrival, "Trail complete" on completion, or — when no event fires — the ordinary-guidance,
         * off-trail, and backtrack checks.
         */
        private fun announceTrailFollowerEvent(
            sample: LocationSample,
            smoothedBearingDeg: Float?,
            match: TrailMatch?,
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
                        // Feeds the endpoint completion radius, which tightens with good GPS and
                        // is capped so poor GPS never widens it.
                        accuracyM = sample.accuracy,
                        // Completion decided from the match rather than the index, so overshooting
                        // the end still finishes the trail, and neither route to it fires before
                        // the user has walked (ADR 0001, S5).
                        completion = guidanceCoordinator.completionEvidence(),
                    )
            ) {
                is TrailFollowerEvent.TrailComplete -> {
                    guidanceCoordinator.clear()
                    followCues = null
                    announce(
                        // Hedged when accuracy was too poor to assert arrival. Saying "trail
                        // complete" to someone who is not there is worse than saying nothing
                        // definite, and the user cannot check a map to resolve it.
                        if (event.hedged) "You should be at the end of the trail" else "Trail complete",
                        kind = OutputKind.TRAIL_COMPLETED,
                        category = OutputCategory.NAVIGATION,
                        origin = OutputOrigin.AUTOMATIC,
                        sample = sample,
                    )
                    trailFollower.stop()
                }

                null -> {
                    val followState = trailFollower.state.value
                    val guidance = guidanceCoordinator.computeGuidance(followState, sample)
                    announceOrdinaryTrailGuidance(followState, sample, guidance)
                    val offTrailAlertFired = announceOffTrail(followState, sample, guidance)
                    announceBacktrack(followState, sample, guidance, wrongVectorAlreadyEmitted = offTrailAlertFired)
                    // Last: match-state, then annotations, then progress — see announceFollowCues.
                    announceFollowCues(sample, match)
                }
            }
        }

        /**
         * Speak the S6 progress/annotation/match-state cues for this fix (ADR 0001, S6).
         *
         * Order is load-bearing, not cosmetic: match-state first, then annotations, then the
         * progress cue last, so [ProgressCueProducer.onFix]'s `lastSpokeAtMs` check sees whatever
         * the first two just said via [announce] (which stamps [lastSpokeAtMs] on every call). That
         * ordering is the entire mechanism by which the progress cue yields instead of talking over
         * an alert — call it in another order and the progress cue can win the race and speak first.
         *
         * A no-op when no follow is active ([followCues] null), when this fix produced no match at
         * all (only possible before the very first matched fix of a follow), or when this fix's
         * match is the literal same [TrailMatch] instance already processed on the previous fix.
         * That last case is not "stale data, process anyway" — [TrailGuidanceCoordinator.onFix]
         * skips matching a too-stale sample and returns the *previous* fix's match unchanged rather
         * than null, so re-running the producers on the identical object would double-count evidence
         * that is not new: [MatchStateCueProducer] would advance its sustain counter twice for one
         * physical fix, and [ProgressCueProducer] would see the same `nowMs` twice.
         */
        private fun announceFollowCues(
            sample: LocationSample,
            match: TrailMatch?,
        ) {
            val cues = followCues ?: return
            if (match == null || match === cues.previousMatch) return
            val units = settings.value.units
            val session = guidanceCoordinator.followSession
            // A hand-built route's polyline is invented (ADR 0001, S6/Task 10), and the matcher's
            // accept test measures cross-track against that same invented geometry — so a walker
            // legitimately on the path can still drive MatchStateCueProducer to Lost on a wide bend.
            // isRecorded defaults true, so a null session (should not happen while followCues is
            // non-null) speaks as before.
            val recorded = session?.isRecorded != false

            cues.matchState.onFix(match.state)?.let { transition ->
                // Gate only the words, not the producer: MatchStateCueProducer keeps running either
                // way, so `isLost` (below, and read at the progress-earcon call site) stays accurate
                // for the *sound*. The earcon's "I do not know where you are" character asserts only
                // that the app cannot place the walker along this route, which is true even here.
                // "Lost the trail" / "Back on the trail" assert something about the walker's actual
                // position that invented geometry cannot support — that is Task 10's whole point,
                // restated at a different microphone, so only the spoken half is suppressed.
                if (recorded) {
                    announce(
                        when (transition) {
                            MatchStateCue.Lost -> "Lost the trail"
                            MatchStateCue.Reacquired -> "Back on the trail"
                        },
                        kind = OutputKind.MATCH_STATE,
                        category = OutputCategory.NAVIGATION,
                        origin = OutputOrigin.AUTOMATIC,
                        sample = sample,
                    )
                }

                // The gap just closed: announce, late and hedged, any marks the along-track jump
                // shows were crossed while lost. alongTrackMBeforeThisFix is last fix's
                // confirmedAlongM, captured below before this fix could move it — and confirmedAlongM
                // is frozen for the whole Uncertain/Lost span, so that value is exactly where the gap
                // began, not merely "one fix ago". (Not separately gated on `recorded`: a hand-built
                // route's annotations are always vertices, never attached via annotationRepo, so
                // `cues.annotation`'s list is empty there and this is a no-op by construction.)
                val fromAlongTrackM = cues.alongTrackMBeforeThisFix
                val toAlongTrackM = match.confirmedAlongM
                if (transition == MatchStateCue.Reacquired && fromAlongTrackM != null && toAlongTrackM != null) {
                    cues.annotation
                        .onReacquired(fromAlongTrackM, toAlongTrackM, match.predictionErrorM, units)
                        .forEach { text ->
                            announce(
                                text,
                                kind = OutputKind.ANNOTATION_PASSED,
                                category = OutputCategory.NAVIGATION,
                                origin = OutputOrigin.AUTOMATIC,
                                sample = sample,
                            )
                        }
                }
            }

            val alongTrackM = match.confirmedAlongM
            if (alongTrackM != null && session != null) {
                cues.annotation.onFix(alongTrackM, session.speedMps, units).forEach { text ->
                    announce(
                        text,
                        kind = OutputKind.ANNOTATION_PASSED,
                        category = OutputCategory.NAVIGATION,
                        origin = OutputOrigin.AUTOMATIC,
                        sample = sample,
                    )
                }
            }

            // Called on every fix while a session is active, not just once alongTrackM is confirmed —
            // ProgressCueProducer.onFix tracks the speech cadence internally (lastSpeechAtMs), and
            // that cadence must keep running even before the match confirms a position, or the first
            // confirmed fix would read as "due" regardless of how long the follow had already run.
            if (session != null) {
                val cue =
                    cues.progress.onFix(
                        nowMs = sample.timestamp,
                        polyline = session.polyline,
                        alongTrackM = alongTrackM,
                        remainingM = alongTrackM?.let { session.remainingM(it) },
                        direction = session.direction,
                        units = units,
                        lastSpokeAtMs = lastSpokeAtMs,
                        // The IMMEDIATE state, not the sustained `isLost` below — confirmedAlongM
                        // freezes the instant the match stops being Matched, so the number is stale
                        // from the very first bad fix, not only once a loss is confirmed. See the
                        // param doc on ProgressCueProducer.onFix for why these are two thresholds.
                        matchLost = match.state != MatchState.Matched,
                    )
                cue.speech?.let { text ->
                    announce(
                        text,
                        kind = OutputKind.PROGRESS,
                        category = OutputCategory.NAVIGATION,
                        origin = OutputOrigin.AUTOMATIC,
                        sample = sample,
                    )
                }
            }

            cues.alongTrackMBeforeThisFix = alongTrackM
            cues.previousMatch = match
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
            val distLabel = formatSpokenDistance(decision.distanceToTargetM, settings.value.units)
            announce(
                "Checkpoint ${decision.checkpointN} of ${decision.total}. $distLabel, ${directionHint(decision.relativeDeg)}.",
                kind = OutputKind.ORDINARY_GUIDANCE,
                category = OutputCategory.NAVIGATION,
                origin = OutputOrigin.AUTOMATIC,
                sample = sample,
                guidance = guidance,
            )
        }

        // BUG-3: alert when consecutive GPS fixes show |relativeDeg| >= 60° (user is off trail).
        private fun announceOffTrail(
            followState: TrailFollowerState,
            sample: LocationSample,
            guidance: TrailGuidanceState?,
        ): Boolean {
            val eval =
                guidanceCoordinator.evaluateOffTrail(followState, sample, guidance) ?: return false
            viewModelScope.launch {
                audioEventLog.append(
                    AudioLogEntry(
                        timestampMs = sample.timestamp,
                        kind = AudioLogEntry.Kind.DETECTION_STATE,
                        trigger = "OffTrailCheck",
                        inputs =
                            "relativeDeg=${eval.relativeDeg?.let { "%.1f°".format(it) } ?: "null"}" +
                                ", smoothed=${guidanceCoordinator.courseIsSmoothed()}",
                        // suppressedByGrace + shadowDisposition, alongside `played` = disposition:
                        // `played` says what actually happened (and can never contradict `fired`);
                        // shadowDisposition says what the grace-free counterfactual would have
                        // decided. Without suppressedByGrace here a reader cannot tell *why* the two
                        // differ on a given line — a `shadow:would_fire` line can now occur outside
                        // grace too (whenever the shadow track simply leads the live one), so its
                        // presence alone no longer implies grace was the cause. ADR 0001, S6,
                        // consequence flagged in re-review, 2026-08-17.
                        outputs =
                            "consecutiveOffTrail=${eval.consecutiveCount}, sinceLastAlertMs=${eval.sinceLastAlertMs}" +
                                ", suppressedByGrace=${eval.suppressedByGrace}, shadowDisposition=${eval.shadowDisposition}",
                        played = eval.disposition,
                    ),
                )
            }
            if (!eval.fired) return false
            announce(
                "You may be off trail.",
                kind = OutputKind.OFF_TRAIL_ALERT,
                category = OutputCategory.PROXIMITY,
                origin = OutputOrigin.AUTOMATIC,
                sample = sample,
                guidance = guidance,
            )
            emitWrongVectorEarcon()
            return true
        }

        // BUG-9: alert when consecutive fixes show the user's position on the trail moving backwards.
        private fun announceBacktrack(
            followState: TrailFollowerState,
            sample: LocationSample,
            guidance: TrailGuidanceState?,
            wrongVectorAlreadyEmitted: Boolean,
        ) {
            val eval =
                guidanceCoordinator.evaluateBacktrack(followState, sample, guidance) ?: return
            viewModelScope.launch {
                audioEventLog.append(
                    AudioLogEntry(
                        timestampMs = sample.timestamp,
                        kind = AudioLogEntry.Kind.DETECTION_STATE,
                        trigger = "BacktrackCheck",
                        // alongTrack first, because it is what the rule is decided on. distToTarget
                        // stays for replay comparison against the rule this detector abandoned, but
                        // logging only that made the 16:07 switchback incident diagnosable solely by
                        // re-projecting the raw fixes offline.
                        inputs =
                            "alongTrack=${eval.alongTrackM.metresOrNull()}" +
                                ", prevAlongTrack=${eval.prevAlongTrackM.metresOrNull()}" +
                                ", matchState=${eval.matchState?.name ?: "null"}" +
                                ", noiseFloor=${eval.noiseFloorM.metresOrNull()}" +
                                ", distToTarget=${eval.distanceToTargetM.metresOrNull()}" +
                                ", prevDist=${eval.prevDistanceToTargetM.metresOrNull()}" +
                                ", smoothed=${guidanceCoordinator.courseIsSmoothed()}",
                        // See the matching comment in announceOffTrail: suppressedByGrace +
                        // shadowDisposition are what let a reader attribute a shadow:would_fire line
                        // (or its absence) to grace specifically, now that the shadow can lead the
                        // live track outside grace too.
                        outputs =
                            "consecutiveBacktrack=${eval.consecutiveCount}, sinceLastAlertMs=${eval.sinceLastAlertMs}" +
                                ", suppressedByGrace=${eval.suppressedByGrace}, shadowDisposition=${eval.shadowDisposition}",
                        played = eval.disposition,
                    ),
                )
            }
            if (!eval.fired) return
            announce(
                "You may be going the wrong way.",
                kind = OutputKind.BACKTRACK_ALERT,
                category = OutputCategory.PROXIMITY,
                origin = OutputOrigin.AUTOMATIC,
                sample = sample,
                guidance = guidance,
            )
            if (!wrongVectorAlreadyEmitted) emitWrongVectorEarcon()
        }

        /** Both alerts describe one wrong-vector condition, so one processed fix gets one earcon. */
        private fun emitWrongVectorEarcon() {
            viewModelScope.launch { scheduler.emitWrongVector() }
        }

        /**
         * Load the CollectionExplorer with an already-composed point list (standalones + trail ends,
         * derived reactively by [collectionNavPoints]), preserving the current auto-advance flag.
         * No-op when no collection is selected.
         */
        private fun reloadCollectionExplorer(points: List<CollectionPoint>) {
            if (selectedCollectionHolder.selectedCollectionId.value == null) return
            val currentAutoAdvance =
                (collectionExplorer.state.value as? CollectionExplorerState.Active)
                    ?.autoAdvance ?: false
            collectionExplorer.load(points, currentAutoAdvance)
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
                            } else if ((collectionExplorer.state.value as? CollectionExplorerState.Active)?.autoAdvance ==
                                false
                            ) {
                                // Issue #8: target stays selected on reach, not cleared — reflect that
                                // instead of implying the user must pick something before it works again.
                                append(" Still targeting it.")
                            } else {
                                append(" No more unvisited points.")
                            }
                        }
                    announce(
                        text,
                        kind = OutputKind.COLLECTION_POINT_REACHED,
                        category = OutputCategory.PROXIMITY,
                        origin = OutputOrigin.AUTOMATIC,
                    )
                }

                is CollectionExplorerEvent.NearTrailEnd -> {
                    // UI reacts to state change; no audio needed here.
                }

                is CollectionExplorerEvent.TargetSkipped -> {
                    // Skip is emitted only by the user action path, not by GPS updates.
                }

                is CollectionExplorerEvent.NearbyPoint -> {
                    // Suppress "nearby" chatter for the trail currently being recorded (issue #6)
                    // — the user is necessarily near its endpoint by definition of extending it
                    // from there, so this would otherwise announce on every fix while recording.
                    val recordingTrailId = (recordingMachine.state.value as? TrailRecordingState.Recording)?.trailId
                    val onRecordingTrail = (event.point as? CollectionPoint.TrailEnd)?.trail?.id == recordingTrailId
                    if (onRecordingTrail) return

                    val dist = formatSpokenDistance(event.distanceM, settings.value.units)
                    announce(
                        "Nearby: ${event.point.displayName()}, $dist",
                        kind = OutputKind.NEARBY_POINT,
                        category = OutputCategory.PROXIMITY,
                        origin = OutputOrigin.AUTOMATIC,
                    )
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

            // Recording is the one writer with no filter on the fix it is handed, so a single wild
            // GPS position becomes a permanent out-and-back spike in the geometry — the `dos` damage
            // (inflated length, a phantom segment the matcher can project onto) arriving from GPS
            // rather than from an attach. Projection does not save us: the spike loses on
            // cross-track, but `alongTrackM` is path length *through* it, so a 10 m real step
            // carries a kilometre-scale jump in the coordinate the window, backtrack and completion
            // are all denominated in.
            //
            // Both the plausibility test and the distance throttle live in [TrackPointGate], which
            // holds an anchor for each. They are not the same anchor: see its docs for what sharing
            // one costs, which is the guard evaporating while the user stands still.
            when (val decision = trackPointGate.consider(LatLng(sample.lat, sample.lon), sample.timestamp, sample.accuracy)) {
                is TrackPointDecision.TooClose -> return

                is TrackPointDecision.Impossible -> {
                    viewModelScope.launch {
                        audioEventLog.append(
                            AudioLogEntry(
                                timestampMs = sample.timestamp,
                                kind = AudioLogEntry.Kind.DETECTION_STATE,
                                trigger = "TrackPointRejected",
                                inputs = "jumpM=${"%.1f".format(decision.jumpM)}, elapsedMs=${decision.elapsedMs}" +
                                    ", accuracyM=${sample.accuracy?.let { "%.1f".format(it) } ?: "null"}",
                                outputs = "budgetM=${"%.1f".format(decision.budgetM)}" +
                                    ", impliedSpeedMps=${"%.1f".format(decision.impliedSpeedMps)}",
                                played = "not recorded",
                            ),
                        )
                    }
                    return
                }

                is TrackPointDecision.Record -> Unit
            }

            val trailId = recording.trailId
            val name = "Track ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"
            viewModelScope.launch {
                waypointRepo.createTrackPoint(trailId, name, sample.lat, sample.lon, sample.altitude)
                recordingMachine.addPoint()
                val count = (recordingMachine.state.value as? TrailRecordingState.Recording)?.pointCount ?: 0
                if (count % AUTO_RECORD_TTS_INTERVAL == 0) {
                    // Previously called spokenGuidancePlayer.speak() directly, bypassing the live
                    // region + audio log entirely — now routed through announce() (issue #11).
                    announce(
                        "$count track points recorded",
                        kind = OutputKind.TRACK_POINT_COUNT,
                        category = OutputCategory.RECORDING,
                        origin = OutputOrigin.AUTOMATIC,
                    )
                }
                // trailWaypoints updates automatically via observeWaypointsForTrail
            }
        }

        /**
         * Single entry point for GPS-screen output — builds an [OutputEvent] and hands it to
         * [OutputManager]. Non-suspending: [OutputManager.emit] doesn't suspend, so emission
         * order here exactly matches emission order into the router (see issue #11).
         */
        private fun announce(
            text: String,
            kind: OutputKind,
            category: OutputCategory,
            origin: OutputOrigin,
            sample: LocationSample? = null,
            guidance: TrailGuidanceState? = null,
            extraOverride: Map<String, Any?> = emptyMap(),
        ) {
            // Every announce() call counts as "something just spoke" for ProgressCueProducer's
            // yield check (ADR 0001, S6) — not only the S6 cues, so the progress beep/speech also
            // yields to an off-trail or backtrack alert spoken the same fix. Falls back to wall
            // clock for the many call sites with no `sample` (e.g. user-driven confirmations); the
            // progress cue only ever compares this against a GPS fix timestamp, and the two clocks
            // are the same domain (epoch millis).
            lastSpokeAtMs = sample?.timestamp ?: System.currentTimeMillis()
            val context =
                buildMap<String, Any?> {
                    putAll(extraOverride)
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
            outputManager.emit(
                OutputEvent(
                    kind = kind,
                    category = category,
                    origin = origin,
                    speech = text,
                    context = context,
                ),
            )
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
                // Previously set _announcement directly, bypassing TTS eligibility + the audio
                // log entirely — now routed through announce() like every other output (issue #11).
                announce(
                    "New trail: $name. Tap mark to add waypoints, or use auto-record.",
                    kind = OutputKind.TRAIL_CREATED,
                    category = OutputCategory.SYSTEM,
                    origin = OutputOrigin.INTERACTION_CONFIRMATION,
                )
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
            trackPointGate.start(
                from = location.value?.let { LatLng(it.lat, it.lon) },
                timestampMs = location.value?.timestamp,
                accuracyM = location.value?.accuracy,
            )
            backgroundSession.setModeActive(GpsBackgroundMode.AutoRecord, true)
            startLocationService() // keep process alive when screen is off
            announce(
                "Auto-recording started. Move to capture track points.",
                kind = OutputKind.AUTO_RECORD_STARTED,
                category = OutputCategory.RECORDING,
                origin = OutputOrigin.INTERACTION_CONFIRMATION,
            )
        }

        fun stopAutoRecord() {
            val count = (recordingMachine.state.value as? TrailRecordingState.Recording)?.pointCount ?: 0
            recordingMachine.stop()
            trackPointGate.stop()
            backgroundSession.setModeActive(GpsBackgroundMode.AutoRecord, false)
            stopLocationServiceIfIdle()
            announce(
                "Auto-recording stopped. $count points recorded.",
                kind = OutputKind.AUTO_RECORD_STOPPED,
                category = OutputCategory.RECORDING,
                origin = OutputOrigin.INTERACTION_CONFIRMATION,
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

                is GpsAction.SetCollectionAutoAdvance -> {
                    setCollectionAutoAdvance(action.enabled)
                }

                is GpsAction.SetAbsoluteSilence -> {
                    setAbsoluteSilence(action.enabled)
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

        override fun onCleared() {
            super.onCleared()
            if (_navigationActive.value) backgroundSession.stopBeaconNavigation()
        }

        companion object {
            /**
             * How long to wait for the explorer to produce a cross-screen target's point.
             *
             * Bounded so a missing or slow collection load cannot hang the bridge. Expiring is a
             * real answer — the request is reported as not applied, and the raising screen says so.
             */
            private const val EXTERNAL_TARGET_TIMEOUT_MS = 2_000L

            private const val AUTO_RECORD_DISTANCE_M = 10.0
            private const val AUTO_RECORD_TTS_INTERVAL = 5

            // Minimum speed (m/s) before GPS course-over-ground is trusted for direction-aware selection.
            private const val MIN_TRAVEL_HEADING_SPEED_MPS = 1.0

            // Padding added to the near-trail gate when sizing the mid-trail snapshot bbox, so the
            // query window always covers whatever distance NearbyTrailResolver may admit.
            private const val NEARBY_TRAIL_BBOX_MARGIN_M = 30.0
        }
    }

/** A metre value for the audio event log, with absence spelled out rather than omitted. */
private fun Double?.metresOrNull(): String = this?.let { "%.1fm".format(it) } ?: "null"
