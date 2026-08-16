package com.boldexplorer.shared.model

data class Waypoint(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val elevM: Double?,
    val description: String?,
    val createdAt: Long,
    val kind: String = KIND_WAYPOINT,
    val tentative: Boolean = false,
) {
    companion object {
        const val KIND_WAYPOINT = "waypoint"
        const val KIND_TRACK_POINT = "track_point"
    }
}

data class Trail(
    val id: Long,
    val name: String,
    val description: String?,
    val createdAt: Long,
    val tentative: Boolean = false,
)

data class TrailWaypoint(
    val id: Long,
    val trailId: Long,
    val waypointId: Long,
    val position: Int,
    val createdAt: Long,
)

data class AutoWaypoint(
    val id: Long,
    val trailId: Long,
    val name: String,
    val segmentIndex: Int,
    val offsetM: Double,
    val lat: Double,
    val lon: Double,
    val createdAt: Long,
)

/**
 * A waypoint attached to a trail without being part of its geometry (ADR 0001, S5b).
 *
 * The [waypoint] is the truth for name, position and description; [segmentIndex] and [offsetM] are
 * derived by projecting it onto the trail and are re-derived whenever that geometry changes. A
 * `TrailWaypoint` by contrast *is* geometry — a vertex of the polyline.
 */
data class TrailAnnotation(
    val id: Long,
    val trailId: Long,
    val waypoint: Waypoint,
    val segmentIndex: Int,
    val offsetM: Double,
    val createdAt: Long,
)

data class Collection(
    val id: Long,
    val name: String,
    val description: String?,
    val createdAt: Long,
)

data class CollectionWaypoint(
    val id: Long,
    val collectionId: Long,
    val waypointId: Long,
    val createdAt: Long,
)

data class CollectionTrail(
    val id: Long,
    val collectionId: Long,
    val trailId: Long,
    val createdAt: Long,
)

data class WaypointWithDistance(
    val waypoint: Waypoint,
    val distanceM: Double,
)

/**
 * One end (start or end) of a trail within a collection, as returned by the lean
 * `trailEndsForCollection` query. Carries the full [waypoint] and [trail] so the UI nav-point
 * list can be built without loading the trail's dense track-point body.
 */
data class TrailEndRow(
    val waypoint: Waypoint,
    val trail: Trail,
    val isStart: Boolean,
)

/**
 * A single trail point (waypoint or track point) inside a spatial bbox snapshot, as returned by
 * `trailPointsInBbox`. Lean by design — only the fields the nearby-trail ranking needs.
 */
data class TrailPointRow(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val trailId: Long,
    val position: Int,
)
