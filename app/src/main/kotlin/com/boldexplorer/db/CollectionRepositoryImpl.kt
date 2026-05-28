package com.boldexplorer.db

import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.model.Collection as ExplorerCollection
import com.boldexplorer.shared.repository.CollectionRepository
import javax.inject.Inject

class CollectionRepositoryImpl @Inject constructor(
    private val db: BoldExplorerDatabase,
) : CollectionRepository {

    override suspend fun getAll(): List<ExplorerCollection> =
        db.collectionQueries.getAll().executeAsList().map { it.toModel() }

    override suspend fun getById(id: Long): ExplorerCollection? =
        db.collectionQueries.getById(id).executeAsOneOrNull()?.toModel()

    override suspend fun create(name: String, description: String?): Long {
        var newId = 0L
        db.transaction {
            db.collectionQueries.insert(name, description, System.currentTimeMillis())
            newId = db.collectionQueries.lastInsertRowId().executeAsOne()
        }
        return newId
    }

    override suspend fun remove(id: Long) {
        db.transaction {
            db.collectionWaypointQueries.deleteForCollection(id)
            db.collectionTrailQueries.deleteForCollection(id)
            db.collectionQueries.remove(id)
        }
    }

    override suspend fun waypointsForCollection(collectionId: Long): List<Waypoint> =
        db.collectionWaypointQueries.waypointsForCollection(collectionId)
            .executeAsList().map { it.toModel() }

    override suspend fun trailsForCollection(collectionId: Long): List<Trail> =
        db.collectionTrailQueries.trailsForCollection(collectionId)
            .executeAsList().map { it.toModel() }

    override suspend fun attachWaypoint(collectionId: Long, waypointId: Long) {
        db.collectionWaypointQueries.insert(collectionId, waypointId, System.currentTimeMillis())
    }

    override suspend fun detachWaypoint(collectionId: Long, waypointId: Long) {
        db.collectionWaypointQueries.delete(collectionId, waypointId)
    }

    override suspend fun attachTrail(collectionId: Long, trailId: Long) {
        db.collectionTrailQueries.insert(collectionId, trailId, System.currentTimeMillis())
    }

    override suspend fun detachTrail(collectionId: Long, trailId: Long) {
        db.collectionTrailQueries.delete(collectionId, trailId)
    }
}

private fun com.boldexplorer.db.Collection.toModel() = ExplorerCollection(
    id = id,
    name = name,
    description = description,
    createdAt = created_at,
)

private fun com.boldexplorer.db.Waypoint.toModel() = Waypoint(
    id = id,
    name = name,
    lat = lat,
    lon = lon,
    elevM = elev_m,
    description = description,
    createdAt = created_at,
)

private fun com.boldexplorer.db.Trail.toModel() = Trail(
    id = id,
    name = name,
    description = description,
    createdAt = created_at,
)
