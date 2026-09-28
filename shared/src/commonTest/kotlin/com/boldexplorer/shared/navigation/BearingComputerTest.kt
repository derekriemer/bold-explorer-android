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
        val rightward = deltaAngle(0.0, 90.0) // heading N, target E → positive
        assertTrue(
            BearingComputer.toRelative(rightward).contains("right"),
            "deltaAngle(N→E)=$rightward should map to a right label",
        )
        val leftward = deltaAngle(90.0, 0.0) // heading E, target N → negative
        assertTrue(
            BearingComputer.toRelative(leftward).contains("left"),
            "deltaAngle(E→N)=$leftward should map to a left label",
        )
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

    // ---------- computeAlignmentPan ----------
    //
    // The old design mapped a sine curve onto ±45° and then hard-clamped flat beyond it: that sine
    // already went nearly flat approaching its own ±45° edge (well before the clamp even kicked in),
    // and the clamp pinned pan at +1 approaching 180° from one side but -1 approaching -180° from the
    // other -- an audible snap when slowly turning past directly-away-from-target. The replacement is
    // one continuous curve for the whole ±180° range: steeper than plain sin() near dead-ahead (so
    // centering keeps good resolution), but converging to the same value (0.0) from both sides as it
    // approaches ±180°, matching how the ordinary beacon's [computePan] already treats "behind" as
    // centred -- so crossing that point is inaudible instead of a jump.

    @Test
    fun computeAlignmentPan_at0deg_isZero() {
        assertEquals(0.0f, BearingComputer.computeAlignmentPan(0.0), absoluteTolerance = 1e-6f)
    }

    @Test
    fun computeAlignmentPan_isOddSymmetric() {
        for (deg in -180..180 step 5) {
            val pos = BearingComputer.computeAlignmentPan(deg.toDouble())
            val neg = BearingComputer.computeAlignmentPan(-deg.toDouble())
            assertEquals(pos, -neg, absoluteTolerance = 1e-6f, message = "asymmetric at $deg°")
        }
    }

    @Test
    fun computeAlignmentPan_nearCentre_isSteeperThanPlainSin() {
        // The whole point of a dedicated alignment curve: more resolution near dead-ahead than the
        // ordinary beacon's plain sin(relativeDeg) gives.
        val alignment = BearingComputer.computeAlignmentPan(15.0)
        val plain = BearingComputer.computePan(15.0)
        assertTrue(alignment > plain, "expected alignment pan ($alignment) > plain sin pan ($plain) at 15°")
    }

    @Test
    fun computeAlignmentPan_at180deg_isZero() {
        assertEquals(0.0f, BearingComputer.computeAlignmentPan(180.0), absoluteTolerance = 1e-4f)
        assertEquals(0.0f, BearingComputer.computeAlignmentPan(-180.0), absoluteTolerance = 1e-4f)
    }

    @Test
    fun computeAlignmentPan_hasNoJumpCrossingBehindTarget() {
        // 179.9° and -179.9° are physically 0.2° apart (both mean "almost directly behind"); a
        // continuous mapping must treat them almost identically instead of snapping between them.
        val justPositive = BearingComputer.computeAlignmentPan(179.9)
        val justNegative = BearingComputer.computeAlignmentPan(-179.9)
        assertTrue(
            abs(justPositive - justNegative) < 0.01f,
            "expected near-identical pan either side of ±180°, got $justPositive vs $justNegative",
        )
    }

    @Test
    fun computeAlignmentPan_neverExceedsBounds() {
        for (deg in -180..180) {
            val pan = BearingComputer.computeAlignmentPan(deg.toDouble())
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
