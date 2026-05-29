package com.boldexplorer.ui.gps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.audio.AudioCuePlayer
import com.boldexplorer.compass.SensorCompassProvider
import com.boldexplorer.location.FusedLocationProviderImpl
import com.boldexplorer.location.LocationForegroundService
import com.boldexplorer.shared.audio.AudioCueScheduler
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.deltaAngle
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.geo.initialBearingDeg
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.navigation.TrailFollower
import com.boldexplorer.shared.navigation.TrailFollowerEvent
import com.boldexplorer.shared.navigation.TrailFollowerState
import com.boldexplorer.shared.navigation.TrailPoint
import com.boldexplorer.shared.repository.CollectionRepository
import com.boldexplorer.shared.repository.SettingsRepository
import com.boldexplorer.shared.repository.TrailRepository
import com.boldexplorer.shared.repository.WaypointRepository
import com.boldexplorer.shared.settings.AppSettings
import com.boldexplorer.shared.settings.Units
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class GpsScope { WAYPOINT, TRAIL, COLLECTION }

private data class TargetInputs(
    val scope: GpsScope,
    val selectedWaypointId: Long?,
    val waypoints: List<Waypoint>,
    val trailWaypoints: List<Waypoint>,
    val collectionWaypoints: List<Waypoint>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GpsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationProvider: FusedLocationProviderImpl,
    private val compassProvider: SensorCompassProvider,
    private val waypointRepo: WaypointRepository,
    private val trailRepo: TrailRepository,
    private val collectionRepo: CollectionRepository,
    private val audioCuePlayer: AudioCuePlayer,
    private val audioScheduler: AudioCueScheduler,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    // ── Settings ──────────────────────────────────────────────────────────────────

    val settings: StateFlow<AppSettings> = settingsRepo.observeSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), AppSettings())

    val audioCuesEnabled: StateFlow<Boolean> = settings
        .map { it.audioCuesEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings().audioCuesEnabled)

    // ── Location ──────────────────────────────────────────────────────────────────

    val location = locationProvider.locationFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val headingDeg: StateFlow<Double?> = combine(
        compassProvider.headingFlow, settings,
    ) { reading, s ->
        when (s.compassMode) {
            com.boldexplorer.shared.settings.CompassMode.TRUE -> reading.trueNorth ?: reading.magnetic
            com.boldexplorer.shared.settings.CompassMode.MAGNETIC -> reading.magnetic
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val compassModeLabel: StateFlow<String> = combine(
        compassProvider.headingFlow, settings,
    ) { reading, s ->
        when (s.compassMode) {
            com.boldexplorer.shared.settings.CompassMode.TRUE ->
                if (reading.trueNorth != null) "True North" else "True North (no fix)"
            com.boldexplorer.shared.settings.CompassMode.MAGNETIC -> "Magnetic"
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), "Magnetic")

    val accuracyM: StateFlow<Double?> = location
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

    // ── Reactive data ─────────────────────────────────────────────────────────────

    val waypoints: StateFlow<List<Waypoint>> = waypointRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val trails: StateFlow<List<Trail>> = trailRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val collections: StateFlow<List<com.boldexplorer.shared.model.Collection>> = collectionRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    // Automatically tracks the selected trail's waypoints. Re-emits on any write
    // to the waypoint or trail_waypoint tables for that trail.
    val trailWaypoints: StateFlow<List<Waypoint>> = _selectedTrailId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else trailRepo.observeWaypointsForTrail(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val collectionWaypoints: StateFlow<List<Waypoint>> = _selectedCollectionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else collectionRepo.observeWaypointsForCollection(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    // ── Trail follower ────────────────────────────────────────────────────────────

    private val trailFollower = TrailFollower()
    val trailFollowState: StateFlow<TrailFollowerState> = trailFollower.state

    // ── Derived navigation values ─────────────────────────────────────────────────

    private val targetInputs = combine(
        _scope, _selectedWaypointId, waypoints, trailWaypoints, collectionWaypoints,
    ) { sc, wpId, wps, trailWps, collectionWps ->
        TargetInputs(sc, wpId, wps, trailWps, collectionWps)
    }

    val targetCoord: StateFlow<LatLng?> = combine(
        targetInputs, trailFollowState,
    ) { inputs, fs ->
        when (inputs.scope) {
            GpsScope.WAYPOINT -> inputs.waypoints.find { it.id == inputs.selectedWaypointId }?.let { LatLng(it.lat, it.lon) }
            GpsScope.TRAIL -> (fs as? TrailFollowerState.Active)
                ?.waypoints?.getOrNull(fs.currentIndex)?.let { LatLng(it.lat, it.lon) }
                ?: inputs.trailWaypoints.firstOrNull()?.let { LatLng(it.lat, it.lon) }
            GpsScope.COLLECTION -> inputs.collectionWaypoints.firstOrNull()?.let { LatLng(it.lat, it.lon) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val targetName: StateFlow<String?> = combine(
        targetInputs, trailFollowState,
    ) { inputs, fs ->
        when (inputs.scope) {
            GpsScope.WAYPOINT -> inputs.waypoints.find { it.id == inputs.selectedWaypointId }?.name
            GpsScope.TRAIL -> (fs as? TrailFollowerState.Active)
                ?.waypoints?.getOrNull(fs.currentIndex)?.name
                ?: inputs.trailWaypoints.firstOrNull()?.name
            GpsScope.COLLECTION -> inputs.collectionWaypoints.firstOrNull()?.name
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val bearingDeg: StateFlow<Double?> = combine(location, targetCoord) { loc, target ->
        if (loc == null || target == null) null
        else initialBearingDeg(LatLng(loc.lat, loc.lon), target)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val distanceM: StateFlow<Double?> = combine(location, targetCoord) { loc, target ->
        if (loc == null || target == null) null
        else haversineDistanceMeters(LatLng(loc.lat, loc.lon), target)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val relativeDeg: StateFlow<Double?> = combine(headingDeg, bearingDeg) { heading, bearing ->
        if (heading == null || bearing == null) null
        else deltaAngle(heading, bearing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    // ── Alignment ─────────────────────────────────────────────────────────────────

    private val _alignmentActive = MutableStateFlow(false)
    val alignmentActive: StateFlow<Boolean> = _alignmentActive.asStateFlow()

    private val _alignmentBearingDeg = MutableStateFlow<Double?>(null)
    val alignmentBearingDeg: StateFlow<Double?> = _alignmentBearingDeg.asStateFlow()

    // When alignment is active, audio uses the alignment bearing; otherwise the waypoint bearing.
    val audioRelativeDeg: StateFlow<Double?> = combine(
        headingDeg, _alignmentBearingDeg, _alignmentActive, relativeDeg,
    ) { heading, alignBearing, alignActive, wpRelative ->
        if (alignActive && heading != null && alignBearing != null)
            deltaAngle(heading, alignBearing)
        else
            wpRelative
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Signed delta between current heading and alignment target.
    val alignmentRelativeDeg: StateFlow<Double?> = combine(
        headingDeg, _alignmentBearingDeg, _alignmentActive,
    ) { heading, alignBearing, alignActive ->
        if (alignActive && heading != null && alignBearing != null)
            deltaAngle(heading, alignBearing)
        else
            null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    // ── TalkBack announcements ────────────────────────────────────────────────────

    private val _announcement = MutableStateFlow("")
    val announcement: StateFlow<String> = _announcement.asStateFlow()

    // ── Navigation active ─────────────────────────────────────────────────────────

    private val _navigationActive = MutableStateFlow(false)
    val navigationActive: StateFlow<Boolean> = _navigationActive.asStateFlow()

    // True when audio was started solely to serve alignment (so we can stop it on stopAlignment).
    private val _audioStartedForAlignment = MutableStateFlow(false)

    // ── Trail recording ───────────────────────────────────────────────────────────

    private val _autoRecording = MutableStateFlow(false)
    val autoRecording: StateFlow<Boolean> = _autoRecording.asStateFlow()

    private val _autoRecordCount = MutableStateFlow(0)
    val autoRecordCount: StateFlow<Int> = _autoRecordCount.asStateFlow()

    @Volatile private var _lastAutoRecordLoc: LatLng? = null

    // ── Init ──────────────────────────────────────────────────────────────────────

    init {
        // Feed GPS fixes to the compass so it can compute magnetic declination.
        viewModelScope.launch {
            location.filterNotNull().collect { s ->
                compassProvider.setLocation(s.lat, s.lon, s.altitude ?: 0.0)
            }
        }
        // Drive TrailFollower on every GPS fix; surface events as announcements + audio.
        // Also handle auto-recording.
        viewModelScope.launch {
            location.filterNotNull().collect { sample ->
                when (val event = trailFollower.onLocationUpdate(LatLng(sample.lat, sample.lon))) {
                    is TrailFollowerEvent.WaypointReached -> {
                        val oneBasedN = event.index + 1
                        val heading = headingDeg.value
                        val relDir = if (heading != null) directionHint(deltaAngle(heading, event.absoluteBearingDeg)) else null
                        val distLabel = formatDistanceM(event.distanceToNextM, settings.value.units)
                        val text = if (event.kind == Waypoint.KIND_TRACK_POINT) {
                            buildString {
                                append("Checkpoint $oneBasedN of ${event.total}.")
                                if (relDir != null) append(" $distLabel, $relDir.")
                            }
                        } else {
                            buildString {
                                append("${event.name}.")
                                if (relDir != null) append(" $distLabel, $relDir.")
                            }
                        }
                        _announcement.value = text
                        launch { audioScheduler.emitWaypointApproach(text, audioCuesEnabled.value) }
                    }
                    is TrailFollowerEvent.TrailComplete -> {
                        _announcement.value = "Trail complete"
                        launch { audioScheduler.emitTrailComplete(audioCuesEnabled.value) }
                        trailFollower.stop()
                    }
                    null -> Unit
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
                            val id = waypointRepo.create(name, sample.lat, sample.lon, sample.altitude, null, Waypoint.KIND_TRACK_POINT)
                            waypointRepo.attach(trailId, id)
                            _autoRecordCount.value++
                            // trailWaypoints updates automatically via observeWaypointsForTrail
                        }
                    }
                }
            }
        }
    }

    // ── Scope + selection ─────────────────────────────────────────────────────────

    fun setScope(scope: GpsScope) { _scope.value = scope }

    fun selectWaypoint(id: Long) { _selectedWaypointId.value = id }

    fun selectTrail(id: Long) { _selectedTrailId.value = id }

    fun selectCollection(id: Long) { _selectedCollectionId.value = id }

    // ── Trail following ───────────────────────────────────────────────────────────

    fun startFollowTrail() {
        val wps = trailWaypoints.value
        if (wps.isEmpty()) return
        val points = wps.map { TrailPoint(it.id, it.name, it.lat, it.lon, it.kind) }
        val loc = location.value?.let { LatLng(it.lat, it.lon) }
        if (loc != null) trailFollower.startNearest(points, loc) else trailFollower.start(points)
        _announcement.value = buildTrailStartAnnouncement("Trail started", points, loc)
    }

    fun startFollowTrailReversed() {
        val wps = trailWaypoints.value
        if (wps.isEmpty()) return
        val points = wps.reversed().map { TrailPoint(it.id, it.name, it.lat, it.lon, it.kind) }
        val loc = location.value?.let { LatLng(it.lat, it.lon) }
        if (loc != null) trailFollower.startNearest(points, loc) else trailFollower.start(points)
        _announcement.value = buildTrailStartAnnouncement("Trail started in reverse", points, loc)
    }

    private fun buildTrailStartAnnouncement(prefix: String, points: List<TrailPoint>, loc: LatLng?): String {
        val active = trailFollower.state.value as? TrailFollowerState.Active
        val firstWp = active?.waypoints?.getOrNull(active.currentIndex)
        val total = active?.waypoints?.size ?: points.size
        val heading = headingDeg.value
        return buildString {
            append("$prefix. Checkpoint 1 of $total.")
            if (firstWp != null && loc != null) {
                val dist = haversineDistanceMeters(loc, LatLng(firstWp.lat, firstWp.lon))
                val bearing = initialBearingDeg(loc, LatLng(firstWp.lat, firstWp.lon))
                val distLabel = formatDistanceM(dist, settings.value.units)
                val relDir = if (heading != null) directionHint(deltaAngle(heading, bearing)) else null
                if (relDir != null) append(" $distLabel, $relDir.")
            }
        }
    }

    fun stopFollowTrail() {
        trailFollower.stop()
        _announcement.value = "Trail navigation stopped"
    }

    // ── Audio navigation ──────────────────────────────────────────────────────────

    fun startNavigation() {
        if (_navigationActive.value) return
        _navigationActive.value = true
        audioCuePlayer.start(
            accuracyM = accuracyM,
            relativeDeg = audioRelativeDeg,
            alignmentActive = _alignmentActive,
            audioCuesEnabled = audioCuesEnabled,
        )
        _audioStartedForAlignment.value = false
        startLocationService()
    }

    fun stopNavigation() {
        if (!_navigationActive.value) return
        _navigationActive.value = false
        if (!_alignmentActive.value) {
            audioCuePlayer.stop()
        }
        stopLocationService()
    }

    // ── Alignment ─────────────────────────────────────────────────────────────────

    fun startAlignment() {
        _alignmentBearingDeg.value = headingDeg.value ?: _alignmentBearingDeg.value ?: 0.0
        _alignmentActive.value = true
        if (!_navigationActive.value) {
            audioCuePlayer.start(
                accuracyM = accuracyM,
                relativeDeg = audioRelativeDeg,
                alignmentActive = _alignmentActive,
                audioCuesEnabled = audioCuesEnabled,
            )
            _audioStartedForAlignment.value = true
        } else {
            _audioStartedForAlignment.value = false
        }
    }

    fun stopAlignment() {
        _alignmentActive.value = false
        if (!_navigationActive.value) {
            audioCuePlayer.stop()
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

    // ── Waypoint marking ──────────────────────────────────────────────────────────

    fun markWaypoint() {
        val loc = location.value ?: return
        val name = "WP ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"
        viewModelScope.launch {
            val waypointId = waypointRepo.create(name, loc.lat, loc.lon, loc.altitude, null)
            if (_scope.value == GpsScope.TRAIL) {
                _selectedTrailId.value?.let { trailId ->
                    waypointRepo.attach(trailId, waypointId)
                }
            }
            _announcement.value = "Waypoint marked: $name"
            // waypoints StateFlow updates automatically via observeAll()
        }
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

    // ── Trail recording ───────────────────────────────────────────────────────────

    fun recordNewTrail() {
        viewModelScope.launch {
            val name = "Trail ${SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())}"
            val id = trailRepo.create(name, null)
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
        _announcement.value = "Auto-recording started. Move to capture track points."
    }

    fun stopAutoRecord() {
        _autoRecording.value = false
        _lastAutoRecordLoc = null
        _announcement.value = "Auto-recording stopped. ${_autoRecordCount.value} points recorded."
    }

    private fun directionHint(relativeDeg: Double): String = when {
        kotlin.math.abs(relativeDeg) < 20  -> "straight ahead"
        relativeDeg in   20.0.. 60.0       -> "slight right"
        relativeDeg in   60.0..120.0       -> "right"
        relativeDeg in  120.0..180.0       -> "sharp right"
        relativeDeg in  -60.0..-20.0       -> "slight left"
        relativeDeg in -120.0..-60.0       -> "left"
        else                               -> "sharp left"
    }

    private fun formatDistanceM(meters: Double, units: Units): String =
        if (units == Units.METRIC) {
            if (meters < 1000) "${meters.roundToInt()} meters"
            else "${"%.1f".format(meters / 1000)} km"
        } else {
            val feet = meters * 3.28084
            if (feet < 1000) "${feet.roundToInt()} feet"
            else "${"%.1f".format(feet / 5280)} miles"
        }

    override fun onCleared() {
        super.onCleared()
        if (_navigationActive.value) audioCuePlayer.stop()
    }

    companion object {
        private const val AUTO_RECORD_DISTANCE_M = 10.0
    }
}
