package com.boldexplorer.shared.settings

data class AppSettings(
    val units: Units = Units.IMPERIAL,
    val compassMode: CompassMode = CompassMode.MAGNETIC,
    val bearingDisplayMode: BearingDisplayMode = BearingDisplayMode.RELATIVE,
    val spokenGuidanceEnabled: Boolean = true,
    val beaconCuesEnabled: Boolean = true,
    val duckAudioEnabled: Boolean = false,
)

enum class Units { METRIC, IMPERIAL }

enum class CompassMode { MAGNETIC, TRUE }

enum class BearingDisplayMode { RELATIVE, CLOCK, TRUE_NORTH }
