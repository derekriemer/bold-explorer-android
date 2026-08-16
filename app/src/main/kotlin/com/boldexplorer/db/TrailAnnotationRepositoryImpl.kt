package com.boldexplorer.db

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.TrailAnnotation
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.navigation.TrailPolyline
import com.boldexplorer.shared.repository.TrailAnnotationFix
import com.boldexplorer.shared.repository.TrailAnnotationRepository
import javax.inject.Inject

/**
 * Annotations are stored, but where they sit along a trail is never stored *by a caller* — it is
 * derived here, from the trail's geometry and the waypoint's own position, every time either could
 * have changed. That is the whole point of S5b: a point attached to a trail can no longer state a
 * position it has no right to.
 */
class TrailAnnotationRepositoryImpl
    @Inject
    constructor(
        private val db: BoldExplorerDatabase,
    ) : TrailAnnotationRepository {
        override suspend fun forTrail(trailId: Long): List<TrailAnnotation> =
            db.trailAnnotationQueries
                .forTrail(trailId)
                .executeAsList()
                .map { row ->
                    TrailAnnotation(
                        id = row.id,
                        trailId = row.trail_id,
                        waypoint =
                            Waypoint(
                                id = row.waypoint_id,
                                name = row.name,
                                lat = row.lat,
                                lon = row.lon,
                                elevM = row.elev_m,
                                description = row.description,
                                createdAt = row.waypoint_created_at,
                                kind = row.kind,
                                tentative = row.tentative != 0L,
                            ),
                        segmentIndex = row.segment_index.toInt(),
                        offsetM = row.offset_m,
                        createdAt = row.created_at,
                    )
                }

        override suspend fun annotate(
            trailId: Long,
            waypointId: Long,
        ): TrailAnnotationFix? {
            val waypoint = db.waypointQueries.getById(waypointId).executeAsOneOrNull() ?: return null
            val fix = project(trailId, LatLng(waypoint.lat, waypoint.lon)) ?: return null
            db.trailAnnotationQueries.insert(
                trailId,
                waypointId,
                fix.segmentIndex.toLong(),
                fix.offsetM,
                System.currentTimeMillis(),
            )
            return fix
        }

        override suspend fun reproject(trailId: Long) {
            val rows = db.trailAnnotationQueries.rowsForTrail(trailId).executeAsList()
            if (rows.isEmpty()) return
            val polyline = polylineFor(trailId) ?: return
            db.transaction {
                rows.forEach { row ->
                    val waypoint = db.waypointQueries.getById(row.waypoint_id).executeAsOneOrNull() ?: return@forEach
                    val position = polyline.project(LatLng(waypoint.lat, waypoint.lon)) ?: return@forEach
                    db.trailAnnotationQueries.updateProjection(
                        position.segmentIndex.toLong(),
                        polyline.offsetInSegmentM(position),
                        trailId,
                        row.waypoint_id,
                    )
                }
            }
        }

        override suspend fun remove(
            trailId: Long,
            waypointId: Long,
        ) {
            db.trailAnnotationQueries.remove(trailId, waypointId)
        }

        override suspend fun removeForTrail(trailId: Long) {
            db.trailAnnotationQueries.removeForTrail(trailId)
        }

        /** Where [point] lands on [trailId], or null when the trail has no geometry to land on. */
        private fun project(
            trailId: Long,
            point: LatLng,
        ): TrailAnnotationFix? {
            val polyline = polylineFor(trailId) ?: return null
            val position = polyline.project(point) ?: return null
            return TrailAnnotationFix(
                segmentIndex = position.segmentIndex,
                offsetM = polyline.offsetInSegmentM(position),
                crossTrackM = position.crossTrackM,
            )
        }

        /**
         * The trail's geometry, or null when there is not enough of it to project onto.
         *
         * A single point is a position, not a path: projecting onto it would return that vertex for
         * every annotation, which says nothing. Callers read null as "this attachment has to be a
         * vertex", which is also the right answer for a trail whose recording has only just begun.
         */
        private fun polylineFor(trailId: Long): TrailPolyline? {
            val points =
                db.waypointQueries
                    .forTrail(trailId)
                    .executeAsList()
                    .map { LatLng(it.lat, it.lon) }
            return if (points.size < 2) null else TrailPolyline(points)
        }
    }
