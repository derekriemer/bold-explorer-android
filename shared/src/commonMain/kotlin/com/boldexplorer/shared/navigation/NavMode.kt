package com.boldexplorer.shared.navigation

/**
 * The single derived mode the GPS screen renders its contextual trail controls from, replacing the
 * prior approach where the UI unioned three independent state holders (CollectionExplorer,
 * TrailRecordingMachine, TrailFollower) plus flags to decide what to show.
 *
 * Produced by [NavModeResolver] with an explicit precedence (active session beats explorer target).
 * Crucially [TrailRecordingState.Selected] is never projected into a NavMode variant — that flag was
 * unreliable (only [TrailRecordingMachine.stop] set its hasPoints) and lingered as stale state, which
 * caused the "can't follow at a trail start until you autorecord+stop" and "stale trail controls"
 * field bugs.
 */
sealed class NavMode {
    /** No collection is selected — only the standing "record new trail" affordance is gated off this. */
    object NoCollection : NavMode()

    /** A collection is selected but there is no current target. */
    object NoTarget : NavMode()

    /** The current target is an ordinary collection point (standalone waypoint, or a trail end with no in-range actions). */
    data class CollectionTarget(
        val point: CollectionPoint,
    ) : NavMode()

    /** The current target is a trail end with one or more in-range [actions] (follow and/or extend). */
    data class AtTrailEnd(
        val trailEnd: CollectionPoint.TrailEnd,
        val actions: Set<TrailEndAction>,
    ) : NavMode()

    /** Actively following [trailId]. */
    data class FollowingTrail(
        val trailId: Long,
    ) : NavMode()

    /** Actively auto-recording onto [trailId]. */
    data class RecordingTrail(
        val trailId: Long,
        val pointCount: Int,
    ) : NavMode()
}

/** Actions available at a trail end, gated by geometry in [NavModeResolver]. */
enum class TrailEndAction { Follow, Extend }
