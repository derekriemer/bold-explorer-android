package com.boldexplorer.location

/**
 * Telemetry for every raw location the OS delivers, before/after the accuracy gate — distinct
 * from [com.boldexplorer.shared.model.LocationSample], which only exists for *accepted* fixes.
 * Exists to diagnose issue #23 (distance/bearing freezing while the earcon keeps updating): this
 * makes it possible to tell "the accuracy gate is dropping fixes" apart from "the OS/GPS chip
 * just isn't delivering fixes" (OEM throttling, Doze, etc.) instead of inferring it from gaps.
 */
data class RawFixEvent(
    val timestampMs: Long,
    val accuracyM: Float?,
    val provider: String,
    val accepted: Boolean,
    // Consecutive discards ending at (and including, if this one was itself discarded) this
    // event. Resets to 0 on acceptance. A climbing streak alongside frequent raw fixes points at
    // the accuracy gate; long gaps between raw fixes at all points at OS/OEM throttling instead.
    val consecutiveDiscards: Int,
)
