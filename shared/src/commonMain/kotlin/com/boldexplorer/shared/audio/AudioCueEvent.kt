package com.boldexplorer.shared.audio

/**
 * How often a cue's source can be expected to produce it — governs whether [AudioFocusController]
 * (`:app`) treats a run of this cue as worth holding a focus lease/`AudioTrack` across, rather than
 * requesting and releasing per cue. See #114/#108: a purely per-cue-scoped lease/track is fine for an
 * infrequent cue, but reopening a Bluetooth-routed stream every ~2s (frequent) is a plausible cause of
 * a stall this fix targets.
 */
enum class CueCadence {
    Frequent,
    Rare,
}

sealed class AudioCueEvent {
    // Directional beacon: pan encodes left/right, pitchHz encodes front/back.
    // 0° ahead → 880 Hz + center; 180° behind → 220 Hz + appropriate pan.
    // Fires every ~5 s during navigation (not during alignment mode).
    data class DirectionalBeacon(
        val pan: Float,
        val pitchHz: Double,
    ) : AudioCueEvent() {
        val cadence: CueCadence = CueCadence.Rare
    }

    // Alignment ping at configurable Hz.
    // pan: [-1, 1] where negative = left ear (turn left), positive = right ear (turn right).
    // pitchHz: continuous, 880 Hz when aligned → 220 Hz at 180° off; higher = closer.
    data class AlignmentPing(
        val pan: Float,
        val pitchHz: Double,
    ) : AudioCueEvent() {
        val cadence: CueCadence = CueCadence.Frequent
    }

    // TTS announcement when the trail is finished.
    object TrailComplete : AudioCueEvent()

    // Earcon played when the user is consistently off-trail or moving away from their target.
    // Two descending tones signal a wrong-vector condition.
    object WrongVector : AudioCueEvent() {
        val cadence: CueCadence = CueCadence.Rare
    }
}
