package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Reactive composition of a collection's navigable points: standalone waypoints plus the start/end of
 * each trail. Every input is a live flow — in particular each trail's waypoints are observed via
 * [trailWaypoints], so adding a track point (which changes only trail_waypoint, not the trail row or
 * collection_waypoint) still re-emits and refreshes that trail's ends. Replaces the prior one-shot
 * `waypointsForTrail` snapshot that left freshly-recorded trails invisible until app restart.
 *
 * @param standalones    collection's standalone waypoints (e.g. observeWaypointsForCollection).
 * @param trails         collection's trails (e.g. observeTrailsForCollection).
 * @param trailWaypoints factory for a trail's ordered waypoints flow (e.g. observeWaypointsForTrail).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun collectionNavPoints(
    standalones: Flow<List<Waypoint>>,
    trails: Flow<List<Trail>>,
    trailWaypoints: (trailId: Long) -> Flow<List<Waypoint>>,
): Flow<List<CollectionPoint>> =
    combine(
        standalones,
        trails.flatMapLatest { ts ->
            if (ts.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(ts.map { t -> trailWaypoints(t.id).map { wps -> t to wps } }) { it.toList() }
            }
        },
    ) { wps, trailWps ->
        val standalonePoints = wps.map { CollectionPoint.Standalone(it) }
        val ends =
            trailWps.flatMap { (trail, ordered) ->
                listOfNotNull(
                    ordered.firstOrNull()?.let { CollectionPoint.TrailEnd(it, trail, isStart = true) },
                    ordered
                        .lastOrNull()
                        ?.takeIf { ordered.size > 1 }
                        ?.let { CollectionPoint.TrailEnd(it, trail, isStart = false) },
                )
            }
        standalonePoints + ends
    }
