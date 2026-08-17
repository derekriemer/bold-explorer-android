package com.boldexplorer.shared.navigation

/**
 * What a "navigate to this" request raised from another screen actually refers to (issue #78).
 *
 * The request says what it means rather than handing over an id for the consumer to guess about.
 * That guess is the defect this type exists to end: the bridge used to take a bare waypoint id and
 * look for a [CollectionPoint.Standalone] with it, which cannot ever match a recorded trail's
 * endpoint. Those are track points — not standalones, not members of any collection — so the
 * lookup found nothing and did nothing, while the screen that raised the request had already
 * announced that it was navigating there.
 *
 * Two callers, two intents. The Waypoints screen means a waypoint; the Trails screen means an end
 * of a named trail, and it knows *which* end, which is not recoverable from a waypoint id.
 */
sealed interface ExternalTargetRequest {
    /** A user-created waypoint, targeted from the Waypoints screen. */
    data class Waypoint(val waypointId: Long) : ExternalTargetRequest

    /** One end of [trailId]'s geometry, targeted from the Trails screen. */
    data class TrailEnd(
        val trailId: Long,
        val isStart: Boolean,
    ) : ExternalTargetRequest
}

/**
 * The point in [points] this request refers to, or `null` when the loaded collection does not hold
 * it.
 *
 * Null is a real answer and the caller must say so out loud. Returning null where the old code
 * returned silently is the whole point: for a blind user a navigation action that claims success
 * and changes nothing is unfalsifiable, because there is no screen to glance at and no target to
 * hear. Better to be told the target could not be set.
 *
 * Matching is exact on kind as well as identity. A [CollectionPoint.TrailEnd] wraps a waypoint, so
 * matching a [ExternalTargetRequest.Waypoint] on waypoint id alone could land on a trail end —
 * which selects the trail too, doing more than the user asked.
 */
fun ExternalTargetRequest.resolveIn(points: List<CollectionPoint>): CollectionPoint? =
    when (this) {
        is ExternalTargetRequest.Waypoint ->
            points.firstOrNull { it is CollectionPoint.Standalone && it.waypoint.id == waypointId }

        is ExternalTargetRequest.TrailEnd ->
            points.firstOrNull {
                it is CollectionPoint.TrailEnd && it.trail.id == trailId && it.isStart == isStart
            }
    }
