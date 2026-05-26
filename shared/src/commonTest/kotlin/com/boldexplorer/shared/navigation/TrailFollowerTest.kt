package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertEquals

class TrailFollowerTest {
    // Two waypoints ~10 m apart so threshold=15m will trigger easily
    private val wp1 = TrailPoint(1, "Start", 0.0, 0.0)
    private val wp2 = TrailPoint(2, "Middle", 0.00009, 0.0) // ~10 m north
    private val wp3 = TrailPoint(3, "End", 0.00018, 0.0)    // ~20 m north

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
        // Far away — 1 km north of wp1
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
        val state = f.state.value as TrailFollowerState.Active
        assertEquals(1, state.currentIndex)
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
}
