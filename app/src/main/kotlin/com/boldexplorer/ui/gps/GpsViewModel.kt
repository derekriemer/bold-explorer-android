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
import com.boldexplorer.shared.repository.TrailRepository
import com.boldexplorer.shared.repository.WaypointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class GpsScope { WAYPOINT, TRAIL, COLLECTION }

@HiltViewModel
class GpsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationProvider: FusedLocationProviderImpl,
    private val compassProvider: SensorCompassProvider,
    private val waypointRepo: WaypointRepository,
    private val trailRepo: TrailRepository,
    private val audioCuePlayer: AudioCuePlayer,
    private val audioScheduler: AudioCueScheduler,
) : ViewModel() {

    // ── Location ──────────────────────────────────────────────────────────────────

    val location = locationProvider.locationFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val headingDeg: StateFlow<Double?> = compassProvider.headingFlow
        .map { it.trueNorth ?: it.magnetic }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val accuracyM: StateFlow<Double?> = location
        .map { it?.accuracy }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    // ── Scope + selection ─────────────────────────────────────────────────────────

    private val _scope = MutableStateFlow(GpsScope.WAYPOINT)
    val scope: StateFlow<GpsScope> = _scope.asStateFlow()

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()

    private val _trails = MutableStateFlow<List<Trail>>(emptyList())
    val trails: StateFlow<List<Trail>> = _trails.asStateFlow()

    private val _selectedWaypointId = MutableStateFlow<Long?>(null)
    val selectedWaypointId: StateFlow<Long?> = _selectedWaypointId.asStateFlow()

    private val _selectedTrailId = MutableStateFlow<Long?>(null)
    val selectedTrailId: StateFlow<Long?> = _selectedTrailId.asStateFlow()

    private val _trailWaypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val trailWaypoints: StateFlow<List<Waypoint>> = _trailWaypoints.asStateFlow()

    // ── Trail follower ────────────────────────────────────────────────────────────

    private val trailFollower = TrailFollower()
    val trailFollowState: StateFlow<TrailFollowerState> = trailFollower.state

    // ── Derived navigation values ─────────────────────────────────────────────────

    val targetCoord: StateFlow<LatLng?> = combine(
        _scope, _selectedWaypointId, _waypoints, trailFollowState,
    ) { sc, wpId, wps, fs ->
        when (sc) {
            GpsScope.WAYPOINT -> wps.find { it.id == wpId }?.let { LatLng(it.lat, it.lon) }
            GpsScope.TRAIL -> (fs as? TrailFollowerState.Active)
                ?.waypoints?.getOrNull(fs.currentIndex)?.let { LatLng(it.lat, it.lon) }
            GpsScope.COLLECTION -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val targetName: StateFlow<String?> = combine(
        _scope, _selectedWaypointId, _waypoints, trailFollowState,
    ) { sc, wpId, wps, fs ->
        when (sc) {
            GpsScope.WAYPOINT -> wps.find { it.id == wpId }?.name
            GpsScope.TRAIL -> (fs as? TrailFollowerState.Active)
                ?.waypoints?.getOrNull(fs.currentIndex)?.name
            GpsScope.COLLECTION -> null
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

    // Signed delta between current heading and alignment target: positive = target right of heading.
    // Null when either heading or alignment bearing is unavailable.
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

    // ── Init ──────────────────────────────────────────────────────────────────────

    init {
        // Feed GPS fixes to the compass so it can compute magnetic declination.
        viewModelScope.launch {
            location.filterNotNull().collect { s ->
                compassProvider.setLocation(s.lat, s.lon, s.altitude ?: 0.0)
            }
        }
        // Drive TrailFollower on every GPS fix; surface events as announcements + audio.
        viewModelScope.launch {
            location.filterNotNull().collect { sample ->
                when (val event = trailFollower.onLocationUpdate(LatLng(sample.lat, sample.lon))) {
                    is TrailFollowerEvent.WaypointReached -> {
                        _announcement.value = "Next waypoint: ${event.name}"
                        launch { audioScheduler.emitWaypointApproach(event.name) }
                    }
                    is TrailFollowerEvent.TrailComplete -> {
                        _announcement.value = "Trail complete"
                        launch { audioScheduler.emitTrailComplete() }
                        trailFollower.stop()
                    }
                    null -> Unit
                }
            }
        }
        viewModelScope.launch { refresh() }
    }

    // ── Data loading ──────────────────────────────────────────────────────────────

    suspend fun refresh() {
        _waypoints.value = waypointRepo.getAll()
        _trails.value = trailRepo.getAll()
    }

    private fun loadTrailWaypoints(trailId: Long) {
        viewModelScope.launch {
            _trailWaypoints.value = trailRepo.waypointsForTrail(trailId)
        }
    }

    // ── Scope + selection ─────────────────────────────────────────────────────────

    fun setScope(scope: GpsScope) { _scope.value = scope }

    fun selectWaypoint(id: Long) { _selectedWaypointId.value = id }

    fun selectTrail(id: Long) {
        _selectedTrailId.value = id
        loadTrailWaypoints(id)
    }

    // ── Trail following ───────────────────────────────────────────────────────────

    fun startFollowTrail() {
        val wps = _trailWaypoints.value
        if (wps.isEmpty()) return
        val points = wps.map { TrailPoint(it.id, it.name, it.lat, it.lon) }
        trailFollower.start(points)
        _announcement.value = "Trail navigation started. First waypoint: ${points.first().name}"
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
        )
        startLocationService()
    }

    fun stopNavigation() {
        if (!_navigationActive.value) return
        _navigationActive.value = false
        audioCuePlayer.stop()
        stopLocationService()
    }

    // ── Alignment ─────────────────────────────────────────────────────────────────

    fun startAlignment() {
        _alignmentBearingDeg.value = headingDeg.value ?: _alignmentBearingDeg.value ?: 0.0
        _alignmentActive.value = true
        // Start audio pings immediately, independent of full navigation.
        if (!_navigationActive.value) {
            audioCuePlayer.start(
                accuracyM = accuracyM,
                relativeDeg = audioRelativeDeg,
                alignmentActive = _alignmentActive,
            )
            _audioStartedForAlignment.value = true
        }
    }

    fun stopAlignment() {
        _alignmentActive.value = false
        if (_audioStartedForAlignment.value) {
            audioCuePlayer.stop()
            _audioStartedForAlignment.value = false
        }
    }

    /** Snap the alignment bearing to the current compass heading. */
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
            waypointRepo.create(name, loc.lat, loc.lon, loc.altitude, null)
            _waypoints.value = waypointRepo.getAll()
            _announcement.value = "Waypoint marked: $name"
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────────

    fun hasForegroundLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
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

    override fun onCleared() {
        super.onCleared()
        if (_navigationActive.value) audioCuePlayer.stop()
    }
}
