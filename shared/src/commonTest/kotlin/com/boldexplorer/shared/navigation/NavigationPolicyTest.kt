package com.boldexplorer.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the two accuracy-aware threshold shapes in [NavigationPolicy].
 *
 * [NavigationPolicy.widenWithAccuracy] and [NavigationPolicy.tightenWithAccuracy] move in opposite
 * directions as GPS accuracy degrades — widen vs. tighten — but both must stay bounded regardless
 * of how bad the accuracy report is. That bounded-uncertainty property is what issue #55 exists to
 * guarantee everywhere a decision threshold scales with accuracy.
 */
class NavigationPolicyTest {
    private val accuracies = listOf(3.0, 10.0, 30.0, 100.0)

    // ── widenWithAccuracy ─────────────────────────────────────────────────────────────────────

    @Test
    fun widenWithAccuracy_neverExceedsTheCap() {
        val baseM = 20.0
        val factor = 2.0
        val capM = 40.0
        for (acc in accuracies) {
            val result = NavigationPolicy.widenWithAccuracy(baseM, factor, acc, capM)
            assertTrue(result <= capM, "accuracy=$acc gave $result, expected <= cap $capM")
        }
    }

    @Test
    fun widenWithAccuracy_neverDropsBelowTheBase() {
        val baseM = 20.0
        val factor = 2.0
        val capM = 40.0
        for (acc in accuracies) {
            val result = NavigationPolicy.widenWithAccuracy(baseM, factor, acc, capM)
            assertTrue(result >= baseM, "accuracy=$acc gave $result, expected >= base $baseM")
        }
    }

    @Test
    fun widenWithAccuracy_nullAccuracy_returnsBase() {
        assertEquals(20.0, NavigationPolicy.widenWithAccuracy(baseM = 20.0, factor = 2.0, accuracyM = null, capM = 40.0))
    }

    @Test
    fun widenWithAccuracy_scalesLinearly_untilCapped() {
        // 10 m accuracy * factor 2 = 20 m, above the 20 m base, below the 40 m cap.
        assertEquals(20.0, NavigationPolicy.widenWithAccuracy(baseM = 20.0, factor = 2.0, accuracyM = 10.0, capM = 40.0))
        // 30 m accuracy * factor 2 = 60 m, clamped at the 40 m cap.
        assertEquals(40.0, NavigationPolicy.widenWithAccuracy(baseM = 20.0, factor = 2.0, accuracyM = 30.0, capM = 40.0))
        // 100 m accuracy: still clamped at the cap, not 200 m.
        assertEquals(40.0, NavigationPolicy.widenWithAccuracy(baseM = 20.0, factor = 2.0, accuracyM = 100.0, capM = 40.0))
    }

    // ── tightenWithAccuracy ───────────────────────────────────────────────────────────────────

    @Test
    fun tightenWithAccuracy_neverExceedsTheCeiling() {
        val ceilingM = 15.0
        val floorM = 5.0
        val factor = 2.0
        for (acc in accuracies) {
            val result = NavigationPolicy.tightenWithAccuracy(ceilingM, floorM, factor, acc)
            assertTrue(result <= ceilingM, "accuracy=$acc gave $result, expected <= ceiling $ceilingM")
        }
    }

    @Test
    fun tightenWithAccuracy_neverDropsBelowTheFloor() {
        val ceilingM = 15.0
        val floorM = 5.0
        val factor = 2.0
        for (acc in accuracies) {
            val result = NavigationPolicy.tightenWithAccuracy(ceilingM, floorM, factor, acc)
            assertTrue(result >= floorM, "accuracy=$acc gave $result, expected >= floor $floorM")
        }
    }

    @Test
    fun tightenWithAccuracy_nullAccuracy_returnsTheCeiling() {
        assertEquals(15.0, NavigationPolicy.tightenWithAccuracy(ceilingM = 15.0, floorM = 5.0, factor = 2.0, accuracyM = null))
    }

    @Test
    fun tightenWithAccuracy_poorAccuracy_doesNotWidenTheCompletionRadius() {
        // The bounded-uncertainty rule, asserted against the real completion policy values: a very
        // poor 100 m fix must not push the acceptance region past the 15 m ceiling. Widening
        // acceptance exactly when the fix is least trustworthy is the anti-pattern #55 removes.
        val radius =
            NavigationPolicy.tightenWithAccuracy(
                ceilingM = NavigationPolicy.COMPLETION_CEILING_M,
                floorM = NavigationPolicy.COMPLETION_FLOOR_M,
                factor = NavigationPolicy.COMPLETION_SIGMA_FACTOR,
                accuracyM = 100.0,
            )
        assertEquals(NavigationPolicy.COMPLETION_CEILING_M, radius, "100 m accuracy must clamp at the ceiling, not widen past it")
    }

    @Test
    fun tightenWithAccuracy_scalesDown_untilFloored() {
        // 3 m accuracy * factor 2 = 6 m, below the 15 m ceiling, above the 5 m floor.
        assertEquals(6.0, NavigationPolicy.tightenWithAccuracy(ceilingM = 15.0, floorM = 5.0, factor = 2.0, accuracyM = 3.0))
        // 1 m accuracy * factor 2 = 2 m, clamped up to the 5 m floor.
        assertEquals(5.0, NavigationPolicy.tightenWithAccuracy(ceilingM = 15.0, floorM = 5.0, factor = 2.0, accuracyM = 1.0))
    }
}
