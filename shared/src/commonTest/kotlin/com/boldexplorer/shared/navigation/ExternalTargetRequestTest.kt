package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Resolving a "navigate to this" request raised from another screen (issue #78).
 *
 * The defect this closes: the bridge matched only `CollectionPoint.Standalone`, and looked the
 * point up by waypoint id. A recorded trail's endpoint is a **track point** — never a standalone,
 * never a member of a collection — so the lookup found nothing and the bridge returned silently
 * while the Trails screen had already said "Navigating to the start of …". The user is blind; an
 * action that announces success and does nothing cannot be caught any other way.
 *
 * The request now says what it means — a waypoint, or an end of a named trail — instead of handing
 * over an id for the bridge to guess about.
 */
class ExternalTargetRequestTest {
    @Test
    fun trailEndRequestResolvesARecordedTrailsEndpoint() {
        // The regression case. This endpoint is a track point: not a standalone, not in any
        // collection, and its name is the clock time the recorder stamped on it.
        val points =
            listOf(
                standalone(1L, "Bench"),
                trailEnd(trailId = 7L, waypointId = 900L, name = "Track 14:32:05", isStart = true),
                trailEnd(trailId = 7L, waypointId = 901L, name = "Track 14:58:11", isStart = false),
            )

        val resolved = ExternalTargetRequest.TrailEnd(trailId = 7L, isStart = true).resolveIn(points)

        val end = assertIs<CollectionPoint.TrailEnd>(resolved)
        assertEquals(900L, end.waypoint.id)
        assertEquals(7L, end.trail.id)
    }

    @Test
    fun startAndEndOfTheSameTrailAreDifferentPoints() {
        // They share a trail and differ only by `isStart`, so resolving by trail id alone would
        // send the user to whichever happened to be first in the list — a coin flip between two
        // ends of a trail, which for a blind walker is the difference between the way home and
        // the far end of it.
        val points =
            listOf(
                trailEnd(trailId = 7L, waypointId = 900L, name = "start", isStart = true),
                trailEnd(trailId = 7L, waypointId = 901L, name = "end", isStart = false),
            )

        val start = ExternalTargetRequest.TrailEnd(7L, isStart = true).resolveIn(points)
        val end = ExternalTargetRequest.TrailEnd(7L, isStart = false).resolveIn(points)

        assertEquals(900L, assertIs<CollectionPoint.TrailEnd>(start).waypoint.id)
        assertEquals(901L, assertIs<CollectionPoint.TrailEnd>(end).waypoint.id)
    }

    @Test
    fun trailEndRequestIgnoresAnotherTrailsEnds() {
        val points =
            listOf(
                trailEnd(trailId = 3L, waypointId = 300L, name = "other start", isStart = true),
                trailEnd(trailId = 7L, waypointId = 900L, name = "wanted start", isStart = true),
            )

        val resolved = ExternalTargetRequest.TrailEnd(7L, isStart = true).resolveIn(points)

        assertEquals(900L, assertIs<CollectionPoint.TrailEnd>(resolved).waypoint.id)
    }

    @Test
    fun waypointRequestStillResolvesAStandalone() {
        // The Waypoints screen's path, which was never broken and must stay that way.
        val points = listOf(standalone(1L, "Bench"), standalone(2L, "Gate"))

        val resolved = ExternalTargetRequest.Waypoint(2L).resolveIn(points)

        assertEquals(2L, assertIs<CollectionPoint.Standalone>(resolved).waypoint.id)
    }

    @Test
    fun aWaypointRequestDoesNotMatchATrailEndThatHappensToShareItsId() {
        // A trail end wraps a waypoint, so matching purely on waypoint id would let a request for
        // a standalone land on a trail end — which also selects the trail, changing more than the
        // user asked for.
        val points = listOf(trailEnd(trailId = 7L, waypointId = 42L, name = "end", isStart = false))

        assertNull(ExternalTargetRequest.Waypoint(42L).resolveIn(points))
    }

    @Test
    fun anUnresolvableRequestReturnsNullRatherThanSomethingClose() {
        // Null is what makes the failure sayable. The old code's silent `return` is exactly what
        // this has to replace: no target, and no claim that there is one.
        val points = listOf(standalone(1L, "Bench"))

        assertNull(ExternalTargetRequest.TrailEnd(7L, isStart = true).resolveIn(points))
        assertNull(ExternalTargetRequest.Waypoint(99L).resolveIn(points))
        assertNull(ExternalTargetRequest.Waypoint(1L).resolveIn(emptyList()))
    }

    private fun standalone(
        id: Long,
        name: String,
    ) = CollectionPoint.Standalone(waypoint(id, name, Waypoint.KIND_WAYPOINT))

    private fun trailEnd(
        trailId: Long,
        waypointId: Long,
        name: String,
        isStart: Boolean,
    ) = CollectionPoint.TrailEnd(
        waypoint = waypoint(waypointId, name, Waypoint.KIND_TRACK_POINT),
        trail = Trail(id = trailId, name = "Trail $trailId", description = null, createdAt = 0L),
        isStart = isStart,
    )

    private fun waypoint(
        id: Long,
        name: String,
        kind: String,
    ) = Waypoint(
        id = id,
        name = name,
        lat = 0.0,
        lon = 0.0,
        elevM = null,
        description = null,
        createdAt = 0L,
        kind = kind,
    )
}
