package com.boldexplorer.shared.repository

import com.boldexplorer.shared.model.*
import com.boldexplorer.shared.model.Collection as ExplorerCollection
import com.boldexplorer.shared.settings.AppSettings
import kotlinx.coroutines.flow.Flow

interface WaypointRepository {
    fun observeAll(): Flow<List<Waypoint>>
    fun observeForTrail(trailId: Long): Flow<List<Waypoint>>
    suspend fun getAll(): List<Waypoint>
    suspend fun getById(id: Long): Waypoint?
    suspend fun create(name: String, lat: Double, lon: Double, elevM: Double?, description: String?, kind: String = Waypoint.KIND_WAYPOINT): Long
    suspend fun update(id: Long, name: String? = null, lat: Double? = null, lon: Double? = null, elevM: Double? = null, description: String? = null)
    suspend fun remove(id: Long)
    suspend fun forTrail(trailId: Long): List<Waypoint>
    suspend fun withDistanceFrom(lat: Double, lon: Double, trailId: Long? = null, limit: Int? = null): List<WaypointWithDistance>
    suspend fun attach(trailId: Long, waypointId: Long, position: Int? = null)
    suspend fun detach(trailId: Long, waypointId: Long)
    suspend fun setPosition(trailId: Long, waypointId: Long, position: Int)
}

interface TrailRepository {
    fun observeAll(): Flow<List<Trail>>
    /** All points (waypoints + track points) in order — used for trail following. */
    fun observeWaypointsForTrail(trailId: Long): Flow<List<Waypoint>>
    /** Named waypoints only (kind=waypoint) — used for trail editor UI. */
    fun observeNamedWaypointsForTrail(trailId: Long): Flow<List<Waypoint>>
    /** Count of track points — cheap summary query. */
    fun observeTrackPointCountForTrail(trailId: Long): Flow<Long>
    /** Track points only — fetched lazily when user requests them. */
    fun observeTrackPointsForTrail(trailId: Long): Flow<List<Waypoint>>
    suspend fun getAll(): List<Trail>
    suspend fun getById(id: Long): Trail?
    suspend fun create(name: String, description: String?): Long
    suspend fun update(id: Long, name: String? = null, description: String? = null)
    suspend fun remove(id: Long)
    suspend fun waypointsForTrail(trailId: Long): List<Waypoint>
    suspend fun trailWaypointsOrdered(trailId: Long): List<TrailWaypoint>
}

interface CollectionRepository {
    fun observeAll(): Flow<List<ExplorerCollection>>
    fun observeWaypointsForCollection(collectionId: Long): Flow<List<Waypoint>>
    fun observeTrailsForCollection(collectionId: Long): Flow<List<Trail>>
    suspend fun getAll(): List<ExplorerCollection>
    suspend fun getById(id: Long): ExplorerCollection?
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
    fun observeSettings(): Flow<AppSettings>
    suspend fun load(): AppSettings
    suspend fun save(settings: AppSettings)
}
