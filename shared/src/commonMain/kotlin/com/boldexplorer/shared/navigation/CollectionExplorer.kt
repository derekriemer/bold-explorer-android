package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.deltaAngle
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.geo.initialBearingDeg
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

// A point in a collection — either a standalone waypoint or one end of a trail.
sealed class CollectionPoint {
    abstract val id: Long // unique key: waypointId for Standalone, synthetic for TrailEnd
    abstract val waypoint: Waypoint

    data class Standalone(
        override val waypoint: Waypoint,
    ) : CollectionPoint() {
        override val id: Long get() = waypoint.id
    }

    // isStart=true → first waypoint of trail; isStart=false → last waypoint.
    data class TrailEnd(
        override val waypoint: Waypoint,
        val trail: Trail,
        val isStart: Boolean,
    ) : CollectionPoint() {
        // Synthetic key so start and end of the same trail are distinct entries.
        override val id: Long get() = if (isStart) trail.id * 10_000L + 1 else trail.id * 10_000L + 2
    }
}

/** Human-readable label for a collection point — a waypoint's name, or "Trail (start|end)". */
fun CollectionPoint.displayName(): String =
    when (this) {
        is CollectionPoint.Standalone -> waypoint.name
        is CollectionPoint.TrailEnd -> "${trail.name} (${if (isStart) "start" else "end"})"
    }

sealed class CollectionExplorerState {
    object Idle : CollectionExplorerState()

    data class Active(
        val points: List<CollectionPoint>, // all points, sorted nearest-first
        val target: CollectionPoint?, // current target; null means no target (silence)
        val visitedIds: List<Long>, // capped at VISITED_CAP; newest last
        val autoAdvance: Boolean, // when on, the system picks/re-picks a target whenever there isn't one
        val nearTrailEndM: Double?, // distance to target if it's a TrailEnd, else null
        val proximityAnnouncedIds: Set<Long> = emptySet(), // points already proximity-announced this session
    ) : CollectionExplorerState()
}

sealed class CollectionExplorerEvent {
    /** Target reached; next target already selected (or null if all visited). */
    data class PointReached(
        val reached: CollectionPoint,
        val next: CollectionPoint?,
    ) : CollectionExplorerEvent()

    /** Current target was rejected by the user; next target already selected (or null if all visited). */
    data class TargetSkipped(
        val skipped: CollectionPoint,
        val next: CollectionPoint?,
    ) : CollectionExplorerEvent()

    /** User is within TRAIL_APPROACH_M of a TrailEnd target — surface Follow button. */
    data class NearTrailEnd(
        val trailEnd: CollectionPoint.TrailEnd,
        val distanceM: Double,
    ) : CollectionExplorerEvent()

    /** User passed within PROXIMITY_M of an unvisited non-target point. Fires once per point per session. */
    data class NearbyPoint(
        val point: CollectionPoint,
        val distanceM: Double,
    ) : CollectionExplorerEvent()
}

class CollectionExplorer {
    private val _state = MutableStateFlow<CollectionExplorerState>(CollectionExplorerState.Idle)
    val state: StateFlow<CollectionExplorerState> = _state.asStateFlow()

    /**
     * Load a collection's resolved points and start targeting.
     *
     * Called both for a genuine collection switch (preceded by [stop], so there is no previous
     * Active state to preserve) and for a reactive refresh of the *same* collection's point list
     * (e.g. a new waypoint was saved) — in the latter case the caller must not lose an
     * in-progress target or visited history just because the list changed underneath it.
     * Otherwise a freshly-saved waypoint, created at the user's current GPS fix (so effectively
     * distance 0), would win the next nearest-unvisited auto-target selection and silently
     * replace whatever target was active (issue #7). We preserve target/visited/proximity state
     * from the previous Active state whenever the referenced point ids still exist in the new
     * list; ids are globally unique waypoint/trail-end keys, so stale state from a different
     * collection can never accidentally match.
     */
    fun load(
        points: List<CollectionPoint>,
        autoAdvance: Boolean = false,
    ) {
        if (points.isEmpty()) {
            _state.value = CollectionExplorerState.Idle
            return
        }
        val previous = _state.value as? CollectionExplorerState.Active
        val byId = points.associateBy { it.id }
        _state.value =
            CollectionExplorerState.Active(
                points = points,
                target = previous?.target?.id?.let { byId[it] }, // auto-filled on next GPS fix if still null
                visitedIds = previous?.visitedIds?.filter { it in byId } ?: emptyList(),
                autoAdvance = autoAdvance,
                nearTrailEndM = null, // recomputed on the next GPS fix against the (possibly new) target
                proximityAnnouncedIds = previous?.proximityAnnouncedIds?.filter { it in byId }?.toSet() ?: emptySet(),
            )
    }

