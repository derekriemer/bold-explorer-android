package com.boldexplorer.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TrailRecordingMachineTest {
    @Test
    fun idle_byDefault() {
        val m = TrailRecordingMachine()
        assertIs<TrailRecordingState.Idle>(m.state.value)
    }

    @Test
    fun selectTrail_fromIdle_toSelected() {
        val m = TrailRecordingMachine()
        m.selectTrail(7, hasPoints = true)
        val s = m.state.value as TrailRecordingState.Selected
        assertEquals(7, s.trailId)
        assertEquals(true, s.hasPoints)
    }

    @Test
    fun selectTrail_fromSelected_reselects() {
        val m = TrailRecordingMachine()
        m.selectTrail(1, hasPoints = false)
        m.selectTrail(2, hasPoints = true)
        val s = m.state.value as TrailRecordingState.Selected
        assertEquals(2, s.trailId)
        assertEquals(true, s.hasPoints)
    }

    @Test
    fun startFollowing_fromSelected_toFollowing() {
        val m = TrailRecordingMachine()
        m.selectTrail(3, hasPoints = true)
        m.startFollowing()
        assertEquals(TrailRecordingState.Following(3), m.state.value)
    }

    @Test
    fun startRecording_fromSelected_toRecordingZero() {
        val m = TrailRecordingMachine()
        m.selectTrail(4, hasPoints = false)
        m.startRecording()
        assertEquals(TrailRecordingState.Recording(4, 0), m.state.value)
    }

    @Test
    fun addPoint_incrementsCount() {
        val m = TrailRecordingMachine()
        m.selectTrail(5, hasPoints = false)
        m.startRecording()
        m.addPoint()
        m.addPoint()
        assertEquals(TrailRecordingState.Recording(5, 2), m.state.value)
    }

    @Test
    fun stop_fromRecording_toSelectedWithPoints() {
        val m = TrailRecordingMachine()
        m.selectTrail(6, hasPoints = false)
        m.startRecording()
        m.addPoint()
        m.stop()
        assertEquals(TrailRecordingState.Selected(6, hasPoints = true), m.state.value)
    }

    @Test
    fun stop_fromFollowing_toSelected() {
        val m = TrailRecordingMachine()
        m.selectTrail(8, hasPoints = true)
        m.startFollowing()
        m.stop()
        assertEquals(TrailRecordingState.Selected(8, hasPoints = true), m.state.value)
    }

    @Test
    fun stop_fromSelected_isNoOp() {
        val m = TrailRecordingMachine()
        m.selectTrail(9, hasPoints = false)
        m.stop()
        assertEquals(TrailRecordingState.Selected(9, hasPoints = false), m.state.value)
    }

    @Test
    fun stop_fromIdle_isNoOp() {
        val m = TrailRecordingMachine()
        m.stop()
        assertIs<TrailRecordingState.Idle>(m.state.value)
    }

    // ── Mutual exclusion: invalid transitions are rejected ───────────────────────────

    @Test
    fun startFollowing_whileRecording_rejected() {
        val m = TrailRecordingMachine()
        m.selectTrail(1, hasPoints = false)
        m.startRecording()
        assertFailsWith<IllegalStateException> { m.startFollowing() }
    }

    @Test
    fun startRecording_whileFollowing_rejected() {
        val m = TrailRecordingMachine()
        m.selectTrail(1, hasPoints = true)
        m.startFollowing()
        assertFailsWith<IllegalStateException> { m.startRecording() }
    }

    @Test
    fun startFollowing_fromIdle_rejected() {
        val m = TrailRecordingMachine()
        assertFailsWith<IllegalStateException> { m.startFollowing() }
    }

    @Test
    fun startRecording_fromIdle_rejected() {
        val m = TrailRecordingMachine()
        assertFailsWith<IllegalStateException> { m.startRecording() }
    }

    @Test
    fun addPoint_whileIdle_rejected() {
        val m = TrailRecordingMachine()
        assertFailsWith<IllegalStateException> { m.addPoint() }
    }

    @Test
    fun addPoint_whileSelected_rejected() {
        val m = TrailRecordingMachine()
        m.selectTrail(1, hasPoints = true)
        assertFailsWith<IllegalStateException> { m.addPoint() }
    }

    @Test
    fun addPoint_whileFollowing_rejected() {
        val m = TrailRecordingMachine()
        m.selectTrail(1, hasPoints = true)
        m.startFollowing()
        assertFailsWith<IllegalStateException> { m.addPoint() }
    }

    @Test
    fun selectTrail_whileRecording_rejected() {
        val m = TrailRecordingMachine()
        m.selectTrail(1, hasPoints = false)
        m.startRecording()
        assertFailsWith<IllegalStateException> { m.selectTrail(2, hasPoints = true) }
    }

    @Test
    fun selectTrail_whileFollowing_rejected() {
        val m = TrailRecordingMachine()
        m.selectTrail(1, hasPoints = true)
        m.startFollowing()
        assertFailsWith<IllegalStateException> { m.selectTrail(2, hasPoints = true) }
    }

    @Test
    fun fullCycle_recordThenStopThenFollow() {
        val m = TrailRecordingMachine()
        m.selectTrail(1, hasPoints = false)
        m.startRecording()
        m.addPoint()
        m.stop() // Selected(hasPoints=true)
        m.startFollowing()
        assertEquals(TrailRecordingState.Following(1), m.state.value)
    }
}
