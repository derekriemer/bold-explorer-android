package com.boldexplorer.shared.audio

sealed class AudioCueEvent {
    // Continuous beacon: frequency maps to GPS accuracy (0m → 880 Hz, 30m → 220 Hz).
    data class AccuracyBeacon(val accuracyM: Double) : AudioCueEvent()

    // Alignment ping at configurable Hz; pan = -1 (left), 0 (center), 1 (right).
    data class AlignmentPing(val pan: Int, val aligned: Boolean) : AudioCueEvent()

    // TTS announcement when a waypoint is reached.
    data class WaypointApproach(val waypointName: String) : AudioCueEvent()

    // TTS announcement when the trail is finished.
    object TrailComplete : AudioCueEvent()
}
