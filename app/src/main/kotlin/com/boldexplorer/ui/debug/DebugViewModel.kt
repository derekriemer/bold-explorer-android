package com.boldexplorer.ui.debug

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.audio.AudioEngine
import com.boldexplorer.audio.AudioEventLog
import com.boldexplorer.audio.AudioLogEntry
import com.boldexplorer.audio.ShadowMatchMonitor
import com.boldexplorer.audio.ShadowMatchSnapshot
import com.boldexplorer.compass.SensorCompassProvider
import com.boldexplorer.gpx.GpxFileWriter
import com.boldexplorer.location.AccuracyHapticMonitor
import com.boldexplorer.audio.MarkerSpanRecorder
import com.boldexplorer.location.LocationDegradationController
import com.boldexplorer.location.LocationProviderRouter
import com.boldexplorer.location.RawFixEvent
import com.boldexplorer.shared.audio.AudioCueScheduler
import com.boldexplorer.shared.gpx.GpxExporter
import com.boldexplorer.shared.model.HeadingReading
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.repository.WaypointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val locationRouter: LocationProviderRouter,
        private val degradation: LocationDegradationController,
        private val markerSpans: MarkerSpanRecorder,
        private val compassProvider: SensorCompassProvider,
        private val waypointRepo: WaypointRepository,
        private val audioEngine: AudioEngine,
        private val scheduler: AudioCueScheduler,
        private val audioEventLog: AudioEventLog,
        private val shadowMatchMonitor: ShadowMatchMonitor,
        private val hapticMonitor: AccuracyHapticMonitor,
    ) : ViewModel() {
        val location: StateFlow<LocationSample?> =
            locationRouter.locationFlow
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        val heading: StateFlow<HeadingReading?> =
            compassProvider.headingFlow
                .map { it }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

        private val _exportStatus = MutableStateFlow<String?>(null)
        val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

        val accuracyBeaconEnabled: StateFlow<Boolean> = scheduler.accuracyBeaconEnabled

        val useGnss: StateFlow<Boolean> = locationRouter.useGnss

        /** Raw-fix telemetry (issue #23) — updates on every fix, accepted or accuracy-gate-dropped. */
        val lastRawFix: StateFlow<RawFixEvent?> = locationRouter.lastRawFix

        /** App-scoped so it keeps buzzing on whichever screen is open, not just this one. */
        val accuracyHapticsEnabled: StateFlow<Boolean> = hapticMonitor.enabled

        /**
         * Trail-match logging (ADR 0001 S4): one TRAIL_MATCH record per fix, for later replay. The
         * matcher beside the live follower runs regardless — since S5a wrong-way detection reads it.
         */
        val shadowMatchEnabled: StateFlow<Boolean> = shadowMatchMonitor.enabled

        /** Latest shadow decision, refreshed at most every 5 s so it stays readable with TalkBack. */
        val shadowMatch: StateFlow<ShadowMatchSnapshot?> = shadowMatchMonitor.snapshot

        fun setShadowMatchEnabled(enabled: Boolean) = shadowMatchMonitor.setEnabled(enabled)

        fun setAccuracyHapticsEnabled(enabled: Boolean) = hapticMonitor.setEnabled(enabled)

        // ── Audio log ─────────────────────────────────────────────────────────────────

        val logEntries: StateFlow<List<AudioLogEntry>> = audioEventLog.entries

        private val _logStatus = MutableStateFlow<String?>(null)
        val logStatus: StateFlow<String?> = _logStatus.asStateFlow()

        // Non-null while the IMPORTANT! dialog is open; holds the timestamp recorded at button-press
        // so the marker is filed at the moment the user pressed the button, not when they dismiss.
        private val _pendingMarkerTimestampMs = MutableStateFlow<Long?>(null)
        val showMarkerDialog: StateFlow<Boolean> =
            _pendingMarkerTimestampMs
                .map { it != null }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

        fun onImportantPressed() {
            _pendingMarkerTimestampMs.value = System.currentTimeMillis()
        }

        fun confirmMarker(note: String) {
            val ts = _pendingMarkerTimestampMs.value ?: return
            _pendingMarkerTimestampMs.value = null
            viewModelScope.launch {
                audioEventLog.append(
                    AudioLogEntry(
                        timestampMs = ts,
                        kind = AudioLogEntry.Kind.USER_MARKER,
                        trigger = "IMPORTANT button",
                        inputs = "",
                        outputs = "",
                        played = "",
                        note = note,
                    ),
                )
            }
        }

        fun dismissMarker() {
            _pendingMarkerTimestampMs.value = null
        }

        // ── Marked sections (spans) ────────────────────────────────────────────────────
        //
        // The interval counterpart to IMPORTANT!. State lives in MarkerSpanRecorder rather than
        // here because a span outlives this ViewModel — see its KDoc.

        val openSpan = markerSpans.openSpan

        /** Non-null while the "start a marked section" dialog is open. */
        private val _pendingSpanTimestampMs = MutableStateFlow<Long?>(null)
        val showSpanDialog: StateFlow<Boolean> =
            _pendingSpanTimestampMs
                .map { it != null }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

        /** Opens the naming dialog, or closes the current span if one is open. */
        fun onSpanButtonPressed() {
            if (markerSpans.openSpan.value != null) {
                markerSpans.end()
            } else {
                _pendingSpanTimestampMs.value = System.currentTimeMillis()
            }
        }

        fun confirmSpan(label: String) {
            val ts = _pendingSpanTimestampMs.value ?: return
            _pendingSpanTimestampMs.value = null
            // Timestamped at the button press, not at dialog dismissal: the point of a bracket is
            // that its edges are the times, and typing a label takes as long as it takes.
            markerSpans.start(label.ifBlank { "section" }, nowMs = ts)
        }

        fun dismissSpan() {
            _pendingSpanTimestampMs.value = null
        }

        fun newLog() {
            audioEventLog.newSession()
            _logStatus.value = "Log cleared"
        }

        fun exportLog() {
            viewModelScope.launch(Dispatchers.IO) {
                _logStatus.value =
                    audioEventLog
                        .exportToDownloads()
                        .fold(onSuccess = { it }, onFailure = { "Export failed: ${it.message}" })
            }
        }

        // ── Existing ──────────────────────────────────────────────────────────────────

        fun setAccuracyBeaconEnabled(enabled: Boolean) {
            scheduler.accuracyBeaconEnabled.value = enabled
        }

        fun setUseGnss(enabled: Boolean) {
            locationRouter.setUseGnss(enabled)
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
                GpxFileWriter
                    .writeToDownloads(context, filename, gpx)
                    .onSuccess {
                        _exportStatus.value = "Exported ${waypoints.size} waypoints → Downloads/$filename"
                    }.onFailure { e ->
                        _exportStatus.value = "Export failed: ${e.message}"
                    }
            }
        }
        // ── GPS degradation (debug-only, #62) ───────────────────────────────────────────

        /**
         * Presets rather than sliders.
         *
         * A continuous slider is awkward to set precisely with a screen reader, and the useful
         * settings here are a handful of scenarios rather than a range. Each names the condition it
         * reproduces so the choice is about the situation, not the numbers.
         */
        enum class DegradationPreset(
            val label: String,
            val biasM: Double,
            val jitterM: Double,
            val accuracyM: Double?,
        ) {
            MILD_BIAS("Mild drift, 10 m", 10.0, 0.0, null),
            SEVERE_BIAS("Severe drift, 25 m", 25.0, 0.0, null),
            POOR_ACCURACY("Poor accuracy only, 30 m", 0.0, 0.0, 30.0),
            CANYON("Canyon: 20 m drift, 8 m jitter, 25 m accuracy", 20.0, 8.0, 25.0),
        }

        val degradationConfig = degradation.config

        /**
         * Arms [preset], drifting toward [biasBearingDeg].
         *
         * Bearing matters: to put a fix on the wrong arm of a switchback the drift has to push
         * across the trail, so aim it perpendicular to the direction of travel.
         */
        fun armDegradation(
            preset: DegradationPreset,
            biasBearingDeg: Double,
        ) {
            degradation.arm(
                lateralBiasM = preset.biasM,
                biasBearingDeg = biasBearingDeg,
                jitterSigmaM = preset.jitterM,
                accuracyOverrideM = preset.accuracyM,
            )
            audioEventLog.append(
                AudioLogEntry(
                    timestampMs = System.currentTimeMillis(),
                    kind = AudioLogEntry.Kind.USER_MARKER,
                    trigger = "GpsDegradationArmed",
                    inputs = preset.label,
                    outputs = "bearing=$biasBearingDeg",
                    played = "armed",
                    note = "Fixes from here are deliberately degraded; readings are not real.",
                ),
            )
        }

        fun disarmDegradation() {
            degradation.disarm()
            audioEventLog.append(
                AudioLogEntry(
                    timestampMs = System.currentTimeMillis(),
                    kind = AudioLogEntry.Kind.USER_MARKER,
                    trigger = "GpsDegradationDisarmed",
                    inputs = "",
                    outputs = "",
                    played = "disarmed",
                ),
            )
        }
    }
