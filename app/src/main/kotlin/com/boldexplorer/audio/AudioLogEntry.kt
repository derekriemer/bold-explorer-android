package com.boldexplorer.audio

data class AudioLogEntry(
    val timestampMs: Long,
    val kind: Kind,
    val trigger: String,
    val inputs: String,
    val outputs: String,
    val played: String,
    val note: String = "",
    val extra: Map<String, Any?> = emptyMap(),
) {
    enum class Kind {
        DIRECTIONAL_BEACON,
        ACCURACY_BEACON,
        ALIGNMENT_PING,
        PROGRESS,
        TRAIL_COMPLETE,
        TTS_ANNOUNCEMENT,
        USER_MARKER,
        DETECTION_STATE,

        /**
         * A shadow-mode trail match: one entry per GPS fix while following a trail.
         *
         * Emitted by the continuous matcher running beside the live follower, and consumed by
         * nobody — it exists purely so its constants can be tuned against real walks before
         * anything depends on them. Carries the raw fix as well as the decision, so a single walk
         * can be replayed offline against different constants instead of needing a new walk per
         * change. Much higher volume than every other kind; see `AudioEventLog`.
         */
        TRAIL_MATCH,
    }
}
