package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.model.TrailEndRow
import com.boldexplorer.shared.model.Waypoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Reactive composition of a collection's navigable points: standalone waypoints plus the start/end of
 * each trail. Both inputs are live flows.
 *
 * [trailEnds] comes from the lean `trailEndsForCollection` SQL query, which returns only each trail's
 * two endpoint rows (never the dense track-point body) and re-emits when any relevant table changes —
 * so adding a track point (which shifts a trail's MAX position) still refreshes that trail's end
 * through the same subscription. This replaces the prior per-trail composition that loaded every
 * trail's full waypoint list just to take first()/last(), which was O(all track points) on a hot
 * combine.
 *
 * @param standalones collection's standalone waypoints (e.g. observeWaypointsForCollection).
 * @param trailEnds   collection's trail ends (NavPointsRepository.observeTrailEndsForCollection).
 */
fun collectionNavPoints(
    standalones: Flow<List<Waypoint>>,
    trailEnds: Flow<List<TrailEndRow>>,
): Flow<List<CollectionPoint>> =
    combine(standalones, trailEnds) { wps, ends ->
        wps.map { CollectionPoint.Standalone(it) } +
            ends.map { CollectionPoint.TrailEnd(it.waypoint, it.trail, it.isStart) }
    }
