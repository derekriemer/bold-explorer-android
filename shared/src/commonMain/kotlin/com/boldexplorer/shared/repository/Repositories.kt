package com.boldexplorer.shared.repository

import com.boldexplorer.shared.geo.DEFAULT_BBOX_RADIUS_M
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.*
import com.boldexplorer.shared.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import com.boldexplorer.shared.model.Collection as ExplorerCollection

interface WaypointRepository {
    fun observeAll(): Flow<List<Waypoint>>

    fun observeForTrail(trailId: Long): Flow<List<Waypoint>>

    suspend fun getAll(): List<Waypoint>

    suspend fun getById(id: Long): Waypoint?

    /**
     * Create a user waypoint that belongs to [collectionId]. Inserts the waypoint and its
     * collection membership atomically — every `kind=waypoint` row has ≥1 collection (invariant).
     */
    suspend fun create(
        collectionId: Long,
        name: String,
        lat: Double,
        lon: Double,
        elevM: Double?,
        description: String?,
        tentative: Boolean = false,
    ): Long

    /**
     * Create a `kind=track_point` waypoint that belongs to a trail (via `trail_waypoint`), NOT a
     * collection. Track points derive their collection through the trail; they never get a
     * `collection_waypoint` row.
     */
    suspend fun createTrackPoint(
        trailId: Long,
        name: String,
        lat: Double,
        lon: Double,
        elevM: Double? = null,
        position: Int? = null,
    ): Long

    suspend fun update(
        id: Long,
        name: String? = null,
        lat: Double? = null,
        lon: Double? = null,
        elevM: Double? = null,
        description: String? = null,
    )

    suspend fun remove(id: Long)

    suspend fun forTrail(trailId: Long): List<Waypoint>

    suspend fun withDistanceFrom(
        lat: Double,
        lon: Double,
        trailId: Long? = null,
        limit: Int? = null,
    ): List<WaypointWithDistance>

    suspend fun attach(
        trailId: Long,
        waypointId: Long,
        position: Int? = null,
    )

    suspend fun detach(
        trailId: Long,
        waypointId: Long,
    )

    suspend fun setPosition(
        trailId: Long,
        waypointId: Long,
        position: Int,
    )
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

    /** Create a trail that belongs to [collectionId]; inserts trail + collection membership atomically. */
    suspend fun create(
        collectionId: Long,
        name: String,
        description: String?,
        tentative: Boolean = false,
    ): Long

    suspend fun update(
        id: Long,
        name: String? = null,
        description: String? = null,
    )

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

    suspend fun create(
        name: String,
        description: String?,
    ): Long

    suspend fun rename(
        id: Long,
        name: String,
    )

    suspend fun remove(id: Long)

    suspend fun waypointsForCollection(collectionId: Long): List<Waypoint>

    suspend fun trailsForCollection(collectionId: Long): List<Trail>

    suspend fun collectionsForWaypoint(waypointId: Long): List<ExplorerCollection>

    suspend fun attachWaypoint(
        collectionId: Long,
        waypointId: Long,
    )

    suspend fun detachWaypoint(
        collectionId: Long,
        waypointId: Long,
    )

    suspend fun attachTrail(
        collectionId: Long,
        trailId: Long,
    )

    suspend fun detachTrail(
        collectionId: Long,
        trailId: Long,
    )
}

/**
 * Lean spatial/positional lookups for GPS-screen navigation. Kept separate from trail CRUD so the
 * hot navigation path never loads dense track-point bodies just to find trail ends or nearby points.
 */
interface NavPointsRepository {
    /**
     * Start/end of every trail in [collectionId], reactive. Re-emits when any relevant table
     * changes (e.g. an auto-record track-point insert shifts a trail's MAX position → its end moves).
     * ≤2 rows per trail; never materializes interior track points.
     */
    fun observeTrailEndsForCollection(collectionId: Long): Flow<List<TrailEndRow>>

    /**
     * One-shot snapshot of trail points (waypoints + track points) within [radiusM] of [center],
     * scoped to [collectionId]. Used per GPS fix for mid-trail follow; bbox-bounded, not a Flow.
     */
    suspend fun trailPointsInBbox(
        collectionId: Long,
        center: LatLng,
        radiusM: Double = DEFAULT_BBOX_RADIUS_M,
    ): List<TrailPointRow>
}

interface AutoWaypointRepository {
    suspend fun forTrail(trailId: Long): List<AutoWaypoint>

    suspend fun create(
        trailId: Long,
        name: String,
        segmentIndex: Int,
        offsetM: Double,
        lat: Double,
        lon: Double,
    ): Long

    suspend fun remove(id: Long)

    suspend fun removeForTrail(trailId: Long)
}

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>

    suspend fun load(): AppSettings

    suspend fun save(settings: AppSettings)
}
