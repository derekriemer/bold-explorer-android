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
        WAYPOINT_APPROACH,
        TRAIL_COMPLETE,
        TTS_ANNOUNCEMENT,
        USER_MARKER,
        DETECTION_STATE,
    }
}
