package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
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
