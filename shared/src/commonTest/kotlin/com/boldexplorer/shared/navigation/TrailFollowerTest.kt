package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrailFollowerTest {
    // Two waypoints ~10 m apart so threshold=15m will trigger easily
    private val wp1 = TrailPoint(1, "Start", 0.0, 0.0)
    private val wp2 = TrailPoint(2, "Middle", 0.00009, 0.0) // ~10 m north
    private val wp3 = TrailPoint(3, "End", 0.00018, 0.0) // ~20 m north

    @Test
    fun idle_byDefault() {
        val f = TrailFollower()
        assertIs<TrailFollowerState.Idle>(f.state.value)
    }

    @Test
    fun start_transitionsToActive() {
        val f = TrailFollower()
        f.start(listOf(wp1, wp2, wp3))
        assertIs<TrailFollowerState.Active>(f.state.value)
    }

    @Test
    fun noEvent_whenFarFromTarget() {
        val f = TrailFollower()
        f.start(listOf(wp1, wp2), thresholdM = 15.0)
        // Far away — 1 km north of wp1. No threshold, no projection (index=0),
        // no divergence (never got close).
        val result = f.onLocationUpdate(LatLng(0.009, 0.0))
        assertNull(result)
        assertIs<TrailFollowerState.Active>(f.state.value)
    }

    @Test
    fun waypointReached_whenWithinThreshold() {
        val f = TrailFollower()
        f.start(listOf(wp1, wp2, wp3), thresholdM = 15.0)
        // Arrive at wp1 (0, 0)
        val event = f.onLocationUpdate(LatLng(0.0, 0.0))
        assertIs<TrailFollowerEvent.WaypointReached>(event)
        assertEquals(1, event.index)
        assertEquals("Middle", event.name)
        assertEquals(com.boldexplorer.shared.model.Waypoint.KIND_WAYPOINT, event.kind)
        assertEquals(3, event.total)
        assert(event.distanceToNextM > 0.0)
        val state = f.state.value as TrailFollowerState.Active
        assertEquals(1, state.currentIndex)
    }

    @Test
    fun waypointReached_includesDistanceAndBearing() {
        // wp1 is at (0, 0), wp2 is ~10m north at (0.00009, 0)
        val f = TrailFollower()
        f.start(listOf(wp1, wp2), thresholdM = 15.0)
        val event = f.onLocationUpdate(LatLng(0.0, 0.0)) as TrailFollowerEvent.WaypointReached
        // Distance from (0,0) to wp2 (0.00009, 0) is ~10 m
        assert(event.distanceToNextM in 5.0..20.0) { "expected ~10m, got ${event.distanceToNextM}" }
        // Bearing from (0,0) heading north to (0.00009, 0) should be near 0° (north)
        assert(event.absoluteBearingDeg < 5.0 || event.absoluteBearingDeg > 355.0) {
            "expected ~0° (north), got ${event.absoluteBearingDeg}"
        }
    }

    @Test
    fun waypointReached_trackPointKindPropagates() {
        val tp1 = TrailPoint(1, "Track 10:00:00", 0.0, 0.0, kind = com.boldexplorer.shared.model.Waypoint.KIND_TRACK_POINT)
        val tp2 = TrailPoint(2, "Track 10:00:10", 0.00009, 0.0, kind = com.boldexplorer.shared.model.Waypoint.KIND_TRACK_POINT)
        val f = TrailFollower()
        f.start(listOf(tp1, tp2), thresholdM = 15.0)
        val event = f.onLocationUpdate(LatLng(0.0, 0.0)) as TrailFollowerEvent.WaypointReached
        assertEquals(com.boldexplorer.shared.model.Waypoint.KIND_TRACK_POINT, event.kind)
    }

    @Test
    fun trailComplete_whenLastWaypointReached() {
        val f = TrailFollower()
        f.start(listOf(wp1, wp2), thresholdM = 15.0)
        // Reach wp1 → advances to wp2
        f.onLocationUpdate(LatLng(0.0, 0.0))
        // Now reach wp2
        val event = f.onLocationUpdate(LatLng(0.00009, 0.0))
        assertIs<TrailFollowerEvent.TrailComplete>(event)
        assertIs<TrailFollowerState.Complete>(f.state.value)
    }

    @Test
    fun stop_resetsToIdle() {
        val f = TrailFollower()
        f.start(listOf(wp1, wp2))
        f.stop()
        assertIs<TrailFollowerState.Idle>(f.state.value)
    }

    @Test
    fun noEvent_whenStopped() {
        val f = TrailFollower()
        f.start(listOf(wp1, wp2))
        f.stop()
        val result = f.onLocationUpdate(LatLng(0.0, 0.0))
        assertNull(result)
    }

    @Test
    fun start_clampsToBounds() {
        val f = TrailFollower()
        f.start(listOf(wp1, wp2), fromIndex = 99)
        val state = f.state.value as TrailFollowerState.Active
        assertEquals(1, state.currentIndex) // clamped to last valid index
    }

    // ── Segment-projection (incoming) ────────────────────────────────────────────
    //
    // Three collinear points ~100 m apart going north.
    // Tight threshold (5 m) so the radial check doesn't fire prematurely.
    //   spA (0, 0) → spB (0.0009, 0) → spC (0.0018, 0)

    private val spA = TrailPoint(10, "A", 0.0, 0.0)
    private val spB = TrailPoint(11, "B", 0.0009, 0.0)
    private val spC = TrailPoint(12, "C", 0.0018, 0.0)

    @Test
    fun incomingProjection_advancesAt90Percent() {
        // User reaches 95% of the spA→spB leg without entering the 5 m threshold.
        // Incoming projection fires (t ≈ 0.95 ≥ 0.9) and advances to spC as new target.
        // Using 95% rather than exactly 90% avoids IEEE 754 boundary ambiguity.
        val f = TrailFollower()
        f.start(listOf(spA, spB, spC), thresholdM = 5.0)
        f.onLocationUpdate(LatLng(spA.lat, spA.lon)) // threshold hit at spA → target is now spB

        val at95pct = LatLng(0.000855, 0.0) // 95% of segment spA→spB, ~5 m from spB
        val event = f.onLocationUpdate(at95pct)
        assertIs<TrailFollowerEvent.WaypointReached>(event)
        assertEquals(2, event.index) // advanced past spB; new target is spC
    }

    @Test
    fun incomingProjection_doesNotAdvanceAt80Percent() {
        val f = TrailFollower()
        f.start(listOf(spA, spB, spC), thresholdM = 5.0)
        f.onLocationUpdate(LatLng(spA.lat, spA.lon))

        val at80pct = LatLng(0.00072, 0.0) // 80% — below 0.9 threshold, ~20 m from spB
        val event = f.onLocationUpdate(at80pct)
        assertNull(event)
    }

    @Test
    fun incomingProjection_skipsWhenOutsideProximityGate() {
        // User is 1 km north of spA (past spB and spC but way off-trail scale).
        // Even though t >> 1.0, proximity gate (4× threshold = 20 m) excludes it.
        val f = TrailFollower()
        f.start(listOf(spA, spB, spC), thresholdM = 5.0)
        f.onLocationUpdate(LatLng(spA.lat, spA.lon)) // advance to spB as target

        val farNorth = LatLng(0.009, 0.0) // 1 km north of spA — way past spB
        val event = f.onLocationUpdate(farNorth) // d to spB ≈ 900 m >> 4×5 = 20 m gate
        assertNull(event)
    }

    // ── Divergence check ─────────────────────────────────────────────────────────
    //
    // dvA (0,0) → dvB (0,0.0002) ~22 m EAST → dvC (0.0002,0.0002)
    // threshold = 10 m → closeness gate = 2× = 20 m, diverge = 5 m

    private val dvA = TrailPoint(20, "dvA", 0.0, 0.0)
    private val dvB = TrailPoint(21, "dvB", 0.0, 0.0002)
    private val dvC = TrailPoint(22, "dvC", 0.0002, 0.0002)

    @Test
    fun divergence_advancesWhenPastTarget() {
        // User approaches dvA from below, gets within 2× threshold (11 m), then
        // moves north-east past dvA while closing on dvB. Divergence fires.
        val f = TrailFollower()
        f.start(listOf(dvA, dvB, dvC), thresholdM = 10.0)

        // Update 1: ~11 m east of dvA — within closeness gate (≤ 20 m).
        f.onLocationUpdate(LatLng(0.0, 0.0001)) // closestApproach = 11 m

        // Update 2: user at (lat=0.0002, lon=0.00015).
        //   d to dvA ≈ 28 m → diverged 17 m ≥ 5 m ✓
        //   dNext to dvB=(lat=0, lon=0.0002) ≈ 23 m < 28 m ✓  (user is past the midpoint)
        val event = f.onLocationUpdate(LatLng(0.0002, 0.00015))
        assertIs<TrailFollowerEvent.WaypointReached>(event)
        assertEquals(1, event.index) // advanced past dvA; new target is dvB
    }

    @Test
    fun divergence_noAdvanceWhenNeverClose() {
        // User approaches dvA but only gets within 25 m — outside the 2× threshold
        // closeness gate (20 m). Divergence check never enables; no advance.
        val f = TrailFollower()
        f.start(listOf(dvA, dvB, dvC), thresholdM = 10.0)

        f.onLocationUpdate(LatLng(0.0, 0.00022)) // ~24.5 m from dvA — outside closeness gate
        // User heads toward dvB but divergence disabled because never close enough.
        val event = f.onLocationUpdate(LatLng(0.0002, 0.00022))
        assertNull(event)
    }

    @Test
    fun divergence_noAdvanceWhenStillApproaching() {
        // User is within closeness gate but still getting closer — not diverging yet.
        val f = TrailFollower()
        f.start(listOf(dvA, dvB, dvC), thresholdM = 10.0)

        f.onLocationUpdate(LatLng(0.0, 0.00015)) // ~16.7 m from dvA; closestApproach = 16.7 m
        // Still approaching: next update is 14 m away (closer). d < closestApproach + 5 m.
        val event = f.onLocationUpdate(LatLng(0.0, 0.000125)) // ~13.9 m — still closing in
        assertNull(event)
    }

    // ── Switchback safety ────────────────────────────────────────────────────────

    @Test
    fun switchback_noFalseAdvance() {
        // 90° switchback: swA(0,0) → swB(0.001,0) → swC(0,0.001).
        // swC is "behind" the user's path in — segments face very different directions.
        // threshold = 5 m → projection gate = 20 m, closeness gate = 10 m.
        val swA = TrailPoint(30, "swA", 0.0, 0.0)
        val swB = TrailPoint(31, "swB", 0.001, 0.0) // ~111 m east
        val swC = TrailPoint(32, "swC", 0.0, 0.001) // ~111 m north of swA

        val f = TrailFollower()
        f.start(listOf(swA, swB, swC), thresholdM = 5.0)
        f.onLocationUpdate(LatLng(swA.lat, swA.lon)) // advance past swA; target is now swB

        // User is 40% along the swA→swB leg (swB is north, so lat=0.0004).
        // d to swB ≈ 67 m — well outside both the projection gate (20 m) and the
        // divergence closeness gate (10 m). Neither check should trigger.
        val midApproach = LatLng(0.0004, 0.0)
        val event = f.onLocationUpdate(midApproach)
        assertNull(event)
        assertEquals(1, (f.state.value as TrailFollowerState.Active).currentIndex)
    }

    // ── New gating: cross-track, heading, divergence bound ───────────────────────
    //
    // Collinear N-S segment: gA(0,0) → gB(0.0009,0) → gC(0.0018,0), threshold=5m.
    // Projection gate = 4×5=20m, cross-track gate = 3×5=15m, heading tol = 60°.

    private val gA = TrailPoint(40, "A", 0.0, 0.0)
    private val gB = TrailPoint(41, "B", 0.0009, 0.0) // ~100 m north
    private val gC = TrailPoint(42, "C", 0.0018, 0.0)

    @Test
    fun projectionGate_crossTrackBlocks() {
        // User is 95% along gA→gB in the N direction but 60m off to the east.
        // Cross-track (60m) >> 3×5=15m gate → projection must not fire.
        val f = TrailFollower()
        f.start(listOf(gA, gB, gC), thresholdM = 5.0)
        f.onLocationUpdate(LatLng(gA.lat, gA.lon)) // advance past gA; target is gB

        // 95% along lat axis (~94.5m north) but 60m east (lon ≈ 0.00054°)
        val offTrail = LatLng(0.000855, 0.00054)
        val event = f.onLocationUpdate(offTrail)
        assertNull(event, "cross-track gate should block projection when user is far off-trail")
    }

    @Test
    fun projectionGate_headingBlocks() {
        // User is 95% along gA→gB (heading north, bearing ~0°) but their COG is south (180°).
        // Heading difference = 180° > 60° tolerance → projection must not fire.
        val f = TrailFollower()
        f.start(listOf(gA, gB, gC), thresholdM = 5.0)
        f.onLocationUpdate(LatLng(gA.lat, gA.lon))

        val at95pct = LatLng(0.000855, 0.0)
        val event = f.onLocationUpdate(at95pct, bearingDeg = 180f) // moving south
        assertNull(event, "heading gate should block projection when COG opposes segment direction")
    }

    @Test
    fun projectionGate_headingSkippedWhenNull() {
        // Same geometry; no COG provided → heading check is bypassed, projection fires.
        val f = TrailFollower()
        f.start(listOf(gA, gB, gC), thresholdM = 5.0)
        f.onLocationUpdate(LatLng(gA.lat, gA.lon))

        val at95pct = LatLng(0.000855, 0.0)
        val event = f.onLocationUpdate(at95pct, bearingDeg = null)
        assertIs<TrailFollowerEvent.WaypointReached>(event)
    }

    @Test
    fun projectionGate_headingAllowsWhenAligned() {
        // COG is north (~0°), segment runs north → heading difference ≈ 0° < 60° → fires.
        val f = TrailFollower()
        f.start(listOf(gA, gB, gC), thresholdM = 5.0)
        f.onLocationUpdate(LatLng(gA.lat, gA.lon))

        val at95pct = LatLng(0.000855, 0.0)
        val event = f.onLocationUpdate(at95pct, bearingDeg = 5f) // nearly north
        assertIs<TrailFollowerEvent.WaypointReached>(event)
    }

    @Test
    fun divergence_blockedWhenDNextTooFar() {
        // dvA(0,0) → dvB(0,0.0002) ~22m east → dvC(0,0.18) very far away.
        // segmentLength(dvB→dvC) ≈ 20km → dNext <= 1.5 * 20km is trivially satisfied,
        // so instead use a case where dNext itself is too large relative to segmentLength.
        //
        // Geometry: dvA → dvBNear(0,0.0002) ~22m, dvCFar(0,0.002) ~222m.
        // segmentLength(dvBNear→dvCFar) ≈ 200m, FACTOR=1.5 → bound = 300m.
        // User diverges from dvA but ends up 400m from dvCFar — bound blocks.
        val dvBNear = TrailPoint(51, "Near", 0.0, 0.0002)
        val dvCFar = TrailPoint(52, "Far", 0.0, 0.002)
        val f = TrailFollower()
        f.start(listOf(dvA, dvBNear, dvCFar), thresholdM = 10.0)

        // Get within closeness gate (~11m from dvA)
        f.onLocationUpdate(LatLng(0.0, 0.0001))

        // Now diverge far enough but place user 400m away from dvCFar (dNext >> 1.5 * segLen)
        // segLen(dvBNear→dvCFar) ≈ 200m, bound = 300m; user at lon=0.0040 is ~446m from dvCFar
        val event = f.onLocationUpdate(LatLng(0.0, 0.0040))
        assertNull(event, "divergence should be blocked when dNext exceeds 1.5 × segment length")
    }

    // ── startNearest with bearing ─────────────────────────────────────────────────

    @Test
    fun startNearest_bearingPrefersAheadWaypoint() {
        // User at (0,0) heading north (bearingDeg=0). Two waypoints: ahead and behind.
        // Ahead: (0.001,0) ~111m north. Behind: (-0.0005,0) ~55m south.
        // Without bearing, the south (closer) waypoint wins. With bearing, north wins.
        val ahead = TrailPoint(60, "Ahead", 0.001, 0.0)
        val behind = TrailPoint(61, "Behind", -0.0005, 0.0)
        val f = TrailFollower()
        f.startNearest(listOf(ahead, behind), LatLng(0.0, 0.0), bearingDeg = 0f)
        val state = f.state.value as TrailFollowerState.Active
        assertEquals(0, state.currentIndex, "should select ahead waypoint when bearing is provided")
    }

    @Test
    fun startNearest_fallsBackToNearestWhenAllBehind() {
        // User heading north (0°); only waypoint is directly south.
        // All candidates are > 90° off — fallback to nearest by distance, no crash.
        val south = TrailPoint(62, "South", -0.001, 0.0)
        val f = TrailFollower()
        f.startNearest(listOf(south), LatLng(0.0, 0.0), bearingDeg = 0f)
        assertIs<TrailFollowerState.Active>(f.state.value)
    }

    // ── onAdvancement callback ────────────────────────────────────────────────────

    @Test
    fun onAdvancement_firesOnRadialAdvance() {
        val f = TrailFollower()
        f.start(listOf(wp1, wp2, wp3), thresholdM = 15.0)
        var reason: AdvancementReason? = null
        f.onAdvancement = { reason = it }

        f.onLocationUpdate(LatLng(0.0, 0.0))
        assertNotNull(reason)
        assertEquals("radial", reason!!.mechanism)
    }

    @Test
    fun onAdvancement_firesOnProjectionWithDiagnostics() {
        val f = TrailFollower()
        f.start(listOf(gA, gB, gC), thresholdM = 5.0)
        f.onLocationUpdate(LatLng(gA.lat, gA.lon))
        var reason: AdvancementReason? = null
        f.onAdvancement = { reason = it }

        f.onLocationUpdate(LatLng(0.000855, 0.0)) // 95%, on-trail
        assertNotNull(reason)
        assertEquals("projection", reason!!.mechanism)
        assertNotNull(reason!!.segmentFraction)
        assertNotNull(reason!!.crossTrackM)
    }

    @Test
    fun onAdvancement_noCallbackWhenNotSet() {
        // Ensure no crash when onAdvancement is null (default).
        val f = TrailFollower()
        f.start(listOf(wp1, wp2), thresholdM = 15.0)
        f.onLocationUpdate(LatLng(0.0, 0.0)) // should not throw
    }

    // ── 3D distance in fireAdvance ────────────────────────────────────────────────

    @Test
    fun fireAdvance_uses3DDistanceWhenElevationAvailable() {
        // Next waypoint is 0m horizontal away but 30m above. 3D distance should be ~30m.
        val base = TrailPoint(70, "Base", 0.0, 0.0, elevationM = 0.0)
        val summit = TrailPoint(71, "Summit", 0.00009, 0.0, elevationM = 30.0)
        val f = TrailFollower()
        f.start(listOf(base, summit), thresholdM = 15.0)
        // Arrive at base; altitudeM=0.0 so elevation delta to summit is 30m.
        val event = f.onLocationUpdate(LatLng(0.0, 0.0), altitudeM = 0.0) as TrailFollowerEvent.WaypointReached
        // Horizontal distance from (0,0) to summit ~10m; 3D = sqrt(10²+30²) ≈ 31.6m
        val expectedMin = sqrt(5.0 * 5.0 + 30.0 * 30.0) // conservative lower bound
        assertTrue(
            event.distanceToNextM >= expectedMin,
            "expected 3D distance >= $expectedMin, got ${event.distanceToNextM}",
        )
    }

    @Test
    fun fireAdvance_fallsBackTo2DWhenElevationMissing() {
        // No elevationM on TrailPoint; distanceToNextM must equal haversine (2D).
        val f = TrailFollower()
        f.start(listOf(wp1, wp2), thresholdM = 15.0)
        val event = f.onLocationUpdate(LatLng(0.0, 0.0), altitudeM = 100.0) as TrailFollowerEvent.WaypointReached
        // wp2 has no elevationM — should ignore altitudeM and use haversine only (~10m)
        assertTrue(event.distanceToNextM < 20.0, "expected 2D haversine (~10m), got ${event.distanceToNextM}")
    }
}
