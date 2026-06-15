package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.math.max

/**
 * Folds the GPS screen's three navigation state holders into a single [NavMode] the UI renders,
 * extracted from `GpsViewModel` so the projection is a pure, JVM-testable delegate (same pattern as
 * [NavigationTargetResolver]). The machines stay the sources of truth; this only derives.
 *
 * Precedence: an active session ([TrailRecordingState.Following]/[TrailRecordingState.Recording])
 * always wins over the collection explorer's current target. [TrailRecordingState.Selected] and its
 * `hasPoints` flag are deliberately never read — the followability of a trail end comes from the
 * explorer's [CollectionExplorerState.Active.nearTrailEndM] (the same approach gate the machine
 * already applies), and extend-ability comes from the accuracy-scaled geometry below.
 */
class NavModeResolver(
    scope: CoroutineScope,
    recordingState: StateFlow<TrailRecordingState>,
    explorerState: StateFlow<CollectionExplorerState>,
    selectedCollectionId: StateFlow<Long?>,
    location: StateFlow<LocationSample?>,
    accuracyM: StateFlow<Double?>,
) {
    val navMode: StateFlow<NavMode> =
        combine(
            recordingState,
            explorerState,
            selectedCollectionId,
            location,
            accuracyM,
        ) { rec, explorer, colId, loc, acc ->
            fold(rec, explorer, colId, loc, acc)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), NavMode.NoCollection)

    private fun fold(
        rec: TrailRecordingState,
        explorer: CollectionExplorerState,
        colId: Long?,
        loc: LocationSample?,
        acc: Double?,
    ): NavMode {
        when (rec) {
            is TrailRecordingState.Following -> return NavMode.FollowingTrail(rec.trailId)
            is TrailRecordingState.Recording -> return NavMode.RecordingTrail(rec.trailId, rec.pointCount)
            else -> Unit // Selected/Idle are never projected; fall through to the explorer target.
        }

        if (explorer !is CollectionExplorerState.Active) {
            return if (colId == null) NavMode.NoCollection else NavMode.NoTarget
        }
        val target = explorer.target ?: return NavMode.NoTarget

        return when (target) {
            is CollectionPoint.TrailEnd -> {
                val actions =
                    buildSet {
                        if (explorer.nearTrailEndM != null) add(TrailEndAction.Follow)
                        if (canExtend(loc, acc, target)) add(TrailEndAction.Extend)
                    }
                if (actions.isEmpty()) NavMode.CollectionTarget(target) else NavMode.AtTrailEnd(target, actions)
            }

            is CollectionPoint.Standalone -> {
                NavMode.CollectionTarget(target)
            }
        }
    }

    private fun canExtend(
        loc: LocationSample?,
        acc: Double?,
        target: CollectionPoint.TrailEnd,
    ): Boolean {
        if (loc == null) return false
        val dist = haversineDistanceMeters(LatLng(loc.lat, loc.lon), LatLng(target.waypoint.lat, target.waypoint.lon))
        return dist <= max(EXTEND_FLOOR_M, EXTEND_ACCURACY_FACTOR * (acc ?: 0.0))
    }

    companion object {
        /** Minimum distance within which a trail end may be extended, regardless of GPS accuracy. */
        const val EXTEND_FLOOR_M = 15.0

        /** Multiplier on reported GPS accuracy when it exceeds [EXTEND_FLOOR_M], to scale the extend threshold. */
        const val EXTEND_ACCURACY_FACTOR = 2.0
    }
}
