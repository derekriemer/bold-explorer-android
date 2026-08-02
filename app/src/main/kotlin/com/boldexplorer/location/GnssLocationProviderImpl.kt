package com.boldexplorer.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.boldexplorer.audio.AudioEventLog
import com.boldexplorer.audio.AudioLogEntry
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.location.LocationProvider
import com.boldexplorer.shared.model.LocationSample
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Location provider that reads directly from the GNSS chip via [LocationManager.GPS_PROVIDER],
 * bypassing Fused Location's sensor-fusion layer. Produces better horizontal accuracy outdoors
 * at the cost of no Wi-Fi/cell fallback indoors.
 *
 * Gate logic mirrors [FusedLocationProviderImpl]: accuracy gate → interval gate → distance gate.
 */
@Singleton
class GnssLocationProviderImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val audioEventLog: AudioEventLog,
    ) : LocationProvider {
        private val locationManager = context.getSystemService(LocationManager::class.java)
        private val _backgroundMode = MutableStateFlow(false)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Telemetry for every raw fix (accepted or accuracy-gate-dropped) — see RawFixEvent.
        private val _lastRawFix = MutableStateFlow<RawFixEvent?>(null)
        val lastRawFix: StateFlow<RawFixEvent?> = _lastRawFix.asStateFlow()
        private var consecutiveDiscards = 0

        @SuppressLint("MissingPermission")
        override val locationFlow: SharedFlow<LocationSample> =
            callbackFlow {
                while (!hasLocationPermission()) {
                    delay(PERMISSION_RECHECK_MS)
                }

                val listener = LocationListener { location -> trySend(location) }

                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_INTERVAL_MS,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
                awaitClose { locationManager.removeUpdates(listener) }
            }.combine(_backgroundMode) { loc, bg ->
                val limit = if (bg) BACKGROUND_ACCURACY_M else FOREGROUND_ACCURACY_M
                val accepted = loc.accuracy <= 0f || loc.accuracy <= limit
                recordRawFix(loc.accuracy, accepted)
                if (accepted) loc else null
            }.filterNotNull()
                .map { loc ->
                    LocationSample(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                        altitude = if (loc.hasAltitude()) loc.altitude else null,
                        heading = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                        speed = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                        timestamp = loc.time,
                        provider = "gnss",
                    )
                }.let { upstream ->
                    var lastEmitMs = 0L
                    var lastSample: LocationSample? = null
                    upstream.filter { sample ->
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs < MIN_INTERVAL_MS) return@filter false
                        val prev = lastSample
                        if (prev != null) {
                            val dist =
                                haversineDistanceMeters(
                                    LatLng(prev.lat, prev.lon),
                                    LatLng(sample.lat, sample.lon),
                                )
                            if (dist < MIN_DISTANCE_M) return@filter false
                        }
                        lastEmitMs = now
                        lastSample = sample
                        true
                    }
                }.shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

        fun setBackgroundMode(enabled: Boolean) {
            _backgroundMode.value = enabled
        }

        private fun hasLocationPermission(): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        // Issue #23 instrumentation: records every raw fix (pre-gate) so the Debug screen and the
        // exported audio log can show whether the accuracy gate is dropping fixes vs. the OS/GPS
        // chip simply not delivering them.
        private fun recordRawFix(
            accuracyM: Float,
            accepted: Boolean,
        ) {
            consecutiveDiscards = if (accepted) 0 else consecutiveDiscards + 1
            val hasAccuracy = accuracyM > 0f
            _lastRawFix.value =
                RawFixEvent(
                    timestampMs = System.currentTimeMillis(),
                    accuracyM = if (hasAccuracy) accuracyM else null,
                    provider = "gnss",
                    accepted = accepted,
                    consecutiveDiscards = consecutiveDiscards,
                )
            if (!accepted) {
                audioEventLog.append(
                    AudioLogEntry(
                        timestampMs = System.currentTimeMillis(),
                        kind = AudioLogEntry.Kind.DETECTION_STATE,
                        trigger = "GpsFixRejected",
                        inputs = "accuracy=${"%.1f".format(accuracyM)}m, provider=gnss",
                        outputs = "consecutiveDiscards=$consecutiveDiscards",
                        played = "bail:accuracy_gate",
                    ),
                )
            }
        }

        companion object {
            private const val FOREGROUND_ACCURACY_M = 10f
            private const val BACKGROUND_ACCURACY_M = 50f
            private const val MIN_INTERVAL_MS = 1_000L
            private const val MIN_DISTANCE_M = 0.0
            private const val STOP_TIMEOUT_MS = 5_000L
            private const val PERMISSION_RECHECK_MS = 500L
        }
    }
