package com.boldexplorer.shared.repository

import com.boldexplorer.shared.model.*

interface WaypointRepository {
    suspend fun getAll(): List<Waypoint>
    suspend fun getById(id: Long): Waypoint?
    suspend fun create(name: String, lat: Double, lon: Double, elevM: Double?, description: String?): Long
    suspend fun update(id: Long, name: String? = null, lat: Double? = null, lon: Double? = null, elevM: Double? = null, description: String? = null)
    suspend fun remove(id: Long)
    suspend fun forTrail(trailId: Long): List<Waypoint>
    suspend fun withDistanceFrom(lat: Double, lon: Double, trailId: Long? = null, limit: Int? = null): List<WaypointWithDistance>
    suspend fun attach(trailId: Long, waypointId: Long, position: Int? = null)
    suspend fun detach(trailId: Long, waypointId: Long)
    suspend fun setPosition(trailId: Long, waypointId: Long, position: Int)
}

interface TrailRepository {
    suspend fun getAll(): List<Trail>
    suspend fun getById(id: Long): Trail?
    suspend fun create(name: String, description: String?): Long
    suspend fun update(id: Long, name: String? = null, description: String? = null)
    suspend fun remove(id: Long)
    suspend fun waypointsForTrail(trailId: Long): List<Waypoint>
    suspend fun trailWaypointsOrdered(trailId: Long): List<TrailWaypoint>
}

interface CollectionRepository {
    suspend fun getAll(): List<Collection>
    suspend fun getById(id: Long): Collection?
    suspend fun create(name: String, description: String?): Long
    suspend fun remove(id: Long)
    suspend fun waypointsForCollection(collectionId: Long): List<Waypoint>
    suspend fun trailsForCollection(collectionId: Long): List<Trail>
    suspend fun attachWaypoint(collectionId: Long, waypointId: Long)
    suspend fun detachWaypoint(collectionId: Long, waypointId: Long)
    suspend fun attachTrail(collectionId: Long, trailId: Long)
    suspend fun detachTrail(collectionId: Long, trailId: Long)
}

interface AutoWaypointRepository {
    suspend fun forTrail(trailId: Long): List<AutoWaypoint>
    suspend fun create(trailId: Long, name: String, segmentIndex: Int, offsetM: Double, lat: Double, lon: Double): Long
    suspend fun remove(id: Long)
    suspend fun removeForTrail(trailId: Long)
}

interface SettingsRepository {
    suspend fun load(): com.boldexplorer.shared.settings.AppSettings
    suspend fun save(settings: com.boldexplorer.shared.settings.AppSettings)
}
