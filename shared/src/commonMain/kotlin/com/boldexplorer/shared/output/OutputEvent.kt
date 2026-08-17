package com.boldexplorer.shared.output

/** Broad grouping used for logging/display and future per-category policy (see #21). */
enum class OutputCategory {
    NAVIGATION,
    PROXIMITY,
    RECORDING,
    ALIGNMENT,
    SYSTEM,
}

/**
 * Why this event happened. Not yet consulted by [OutputPolicy] (which still only mirrors today's
 * `spokenGuidanceEnabled` setting), but recorded on every event now so a future silence-mode
 * policy can suppress AUTOMATIC chatter while still honoring USER_REQUESTED/INTERACTION_CONFIRMATION
 * output without another call-site migration.
 */
enum class OutputOrigin {
    AUTOMATIC,
    USER_REQUESTED,
    INTERACTION_CONFIRMATION,
}

/** Concrete identity for every current output call site — see the migration table in issue #11. */
enum class OutputKind {
    COLLECTION_SKIP,
    TRAIL_STARTED,
    TRAIL_STOPPED,
    TRAIL_COMPLETED,
    ORDINARY_GUIDANCE,
    OFF_TRAIL_ALERT,
    BACKTRACK_ALERT,
    COLLECTION_POINT_REACHED,
    NEARBY_POINT,
    AUTO_RECORD_STARTED,
    AUTO_RECORD_STOPPED,
    TRACK_POINT_COUNT,
    ALIGNMENT_DELTA,
    COPY_COORDINATES,
    WAYPOINT_MARKED,
    TRAIL_CREATED,
    GPS_TARGET_RESULT,
    PROGRESS,
    ANNOTATION_PASSED,
    MATCH_STATE,
}

/**
 * A semantic output event. `speech` is nullable rather than mandatory — this is not a speech-first
 * model, even though speech is the only presentation routed through it today (earcons/haptics are
 * tracked separately, see issue #22).
 */
data class OutputEvent(
    val kind: OutputKind,
    val category: OutputCategory,
    val origin: OutputOrigin,
    val speech: String?,
    // Debug-only payload, mapped onto AudioLogEntry.extra by OutputRouter. Kept as the existing
    // Map<String, Any?> convention already used in production for this exact purpose rather than
    // inventing a typed hierarchy for a debug sink (see issue #22).
    val context: Map<String, Any?> = emptyMap(),
)
