package com.boldexplorer.db

import com.boldexplorer.shared.model.AutoWaypoint
import com.boldexplorer.shared.repository.AutoWaypointRepository
import javax.inject.Inject

class AutoWaypointRepositoryImpl
    @Inject
    constructor(
        private val db: BoldExplorerDatabase,
    ) : AutoWaypointRepository {
        override suspend fun forTrail(trailId: Long): List<AutoWaypoint> =
            db.autoWaypointQueries
                .forTrail(trailId)
                .executeAsList()
                .map { it.toModel() }

        override suspend fun create(
            trailId: Long,
            name: String,
            segmentIndex: Int,
            offsetM: Double,
            lat: Double,
            lon: Double,
        ): Long {
            var newId = 0L
            db.transaction {
                db.autoWaypointQueries.insert(
                    trailId,
                    name,
                    segmentIndex.toLong(),
                    offsetM,
                    lat,
                    lon,
                    System.currentTimeMillis(),
                )
                newId = db.autoWaypointQueries.lastInsertRowId().executeAsOne()
            }
            return newId
        }

        override suspend fun remove(id: Long) {
            db.autoWaypointQueries.remove(id)
        }

        override suspend fun removeForTrail(trailId: Long) {
            db.autoWaypointQueries.removeForTrail(trailId)
        }
    }

private fun com.boldexplorer.db.Auto_waypoint.toModel() =
    AutoWaypoint(
        id = id,
        trailId = trail_id,
        name = name,
        segmentIndex = segment_index.toInt(),
        offsetM = offset_m,
        lat = lat,
        lon = lon,
        createdAt = created_at,
    )
