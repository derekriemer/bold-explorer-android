package com.boldexplorer.shared.geo

import kotlin.math.*

// Ported line-for-line from src/utils/geo.ts — keep in sync with the source spec.

const val DEG_TO_RAD = PI / 180.0
const val EARTH_R = 6_371_000.0
const val METERS_PER_DEG_LAT = 111_320.0
const val DEFAULT_BBOX_RADIUS_M = 50_000.0
const val EPS_COS_LAT_POLE = 1e-6

fun haversineDistanceMeters(
    a: LatLng,
    b: LatLng,
): Double {
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

fun initialBearingDeg(
    a: LatLng,
    b: LatLng,
): Double {
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
fun deltaAngle(
    heading: Double,
    bearing: Double,
): Double = ((bearing - heading + 540) % 360) - 180

/**
 * Scalar projection of [p] onto segment [a]→[b], in the unit space of the segment.
 *
 * Returns 0.0 when [p] projects onto [a], 1.0 onto [b], >1.0 past [b], <0.0 behind [a].
 * Uses approximate flat-earth coordinates (scaled lon by cos(lat)); accurate for segments < ~1 km.
 * Returns 0.0 for degenerate segments (a == b).
 */
fun segmentFraction(
    p: LatLng,
    a: LatLng,
    b: LatLng,
): Double {
    val cosLat = cos((a.lat + b.lat) / 2.0 * DEG_TO_RAD)
    val abLat = b.lat - a.lat
    val abLon = (b.lon - a.lon) * cosLat
    val apLat = p.lat - a.lat
    val apLon = (p.lon - a.lon) * cosLat
    val len2 = abLat * abLat + abLon * abLon
    return if (len2 < 1e-12) 0.0 else (abLat * apLat + abLon * apLon) / len2
}

/**
 * Perpendicular distance from [point] to the segment [segStart]→[segEnd].
 * Clamps the projection to the segment endpoints, so points past the end return
 * distance to the endpoint, not the extended line. Valid for segments < ~1 km.
 */
fun distanceToSegmentMeters(
    point: LatLng,
    segStart: LatLng,
    segEnd: LatLng,
): Double {
    val t = segmentFraction(point, segStart, segEnd).coerceIn(0.0, 1.0)
    val projLat = segStart.lat + t * (segEnd.lat - segStart.lat)
    val projLon = segStart.lon + t * (segEnd.lon - segStart.lon)
    return haversineDistanceMeters(point, LatLng(projLat, projLon))
}

/** Absolute angular difference between two bearings, in [0, 180]. */
fun angleDifferenceDeg(
    a: Double,
    b: Double,
): Double = abs(deltaAngle(a, b))

/**
 * 3D distance combining a horizontal haversine distance and an elevation delta.
 * Use when both horizontal and vertical displacements are meaningful (e.g. steep terrain).
 * Sign of [elevDeltaM] does not matter — it is squared internally.
 */
fun distance3DMeters(
    horizDistM: Double,
    elevDeltaM: Double,
): Double = sqrt(horizDistM * horizDistM + elevDeltaM * elevDeltaM)

fun computeBbox(
    center: LatLng,
    radiusM: Double = DEFAULT_BBOX_RADIUS_M,
): BboxResult {
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
