package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.LocalFrame
import kotlin.math.sqrt

/**
 * A trail's geometry as a continuous polyline with a cumulative along-trail coordinate.
 *
 * This is the object that replaces "index into a list of waypoints" as the representation of where
 * a user is on a trail. Progress is `alongTrackM`, a scalar in metres from the first vertex;
 * segment indices survive only as an implementation detail.
 *
 * ## Coordinate system
 *
 * Points are converted **once** at construction into the [frame]'s planar east/north space and held
 * as parallel [DoubleArray]s rather than as objects — a GPX import applies no decimation, so a trail
 * can carry 10k+ points and the global scan needs to stay cache-friendly.
 *
 * [cumulativeM] is computed **in-frame**, so it and any `alongTrackM` derived from a projection
 * share one coordinate system. Measuring lengths by haversine while projecting in-frame is the seam
 * that exists in the current code, and rebuilding it one layer up would make density-equivalence
 * failures unattributable.
 *
 * ## Lifetime
 *
 * Immutable for a session. A geometry change means constructing a new polyline and restarting the
 * follow — [cumulativeM] would otherwise go silently stale.
 *
 * @param points ordered trail geometry; must be non-empty.
 * @param frame the coordinate frame. Defaults to one anchored at this polyline's own centroid, but
 *   is a parameter so several polylines can share one — positions from different trails are only
 *   comparable inside a common frame, which junction geometry (#59) will require.
 */
class TrailPolyline(
    points: List<LatLng>,
    val frame: LocalFrame = LocalFrame.centredOn(points),
) {
    init {
        require(points.isNotEmpty()) { "A TrailPolyline needs at least one point" }
    }

    /** Planar east coordinates, metres from [frame]'s origin. Parallel to [ys]. */
    private val xs: DoubleArray = DoubleArray(points.size)

    /** Planar north coordinates, metres from [frame]'s origin. Parallel to [xs]. */
    private val ys: DoubleArray = DoubleArray(points.size)

    /**
     * Along-trail distance in metres from the first vertex to each vertex.
     *
     * `cumulativeM[0]` is always 0. Monotonically non-decreasing — repeated fixes in a recorded
     * track produce zero-length segments, which contribute nothing rather than breaking the order.
     */
    val cumulativeM: DoubleArray = DoubleArray(points.size)

    init {
        for (i in points.indices) {
            val v = frame.toLocal(points[i])
            xs[i] = v.x
            ys[i] = v.y
            if (i > 0) {
                val dx = xs[i] - xs[i - 1]
                val dy = ys[i] - ys[i - 1]
                cumulativeM[i] = cumulativeM[i - 1] + sqrt(dx * dx + dy * dy)
            }
        }
    }

    /** Number of vertices. */
    val size: Int get() = cumulativeM.size

    /** Number of segments — one fewer than [size], and zero for a single-point polyline. */
    val segmentCount: Int get() = (size - 1).coerceAtLeast(0)

    /** Total along-trail length in metres. Zero for a single-point polyline. */
    val totalLengthM: Double get() = cumulativeM[size - 1]
}
