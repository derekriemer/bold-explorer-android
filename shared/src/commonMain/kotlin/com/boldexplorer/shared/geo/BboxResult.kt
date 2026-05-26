package com.boldexplorer.shared.geo

data class BboxResult(
    val latMin: Double,
    val latMax: Double,
    val lonMin: Double,
    val lonMax: Double,
    val needsLonFilter: Boolean,
    val crossing: Boolean,
)
