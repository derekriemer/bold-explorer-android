package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng

/**
 * A position along a [TrailPolyline], produced by projecting a location onto its geometry.
 *
 * This replaces "index of the waypoint I am trying to reach" as the representation of trail
 * progress. [alongTrackM] is the canonical scalar; [segmentIndex] and [fraction] survive only as
 * implementation detail for callers that need to walk the underlying geometry.
 *
 * **Invariant: a `TrailPosition` is only ever produced by [TrailPolyline.project].** Nothing
 * constructs one from dead reckoning. If a `TrailPosition` exists, geometry made it — which is what
 * keeps predicted position from silently becoming committed progress.
 *
 * @property segmentIndex index of the segment this position lies on, `0` for a single-point
 *   polyline that has no segments.
 * @property fraction position within that segment, clamped to `[0, 1]`.
 * @property alongTrackM distance in metres along the trail from its first vertex.
 * @property crossTrackM **signed** perpendicular offset from the trail, in metres. Positive means
 *   the projected location is to the **right** of travel, matching `deltaAngle`'s convention. Where
 *   the nearest point on the trail is a vertex rather than a segment interior, the magnitude is the
 *   distance to that vertex and the sign is the side of the adjacent segment.
 * @property snapped the point on the trail nearest the projected location.
 */
data class TrailPosition(
    val segmentIndex: Int,
    val fraction: Double,
    val alongTrackM: Double,
    val crossTrackM: Double,
    val snapped: LatLng,
    val kind: ProjectionKind = ProjectionKind.Interior,
)

/** Which of the three kinds of answer a projection is. See [TrailPosition.kind]. */
enum class ProjectionKind {
    Interior,
    VertexClamped,
    EndpointClamped,
}