    fun stop() {
        _state.value = CollectionExplorerState.Idle
    }

    fun setAutoAdvance(enabled: Boolean) {
        val s = _state.value as? CollectionExplorerState.Active ?: return
        _state.value = s.copy(autoAdvance = enabled)
    }

    /** User explicitly selected a target from the list. */
    fun selectTarget(point: CollectionPoint) {
        val s = _state.value as? CollectionExplorerState.Active ?: return
        _state.value = s.copy(target = point, nearTrailEndM = null)
    }

    /** Clear the current target — no target, no auto-pick, silence until the next GPS fix decides otherwise. */
    fun clearTarget() {
        val s = _state.value as? CollectionExplorerState.Active ?: return
        _state.value = s.copy(target = null, nearTrailEndM = null)
    }

    /** Reset visited queue (useful to re-tour in auto-advance mode). */
    fun clearVisited() {
        val s = _state.value as? CollectionExplorerState.Active ?: return
        _state.value =
            s.copy(
                visitedIds = emptyList(),
                target = null,
                nearTrailEndM = null,
                proximityAnnouncedIds = emptySet(),
            )
    }

    /** Reject the current target and advance to the next nearest unvisited point. */
    fun skipTarget(): CollectionExplorerEvent.TargetSkipped? {
        val s = _state.value as? CollectionExplorerState.Active ?: return null
        val target = s.target ?: s.points.firstOrNull { it.id !in s.visitedIds } ?: return null
        val newVisited = (s.visitedIds + target.id).takeLast(VISITED_CAP)
        val nextTarget = s.points.firstOrNull { it.id !in newVisited }
        _state.value =
            s.copy(
                target = nextTarget,
                visitedIds = newVisited,
                nearTrailEndM = null,
            )
        return CollectionExplorerEvent.TargetSkipped(target, nextTarget)
    }

