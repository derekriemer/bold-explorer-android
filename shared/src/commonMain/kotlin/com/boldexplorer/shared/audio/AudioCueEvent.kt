package com.boldexplorer.shared.audio

sealed class AudioCueEvent {
    // Directional beacon: pan encodes left/right, pitchHz encodes front/back.
    // 0° ahead → 880 Hz + center; 180° behind → 220 Hz + appropriate pan.
    // Fires every ~5 s during navigation (not during alignment mode).
    data class DirectionalBeacon(val pan: Float, val pitchHz: Double) : AudioCueEvent()

    // Debug-only: frequency maps GPS accuracy (0m → 880 Hz, 30m → 220 Hz).
    // Enabled via the debug screen toggle, suppressed in production by default.
    data class AccuracyBeacon(val accuracyM: Double) : AudioCueEvent()

    // Alignment ping at configurable Hz; pan in [-1.0, 1.0] (-1 = full left, +1 = full right).
    data class AlignmentPing(val pan: Float, val aligned: Boolean) : AudioCueEvent()

    // TTS announcement when a waypoint is reached.
    data class WaypointApproach(val waypointName: String) : AudioCueEvent()

    // TTS announcement when the trail is finished.
    object TrailComplete : AudioCueEvent()
}
