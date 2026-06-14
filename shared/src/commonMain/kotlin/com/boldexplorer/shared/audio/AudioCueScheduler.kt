package com.boldexplorer.shared.audio

import com.boldexplorer.shared.navigation.BearingComputer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs

// Pure scheduling logic — decides WHAT to emit and WHEN.
// Android AudioCuePlayer consumes the SharedFlow and handles actual playback.
// iOS can plug in its own player without touching this class.
class AudioCueScheduler(
    val config: AudioCueConfig = AudioCueConfig(),
) {
    private val _events = MutableSharedFlow<AudioCueEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AudioCueEvent> = _events.asSharedFlow()

    // Toggled by the debug screen at runtime; off by default in production.
    val accuracyBeaconEnabled = MutableStateFlow(false)

    fun start(
        scope: CoroutineScope,
        accuracyM: StateFlow<Double?>,
        relativeDeg: StateFlow<Double?>,
        alignmentActive: StateFlow<Boolean>,
        beaconCuesEnabled: StateFlow<Boolean>,
    ): Job? {
        if (!config.enabled) return null

        return scope.launch {
            coroutineScope {
                // Directional beacon: timer-based, fires every directionalBeaconIntervalMs.
                // Pan = sin(bearing): 0°→0.0, ±90°→±1.0, ±180°→0.0.
                // Pitch = 220×2^(1+cos(bearing)): 0°→880 Hz (A5), ±90°→440 Hz (A4), ±180°→220 Hz (A3).
                // Suppressed during alignment — alignment pings cover that mode.
                if (config.directionalBeaconEnabled) {
                    launch {
                        while (true) {
                            if (beaconCuesEnabled.value && !alignmentActive.value) {
                                val deg = relativeDeg.value
                                if (deg != null) {
                                    val pan = BearingComputer.computePan(deg)
                                    val pitchHz = BearingComputer.computePitchHz(deg)
                                    _events.emit(AudioCueEvent.DirectionalBeacon(pan, pitchHz))
                                }
                            }
                            delay(config.directionalBeaconIntervalMs)
                        }
                    }
                }

                // Debug-only accuracy beacon: reactive to GPS updates, gated by runtime toggle.
                launch {
                    accuracyM.filterNotNull().collect { acc ->
                        if (beaconCuesEnabled.value && !alignmentActive.value && accuracyBeaconEnabled.value) {
                            _events.emit(AudioCueEvent.AccuracyBeacon(acc))
                        }
                    }
                }

                // Alignment ping loop at configurable Hz; slows to 1/3 Hz when aligned.
                if (config.alignmentPingEnabled) {
                    launch {
                        while (true) {
                            if (beaconCuesEnabled.value && alignmentActive.value) {
                                val deg = relativeDeg.value
                                if (deg != null) {
                                    val aligned = abs(deg) <= config.alignmentDeadbandDeg
                                    val pan = BearingComputer.computeAlignmentPan(deg)
                                    val pitchHz = BearingComputer.computeAlignmentPitchHz(deg)
                                    _events.emit(AudioCueEvent.AlignmentPing(pan, pitchHz))
                                    val intervalMs =
                                        if (aligned) {
                                            (1000.0 / (config.alignmentPingHz / 3.0)).toLong()
                                        } else {
                                            (1000.0 / config.alignmentPingHz).toLong()
                                        }
                                    delay(intervalMs)
                                } else {
                                    delay(500)
                                }
                            } else {
                                delay(200)
                            }
                        }
                    }
                }
            }
        }
    }

    // Called by GpsViewModel when TrailFollower emits a WaypointReached event.
    suspend fun emitWaypointApproach(
        name: String,
        spokenGuidanceEnabled: Boolean,
    ) {
        if (config.waypointApproachEnabled && spokenGuidanceEnabled) {
            _events.emit(AudioCueEvent.WaypointApproach(name))
        }
    }

    suspend fun emitTrailComplete(spokenGuidanceEnabled: Boolean) {
        if (config.waypointApproachEnabled && spokenGuidanceEnabled) {
            _events.emit(AudioCueEvent.TrailComplete)
        }
    }

    suspend fun emitWrongVector() {
        _events.emit(AudioCueEvent.WrongVector)
    }
}
