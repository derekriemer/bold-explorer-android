package com.boldexplorer.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A projection onto a polyline is not one kind of answer, and ADR 0001 Amendment 1 requires
 * [TrailPosition] to say which kind it is.
 *
 * Interior projections carry a well-defined `alongTrackM` and a perpendicular `crossTrackM`. A
 * projection clamped to a vertex carries neither: an entire wedge of ground maps to one along-track
 * scalar, and the distance reported is radial to the corner rather than perpendicular to anything.
 * Clamping at the *ends* of the trail is degenerate too, but means something real — "before the
 * start" and "past the end" are states the app wants to talk about — so it must be distinguishable
 * from an apex.
 */
class TrailProjectionKindTest {
    private val legM = 100.0
    private val corner = TrailPolyline(cornerShape(legM))

    @Test
    fun aPointInTheWedgeOutsideACornerIsVertexClamped() {
        // cornerShape turns 90° right at vertex 1. Points north-west of that corner are past the end
        // of the first leg and behind the start of the second, so both segments clamp to the corner
        // itself — this is the case that froze along-track for 36 consecutive fixes in the field.
        val inTheWedge = offsetFromOrigin(northM = legM + 20.0, eastM = -20.0)
        val position = corner.project(inTheWedge)!!
        assertEquals(ProjectionKind.VertexClamped, position.kind)
    }

    @Test
    fun aPointBesideAStraightStretchIsInterior() {
        val besideTheFirstLeg = offsetFromOrigin(northM = legM / 2, eastM = 10.0)
        assertEquals(ProjectionKind.Interior, corner.project(besideTheFirstLeg)!!.kind)
    }

    @Test
    fun aPointBeforeTheTrailHeadIsEndpointClampedNotVertexClamped() {
        // Degenerate in the same way as an apex, but meaningful: acquiring a trail while standing
        // just short of its start is ordinary, and must not be treated as an ambiguous wedge.
        val beforeTheStart = offsetFromOrigin(northM = -20.0, eastM = 0.0)
        assertEquals(ProjectionKind.EndpointClamped, corner.project(beforeTheStart)!!.kind)
    }

    @Test
    fun aPointPastTheTrailEndIsEndpointClamped() {
        val pastTheEnd = offsetFromOrigin(northM = legM, eastM = legM + 30.0)
        assertEquals(ProjectionKind.EndpointClamped, corner.project(pastTheEnd)!!.kind)
    }

    @Test
    fun aProjectionPulledBackByTheSearchWindowIsNotAVertexClamp() {
        // The trap. `projectOnSegment` clamps the segment fraction to the search window as well as
        // to the segment, so a windowed projection can land exactly on a segment boundary without
        // the geometry having clamped anything. Reporting that as a vertex clamp would make the kind
        // depend on how wide the caller's window was, and would make the tracker distrust positions
        // in the middle of a perfectly ordinary straight.
        val besideTheFirstLeg = offsetFromOrigin(northM = 60.0, eastM = 5.0)
        val windowed = corner.project(besideTheFirstLeg, window = 0.0..30.0)!!
        assertEquals(30.0, windowed.alongTrackM, 0.001, "the window should have pulled it back")
        assertEquals(ProjectionKind.Interior, windowed.kind)
    }

    @Test
    fun aSinglePointTrailIsEndpointClamped() {
        val single = TrailPolyline(listOf(offsetFromOrigin(0.0, 0.0)))
        assertEquals(ProjectionKind.EndpointClamped, single.project(offsetFromOrigin(10.0, 10.0))!!.kind)
    }
}
