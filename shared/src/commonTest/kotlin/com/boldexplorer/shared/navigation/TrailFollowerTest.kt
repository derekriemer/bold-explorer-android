package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // The event that used to carry index/name/kind/total/distance/bearing is gone — advancing
        // off a waypoint is now silent. The state still moves; nothing is emitted to say so.
        val f = TrailFollower()
        f.start(listOf(wp1, wp2, wp3), thresholdM = 15.0)
        // Arrive at wp1 (0, 0)
        val event = f.onLocationUpdate(LatLng(0.0, 0.0))
        assertNull(event, "advancing off a waypoint must not speak")
        val state = f.state.value as TrailFollowerState.Active
        assertEquals(1, state.currentIndex, "target still advances even though nothing is emitted")
    }

    // waypointReached_includesDistanceAndBearing is deleted, not rewritten: it asserted on
    // WaypointReached.distanceToNextM/absoluteBearingDeg, fields that no longer exist because the
    // computation that produced them (in fireAdvance, for a non-terminal advance) was deleted along
    // with the emission site. Nothing downstream depends on a next-target distance/bearing computed
    // *inside* TrailFollower any more — the dedicated cue producers compute their own.

    @Test
    fun trackPointKind_alsoAdvancesSilently() {
        // What survives of waypointReached_trackPointKindPropagates: a track-point-kind trail point
        // still advances the target. What's gone is the event.kind field it used to assert on —
        // there is no event to carry it.
        val tp1 = TrailPoint(1, "Track 10:00:00", 0.0, 0.0, kind = com.boldexplorer.shared.model.Waypoint.KIND_TRACK_POINT)
        val tp2 = TrailPoint(2, "Track 10:00:10", 0.00009, 0.0, kind = com.boldexplorer.shared.model.Waypoint.KIND_TRACK_POINT)
        val f = TrailFollower()
        f.start(listOf(tp1, tp2), thresholdM = 15.0)
        val event = f.onLocationUpdate(LatLng(0.0, 0.0))
        assertNull(event, "track points are silent regardless of kind")
        assertEquals(1, (f.state.value as TrailFollowerState.Active).currentIndex)
    }

    /** A session that has walked far enough for a completion to be believable. */
    private val walked = CompletionEvidence(pastTheEnd = false, travelled = true)

    @Test
    fun trailComplete_whenLastWaypointReached() {
        val f = TrailFollower()
        f.start(listOf(wp1, wp2), thresholdM = 15.0)
        // Reach wp1 → advances to wp2
        f.onLocationUpdate(LatLng(0.0, 0.0))
        // Now reach wp2
        val event = f.onLocationUpdate(LatLng(0.00009, 0.0), completion = walked)
        assertIs<TrailFollowerEvent.TrailComplete>(event)
        assertIs<TrailFollowerState.Complete>(f.state.value)
    }

    @Test
    fun pastTheEnd_completesEvenWithTheIndexNowhereNearTheEnd() {
        // The point of deciding completion from the match rather than from `currentIndex`. The
        // index only advances when a waypoint check fires, so a walker can be standing past the
        // end of the trail with it still pointing at the first waypoint — and the radial branch
        // that owns completion never runs, because it is gated on the index being at the last one.
        // The walk then never finishes: the follow stays active and guidance keeps steering at a
        // waypoint behind the user.
        val f = TrailFollower()
        f.start(listOf(wp1, wp2), thresholdM = 15.0)
        assertIs<TrailFollowerState.Active>(f.state.value)
        assertEquals(0, (f.state.value as TrailFollowerState.Active).currentIndex, "precondition: index at the start")

        // Somewhere far from either waypoint, so no radial or projection check can fire.
        val event =
            f.onLocationUpdate(
                LatLng(0.01, 0.01),
                completion = CompletionEvidence(pastTheEnd = true, travelled = true),
            )

        assertIs<TrailFollowerEvent.TrailComplete>(event)
        assertIs<TrailFollowerState.Complete>(f.state.value)
    }

    @Test
    fun pastTheEndWithoutTravelDoesNotComplete() {
        // Being at the end is not evidence of having walked to it — a loop's start is also its end.
        val f = TrailFollower()
        f.start(listOf(wp1, wp2), thresholdM = 15.0)

        val event =
            f.onLocationUpdate(
                LatLng(0.01, 0.01),
                completion = CompletionEvidence(pastTheEnd = true, travelled = false),
            )

        assertNull(event, "completed a trail the session had not walked")
        assertIs<TrailFollowerState.Active>(f.state.value)
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
        assertNull(event, "advancing must not speak")
        assertEquals(2, (f.state.value as TrailFollowerState.Active).currentIndex) // advanced past spB; new target is spC
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
        assertNull(event, "advancing must not speak")
        assertEquals(1, (f.state.value as TrailFollowerState.Active).currentIndex) // advanced past dvA; new target is dvB
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
        assertNull(event, "advancing must not speak")
        assertEquals(2, (f.state.value as TrailFollowerState.Active).currentIndex, "projection still fired")
    }

    @Test
    fun projectionGate_headingAllowsWhenAligned() {
        // COG is north (~0°), segment runs north → heading difference ≈ 0° < 60° → fires.
        val f = TrailFollower()
        f.start(listOf(gA, gB, gC), thresholdM = 5.0)
        f.onLocationUpdate(LatLng(gA.lat, gA.lon))

        val at95pct = LatLng(0.000855, 0.0)
        val event = f.onLocationUpdate(at95pct, bearingDeg = 5f) // nearly north
        assertNull(event, "advancing must not speak")
        assertEquals(2, (f.state.value as TrailFollowerState.Active).currentIndex, "projection still fired")
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

    // ── Cascade guard (issue #9) ─────────────────────────────────────────────────
    //
    // Dense checkpoints (5 m apart, closer than the 15 m threshold) so several are
    // simultaneously within radial range of one stationary position.

    private val denseA = TrailPoint(80, "A", 0.0, 0.0)
    private val denseB = TrailPoint(81, "B", 0.000045, 0.0) // ~5 m north
    private val denseC = TrailPoint(82, "C", 0.00009, 0.0) // ~10 m north
    private val denseD = TrailPoint(83, "D", 0.000135, 0.0) // ~15 m north

    @Test
    fun cascade_blockedWhenStationaryNearDenseCheckpoints() {
        val f = TrailFollower()
        f.start(listOf(denseA, denseB, denseC, denseD), thresholdM = 15.0)

        // First fix: user is standing at denseA — legitimate "already here" advance to denseB.
        val first = f.onLocationUpdate(LatLng(denseA.lat, denseA.lon))
        assertNull(first, "advancing must not speak")
        assertEquals(1, (f.state.value as TrailFollowerState.Active).currentIndex)

        // Second fix: same physical position (no movement) — denseB is only ~5m away, well
        // within the 15m threshold, but the user hasn't actually walked anywhere. Must not cascade.
        val second = f.onLocationUpdate(LatLng(denseA.lat, denseA.lon))
        assertNull(second)
        assertEquals(1, (f.state.value as TrailFollowerState.Active).currentIndex)
    }

    @Test
    fun cascade_allowedAfterRealMovement() {
        val f = TrailFollower()
        f.start(listOf(denseA, denseB, denseC, denseD), thresholdM = 15.0)

        f.onLocationUpdate(LatLng(denseA.lat, denseA.lon)) // advance to denseB

        // User genuinely walks to denseB (~5 m north) — a real advance should still fire.
        val event = f.onLocationUpdate(LatLng(denseB.lat, denseB.lon))
        assertNull(event, "advancing must not speak")
        assertEquals(2, (f.state.value as TrailFollowerState.Active).currentIndex)
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

    // fireAdvance_uses3DDistanceWhenElevationAvailable and fireAdvance_fallsBackTo2DWhenElevationMissing
    // are deleted, not rewritten: they tested the 3D-vs-2D distanceToNextM computation that used to
    // run inside fireAdvance to build the WaypointReached event. That computation (nextWp/nextLL/
    // horizDist/distToNext, and the distance3DMeters call) was deleted along with the emission site
    // itself, not merely hidden from the event — nothing in fireAdvance computes a next-target
    // distance any more, silent or otherwise. There is no remaining behaviour to assert on.

    // ── Smoothed bearing (slow-walker heading fallback) ───────────────────────
    //
    // Reuses the spA/spB/spC geometry (~100 m apart going north, threshold = 5 m).

    @Test
    fun incomingProjection_firesWithSmoothedBearing_whenNoBearingDeg() {
        val f = TrailFollower()
        var reason: AdvancementReason? = null
        f.onAdvancement = { reason = it }
        f.start(listOf(spA, spB, spC), thresholdM = 5.0)
        f.onLocationUpdate(LatLng(spA.lat, spA.lon)) // advance to spB target

        val at95pct = LatLng(0.000855, 0.0) // 95% along spA→spB
        // bearingDeg = null (slow walker), smoothedBearingDeg = 0° (North — agrees with segment)
        val event = f.onLocationUpdate(at95pct, bearingDeg = null, smoothedBearingDeg = 0f)
        assertNull(event, "advancing must not speak")
        assertNotNull(reason)
        assertEquals("projection", reason!!.mechanism)
        assertTrue(reason!!.smoothedBearingUsed)
    }

    @Test
    fun incomingProjection_doesNotFire_whenSmoothedBearingOpposesSegment() {
        val f = TrailFollower()
        f.start(listOf(spA, spB, spC), thresholdM = 5.0)
        f.onLocationUpdate(LatLng(spA.lat, spA.lon)) // advance to spB target

        val at95pct = LatLng(0.000855, 0.0)
        // Smoothed bearing 180° (South) — opposite to Northbound segment
        val event = f.onLocationUpdate(at95pct, bearingDeg = null, smoothedBearingDeg = 180f)
        assertNull(event)
    }

    @Test
    fun smoothedBearingUsed_isFalse_whenBearingDegProvided() {
        val f = TrailFollower()
        var reason: AdvancementReason? = null
        f.onAdvancement = { reason = it }
        f.start(listOf(spA, spB, spC), thresholdM = 5.0)
        f.onLocationUpdate(LatLng(spA.lat, spA.lon))

        val at95pct = LatLng(0.000855, 0.0)
        // Both bearingDeg and smoothedBearingDeg provided — bearingDeg takes precedence
        f.onLocationUpdate(at95pct, bearingDeg = 0f, smoothedBearingDeg = 0f)
        assertNotNull(reason)
        assertFalse(reason!!.smoothedBearingUsed)
    }

    // ── Silent track points (S6: the event vocabulary) ───────────────────────────
    //
    // Track points recorded every ~10 m used to speak "Checkpoint N of M" on every single fix that
    // crossed one — a report of an index into a vertex array at whatever rate the trail happened to
    // be recorded at. That firehose is gone: passing a track point now advances the target with no
    // event at all.

    private val tpA = TrailPoint(90, "Track A", 0.0, 0.0, kind = com.boldexplorer.shared.model.Waypoint.KIND_TRACK_POINT)
    private val tpB = TrailPoint(91, "Track B", 0.00018, 0.0, kind = com.boldexplorer.shared.model.Waypoint.KIND_TRACK_POINT) // ~20 m north
    private val tpC = TrailPoint(92, "Track C", 0.00036, 0.0, kind = com.boldexplorer.shared.model.Waypoint.KIND_TRACK_POINT) // ~40 m north
    private val tpD = TrailPoint(93, "Track D", 0.00054, 0.0, kind = com.boldexplorer.shared.model.Waypoint.KIND_TRACK_POINT) // ~60 m north
    private val tpE = TrailPoint(94, "Track E", 0.00072, 0.0, kind = com.boldexplorer.shared.model.Waypoint.KIND_TRACK_POINT) // ~80 m north

    @Test
    fun passingATrackPointEmitsNothing() {
        // A ruling on the brief's original version of this test: `(0..5).mapNotNull { ... }`
        // followed by `events.all { it is TrailComplete }` is vacuous once track points are silent
        // — mapNotNull yields an empty list, and `all {}` over an empty list is true regardless of
        // what actually happened. Assert the list is genuinely empty instead, and prove the follower
        // hasn't simply gone deaf to everything by checking a real TrailComplete still fires at the
        // trail's end.
        val f = TrailFollower()
        f.start(listOf(tpA, tpB, tpC, tpD, tpE), thresholdM = 15.0)

        val midTrailFixes = listOf(tpA, tpB, tpC, tpD)
        val events = midTrailFixes.mapNotNull { f.onLocationUpdate(LatLng(it.lat, it.lon)) }
        assertTrue(events.isEmpty(), "track points must be silent, got $events")
        assertEquals(4, (f.state.value as TrailFollowerState.Active).currentIndex, "target still advances silently")

        val finalEvent =
            f.onLocationUpdate(
                LatLng(tpE.lat, tpE.lon),
                completion = CompletionEvidence(pastTheEnd = false, travelled = true),
            )
        assertIs<TrailFollowerEvent.TrailComplete>(finalEvent, "the trail's end must still be announced")
    }
}
