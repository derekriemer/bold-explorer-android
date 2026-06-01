package com.boldexplorer.shared.navigation

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BearingComputerTest {

    // ---------- computePan ----------

    @Test
    fun computePan_at0deg_isZero() {
        assertEquals(0.0f, BearingComputer.computePan(0.0), absoluteTolerance = 1e-6f)
    }

    @Test
    fun computePan_at90deg_isOne() {
        assertEquals(1.0f, BearingComputer.computePan(90.0), absoluteTolerance = 1e-6f)
    }

    @Test
    fun computePan_atMinus90deg_isMinusOne() {
        assertEquals(-1.0f, BearingComputer.computePan(-90.0), absoluteTolerance = 1e-6f)
    }

    @Test
    fun computePan_at180deg_isZero() {
        // sin(π) ≈ 1.2e-16 — rounds to 0 within float tolerance
        assertEquals(0.0f, BearingComputer.computePan(180.0), absoluteTolerance = 1e-6f)
    }

    @Test
    fun computePan_at45deg_isSinOf45() {
        val expected = kotlin.math.sin(kotlin.math.PI / 4.0).toFloat()
        assertEquals(expected, BearingComputer.computePan(45.0), absoluteTolerance = 1e-6f)
    }

    @Test
    fun computePan_neverExceedsBounds() {
        for (deg in -360..360) {
            val pan = BearingComputer.computePan(deg.toDouble())
            assertTrue(pan >= -1.0f && pan <= 1.0f, "pan out of bounds at $deg°: $pan")
        }
    }

    // ---------- computePitchHz ----------

    @Test
    fun computePitchHz_at0deg_is880() {
        assertEquals(880.0, BearingComputer.computePitchHz(0.0), absoluteTolerance = 0.001)
    }

    @Test
    fun computePitchHz_at90deg_is440() {
        assertEquals(440.0, BearingComputer.computePitchHz(90.0), absoluteTolerance = 0.001)
    }

    @Test
    fun computePitchHz_atMinus90deg_is440() {
        assertEquals(440.0, BearingComputer.computePitchHz(-90.0), absoluteTolerance = 0.001)
    }

    @Test
    fun computePitchHz_at180deg_is220() {
        assertEquals(220.0, BearingComputer.computePitchHz(180.0), absoluteTolerance = 0.001)
    }

    @Test
    fun computePitchHz_atMinus180deg_is220() {
        assertEquals(220.0, BearingComputer.computePitchHz(-180.0), absoluteTolerance = 0.001)
    }
}
