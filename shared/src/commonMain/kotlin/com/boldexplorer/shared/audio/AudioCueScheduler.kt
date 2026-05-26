package com.boldexplorer.shared.audio

import com.boldexplorer.shared.navigation.BearingComputer
import kotlinx.coroutines.CoroutineScope
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
    ) {
        if (!config.enabled) return

        // Accuracy beacon: emit on every non-null accuracy update
        if (config.accuracyBeaconEnabled) {
            scope.launch {
                accuracyM.filterNotNull().collect { acc ->
                    _events.emit(AudioCueEvent.AccuracyBeacon(acc))
                }
            }
        }

        // Alignment ping loop at configurable Hz; slows to 1/3 Hz when aligned
        if (config.alignmentPingEnabled) {
            scope.launch {
                while (true) {
                    if (alignmentActive.value) {
                        val deg = relativeDeg.value
                        if (deg != null) {
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

    // Called by GpsViewModel when TrailFollower emits a WaypointReached event.
    suspend fun emitWaypointApproach(name: String) {
        if (config.waypointApproachEnabled) {
            _events.emit(AudioCueEvent.WaypointApproach(name))
        }
    }

    suspend fun emitTrailComplete() {
        if (config.waypointApproachEnabled) {
            _events.emit(AudioCueEvent.TrailComplete)
        }
    }
}
