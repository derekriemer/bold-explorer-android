package com.boldexplorer.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.boldexplorer.audio.AudioEventLog
import com.boldexplorer.audio.AudioLogEntry
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.location.LocationProvider
import com.boldexplorer.shared.model.LocationSample
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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

@Singleton
class FusedLocationProviderImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val audioEventLog: AudioEventLog,
    ) : LocationProvider {
        private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        private val _backgroundMode = MutableStateFlow(false)

        // Internal scope lives as long as the singleton (application lifetime).
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Telemetry for every raw fix (accepted or accuracy-gate-dropped) — see RawFixEvent.
        private val _lastRawFix = MutableStateFlow<RawFixEvent?>(null)
        val lastRawFix: StateFlow<RawFixEvent?> = _lastRawFix.asStateFlow()
        private var consecutiveDiscards = 0

        // Port of locationStream.ts gating logic:
        //   accuracy gate → interval gate → distance gate
        // WhileSubscribed: upstream GPS runs only while there is at least one collector.
        // The LocationForegroundService subscribes to keep GPS alive when screen is off.
        @SuppressLint("MissingPermission")
        override val locationFlow: SharedFlow<LocationSample> =
            callbackFlow {
                while (!hasLocationPermission()) {
                    delay(PERMISSION_RECHECK_MS)
                }

                val request =
                    LocationRequest
                        .Builder(Priority.PRIORITY_HIGH_ACCURACY, MIN_INTERVAL_MS)
                        .setMinUpdateDistanceMeters(0f)
                        .setWaitForAccurateLocation(true)
                        .setMinUpdateIntervalMillis(500L)
                        .setMaxUpdateDelayMillis(MIN_INTERVAL_MS * 2)
                        .build()

                val callback =
                    object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            result.lastLocation?.let { trySend(it) }
                        }
                    }

                fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                awaitClose { fusedClient.removeLocationUpdates(callback) }
            }.combine(_backgroundMode) { loc, bg ->
                val limit = if (bg) BACKGROUND_ACCURACY_M else FOREGROUND_ACCURACY_M
                // Accuracy is 0 when unavailable on some devices; treat <=0 as acceptable
                val accepted = loc.accuracy <= 0f || loc.accuracy <= limit
                recordRawFix(loc.accuracy, loc.provider ?: "fused", accepted)
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
                        provider = loc.provider ?: "fused",
                    )
                }
                // Interval + distance gate (stateful; safe because shareIn serialises the upstream)
                .let { upstream ->
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

        /** Called by LocationForegroundService when transitioning to/from background. */
        fun setBackgroundMode(enabled: Boolean) {
            _backgroundMode.value = enabled
        }

        private fun hasLocationPermission(): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        // Issue #23 instrumentation: records every raw fix (pre-gate) so the Debug screen and the
        // exported audio log can show whether the accuracy gate is dropping fixes vs. the OS/GPS
        // chip simply not delivering them.
        private fun recordRawFix(
            accuracyM: Float,
            provider: String,
            accepted: Boolean,
        ) {
            consecutiveDiscards = if (accepted) 0 else consecutiveDiscards + 1
            val hasAccuracy = accuracyM > 0f
            _lastRawFix.value =
                RawFixEvent(
                    timestampMs = System.currentTimeMillis(),
                    accuracyM = if (hasAccuracy) accuracyM else null,
                    provider = provider,
                    accepted = accepted,
                    consecutiveDiscards = consecutiveDiscards,
                )
            if (!accepted) {
                audioEventLog.append(
                    AudioLogEntry(
                        timestampMs = System.currentTimeMillis(),
                        kind = AudioLogEntry.Kind.DETECTION_STATE,
                        trigger = "GpsFixRejected",
                        inputs = "accuracy=${"%.1f".format(accuracyM)}m, provider=$provider",
                        outputs = "consecutiveDiscards=$consecutiveDiscards",
                        played = "bail:accuracy_gate",
                    ),
                )
            }
        }

        companion object {
            /** Foreground GPS: require ≤10 m accuracy (same as Vue locationStream.ts default). */
            private const val FOREGROUND_ACCURACY_M = 10f

            /** Background GPS: relax to 50 m — device GPS accuracy degrades significantly when backgrounded. */
            private const val BACKGROUND_ACCURACY_M = 50f
            private const val MIN_INTERVAL_MS = 1_000L

            /** No distance gate by default; interval gate is sufficient. */
            private const val MIN_DISTANCE_M = 0.0

            /** Keep GPS alive for 5 s after last subscriber drops. */
            private const val STOP_TIMEOUT_MS = 5_000L
            private const val PERMISSION_RECHECK_MS = 500L
        }
    }
