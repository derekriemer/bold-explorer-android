package com.boldexplorer.shared.geo

import kotlin.math.*

// Ported line-for-line from src/utils/geo.ts — keep in sync with the source spec.

const val DEG_TO_RAD = PI / 180.0
const val EARTH_R = 6_371_000.0
const val METERS_PER_DEG_LAT = 111_320.0
const val DEFAULT_BBOX_RADIUS_M = 50_000.0
const val EPS_COS_LAT_POLE = 1e-6

fun haversineDistanceMeters(a: LatLng, b: LatLng): Double {
    val dLat = (b.lat - a.lat) * DEG_TO_RAD
    val dLon = (b.lon - a.lon) * DEG_TO_RAD
    val lat1 = a.lat * DEG_TO_RAD
    val lat2 = b.lat * DEG_TO_RAD
    val sinDlat = sin(dLat / 2)
    val sinDlon = sin(dLon / 2)
    val h = sinDlat * sinDlat + cos(lat1) * cos(lat2) * sinDlon * sinDlon
    val c = 2 * asin(sqrt(h))
    return EARTH_R * c
}

fun initialBearingDeg(a: LatLng, b: LatLng): Double {
    val lat1 = a.lat * DEG_TO_RAD
    val lat2 = b.lat * DEG_TO_RAD
    val dLon = (b.lon - a.lon) * DEG_TO_RAD
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val brng = atan2(y, x) / DEG_TO_RAD
    return (brng + 360) % 360
}

// Normalized signed delta from heading to bearing: range [-180, 180).
// Positive = target is to the right of heading; negative = to the left.
fun deltaAngle(heading: Double, bearing: Double): Double =
    ((bearing - heading + 540) % 360) - 180

fun computeBbox(center: LatLng, radiusM: Double = DEFAULT_BBOX_RADIUS_M): BboxResult {
    val degLat = radiusM / METERS_PER_DEG_LAT
    val latMin = maxOf(-90.0, center.lat - degLat)
    val latMax = minOf(90.0, center.lat + degLat)

    val cosLat = cos(center.lat * DEG_TO_RAD)
    if (abs(cosLat) < EPS_COS_LAT_POLE) {
        return BboxResult(latMin, latMax, -180.0, 180.0, needsLonFilter = false, crossing = false)
    }

    val degLon = radiusM / (METERS_PER_DEG_LAT * cosLat)
    if (degLon >= 180) {
        return BboxResult(latMin, latMax, -180.0, 180.0, needsLonFilter = false, crossing = false)
    }

    val lonMinRaw = center.lon - degLon
    val lonMaxRaw = center.lon + degLon
    val crossing = lonMinRaw < -180 || lonMaxRaw > 180

    fun norm(x: Double) = (((x + 180) % 360) + 360) % 360 - 180
    val lonMin = if (crossing) norm(lonMinRaw) else lonMinRaw
    val lonMax = if (crossing) norm(lonMaxRaw) else lonMaxRaw

    return BboxResult(latMin, latMax, lonMin, lonMax, needsLonFilter = true, crossing = crossing)
}
