package com.boldexplorer.ui.debug

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.audio.AudioEngine
import com.boldexplorer.compass.SensorCompassProvider
import com.boldexplorer.gpx.GpxExporter
import com.boldexplorer.location.FusedLocationProviderImpl
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
) : ViewModel() {

    val location: StateFlow<LocationSample?> = locationProvider.locationFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val heading: StateFlow<HeadingReading?> = compassProvider.headingFlow
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

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
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, filename)
                        put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("could not create file in Downloads")
                    resolver.openOutputStream(uri)!!.use { it.write(gpx.toByteArray()) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    java.io.File(dir, filename).writeText(gpx)
                }
                _exportStatus.value = "Exported ${waypoints.size} waypoints → Downloads/$filename"
            }.onFailure { e ->
                _exportStatus.value = "Export failed: ${e.message}"
            }
        }
    }
}
