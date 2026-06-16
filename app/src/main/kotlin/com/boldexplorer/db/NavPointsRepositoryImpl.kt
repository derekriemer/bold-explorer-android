package com.boldexplorer.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.computeBbox
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.TrailEndRow
import com.boldexplorer.shared.model.TrailPointRow
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.repository.NavPointsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class NavPointsRepositoryImpl
    @Inject
    constructor(
        private val db: BoldExplorerDatabase,
    ) : NavPointsRepository {
        override fun observeTrailEndsForCollection(collectionId: Long): Flow<List<TrailEndRow>> =
            db.navPointsQueries
                .trailEndsForCollection(collectionId)
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { rows ->
                    rows.map { row ->
                        TrailEndRow(
                            waypoint =
                                Waypoint(
                                    id = row.id,
                                    name = row.name,
                                    lat = row.lat,
                                    lon = row.lon,
                                    elevM = row.elev_m,
                                    description = row.description,
                                    createdAt = row.created_at,
                                    kind = row.kind,
                                    tentative = row.tentative != 0L,
                                ),
                            trail =
                                Trail(
                                    id = row.trail_id,
                                    name = row.trail_name,
                                    description = row.trail_description,
                                    createdAt = row.trail_created_at,
                                    tentative = row.trail_tentative != 0L,
                                ),
                            isStart = row.is_start != 0L,
                        )
                    }
                }

        override suspend fun trailPointsInBbox(
            collectionId: Long,
            center: LatLng,
            radiusM: Double,
        ): List<TrailPointRow> {
            val bbox = computeBbox(center, radiusM)
            // Each branch's query generates a distinct row type even with identical columns, so map
            // to TrailPointRow inside the branch to keep a single, well-typed result list.
            return if (bbox.needsLonFilter && bbox.crossing) {
                db.navPointsQueries
                    .trailPointsInBboxCrossing(
                        collectionId = collectionId,
                        latMin = bbox.latMin,
                        latMax = bbox.latMax,
                        lonMin = bbox.lonMin,
                        lonMax = bbox.lonMax,
                    ).executeAsList()
                    .map { TrailPointRow(it.id, it.lat, it.lon, it.trail_id, it.position.toInt()) }
            } else {
                val lonMin = if (bbox.needsLonFilter) bbox.lonMin else -180.0
                val lonMax = if (bbox.needsLonFilter) bbox.lonMax else 180.0
                db.navPointsQueries
                    .trailPointsInBboxNoCrossing(
                        collectionId = collectionId,
                        latMin = bbox.latMin,
                        latMax = bbox.latMax,
                        lonMin = lonMin,
                        lonMax = lonMax,
                    ).executeAsList()
                    .map { TrailPointRow(it.id, it.lat, it.lon, it.trail_id, it.position.toInt()) }
            }
        }
    }