    /**
     * Call on every GPS fix.
     * - Re-sorts points by distance.
     * - Auto-fills a target when there isn't one and autoAdvance is on.
     * - Checks reach threshold and trail-approach threshold.
     * Returns an event if something notable happened, null otherwise.
     */
    fun onLocationUpdate(
        location: LatLng,
        travelHeadingDeg: Double? = null,
    ): CollectionExplorerEvent? {
        val s = _state.value as? CollectionExplorerState.Active ?: return null

        // Re-sort all points by current distance.
        val sorted =
            s.points.sortedBy { p ->
                haversineDistanceMeters(location, LatLng(p.waypoint.lat, p.waypoint.lon))
            }

        val target =
            s.target ?: if (s.autoAdvance) {
                selectAutomaticTarget(sorted, s.visitedIds, location, travelHeadingDeg)
            } else {
                null
            }

        if (target == null) {
            // No target selected (manual or auto-advance) — still worth scanning for nearby
            // points. The proximity scan is about points *other than* the target, so it
            // shouldn't require one to exist; this is what lets NearbyPoint fire while just
            // walking around with nothing picked, or trail-following without a synced explorer
            // target (issue filed 2026-08-04).
            val nearby = proximityCheck(sorted, excludeId = null, s, location)
            _state.value =
                s.copy(
                    points = sorted,
                    target = null,
                    nearTrailEndM = null,
                    proximityAnnouncedIds = if (nearby != null) s.proximityAnnouncedIds + nearby.id else s.proximityAnnouncedIds,
                )
            return nearby?.let {
                CollectionExplorerEvent.NearbyPoint(it, haversineDistanceMeters(location, LatLng(it.waypoint.lat, it.waypoint.lon)))
            }
        }

        val distToTarget =
            haversineDistanceMeters(
                location,
                LatLng(target.waypoint.lat, target.waypoint.lon),
            )

        // Trail endpoints: show Follow button when within TRAIL_APPROACH_M.
        // Never auto-reach a trail end — user must explicitly Follow or Clear to move on.
        if (target is CollectionPoint.TrailEnd) {
            val near = distToTarget <= TRAIL_APPROACH_M
            val nearTrailEndM: Double? = if (near) distToTarget else null
            val nearby = proximityCheck(sorted, target.id, s, location)
            _state.value =
                s.copy(
                    points = sorted,
                    target = target,
                    nearTrailEndM = nearTrailEndM,
                    proximityAnnouncedIds = if (nearby != null) s.proximityAnnouncedIds + nearby.id else s.proximityAnnouncedIds,
                )
            return when {
                near && nearTrailEndM != s.nearTrailEndM -> {
                    CollectionExplorerEvent.NearTrailEnd(target, distToTarget)
                }

                nearby != null -> {
                    CollectionExplorerEvent.NearbyPoint(
                        nearby,
                        haversineDistanceMeters(location, LatLng(nearby.waypoint.lat, nearby.waypoint.lon)),
                    )
                }

                else -> {
                    null
                }
            }
        }

        // Standalone waypoint: check reach threshold.
        val nearTrailEndM: Double? = null
        if (distToTarget <= REACH_THRESHOLD_M) {
            val newVisited = (s.visitedIds + target.id).takeLast(VISITED_CAP)

            // Auto-advance: immediately pick the next nearest unvisited; otherwise fall silent.
            val nextTarget =
                if (s.autoAdvance) selectAutomaticTarget(sorted, newVisited, location, travelHeadingDeg) else null

            _state.value =
                s.copy(
                    points = sorted,
                    target = nextTarget,
                    visitedIds = newVisited,
                    nearTrailEndM = null,
                )
            return CollectionExplorerEvent.PointReached(target, nextTarget)
        }

        // Proximity scan: announce unvisited non-target points within PROXIMITY_M once per session.
        val nearby = proximityCheck(sorted, target.id, s, location)
        _state.value =
            s.copy(
                points = sorted,
                target = target,
                nearTrailEndM = nearTrailEndM,
                proximityAnnouncedIds = if (nearby != null) s.proximityAnnouncedIds + nearby.id else s.proximityAnnouncedIds,
            )
        return if (nearby != null) {
            val dist = haversineDistanceMeters(location, LatLng(nearby.waypoint.lat, nearby.waypoint.lon))
            CollectionExplorerEvent.NearbyPoint(nearby, dist)
        } else {
            null
        }
    }

    private fun proximityCheck(
        sorted: List<CollectionPoint>,
        excludeId: Long?,
        s: CollectionExplorerState.Active,
        location: LatLng,
    ): CollectionPoint? =
        sorted.firstOrNull { p ->
            p.id != excludeId &&
                p.id !in s.visitedIds &&
                p.id !in s.proximityAnnouncedIds &&
                haversineDistanceMeters(location, LatLng(p.waypoint.lat, p.waypoint.lon)) <= PROXIMITY_M
        }

    private fun selectAutomaticTarget(
        sorted: List<CollectionPoint>,
        visitedIds: List<Long>,
        location: LatLng,
        travelHeadingDeg: Double?,
    ): CollectionPoint? {
        val candidates = sorted.filter { it.id !in visitedIds }
        if (travelHeadingDeg == null) return candidates.firstOrNull()
        return candidates.minByOrNull { point ->
            val pointLocation = LatLng(point.waypoint.lat, point.waypoint.lon)
            val distanceM = haversineDistanceMeters(location, pointLocation)
            val bearingDeg = initialBearingDeg(location, pointLocation)
            val headingDeltaDeg = abs(deltaAngle(travelHeadingDeg, bearingDeg))
            distanceM + headingDeltaDeg * HEADING_DEGREE_PENALTY_M
        }
    }

    companion object {
        const val REACH_THRESHOLD_M = 15.0
        const val TRAIL_APPROACH_M = 10.0
        const val PROXIMITY_M = 30.0
        const val VISITED_CAP = 3
        const val HEADING_DEGREE_PENALTY_M = 1.5
    }
}
