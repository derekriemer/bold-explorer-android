package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.LocalFrame
import com.boldexplorer.shared.geo.Vec2
import com.boldexplorer.shared.geo.bearingDeg
import com.boldexplorer.shared.geo.crossTrackRightM
import kotlin.math.abs
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
                kind = ProjectionKind.EndpointClamped,
            )
        }

        var best: TrailPosition? = null
        var bestDistM = Double.MAX_VALUE

        for (i in 0 until segmentCount) {
            val here = projectOnSegment(p, i, window) ?: continue
            val distM = abs(here.crossTrackM)
            // Strict `<` keeps the first winner, so ties resolve toward the lower alongTrackM.
            if (distM < bestDistM) {
                bestDistM = distM
                best = here
            }
        }
        return best
    }

    /**
     * How far [position] sits from the start of its own segment.
     *
     * The storage form for a trail annotation (ADR 0001, S5b): a segment index plus an offset
     * within it, rather than a single along-track scalar. Both say the same thing about a given
     * geometry — `cumulativeM[segmentIndex] + offsetM` is the along-track distance — but the pair
     * degrades more locally when the geometry is edited, which is the case a stored annotation has
     * to survive.
     */
    fun offsetInSegmentM(position: TrailPosition): Double = position.alongTrackM - cumulativeM[position.segmentIndex]

    /**
     * Every position on this polyline that is a *local* minimum of distance to [point], ordered
     * nearest first.
     *
     * Where [project] returns only the winner, this returns its rivals too — which is what the
     * reacquisition ladder needs in order to record a best-rejected candidate, to break ties toward
     * a predicted position, and to choose an end of a loop by travel direction. A decision made
     * without seeing the runner-up cannot be explained afterwards from a log.
     *
     * One candidate per *stretch* of trail, not per segment. Vertex density is an artefact of how
     * the trail was recorded — an arm of a switchback may be one segment or fifty — so enumerating
     * segments would swamp the genuine rival with near-duplicates of the winner and make the result
     * density-dependent. A local minimum is exactly "a place the user could plausibly be".
     *
     * No relevance cutoff is applied: a stretch 100 m away is still somewhere the user could be,
     * and it simply ranks last. Callers that only care about plausible rivals should gate on
     * `crossTrackM` themselves rather than have a threshold baked in here.
     *
     * @param window as [project] — segments outside it are not considered at all. A window that
     *   excludes a middle stretch also breaks continuity there, so the stretches either side are
     *   enumerated independently rather than merged across the gap.
     */
    fun candidates(
        point: LatLng,
        window: ClosedFloatingPointRange<Double>? = null,
    ): List<TrailPosition> {
        // A single-point polyline has exactly one position, so there is nothing to rank.
        if (segmentCount == 0) return listOfNotNull(project(point, window))

        val p = frame.toLocal(point)
        val found = mutableListOf<TrailPosition>()

        // Walk the trail in along-track order tracking whether distance is currently falling. A
        // fall followed by a rise brackets a local minimum; the minimum itself is the last position
        // seen before the rise began.
        var falling = true
        var bestPos: TrailPosition? = null
        var bestDistM = Double.MAX_VALUE
        var previousIndex = Int.MIN_VALUE

        fun endStretch() {
            if (falling) bestPos?.let { found.add(it) }
            falling = true
            bestPos = null
            bestDistM = Double.MAX_VALUE
        }

        for (i in 0 until segmentCount) {
            val here = projectOnSegment(p, i, window) ?: continue

            // A window-excluded segment breaks the trail into disconnected stretches; distance
            // cannot be compared across the gap, so the previous stretch ends here.
            if (i != previousIndex + 1) endStretch()
            previousIndex = i

            val distM = abs(here.crossTrackM)
            if (distM <= bestDistM) {
                // Still falling, or flat — a plateau across a vertex is one minimum, not two.
                bestDistM = distM
                bestPos = here
                falling = true
            } else {
                if (falling) found.add(bestPos!!)
                // Keep tracking the rise so that a later fall is recognised as a new stretch.
                falling = false
                bestDistM = distM
                bestPos = here
            }
        }
        endStretch()

        return found.sortedBy { abs(it.crossTrackM) }
    }

    /**
     * Nearest position to [p] on segment [i], or `null` if [window] excludes that segment.
     *
     * Shared by [project] and [candidates] so the two can never disagree about where a segment's
     * nearest point is or what it is called in along-track coordinates.
     */
    private fun projectOnSegment(
        p: Vec2,
        i: Int,
        window: ClosedFloatingPointRange<Double>?,
    ): TrailPosition? {
        val segStartM = cumulativeM[i]
        val segLenM = cumulativeM[i + 1] - segStartM

        // Express the window as a range of segment fractions, so a segment straddling the
        // window boundary yields a position *at* the boundary rather than outside it.
        var tMin = 0.0
        var tMax = 1.0
        if (window != null) {
            if (segLenM <= DEGENERATE_SEGMENT_M) {
                if (segStartM !in window) return null
            } else {
                tMin = max(0.0, (window.start - segStartM) / segLenM)
                tMax = min(1.0, (window.endInclusive - segStartM) / segLenM)
                if (tMin > tMax) return null // segment lies wholly outside the window
            }
        }

        val ax = xs[i]
        val ay = ys[i]
        val abx = xs[i + 1] - ax
        val aby = ys[i + 1] - ay
        val len2 = abx * abx + aby * aby
        val tRaw = if (len2 <= DEGENERATE_SEGMENT_M) 0.0 else ((p.x - ax) * abx + (p.y - ay) * aby) / len2
        val t = tRaw.coerceIn(tMin, tMax)

        // Which kind of answer this is. The test is against the *geometric* bounds, not against
        // tMin/tMax: a projection pulled back by the search window landed on a window boundary, not
        // on a vertex, and calling that a vertex clamp would make the kind depend on how wide the
        // caller's window happened to be.
        val kind =
            when {
                tRaw <= 0.0 && t == 0.0 -> kindOfVertex(i)
                tRaw >= 1.0 && t == 1.0 -> kindOfVertex(i + 1)
                else -> ProjectionKind.Interior
            }

        val footX = ax + t * abx
        val footY = ay + t * aby
        val dx = p.x - footX
        val dy = p.y - footY
        val distM = sqrt(dx * dx + dy * dy)

        // Magnitude is the distance to the nearest point (which may be an endpoint); the sign is
        // which side of the segment the fix lies on.
        val side = crossTrackRightM(p, Vec2(ax, ay), Vec2(xs[i + 1], ys[i + 1]))
        return TrailPosition(
            segmentIndex = i,
            fraction = t,
            alongTrackM = segStartM + t * segLenM,
            crossTrackM = if (side < 0.0) -distM else distM,
            snapped = frame.toLatLng(Vec2(footX, footY)),
            kind = kind,
        )
    }

    /**
     * A clamp at the first or last vertex is [ProjectionKind.EndpointClamped]; anywhere else it is
     * [ProjectionKind.VertexClamped].
     *
     * The distinction is load-bearing rather than cosmetic. Both are degenerate — a region of ground
     * maps to one along-track value — but "before the start" and "past the end" are real states the
     * app already relies on, notably when acquiring a trail from slightly before its head. Only an
     * interior vertex represents the apex wedge, where the along-track answer is not merely coarse
     * but meaningless.
     */
    private fun kindOfVertex(vertexIndex: Int): ProjectionKind {
        // By *position*, not by index. A trail can end with duplicated points — GPS recorders emit
        // them, and resampling can leave a final segment micrometres long. The last segment then
        // has no length, both clamps tie on cross-track, and the tie-break keeps the lower
        // alongTrackM: the second-to-last vertex. Comparing indices called that interior, so
        // walking past the end looked like an apex wedge, which the vertex accept radius rejects —
        // leaving the tracker Uncertain at the one place completion has to be decided.
        val atM = cumulativeM[vertexIndex]
        return if (atM <= ENDPOINT_TOLERANCE_M || atM >= totalLengthM - ENDPOINT_TOLERANCE_M) {
            ProjectionKind.EndpointClamped
        } else {
            ProjectionKind.VertexClamped
        }
    }

    /**
     * The point on the trail at [alongTrackM] metres from the start, clamped to the trail's extent.
     *
     * The inverse of the `alongTrackM` half of [project]: where [project] answers "where am I on
     * this trail", this answers "what is at this distance along it".
     */
    fun positionAt(alongTrackM: Double): LatLng = frame.toLatLng(localAt(alongTrackM))

    /**
     * Largest segment index whose start is at or before [alongTrackM]. Binary search, hand-rolled
     * because the common stdlib has no `binarySearch` for [DoubleArray].
     */
    private fun segmentIndexFor(alongTrackM: Double): Int {
        var lo = 0
        var hi = segmentCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cumulativeM[mid] <= alongTrackM) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** [positionAt] without leaving the frame — the form the curvature accessors want. */
    private fun localAt(alongTrackM: Double): Vec2 {
        if (segmentCount == 0) return Vec2(xs[0], ys[0])
        val a = alongTrackM.coerceIn(0.0, totalLengthM)
        val i = segmentIndexFor(a)
        val segLenM = cumulativeM[i + 1] - cumulativeM[i]
        val t = if (segLenM <= DEGENERATE_SEGMENT_M) 0.0 else ((a - cumulativeM[i]) / segLenM).coerceIn(0.0, 1.0)
        return Vec2(xs[i] + t * (xs[i + 1] - xs[i]), ys[i] + t * (ys[i + 1] - ys[i]))
    }

    /**
     * True bearing of the chord centred at [alongTrackM] and spanning [baselineM] metres.
     *
     * This is the density-invariant replacement for "bearing of the segment I am on". A single
     * segment bearing at 2 m vertex spacing is nearly random once recording noise is included, which
     * would make every downstream decision depend on how densely the trail happened to be recorded.
     * Measuring across a fixed *physical* baseline removes that dependence.
     *
     * The chord is truncated at the trail's ends rather than extrapolated.
     *
     * @return the bearing, or `null` if the trail has no length to measure across.
     */
    fun chordBearingAt(
        alongTrackM: Double,
        baselineM: Double,
    ): Double? {
        if (totalLengthM <= DEGENERATE_SEGMENT_M) return null
        val half = baselineM / 2.0
        val startM = (alongTrackM - half).coerceIn(0.0, totalLengthM)
        val endM = (alongTrackM + half).coerceIn(0.0, totalLengthM)
        if (endM - startM <= DEGENERATE_SEGMENT_M) return null
        val a = localAt(startM)
        val b = localAt(endM)
        return bearingDeg(Vec2(b.x - a.x, b.y - a.y))
    }

    /**
     * Maximum perpendicular departure of the trail from its own chord, over
     * `[alongTrackM, alongTrackM + lookaheadM]`.
     *
     * Sagitta is the primary curvature measure rather than accumulated turn angle, for two reasons.
     * Summing `|Δbearing|` per vertex grows without bound as density increases, so it is not
     * density-invariant at all. And *signed* net turn, while invariant, reads zero across an S-bend
     * that returns to its original heading — a path that is plainly not straight. Sagitta catches
     * both, and it falls straight out of the cross-track machinery already present.
     *
     * @return departure in metres; zero for a straight stretch.
     */
    fun sagittaOver(
        alongTrackM: Double,
        lookaheadM: Double,
    ): Double {
        val startM = alongTrackM.coerceIn(0.0, totalLengthM)
        val endM = (alongTrackM + lookaheadM).coerceIn(0.0, totalLengthM)
        if (endM - startM <= DEGENERATE_SEGMENT_M) return 0.0

        val a = localAt(startM)
        val b = localAt(endM)
        // A polyline's greatest departure from a chord always occurs at a vertex, so checking the
        // vertices strictly inside the window is exact rather than a sampled approximation — and it
        // is what makes the result density-invariant: adding vertices along a straight stretch adds
        // no new extremum.
        var worstM = 0.0
        for (i in 0 until size) {
            val c = cumulativeM[i]
            if (c <= startM || c >= endM) continue
            val d = abs(crossTrackRightM(Vec2(xs[i], ys[i]), a, b))
            if (d > worstM) worstM = d
        }
        return worstM
    }

    private companion object {
        /** Below this length a segment carries no direction and is treated as a point. */
        const val DEGENERATE_SEGMENT_M = 1e-9

        /**
         * How close to a terminus a vertex must be to count as that terminus.
         *
         * Well below anything navigationally meaningful, and well above the float noise and
         * duplicate points that make the last vertex ambiguous.
         */
        const val ENDPOINT_TOLERANCE_M = 0.5
    }
}
