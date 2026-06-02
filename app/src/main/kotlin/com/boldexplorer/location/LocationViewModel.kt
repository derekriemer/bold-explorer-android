package com.boldexplorer.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.compass.SensorCompassProvider
import com.boldexplorer.shared.location.LocationProvider
import com.boldexplorer.shared.model.HeadingReading
import com.boldexplorer.shared.model.LocationSample
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationProvider: LocationProvider,
    private val compassProvider: SensorCompassProvider,
) : ViewModel() {

    /** Latest GPS fix passing the accuracy/interval/distance gates. */
    val location: StateFlow<LocationSample?> = locationProvider.locationFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /** Latest compass reading (magnetic + optional true-north). */
    val heading: StateFlow<HeadingReading?> = compassProvider.headingFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /**
     * GPS accuracy in metres — null until first fix.
     * Consumed by AudioCueScheduler for the accuracy-beacon frequency.
     */
    val accuracyM: StateFlow<Double?> = location
        .map { it?.accuracy }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /**
     * True-north heading in degrees [0, 360), falling back to magnetic if no GPS fix yet.
     * Null until the first sensor reading.
     */
    val headingDeg: StateFlow<Double?> = heading
        .map { it?.trueNorth ?: it?.magnetic }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    init {
        // Feed GPS coordinates to the compass so it can compute magnetic declination.
        viewModelScope.launch {
            location.filterNotNull().collect { sample ->
                compassProvider.setLocation(
                    lat = sample.lat,
                    lon = sample.lon,
                    altM = sample.altitude ?: 0.0,
                )
            }
        }
    }

    // ── Permissions ──────────────────────────────────────────────────────────────

    /** True if foreground location permission is granted. */
    fun hasForegroundLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** True if background location permission is granted (required for screen-off tracking). */
    fun hasBackgroundLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ── Foreground service control ────────────────────────────────────────────────

    /**
     * Start background GPS tracking via [LocationForegroundService].
     * Call this when the user begins navigation and may turn the screen off.
     * Requires both foreground and background location permissions to be granted first.
     */
    fun startLocationService() {
        val intent = Intent(context, LocationForegroundService::class.java).apply {
            action = LocationForegroundService.ACTION_START
        }
        context.startForegroundService(intent)
    }

    /**
     * Stop background GPS tracking and remove the persistent notification.
     */
    fun stopLocationService() {
        val intent = Intent(context, LocationForegroundService::class.java).apply {
            action = LocationForegroundService.ACTION_STOP
        }
        context.startService(intent)
    }
}
