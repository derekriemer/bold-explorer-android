package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.LocationSample
import kotlin.math.floor

/**
 * Shared fixture helpers for navigation tests.
 *
 * This is the repo's first shared `commonTest` fixture file — every existing navigation test
 * hand-rolls its geometry as file-local literals. The pattern starts here because density
 * equivalence cannot be expressed at all without a way to say "the same physical path, sampled
 * differently", and that has to be one definition rather than one per test.
 */

/**
 * Resamples [shape] at roughly [spacingM] metre intervals, preserving the original vertices.
 *
 * The vertex preservation is the load-bearing part. Resampling purely at fixed offsets would drop
 * an original corner whenever the spacing does not divide the leg length evenly, and the densified
 * path would then *cut that corner* — a genuinely different shape. A density-equivalence test built
 * on that would fail for geometric reasons and be misread as a bug in the projection maths.
 *
 * Implemented on [TrailPolyline.positionAt], which is independently tested. That is a mild
 * circularity — fixtures for the class built from the class — but the alternative is duplicating
 * interpolation in test code, where it would drift.
 *
 * @param shape the coarse path defining the geometry; needs at least two points.
 * @param spacingM target spacing between emitted points along each segment.
 */
fun densify(
    shape: List<LatLng>,
    spacingM: Double,
): List<LatLng> {
    require(shape.size >= 2) { "densify needs at least two points to interpolate between" }
    require(spacingM > 0.0) { "spacing must be positive" }

    val poly = TrailPolyline(shape)
    val out = mutableListOf<LatLng>()
    for (i in 0 until poly.segmentCount) {
        val startM = poly.cumulativeM[i]
        val endM = poly.cumulativeM[i + 1]
        out.add(poly.positionAt(startM)) // the original vertex — always kept
        val steps = floor((endM - startM) / spacingM).toInt()
        for (s in 1..steps) {
            val a = startM + s * spacingM
            if (a < endM - 1e-6) out.add(poly.positionAt(a))
        }
    }
    out.add(poly.positionAt(poly.totalLengthM)) // the final vertex
    return out
}

/** Metres per degree of longitude at latitude 40 — the latitude every fixture here is built at. */
private const val M_PER_DEG_LON_AT_40 = 111_194.9 * 0.766

/**
 * A hairpin: north [legM] metres, east [gapM], then south [legM] again.
 *
 * The two long arms are parallel, [gapM] apart, and traversed in *opposite* directions. This is the
 * geometry that motivates continuity being primary — a fix midway between the arms is equidistant
 * from both, so no purely geometric test can say which one the user is on.
 */
fun switchbackShape(
    legM: Double,
    gapM: Double,
): List<LatLng> {
    val north = 40.0 + legM / 111_194.9
    val east = -105.0 + gapM / M_PER_DEG_LON_AT_40
    return listOf(
        LatLng(40.0, -105.0),
        LatLng(north, -105.0),
        LatLng(north, east),
        LatLng(40.0, east),
    )
}

/** A point [northM] metres north and [eastM] metres east of the fixtures' common origin. */
fun offsetFromOrigin(
    northM: Double,
    eastM: Double,
): LatLng = LatLng(40.0 + northM / 111_194.9, -105.0 + eastM / M_PER_DEG_LON_AT_40)

/**
 * A GPS fix [northM] metres north and [eastM] metres east of the fixtures' common origin.
 *
 * On a due-north trail built by [northShape] these read directly as along-track and cross-track
 * offsets, which keeps tracker tests legible: `sampleAt(northM = 100.0, eastM = 30.0)` is "100 m
 * along the trail and 30 m off it".
 */
fun sampleAt(
    northM: Double,
    eastM: Double,
    timestampMs: Long,
    accuracyM: Double? = 5.0,
    speedMps: Double? = 1.4,
    courseDeg: Double? = 0.0,
): LocationSample {
    val p = offsetFromOrigin(northM, eastM)
    return LocationSample(
        lat = p.lat,
        lon = p.lon,
        accuracy = accuracyM,
        heading = courseDeg,
        speed = speedMps,
        timestamp = timestampMs,
    )
}

/** A due-north line from (40, -105) of [lengthM] metres, as a two-point shape. */
fun northShape(lengthM: Double): List<LatLng> =
    listOf(LatLng(40.0, -105.0), LatLng(40.0 + lengthM / 111_194.9, -105.0))

/** [legM] metres due north then [legM] metres due east — a right-angle corner. */
fun cornerShape(legM: Double): List<LatLng> {
    val north = 40.0 + legM / 111_194.9
    return listOf(
        LatLng(40.0, -105.0),
        LatLng(north, -105.0),
        LatLng(north, -105.0 + legM / (111_194.9 * 0.766)),
    )
}
