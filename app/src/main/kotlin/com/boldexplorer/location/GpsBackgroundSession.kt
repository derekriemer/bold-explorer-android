package com.boldexplorer.location

import com.boldexplorer.audio.AudioCuePlayer
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.navigation.TrailGuidanceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class GpsBackgroundMode {
    TrailFollow,
    CollectionFollow,
    AutoRecord,
    BeaconNavigation,
}

data class GpsBackgroundSessionState(
    val activeModes: Set<GpsBackgroundMode> = emptySet(),
    val serviceStarted: Boolean = false,
    val beaconPlaybackActive: Boolean = false,
) {
    val needsForegroundService: Boolean
        get() = activeModes.isNotEmpty()
}

data class BeaconAudioInputs(
    val accuracyM: StateFlow<Double?>,
    val relativeDeg: StateFlow<Double?>,
    val alignmentActive: StateFlow<Boolean>,
    val beaconCuesEnabled: StateFlow<Boolean>,
    val location: StateFlow<LocationSample?>,
    val trailGuidance: StateFlow<TrailGuidanceState?>,
)

/**
 * App-layer owner for background GPS work and beacon playback lifetime.
 *
 * The foreground service owns Android process priority. The GPS UI registers
 * current navigation inputs here, and the service starts playback after
 * startForeground() succeeds.
 */
@Singleton
class GpsBackgroundSession
    @Inject
    constructor(
        private val audioCuePlayer: AudioCuePlayer,
    ) {
        private val _state = MutableStateFlow(GpsBackgroundSessionState())
        val state: StateFlow<GpsBackgroundSessionState> = _state.asStateFlow()

        private var beaconInputs: BeaconAudioInputs? = null
        private var alignmentActive = false

        fun setModeActive(
            mode: GpsBackgroundMode,
            active: Boolean,
        ) {
            _state.value =
                _state.value.copy(
                    activeModes = if (active) _state.value.activeModes + mode else _state.value.activeModes - mode,
                )
            if (!active && mode == GpsBackgroundMode.BeaconNavigation) stopBeaconPlaybackIfIdle()
        }

        fun configureBeaconInputs(inputs: BeaconAudioInputs) {
            beaconInputs = inputs
            startBeaconPlaybackIfNeeded()
        }

        fun startBeaconNavigation(inputs: BeaconAudioInputs) {
            beaconInputs = inputs
            setModeActive(GpsBackgroundMode.BeaconNavigation, true)
            startBeaconPlaybackIfNeeded()
        }

        fun stopBeaconNavigation() {
            setModeActive(GpsBackgroundMode.BeaconNavigation, false)
        }

        fun startAlignment(inputs: BeaconAudioInputs) {
            beaconInputs = inputs
            alignmentActive = true
            startBeaconPlaybackIfNeeded()
        }

        fun stopAlignment() {
            alignmentActive = false
            stopBeaconPlaybackIfIdle()
        }

        fun onServiceStarted() {
            _state.value = _state.value.copy(serviceStarted = true)
            startBeaconPlaybackIfNeeded()
        }

        fun onServiceStopped() {
            _state.value = _state.value.copy(serviceStarted = false)
            if (!alignmentActive) stopBeaconPlayback(force = true)
        }

        private fun startBeaconPlaybackIfNeeded() {
            val shouldPlay =
                alignmentActive ||
                    (_state.value.serviceStarted && GpsBackgroundMode.BeaconNavigation in _state.value.activeModes)
            if (!shouldPlay || _state.value.beaconPlaybackActive) return
            val inputs = beaconInputs ?: return
            audioCuePlayer.start(
                accuracyM = inputs.accuracyM,
                relativeDeg = inputs.relativeDeg,
                alignmentActive = inputs.alignmentActive,
                beaconCuesEnabled = inputs.beaconCuesEnabled,
                location = inputs.location,
                trailGuidance = inputs.trailGuidance,
            )
            _state.value = _state.value.copy(beaconPlaybackActive = true)
        }

        private fun stopBeaconPlaybackIfIdle() {
            if (alignmentActive) return
            if (GpsBackgroundMode.BeaconNavigation in _state.value.activeModes) return
            stopBeaconPlayback(force = false)
        }

        private fun stopBeaconPlayback(force: Boolean) {
            if (!force && !_state.value.beaconPlaybackActive) return
            audioCuePlayer.stop()
            _state.value = _state.value.copy(beaconPlaybackActive = false)
        }
    }
