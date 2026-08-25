package com.boldexplorer.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.computeBbox
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.model.WaypointWithDistance
import com.boldexplorer.shared.repository.TrailAnnotationRepository
import com.boldexplorer.shared.repository.TrailAttachment
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
        // Defaulted so the many direct constructions in tests keep working; Dagger passes the bound
        // singleton in production. Attaching has to be able to annotate, and the decision belongs
        // here rather than in five view models that would each have to remember it.
        private val annotations: TrailAnnotationRepository = TrailAnnotationRepositoryImpl(db),
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
            collectionId: Long,
            name: String,
            lat: Double,
            lon: Double,
            elevM: Double?,
            description: String?,
            tentative: Boolean,
        ): Long {
            var newId = 0L
            val now = System.currentTimeMillis()
            db.transaction {
                db.waypointQueries.insert(
                    name = name,
                    lat = lat,
                    lon = lon,
                    elevM = elevM,
                    description = description,
                    createdAt = now,
                    kind = Waypoint.KIND_WAYPOINT,
                    tentative = if (tentative) 1L else 0L,
                )
                newId = db.waypointQueries.lastInsertRowId().executeAsOne()
                db.collectionWaypointQueries.insert(collectionId, newId, now)
            }
            return newId
        }

        override suspend fun createTrackPoint(
            trailId: Long,
            name: String,
            lat: Double,
            lon: Double,
            elevM: Double?,
            position: Int?,
        ): Long {
            var newId = 0L
            val now = System.currentTimeMillis()
            db.transaction {
                db.waypointQueries.insert(
                    name = name,
                    lat = lat,
                    lon = lon,
                    elevM = elevM,
                    description = null,
                    createdAt = now,
                    kind = Waypoint.KIND_TRACK_POINT,
                    tentative = 0L,
                )
                newId = db.waypointQueries.lastInsertRowId().executeAsOne()
                val pos =
                    if (position == null) {
                        db.trailWaypointQueries.nextPosition(trailId).executeAsOne()
                    } else {
                        db.trailWaypointQueries.shiftUpFromPosition(trailId, position.toLong())
                        position.toLong()
                    }
                db.trailWaypointQueries.insert(trailId, newId, pos, now)
            }
            // Recording extends the geometry an annotation was projected onto — most visibly for
            // one clamped to what used to be the end. Cheap: it returns immediately when the trail
            // has no annotations, which is every trail during an ordinary recording.
            annotations.reproject(trailId)
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
            val newLat = lat ?: current.lat
            val newLon = lon ?: current.lon
            db.waypointQueries.updateFull(
                name = name ?: current.name,
                lat = newLat,
                lon = newLon,
                elevM = elevM ?: current.elev_m,
                description = description ?: current.description,
                id = id,
            )
            // A projection is derived from coordinates, so an edit to them invalidates it — whether
            // this point is the annotation that moved or a vertex of the trail one was projected
            // onto. Renames and description edits change neither, and skip the work.
            if (newLat != current.lat || newLon != current.lon) {
                annotations.reprojectForWaypoint(id)
            }
        }

        override suspend fun remove(id: Long) {
            db.transaction {
                db.trailWaypointQueries.deleteForWaypoint(id)
                db.trailAnnotationQueries.removeForWaypoint(id)
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
        ): TrailAttachment {
            // trail_waypoint and trail_annotation both enforce UNIQUE(trail_id, waypoint_id). Callers
            // offering an "attach to trail" choice should already exclude this pair via trailIdsFor;
            // this is the safety net for when that list went stale between offering the choice and
            // the user confirming it, not the primary guard.
            if (trailId in trailIdsFor(waypointId)) return TrailAttachment.AlreadyAttached

            // A recorded trail's geometry is finished; anything arriving afterwards annotates it.
            // Asked of the data rather than of the caller, because the answer is a property of the
            // trail — and because a user marking a waypoint mid-walk should not have to arbitrate a
            // data-model distinction to do it.
            if (position == null && isRecorded(trailId)) {
                annotations.annotate(trailId, waypointId)?.let { return TrailAttachment.Annotation(it) }
                // Falls through only when there is not enough geometry to project onto — a recording
                // that has just begun. A vertex is then the honest answer, not a failure.
            }
            var pos = 0
            db.transaction {
                pos =
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
            annotations.reproject(trailId)
            return TrailAttachment.Vertex(pos)
        }

        override suspend fun detach(
            trailId: Long,
            waypointId: Long,
        ) {
            db.transaction {
                val pos =
                    db.trailWaypointQueries
                        .getPosition(trailId, waypointId)
                        .executeAsOneOrNull()
                if (pos != null) {
                    db.trailWaypointQueries.delete(trailId, waypointId)
                    db.trailWaypointQueries.shiftDownAfterPosition(trailId, pos)
                }
            }
            // Detaching is one gesture to the user whichever way the point was attached.
            annotations.remove(trailId, waypointId)
            annotations.reproject(trailId)
        }

        override suspend fun trailIdsFor(waypointId: Long): Set<Long> {
            val vertexOf = db.trailWaypointQueries.trailsForWaypoint(waypointId).executeAsList()
            val annotated = db.trailAnnotationQueries.trailsForWaypoint(waypointId).executeAsList()
            return (vertexOf + annotated).toSet()
        }

        /**
         * Whether [trailId] was recorded — that is, whether it has track points of its own.
         *
         * The data-level form of "geometry is what the trail was built from". A trail with no track
         * points is a hand-built route: its named waypoints are its geometry, and attaching another
         * one extends it legitimately.
         */
        private fun isRecorded(trailId: Long): Boolean = db.waypointQueries.trackPointCountForTrail(trailId).executeAsOne() > 0L

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
            annotations.reproject(trailId)
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
        tentative = tentative != 0L,
    )
