package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.distanceToSegmentMeters
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.TrailPointRow
import kotlin.math.max

/**
 * The nearest follow-able trail to a GPS fix, as resolved from a per-fix spatial snapshot.
 *
 * @param trailId       the trail the user is near (and can pick up mid-trail).
 * @param distanceM     perpendicular distance from the fix to the trail's nearest line segment.
 * @param nearestIndex  index, within the trail's position-ordered points *in this snapshot*, of the
 *                       closest vertex. Indicative only — the follow path recomputes the true nearest
 *                       over the full trail; direction is always offered both ways (see Phase 4).
 */
data class NearbyTrail(
    val trailId: Long,
    val distanceM: Double,
    val nearestIndex: Int,
)

/**
 * Pure ranking of which trail a GPS fix is "near enough" to follow mid-trail. Handed a per-fix
 * snapshot already bbox-filtered by the repo (`NavPointsRepository.trailPointsInBbox`); it never
 * subscribes to dense data and holds no DB or Flow. Mirrors [NavModeResolver]'s accuracy-scaled
 * geometry so the near-trail gate widens with GPS uncertainty, since mid-trail you may legitimately
 * be off the recorded line.
 */
object NearbyTrailResolver {
    /**
     * @return all trails whose closest segment is within the accuracy-scaled threshold, ordered
     *   nearest-first. Empty when no trail in [points] qualifies.
     */
    fun resolve(
        location: LatLng,
        accuracyM: Double?,
        points: List<TrailPointRow>,
    ): List<NearbyTrail> {
        if (points.isEmpty()) return emptyList()
        val threshold = max(NEAR_TRAIL_FLOOR_M, NEAR_TRAIL_ACCURACY_FACTOR * (accuracyM ?: 0.0))

        return points
            .groupBy { it.trailId }
            .map { (trailId, rows) -> nearestForTrail(trailId, rows, location) }
            .filter { it.distanceM <= threshold }
            .sortedBy { it.distanceM }
    }

    /**
     * Closest approach of [location] to one trail's polyline, plus the closest vertex index. A
     * single-point trail degrades to the point distance; a multi-point trail takes the minimum over
     * its consecutive segments.
     */
    private fun nearestForTrail(
        trailId: Long,
        rows: List<TrailPointRow>,
        location: LatLng,
    ): NearbyTrail {
        val ordered = rows.sortedBy { it.position }

        var nearestIndex = 0
        var nearestVertexM = Double.MAX_VALUE
        ordered.forEachIndexed { i, row ->
            val d = haversineDistanceMeters(location, LatLng(row.lat, row.lon))
            if (d < nearestVertexM) {
                nearestVertexM = d
                nearestIndex = i
            }
        }

        var nearestSegmentM = nearestVertexM
        for (i in 0 until ordered.size - 1) {
            val a = LatLng(ordered[i].lat, ordered[i].lon)
            val b = LatLng(ordered[i + 1].lat, ordered[i + 1].lon)
            val d = distanceToSegmentMeters(location, a, b)
            if (d < nearestSegmentM) nearestSegmentM = d
        }

        return NearbyTrail(trailId = trailId, distanceM = nearestSegmentM, nearestIndex = nearestIndex)
    }

    /** Minimum distance to a trail's line within which it counts as follow-able, regardless of GPS accuracy. */
    const val NEAR_TRAIL_FLOOR_M = 20.0

    /** Multiplier on reported GPS accuracy when it exceeds [NEAR_TRAIL_FLOOR_M], to widen the near-trail gate. */
    const val NEAR_TRAIL_ACCURACY_FACTOR = 2.0
}
