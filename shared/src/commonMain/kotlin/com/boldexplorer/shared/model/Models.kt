package com.boldexplorer.shared.model

data class Waypoint(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val elevM: Double?,
    val description: String?,
    val createdAt: Long,
)

data class Trail(
    val id: Long,
    val name: String,
    val description: String?,
    val createdAt: Long,
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
