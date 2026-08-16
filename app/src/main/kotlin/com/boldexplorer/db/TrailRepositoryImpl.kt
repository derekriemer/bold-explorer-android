package com.boldexplorer.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.TrailNamedPoint
import com.boldexplorer.shared.model.TrailWaypoint
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.repository.TrailRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class TrailRepositoryImpl
    @Inject
    constructor(
        private val db: BoldExplorerDatabase,
    ) : TrailRepository {
        override fun observeAll(): Flow<List<Trail>> =
            db.trailQueries
                .getAll()
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { list -> list.map { it.toModel() } }

        override fun observeWaypointsForTrail(trailId: Long): Flow<List<Waypoint>> =
            db.waypointQueries
                .forTrail(trailId)
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { list -> list.map { it.toModel() } }

        override fun observeNamedWaypointsForTrail(trailId: Long): Flow<List<TrailNamedPoint>> =
            db.waypointQueries
                .namedWaypointsForTrail(trailId)
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { list -> list.map { it.toNamedPoint() } }

        override fun observeTrackPointCountForTrail(trailId: Long): Flow<Long> =
            db.waypointQueries
                .trackPointCountForTrail(trailId)
                .asFlow()
                .mapToOne(Dispatchers.IO)

        override fun observeTrackPointsForTrail(trailId: Long): Flow<List<Waypoint>> =
            db.waypointQueries
                .trackPointsForTrail(trailId)
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { list -> list.map { it.toModel() } }

        override suspend fun getAll(): List<Trail> =
            db.trailQueries
                .getAll()
                .executeAsList()
                .map { it.toModel() }

        override suspend fun getById(id: Long): Trail? =
            db.trailQueries
                .getById(id)
                .executeAsOneOrNull()
                ?.toModel()

        override suspend fun create(
            collectionId: Long,
            name: String,
            description: String?,
            tentative: Boolean,
        ): Long {
            var newId = 0L
            val now = System.currentTimeMillis()
            db.transaction {
                db.trailQueries.insert(name, description, now, if (tentative) 1L else 0L)
                newId = db.trailQueries.lastInsertRowId().executeAsOne()
                db.collectionTrailQueries.insert(collectionId, newId, now)
            }
            return newId
        }

        override suspend fun update(
            id: Long,
            name: String?,
            description: String?,
        ) {
            val current = db.trailQueries.getById(id).executeAsOneOrNull() ?: return
            db.trailQueries.updateFull(
                name = name ?: current.name,
                description = description ?: current.description,
                id = id,
            )
        }

        override suspend fun remove(id: Long) {
            db.transaction {
                db.trailWaypointQueries.deleteForTrail(id)
                db.collectionTrailQueries.deleteForTrail(id)
                db.autoWaypointQueries.removeForTrail(id)
                // An annotation belongs to the trail, not to the waypoint it names: the waypoint
                // outlives the trail, the link does not. Left behind it is a row referencing a
                // trail that is gone.
                db.trailAnnotationQueries.removeForTrail(id)
                db.trailQueries.remove(id)
            }
        }

        override suspend fun waypointsForTrail(trailId: Long): List<Waypoint> =
            db.waypointQueries
                .forTrail(trailId)
                .executeAsList()
                .map { it.toModel() }

        override suspend fun trailWaypointsOrdered(trailId: Long): List<TrailWaypoint> =
            db.trailWaypointQueries
                .getByTrail(trailId)
                .executeAsList()
                .map { it.toModel() }
    }

private fun com.boldexplorer.db.Trail.toModel() =
    Trail(
        id = id,
        name = name,
        description = description,
        createdAt = created_at,
        tentative = tentative != 0L,
    )

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
        tentative = tentative != 0L,
    )

private fun NamedWaypointsForTrail.toNamedPoint() =
    TrailNamedPoint(
        waypoint =
            Waypoint(
                id = id,
                name = name,
                lat = lat,
                lon = lon,
                elevM = elev_m,
                description = description,
                createdAt = created_at,
                kind = kind,
                tentative = tentative != 0L,
            ),
        isAnnotation = is_annotation != 0L,
    )

private fun com.boldexplorer.db.Trail_waypoint.toModel() =
    TrailWaypoint(
        id = id,
        trailId = trail_id,
        waypointId = waypoint_id,
        position = position.toInt(),
        createdAt = created_at,
    )
