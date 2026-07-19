package com.boldexplorer.shared.settings

data class AppSettings(
    val units: Units = Units.IMPERIAL,
    val compassMode: CompassMode = CompassMode.MAGNETIC,
    val bearingDisplayMode: BearingDisplayMode = BearingDisplayMode.RELATIVE,
    val spokenGuidanceEnabled: Boolean = true,
    val beaconCuesEnabled: Boolean = true,
    val duckAudioEnabled: Boolean = false,
    // Absolute environmental silence (issue #14): sticky, never auto-restored. Gated in
    // OutputPolicy for TTS/live-region and as a direct stopgap check in AudioCuePlayer for
    // earcons (see issue #22 — earcons don't route through OutputPolicy yet).
    val absoluteSilenceEnabled: Boolean = false,
)

enum class Units { METRIC, IMPERIAL }

enum class CompassMode { MAGNETIC, TRUE }

enum class BearingDisplayMode { RELATIVE, CLOCK, TRUE_NORTH }
