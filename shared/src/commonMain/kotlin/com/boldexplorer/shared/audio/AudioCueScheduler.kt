package com.boldexplorer.shared.audio

import com.boldexplorer.shared.navigation.BearingComputer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs

// Pure scheduling logic — decides WHAT to emit and WHEN.
// Android AudioCuePlayer consumes the SharedFlow and handles actual playback.
// iOS can plug in its own player without touching this class.
class AudioCueScheduler(val config: AudioCueConfig = AudioCueConfig()) {
    private val _events = MutableSharedFlow<AudioCueEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AudioCueEvent> = _events.asSharedFlow()

    fun start(
        scope: CoroutineScope,
        accuracyM: StateFlow<Double?>,
        relativeDeg: StateFlow<Double?>,
        alignmentActive: StateFlow<Boolean>,
        audioCuesEnabled: StateFlow<Boolean>,
    ): Job? {
        if (!config.enabled) return null

        return scope.launch {
            coroutineScope {
                // Accuracy beacon: emit on every non-null accuracy update.
                // Suppressed while alignment is active — alignment pings are the only
                // audio signal needed then; mixing in a centered beacon is confusing.
                if (config.accuracyBeaconEnabled) {
                    launch {
                        accuracyM.filterNotNull().collect { acc ->
                            if (audioCuesEnabled.value && !alignmentActive.value) {
                                _events.emit(AudioCueEvent.AccuracyBeacon(acc))
                            }
                        }
                    }
                }

                // Alignment ping loop at configurable Hz; slows to 1/3 Hz when aligned.
                if (config.alignmentPingEnabled) {
                    launch {
                        while (true) {
                            if (audioCuesEnabled.value && alignmentActive.value) {
                                val deg = relativeDeg.value
                                if (deg != null && abs(deg) <= 90.0) {
                                    val aligned = abs(deg) <= config.alignmentDeadbandDeg
                                    val pan = BearingComputer.computePan(deg)
                                    _events.emit(AudioCueEvent.AlignmentPing(pan, aligned))
                                    val intervalMs = if (aligned) {
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
    suspend fun emitWaypointApproach(name: String, audioCuesEnabled: Boolean) {
        if (config.waypointApproachEnabled && audioCuesEnabled) {
            _events.emit(AudioCueEvent.WaypointApproach(name))
        }
    }

    suspend fun emitTrailComplete(audioCuesEnabled: Boolean) {
        if (config.waypointApproachEnabled && audioCuesEnabled) {
            _events.emit(AudioCueEvent.TrailComplete)
        }
    }
}
