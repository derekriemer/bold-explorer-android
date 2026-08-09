package com.boldexplorer.shared.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Local east/north tangent frame and the planar vector primitives built on it.
 *
 * ## Two-layer rule
 *
 * `shared/geo` is deliberately split and navigation code must not mix the layers:
 *
 * - **Spherical layer** ([GeoMath]): [LatLng], [haversineDistanceMeters], [initialBearingDeg].
 *   Correct anywhere on the globe, and the only place geodesy lives.
 * - **Bridge**: [LocalFrame], which converts between the two exactly once per point.
 * - **Planar layer** (this file): [Vec2] and plain 2-D vector algebra, valid only inside a frame.
 *
 * Projection, cross-track, chord bearing, sagitta and turn angle are all one-liners in the planar
 * layer and awkward special cases in the spherical one. Converting once at the boundary is what
 * keeps them consistent with each other — see docs/adr/0001-continuous-trail-matching.md.
 *
 * ## Angle conventions
 *
 * | | Zero at | Direction |
 * |---|---|---|
 * | GPS / compass bearing | North | clockwise (90° = East) |
 * | Unit circle / standard `atan2(y, x)` | East (+x) | counter-clockwise |
 *
 * In this frame `x` is east and `y` is north, so converting a vector to a true bearing is an
 * `atan2` **argument swap**: `atan2(x, y)`, not `atan2(y, x)`. There is no grid convergence to
 * correct. A mix-up here fails silently by reflection rather than loudly, so every angle should be
 * produced through [bearingDeg] and [unitVec] rather than a hand-rolled `atan2` at a call site.
 */

/** A planar displacement inside a [LocalFrame], in metres. [x] is east, [y] is north. */
data class Vec2(val x: Double, val y: Double)

/**
 * An equirectangular east/north tangent frame anchored at an explicit [origin].
 *
 * The origin is a **parameter, not an invariant**: several polylines can be constructed against one
 * shared frame, which is what lets positions from different trails be compared directly. That is a
 * prerequisite for computing junction geometry (#59) and is why the origin is not simply the first
 * point of whatever polyline created the frame.
 *
 * Longitude is unwrapped relative to [origin] at conversion time, so a trail spanning the
 * anti-meridian needs no special casing.
 *
 * Accuracy: the flat approximation's error is measured rather than assumed — see
 * `LocalFramePinningTest`. Worst case at 70° latitude is ~0.02% over 1 km and ~0.2% over 10 km,
 * far below GPS accuracy at any trail scale.
 */
class LocalFrame(val origin: LatLng) {
    /**
     * Cosine of the origin latitude, floored at [EPS_COS_LAT_POLE].
     *
     * The same clamped value is used in both directions deliberately: clamping only in [toLatLng],
     * where it prevents a division blowing up at the pole, would break round-trip identity there.
     */
    private val cosOriginLat: Double =
        max(cos(origin.lat * DEG_TO_RAD), EPS_COS_LAT_POLE)

    /** Converts [p] to a planar offset from [origin], in metres. */
    fun toLocal(p: LatLng): Vec2 =
        Vec2(
            x = unwrapLonDeg(p.lon - origin.lon) * DEG_TO_RAD * EARTH_R * cosOriginLat,
            y = (p.lat - origin.lat) * DEG_TO_RAD * EARTH_R,
        )

    /** Converts a planar offset [v] from [origin] back to geographic coordinates. */
    fun toLatLng(v: Vec2): LatLng =
        LatLng(
            lat = origin.lat + v.y / (EARTH_R * DEG_TO_RAD),
            lon = normalizeLonDeg(origin.lon + v.x / (EARTH_R * DEG_TO_RAD * cosOriginLat)),
        )

    companion object {
        /**
         * A frame anchored at the centroid of [points] — the default for a polyline.
         *
         * Centroid rather than the first point: it halves the worst-case scale error across the
         * polyline's extent, which matters most for long north-south trails.
         *
         * Longitudes are averaged after unwrapping relative to the first point, so a polyline
         * spanning the anti-meridian yields a centroid near the polyline rather than halfway
         * around the globe.
         */
        fun centredOn(points: List<LatLng>): LocalFrame {
            require(points.isNotEmpty()) { "Cannot anchor a frame on an empty point list" }
            val first = points.first()
            val lat = first.lat + points.sumOf { it.lat - first.lat } / points.size
            val lon = first.lon + points.sumOf { unwrapLonDeg(it.lon - first.lon) } / points.size
            return LocalFrame(LatLng(lat, normalizeLonDeg(lon)))
        }
    }
}

/**
 * True compass bearing of [v] in degrees, normalized to `[0, 360)`.
 *
 * Zero at north, increasing clockwise. Note the argument order: `atan2(x, y)`.
 */
fun bearingDeg(v: Vec2): Double {
    val deg = atan2(v.x, v.y) / DEG_TO_RAD
    return (deg % 360.0 + 360.0) % 360.0
}

/**
 * Wraps a longitude *difference* into `[-180, 180)`.
 *
 * This is what makes the anti-meridian a non-event: a point 1° east of 179.5°E is a difference of
 * +1°, not −359°, so the frame never sees a discontinuity there.
 */
internal fun unwrapLonDeg(deltaLonDeg: Double): Double =
    ((deltaLonDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

/**
 * Wraps an absolute longitude into `[-180, 180)`.
 *
 * Note that exactly +180° normalizes to −180°, which is the same meridian.
 */
internal fun normalizeLonDeg(lonDeg: Double): Double = unwrapLonDeg(lonDeg)

/**
 * Signed perpendicular offset of [p] from the infinite line through [a] and [b], in metres.
 *
 * **Positive means [p] is to the RIGHT of travel along `a → b`**, matching [deltaAngle]'s
 * right-positive convention. The naive planar cross product `ab.x*ap.y - ab.y*ap.x` is
 * *left*-positive, so this negates it. A sign error here is a wrong-direction cue, not a cosmetic
 * bug, and it fails silently — hence [SignedCrossTrackTest].
 *
 * This is the line, not the segment: it does not clamp to the endpoints. Cross-track for a bounded
 * segment is the caller's business once it knows which segment it is on.
 *
 * A zero-length segment has no direction and therefore no side; the magnitude returned is the
 * distance to the degenerate point and the sign is not meaningful.
 */
fun crossTrackRightM(
    p: Vec2,
    a: Vec2,
    b: Vec2,
): Double {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val apx = p.x - a.x
    val apy = p.y - a.y
    val len = sqrt(abx * abx + aby * aby)
    if (len < DEGENERATE_SEGMENT_M) return sqrt(apx * apx + apy * apy)
    // Negated relative to the standard left-positive cross product — see the KDoc.
    return (aby * apx - abx * apy) / len
}

/** Below this length a segment is treated as a point: no direction, so no meaningful side. */
private const val DEGENERATE_SEGMENT_M = 1e-9

/** Unit vector pointing along the true compass bearing [bearingDeg]. Inverse of [bearingDeg]. */
fun unitVec(bearingDeg: Double): Vec2 {
    val rad = bearingDeg * DEG_TO_RAD
    return Vec2(x = sin(rad), y = cos(rad))
}
