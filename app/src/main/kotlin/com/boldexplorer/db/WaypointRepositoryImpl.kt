package com.boldexplorer.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.computeBbox
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.model.WaypointWithDistance
import com.boldexplorer.shared.repository.WaypointRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private const val BBOX_CANDIDATE_MULTIPLIER = 3

class WaypointRepositoryImpl
    @Inject
    constructor(
        private val db: BoldExplorerDatabase,
    ) : WaypointRepository {
        override fun observeAll(): Flow<List<Waypoint>> =
            db.waypointQueries
                .getAll()
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { list -> list.map { it.toModel() } }

        override fun observeForTrail(trailId: Long): Flow<List<Waypoint>> =
            db.waypointQueries
                .forTrail(trailId)
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { list -> list.map { it.toModel() } }

        override suspend fun getAll(): List<Waypoint> =
            db.waypointQueries
                .getAll()
                .executeAsList()
                .map { it.toModel() }

        override suspend fun getById(id: Long): Waypoint? =
            db.waypointQueries
                .getById(id)
                .executeAsOneOrNull()
                ?.toModel()

        override suspend fun create(
            name: String,
            lat: Double,
            lon: Double,
            elevM: Double?,
            description: String?,
            kind: String,
        ): Long {
            var newId = 0L
            db.transaction {
                db.waypointQueries.insert(name, lat, lon, elevM, description, System.currentTimeMillis(), kind)
                newId = db.waypointQueries.lastInsertRowId().executeAsOne()
            }
            return newId
        }

        override suspend fun update(
            id: Long,
            name: String?,
            lat: Double?,
            lon: Double?,
            elevM: Double?,
            description: String?,
        ) {
            val current = db.waypointQueries.getById(id).executeAsOneOrNull() ?: return
            db.waypointQueries.updateFull(
                name = name ?: current.name,
                lat = lat ?: current.lat,
                lon = lon ?: current.lon,
                elevM = elevM ?: current.elev_m,
                description = description ?: current.description,
                id = id,
            )
        }

        override suspend fun remove(id: Long) {
            db.transaction {
                db.trailWaypointQueries.deleteForWaypoint(id)
                db.collectionWaypointQueries.deleteForWaypoint(id)
                db.waypointQueries.remove(id)
            }
        }

        override suspend fun forTrail(trailId: Long): List<Waypoint> =
            db.waypointQueries
                .forTrail(trailId)
                .executeAsList()
                .map { it.toModel() }

        override suspend fun withDistanceFrom(
            lat: Double,
            lon: Double,
            trailId: Long?,
            limit: Int?,
        ): List<WaypointWithDistance> {
            val center = LatLng(lat, lon)
            val bbox = computeBbox(center)
            val candidateLimit = (limit ?: Int.MAX_VALUE).toLong() * BBOX_CANDIDATE_MULTIPLIER

            val candidates =
                if (bbox.needsLonFilter && bbox.crossing) {
                    db.waypointQueries
                        .waypointsInBboxCrossing(
                            latMin = bbox.latMin,
                            latMax = bbox.latMax,
                            lonMin = bbox.lonMin,
                            lonMax = bbox.lonMax,
                            centerLat = lat,
                            centerLon = lon,
                            candidateLimit = candidateLimit,
                        ).executeAsList()
                } else {
                    val lonMin = if (bbox.needsLonFilter) bbox.lonMin else -180.0
                    val lonMax = if (bbox.needsLonFilter) bbox.lonMax else 180.0
                    db.waypointQueries
                        .waypointsInBboxNoCrossing(
                            latMin = bbox.latMin,
                            latMax = bbox.latMax,
                            lonMin = lonMin,
                            lonMax = lonMax,
                            centerLat = lat,
                            centerLon = lon,
                            candidateLimit = candidateLimit,
                        ).executeAsList()
                }

            val trailWaypointIds: Set<Long>? =
                if (trailId != null) {
                    db.trailWaypointQueries
                        .getByTrail(trailId)
                        .executeAsList()
                        .map { it.waypoint_id }
                        .toSet()
                } else {
                    null
                }

            return candidates
                .filter { trailWaypointIds == null || it.id in trailWaypointIds }
                .map { row ->
                    WaypointWithDistance(
                        waypoint = row.toModel(),
                        distanceM = haversineDistanceMeters(center, LatLng(row.lat, row.lon)),
                    )
                }.sortedBy { it.distanceM }
                .let { if (limit != null) it.take(limit) else it }
        }

        override suspend fun attach(
            trailId: Long,
            waypointId: Long,
            position: Int?,
        ) {
            db.transaction {
                val pos =
                    if (position == null) {
                        db.trailWaypointQueries
                            .nextPosition(trailId)
                            .executeAsOne()
                            .toInt()
                    } else {
                        db.trailWaypointQueries.shiftUpFromPosition(trailId, position.toLong())
                        position
                    }
                db.trailWaypointQueries.insert(trailId, waypointId, pos.toLong(), System.currentTimeMillis())
            }
        }

        override suspend fun detach(
            trailId: Long,
            waypointId: Long,
        ) {
            db.transaction {
                val pos =
                    db.trailWaypointQueries
                        .getPosition(trailId, waypointId)
                        .executeAsOneOrNull() ?: return@transaction
                db.trailWaypointQueries.delete(trailId, waypointId)
                db.trailWaypointQueries.shiftDownAfterPosition(trailId, pos)
            }
        }

        override suspend fun setPosition(
            trailId: Long,
            waypointId: Long,
            position: Int,
        ) {
            db.transaction {
                val a =
                    db.trailWaypointQueries
                        .getPosition(trailId, waypointId)
                        .executeAsOneOrNull() ?: return@transaction
                val b = position.toLong()
                when {
                    a < b -> db.trailWaypointQueries.shiftDown(trailId, fromPos = a, toPos = b)
                    a > b -> db.trailWaypointQueries.shiftUp(trailId, toPos = b, fromPos = a)
                }
                db.trailWaypointQueries.updatePosition(position = b, trailId = trailId, waypointId = waypointId)
            }
        }
    }

private fun com.boldexplorer.db.Waypoint.toModel() =
    Waypoint(
        id = id,
        name = name,
        lat = lat,
        lon = lon,
        elevM = elev_m,
        description = description,
        createdAt = created_at,
        kind = kind,
    )
