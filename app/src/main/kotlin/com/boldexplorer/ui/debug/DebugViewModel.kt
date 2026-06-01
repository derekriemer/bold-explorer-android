package com.boldexplorer.ui.debug

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.audio.AudioEngine
import com.boldexplorer.compass.SensorCompassProvider
import com.boldexplorer.gpx.GpxExporter
import com.boldexplorer.gpx.GpxFileWriter
import com.boldexplorer.location.FusedLocationProviderImpl
import com.boldexplorer.shared.audio.AudioCueScheduler
import com.boldexplorer.shared.model.HeadingReading
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.repository.WaypointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationProvider: FusedLocationProviderImpl,
    private val compassProvider: SensorCompassProvider,
    private val waypointRepo: WaypointRepository,
    private val audioEngine: AudioEngine,
    private val scheduler: AudioCueScheduler,
) : ViewModel() {

    val location: StateFlow<LocationSample?> = locationProvider.locationFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val heading: StateFlow<HeadingReading?> = compassProvider.headingFlow
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    val accuracyBeaconEnabled: StateFlow<Boolean> = scheduler.accuracyBeaconEnabled

    fun setAccuracyBeaconEnabled(enabled: Boolean) {
        scheduler.accuracyBeaconEnabled.value = enabled
    }

    fun testAccuracyBeacon() {
        audioEngine.playAccuracyBeacon(location.value?.accuracy ?: 15.0)
    }

    fun exportAllWaypoints() {
        viewModelScope.launch {
            val waypoints = waypointRepo.getAll()
            if (waypoints.isEmpty()) {
                _exportStatus.value = "No waypoints to export"
                return@launch
            }
            val gpx = GpxExporter.exportWaypoints(waypoints)
            val filename = "bold_explorer_waypoints.gpx"
            GpxFileWriter.writeToDownloads(context, filename, gpx)
                .onSuccess {
                _exportStatus.value = "Exported ${waypoints.size} waypoints → Downloads/$filename"
                }
                .onFailure { e ->
                    _exportStatus.value = "Export failed: ${e.message}"
                }
        }
    }
}
