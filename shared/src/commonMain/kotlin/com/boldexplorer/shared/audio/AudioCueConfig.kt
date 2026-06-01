package com.boldexplorer.shared.audio

data class AudioCueConfig(
    val enabled: Boolean = true,
    // Directional beacon fires on a timer during navigation (not alignment mode).
    val directionalBeaconEnabled: Boolean = true,
    val directionalBeaconIntervalMs: Long = 5000L,
    val alignmentPingEnabled: Boolean = true,
    val waypointApproachEnabled: Boolean = true,
    // Ping frequency in Hz when off-bearing; rate drops to 1/3 when aligned.
    val alignmentPingHz: Double = 1.0,
    // Degrees within which the user is considered "aligned" (deadband).
    val alignmentDeadbandDeg: Double = 3.0,
)
