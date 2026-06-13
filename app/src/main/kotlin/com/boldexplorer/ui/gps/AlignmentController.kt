package com.boldexplorer.ui.gps

import com.boldexplorer.audio.SpokenGuidancePlayer
import com.boldexplorer.shared.geo.deltaAngle
import com.boldexplorer.shared.navigation.BearingComputer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Owns bearing-alignment state and the math/speech around it, extracted from [GpsViewModel] so that
 * class stays a coordinator rather than a god object.
 *
 * The controller is intentionally **audio-agnostic**: starting/stopping the directional-beacon
 * session stays in the ViewModel because it depends on navigation state (alignment shares the audio
 * session with active navigation). This class owns only whether alignment is *active*, the *target
 * bearing*, and the signed *delta* to the live compass heading.
 *
 * @param headingDeg live compass heading (sensor, never GPS course — the user is physically pointing
 *   the phone at a target, not travelling toward it).
 * @param targetBearingDeg bearing to the current navigation target, consumed by [alignToTarget].
 */
class AlignmentController(
    scope: CoroutineScope,
    private val headingDeg: StateFlow<Double?>,
    private val targetBearingDeg: StateFlow<Double?>,
    private val spokenGuidancePlayer: SpokenGuidancePlayer,
) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _bearingDeg = MutableStateFlow<Double?>(null)
    val bearingDeg: StateFlow<Double?> = _bearingDeg.asStateFlow()

    /** Signed delta between the live compass heading and the alignment target; null when inactive. */
    val relativeDeg: StateFlow<Double?> =
        combine(headingDeg, _bearingDeg, _active) { heading, bearing, active ->
            if (active && heading != null && bearing != null) {
                deltaAngle(heading, bearing)
            } else {
                null
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), null)

    /** Activate alignment, seeding the target bearing from the current heading when unset. */
    fun start() {
        _bearingDeg.value = headingDeg.value ?: _bearingDeg.value ?: 0.0
        _active.value = true
    }

    fun stop() {
        _active.value = false
    }

    /** Re-seed the alignment target to the current compass heading. */
    fun resetToCurrentHeading() {
        headingDeg.value?.let { setBearing(it) }
    }

    fun setBearing(deg: Double) {
        _bearingDeg.value = ((deg % 360) + 360) % 360
    }

    /** Point alignment at the current navigation target's bearing. */
    fun alignToTarget() {
        targetBearingDeg.value?.let { setBearing(it) }
    }

    /** Speak the current alignment delta on demand (e.g. the modal's opt-in 5 s ticker). */
    fun speakDelta() {
        if (!_active.value) return
        val delta = relativeDeg.value ?: return
        spokenGuidancePlayer.speak(BearingComputer.toAlignmentRelative(delta))
    }
}
