package com.boldexplorer.shared.model

data class LocationSample(
    val lat: Double,
    val lon: Double,
    val accuracy: Double? = null,
    val altitude: Double? = null,
    val heading: Double? = null,
    val speed: Double? = null,
    val timestamp: Long,
    val provider: String = "unknown",
)

data class HeadingReading(
    val magnetic: Double?,
    val trueNorth: Double?,
    val timestamp: Long,
)
