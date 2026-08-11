package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Optional seam for describing follow directions. Given a targeted trail end, return its follow
 * options; returning two yields a chooser (e.g. a loop's coincident ends), one a direct button. The
 * default (a null describer) treats every end as linear — a single Forward option. Richer descriptors
 * (cardinal, loop clockwise/counter-clockwise) can be supplied here later without touching the
 * resolver or UI.
 */
fun interface DirectionDescriber {
    fun describe(trailEnd: CollectionPoint.TrailEnd): List<FollowOption>
}

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
    nearbyTrail: StateFlow<List<NearbyTrail>> = MutableStateFlow(emptyList()),
    private val directionDescriber: DirectionDescriber? = null,
) {
    private data class Inputs(
        val rec: TrailRecordingState,
        val explorer: CollectionExplorerState,
        val colId: Long?,
        val loc: LocationSample?,
        val acc: Double?,
    )

    val navMode: StateFlow<NavMode> =
        combine(
            recordingState,
            explorerState,
            selectedCollectionId,
            location,
            accuracyM,
        ) { rec, explorer, colId, loc, acc ->
            Inputs(rec, explorer, colId, loc, acc)
        }.combine(nearbyTrail) { inputs, nearby ->
            fold(inputs.rec, inputs.explorer, inputs.colId, inputs.loc, inputs.acc, nearby)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), NavMode.NoCollection)

    private fun fold(
        rec: TrailRecordingState,
        explorer: CollectionExplorerState,
        colId: Long?,
        loc: LocationSample?,
        acc: Double?,
        nearby: List<NearbyTrail>,
    ): NavMode {
        when (rec) {
            is TrailRecordingState.Following -> return NavMode.FollowingTrail(rec.trailId)
            is TrailRecordingState.Recording -> return NavMode.RecordingTrail(rec.trailId, rec.pointCount)
            else -> Unit // Selected/Idle are never projected; fall through to the explorer target.
        }

        val active = explorer as? CollectionExplorerState.Active
        val target = active?.target

        // Precedence 2: an actionable trail end keeps primacy (its explicit 10 m approach gate).
        if (active != null && target is CollectionPoint.TrailEnd) {
            val actions =
                buildSet {
                    if (active.nearTrailEndM != null) add(TrailEndAction.Follow)
                    if (canExtend(loc, acc, target)) add(TrailEndAction.Extend)
                }
            if (actions.isNotEmpty()) return NavMode.AtTrailEnd(target, actions, trailEndOptions(target))
        }

        // Precedence 3: follow-able nearby trail(s) (no actionable end) sit above a plain target.
        if (active != null && nearby.isNotEmpty()) {
            val trailsById =
                active.points
                    .filterIsInstance<CollectionPoint.TrailEnd>()
                    .map { it.trail }
                    .associateBy { it.id }
            val nearbyTrails = nearby.mapNotNull { trailsById[it.trailId] }.distinctBy { it.id }
            if (nearbyTrails.isNotEmpty()) return NavMode.NearTrail(nearbyTrails, nearTrailOptions())
        }

        // Precedence 4: ordinary target / no target / no collection.
        if (active == null) return if (colId == null) NavMode.NoCollection else NavMode.NoTarget
        if (target == null) return NavMode.NoTarget
        return NavMode.CollectionTarget(target)
    }

    /** Mid-trail follow always offers both directions (see [NavMode.NearTrail]). */
    private fun nearTrailOptions(): List<FollowOption> =
        listOf(
            FollowOption(reversed = false, descriptor = DirectionDescriptor.Forward),
            FollowOption(reversed = true, descriptor = DirectionDescriptor.Backward),
        )

    /**
     * Follow options for a targeted end. A [directionDescriber] (when supplied) may return two for a
     * loop; the default treats the end as linear — a single option leaving the trailhead.
     */
    private fun trailEndOptions(end: CollectionPoint.TrailEnd): List<FollowOption> =
        directionDescriber?.describe(end)
            ?: listOf(FollowOption(reversed = !end.isStart, descriptor = DirectionDescriptor.Forward))

    private fun canExtend(
        loc: LocationSample?,
        acc: Double?,
        target: CollectionPoint.TrailEnd,
    ): Boolean {
        if (loc == null) return false
        val dist = haversineDistanceMeters(LatLng(loc.lat, loc.lon), LatLng(target.waypoint.lat, target.waypoint.lon))
        // No cap was ever applied here — the extend gate was, and remains, genuinely unbounded.
        // NavigationPolicy.UNBOUNDED_CAP_M names it so the remaining unbounded gates stay greppable,
        // this behaves identically to the old `max(floor, factor*accuracy)` for every accuracy
        // value, not merely the ones the test suite happens to exercise.
        return dist <=
            NavigationPolicy.widenWithAccuracy(
                baseM = NavigationPolicy.EXTEND_FLOOR_M,
                factor = NavigationPolicy.EXTEND_ACCURACY_FACTOR,
                accuracyM = acc,
                capM = NavigationPolicy.UNBOUNDED_CAP_M,
            )
    }
}
