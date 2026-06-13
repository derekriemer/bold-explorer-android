package com.boldexplorer.shared.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure state machine for trail follow/record on the GPS screen.
 *
 * The machine is the single source of truth that guarantees follow and record controls are never
 * offered at the same time: a trail is either being [Following] or [Recording], never both. The UI
 * reads [state] and surfaces exactly one primary action.
 *
 * Record-extend gating (only at a trail end within an accuracy-scaled threshold) is a UI concern —
 * the machine permits [startRecording] from [Selected]; the caller decides *when* to offer it.
 */
sealed class TrailRecordingState {
    object Idle : TrailRecordingState()

    /** A trail is selected but neither following nor recording. [hasPoints] drives the default action. */
    data class Selected(
        val trailId: Long,
        val hasPoints: Boolean,
    ) : TrailRecordingState()

    data class Following(
        val trailId: Long,
    ) : TrailRecordingState()

    data class Recording(
        val trailId: Long,
        val pointCount: Int,
    ) : TrailRecordingState()
}

class TrailRecordingMachine {
    private val _state = MutableStateFlow<TrailRecordingState>(TrailRecordingState.Idle)
    val state: StateFlow<TrailRecordingState> = _state.asStateFlow()

    /** Select a trail. Only valid while idle or already selected — stop an active session first. */
    fun selectTrail(
        trailId: Long,
        hasPoints: Boolean,
    ) {
        when (_state.value) {
            is TrailRecordingState.Idle,
            is TrailRecordingState.Selected,
            -> _state.value = TrailRecordingState.Selected(trailId, hasPoints)

            else -> reject("selectTrail")
        }
    }

    /** Begin following the selected trail. Rejected while recording. */
    fun startFollowing() {
        val s = _state.value as? TrailRecordingState.Selected ?: return reject("startFollowing")
        _state.value = TrailRecordingState.Following(s.trailId)
    }

    /** Begin recording onto the selected trail. Rejected while following. */
    fun startRecording() {
        val s = _state.value as? TrailRecordingState.Selected ?: return reject("startRecording")
        _state.value = TrailRecordingState.Recording(s.trailId, pointCount = 0)
    }

    /** Record one captured track point. Only valid while recording. */
    fun addPoint() {
        val s = _state.value as? TrailRecordingState.Recording ?: return reject("addPoint")
        _state.value = s.copy(pointCount = s.pointCount + 1)
    }

    /** Stop any active session, returning to [Selected]. Idempotent from idle/selected. */
    fun stop() {
        _state.value =
            when (val s = _state.value) {
                is TrailRecordingState.Following -> TrailRecordingState.Selected(s.trailId, hasPoints = true)
                is TrailRecordingState.Recording -> TrailRecordingState.Selected(s.trailId, hasPoints = true)
                else -> s
            }
    }

    private fun reject(action: String): Nothing =
        throw IllegalStateException(
            "$action is not valid from ${_state.value::class.simpleName}",
        )
}
