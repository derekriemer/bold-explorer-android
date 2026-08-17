package com.boldexplorer.shared.audio

sealed class AudioCueEvent {
    // Directional beacon: pan encodes left/right, pitchHz encodes front/back.
    // 0° ahead → 880 Hz + center; 180° behind → 220 Hz + appropriate pan.
    // Fires every ~5 s during navigation (not during alignment mode).
    data class DirectionalBeacon(
        val pan: Float,
        val pitchHz: Double,
    ) : AudioCueEvent()

    // Debug-only: frequency maps GPS accuracy (0m → 880 Hz, 30m → 220 Hz).
    // Enabled via the debug screen toggle, suppressed in production by default.
    data class AccuracyBeacon(
        val accuracyM: Double,
    ) : AudioCueEvent()

    // Alignment ping at configurable Hz.
    // pan: [-1, 1] where negative = left ear (turn left), positive = right ear (turn right).
    // pitchHz: continuous, 880 Hz when aligned → 220 Hz at 180° off; higher = closer.
    data class AlignmentPing(
        val pan: Float,
        val pitchHz: Double,
    ) : AudioCueEvent()

    /**
     * The periodic "still following" beep (ADR 0001, S6). [lost] renders it differently, so the
     * sound never implies confidence the tracker does not have — the same failure mode a hard
     * accuracy gate produced once before, where distance/bearing froze while this earcon kept
     * carrying on as if nothing had changed.
     */
    data class Progress(
        val lost: Boolean,
    ) : AudioCueEvent()

    // TTS announcement when the trail is finished.
    object TrailComplete : AudioCueEvent()

    // Earcon played when the user is consistently off-trail or moving away from their target.
    // Two descending tones signal a wrong-vector condition.
    object WrongVector : AudioCueEvent()
}
