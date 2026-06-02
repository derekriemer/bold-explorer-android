package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.deltaAngle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BearingComputerTest {

    // ---------- toRelative ----------

    @Test
    fun toRelative_positiveIsRight() {
        assertTrue(BearingComputer.toRelative(30.0).contains("right"))
        assertTrue(BearingComputer.toRelative(90.0).contains("right"))
        assertTrue(BearingComputer.toRelative(150.0).contains("right"))
    }

    @Test
    fun toRelative_negativeIsLeft() {
        assertTrue(BearingComputer.toRelative(-30.0).contains("left"))
        assertTrue(BearingComputer.toRelative(-90.0).contains("left"))
        assertTrue(BearingComputer.toRelative(-150.0).contains("left"))
    }

    @Test
    fun toRelative_zeroIsStraightAhead() {
        assertEquals("straight ahead", BearingComputer.toRelative(0.0))
        assertEquals("straight ahead", BearingComputer.toRelative(10.0))
        assertEquals("straight ahead", BearingComputer.toRelative(-10.0))
    }

    @Test
    fun toRelative_consistentWithDeltaAngle() {
        // Cross-module contract: deltaAngle positive = target is right of heading.
        // toRelative must agree. If either function's sign convention is changed
        // without updating the other, this test fails.
        val rightward = deltaAngle(0.0, 90.0)   // heading N, target E → positive
        assertTrue(BearingComputer.toRelative(rightward).contains("right"),
            "deltaAngle(N→E)=$rightward should map to a right label")
        val leftward = deltaAngle(90.0, 0.0)    // heading E, target N → negative
        assertTrue(BearingComputer.toRelative(leftward).contains("left"),
            "deltaAngle(E→N)=$leftward should map to a left label")
    }

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

    // ---------- toAlignmentRelative ----------

    @Test
    fun toAlignmentRelative_withinDeadband_isAligned() {
        assertEquals("aligned", BearingComputer.toAlignmentRelative(0.0))
        assertEquals("aligned", BearingComputer.toAlignmentRelative(5.0))
        assertEquals("aligned", BearingComputer.toAlignmentRelative(-5.0))
    }

    @Test
    fun toAlignmentRelative_positiveIsRight() {
        val result = BearingComputer.toAlignmentRelative(15.0)
        assertTrue(result.contains("right"), "expected 'right' in '$result'")
        assertTrue(result.contains("15"), "expected degrees in '$result'")
    }

    @Test
    fun toAlignmentRelative_negativeIsLeft() {
        val result = BearingComputer.toAlignmentRelative(-15.0)
        assertTrue(result.contains("left"), "expected 'left' in '$result'")
        assertTrue(result.contains("15"), "expected degrees in '$result'")
    }

    @Test
    fun toAlignmentRelative_neverSaysStraightAhead() {
        // Unlike toRelative, alignment mode must always give direction
        val result = BearingComputer.toAlignmentRelative(10.0)
        assertTrue(!result.contains("straight"), "should not say 'straight ahead' in alignment mode: '$result'")
    }

    // ---------- computeAlignmentPitchHz ----------

    @Test
    fun computeAlignmentPitchHz_at0deg_is880() {
        assertEquals(880.0, BearingComputer.computeAlignmentPitchHz(0.0), absoluteTolerance = 0.001)
    }

    @Test
    fun computeAlignmentPitchHz_at90deg_is440() {
        assertEquals(440.0, BearingComputer.computeAlignmentPitchHz(90.0), absoluteTolerance = 0.001)
    }

    @Test
    fun computeAlignmentPitchHz_at180deg_is220() {
        assertEquals(220.0, BearingComputer.computeAlignmentPitchHz(180.0), absoluteTolerance = 0.001)
    }

    @Test
    fun computeAlignmentPitchHz_symmetricLeftRight() {
        // 30° left and 30° right should sound identical in pitch
        assertEquals(
            BearingComputer.computeAlignmentPitchHz(30.0),
            BearingComputer.computeAlignmentPitchHz(-30.0),
            absoluteTolerance = 0.001,
        )
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
