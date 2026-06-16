package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.model.Trail

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

    /**
     * The current target is a trail end with one or more in-range [actions] (follow and/or extend).
     * [followOptions] describe how Follow can proceed: one option for a linear end (the only sensible
     * way off a trailhead you're standing on), two for a coincident loop end.
     */
    data class AtTrailEnd(
        val trailEnd: CollectionPoint.TrailEnd,
        val actions: Set<TrailEndAction>,
        val followOptions: List<FollowOption>,
    ) : NavMode()

    /**
     * The user is near one or more follow-able trails mid-line (no targeted end). [trails] is
     * ordered nearest-first so the UI can list them. Always offers both directions via [options] —
     * off the endpoints the recorded line is only approximate and GPS wanders, so guessing a single
     * direction risks sending a blind user the wrong way.
     */
    data class NearTrail(
        val trails: List<Trail>,
        val options: List<FollowOption>,
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

/**
 * One way to follow a trail: the [reversed] flag passed to the follow path, paired with a
 * [descriptor] the UI renders. A single option → direct "Follow {trail}" button; two → a chooser.
 */
data class FollowOption(
    val reversed: Boolean,
    val descriptor: DirectionDescriptor,
)

/**
 * How a follow direction is described to the user. Forward/Backward are the only descriptors
 * produced today; [Cardinal] and [Loop] are typed seams so a future [DirectionDescriber] can emit
 * richer descriptors (e.g. "north", "clockwise") without reworking the resolver or UI.
 */
sealed interface DirectionDescriptor {
    data object Forward : DirectionDescriptor

    data object Backward : DirectionDescriptor

    data class Cardinal(
        val text: String,
    ) : DirectionDescriptor

    data class Loop(
        val clockwise: Boolean,
    ) : DirectionDescriptor
}
