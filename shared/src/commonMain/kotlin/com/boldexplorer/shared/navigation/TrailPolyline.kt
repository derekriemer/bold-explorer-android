package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.LocalFrame
import com.boldexplorer.shared.geo.Vec2
import com.boldexplorer.shared.geo.crossTrackRightM
import kotlin.math.max
import kotlin.math.min
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

    /**
     * Projects [point] onto this polyline, returning the nearest position on it.
     *
     * @param window optional restriction on `alongTrackM`. Only segments whose cumulative span
     *   intersects the window are considered; `null` scans the whole polyline.
     *
     *   The window is load-bearing twice. It keeps a 10k-point trail viable at 1 Hz, and it is the
     *   mechanism that stops a fix snapping onto a geometrically-near stretch the user has not
     *   walked — the switchback case. Callers that widen it are trading safety for reach and should
     *   do so deliberately.
     *
     * @return the nearest position, or `null` if the window admits no segment. Null is a real
     *   answer, not an error: the caller decides whether that means "uncertain" or "search wider".
     *
     * Ties are broken toward the lower `alongTrackM` so the result is deterministic. Ranking by
     * anything richer — a predicted position, for instance — belongs to the caller, which is the
     * only layer that knows where the user was a moment ago.
     */
    fun project(
        point: LatLng,
        window: ClosedFloatingPointRange<Double>? = null,
    ): TrailPosition? {
        val p = frame.toLocal(point)

        // A single-point polyline has no segments; the only position is the vertex itself.
        if (segmentCount == 0) {
            if (window != null && 0.0 !in window) return null
            val dx = p.x - xs[0]
            val dy = p.y - ys[0]
            return TrailPosition(
                segmentIndex = 0,
                fraction = 0.0,
                alongTrackM = 0.0,
                crossTrackM = sqrt(dx * dx + dy * dy),
                snapped = frame.toLatLng(Vec2(xs[0], ys[0])),
            )
        }

        var best: TrailPosition? = null
        var bestDistM = Double.MAX_VALUE

        for (i in 0 until segmentCount) {
            val segStartM = cumulativeM[i]
            val segLenM = cumulativeM[i + 1] - segStartM

            // Express the window as a range of segment fractions, so a segment straddling the
            // window boundary yields a position *at* the boundary rather than outside it.
            var tMin = 0.0
            var tMax = 1.0
            if (window != null) {
                if (segLenM <= DEGENERATE_SEGMENT_M) {
                    if (segStartM !in window) continue
                } else {
                    tMin = max(0.0, (window.start - segStartM) / segLenM)
                    tMax = min(1.0, (window.endInclusive - segStartM) / segLenM)
                    if (tMin > tMax) continue // segment lies wholly outside the window
                }
            }

            val ax = xs[i]
            val ay = ys[i]
            val abx = xs[i + 1] - ax
            val aby = ys[i + 1] - ay
            val len2 = abx * abx + aby * aby
            val tRaw = if (len2 <= DEGENERATE_SEGMENT_M) 0.0 else ((p.x - ax) * abx + (p.y - ay) * aby) / len2
            val t = tRaw.coerceIn(tMin, tMax)

            val footX = ax + t * abx
            val footY = ay + t * aby
            val dx = p.x - footX
            val dy = p.y - footY
            val distM = sqrt(dx * dx + dy * dy)

            // Strict `<` keeps the first winner, so ties resolve toward the lower alongTrackM.
            if (distM < bestDistM) {
                bestDistM = distM
                // Magnitude is the distance to the nearest point (which may be an endpoint);
                // the sign is which side of the segment the fix lies on.
                val side = crossTrackRightM(p, Vec2(ax, ay), Vec2(xs[i + 1], ys[i + 1]))
                best =
                    TrailPosition(
                        segmentIndex = i,
                        fraction = t,
                        alongTrackM = segStartM + t * segLenM,
                        crossTrackM = if (side < 0.0) -distM else distM,
                        snapped = frame.toLatLng(Vec2(footX, footY)),
                    )
            }
        }
        return best
    }

    private companion object {
        /** Below this length a segment carries no direction and is treated as a point. */
        const val DEGENERATE_SEGMENT_M = 1e-9
    }
}
