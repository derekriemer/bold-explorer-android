package com.boldexplorer.shared.geo

data class LatLng(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "Latitude out of range: $lat" }
        require(lon in -180.0..180.0) { "Longitude out of range: $lon" }
    }
}
