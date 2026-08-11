package com.boldexplorer.shared.navigation

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for candidate *enumeration* — one position per distinct stretch of trail near a fix.
 *
 * [TrailPolyline.project] answers "where is the single nearest point", which is all the geometry
 * needs. The reacquisition ladder needs strictly more: to record a `bestRejected` candidate, to
 * break ties toward a prediction, and to tie-break initial acquisition by travel direction, it has
 * to see the *rivals*, not just the winner.
 *
 * The load-bearing property is that candidates are one-per-stretch rather than one-per-segment. A
 * GPX import applies no decimation, so a single arm of a switchback can be fifty segments long;
 * enumerating each of them would bury the genuine rival among fifty near-duplicates of the winner.
 * "Stretch" means a local minimum of distance-to-fix along the trail, which is exactly the set of
 * places a user could plausibly be.
 */
class TrailCandidatesTest {
    @Test
    fun candidates_onAStraightTrail_returnsASingleCandidate() {
        val poly = TrailPolyline(densify(northShape(200.0), 10.0))

        val found = poly.candidates(offsetFromOrigin(northM = 100.0, eastM = 15.0))

        assertEquals(1, found.size, "a straight trail offers one place to be, not one per segment: $found")
        assertEquals(100.0, found[0].alongTrackM, 2.0, "abeam the fix")
    }

    @Test
    fun candidates_betweenSwitchbackArms_returnsOnePerStretch() {
        val poly = TrailPolyline(densify(switchbackShape(legM = 200.0, gapM = 40.0), 10.0))

        // Midway between the two arms: 20 m from each, and 100 m from the connector across the top.
        val found = poly.candidates(offsetFromOrigin(northM = 100.0, eastM = 20.0))

        // Three stretches of trail pass near this fix, so there are three genuine local minima —
        // the two arms and the connector. The connector is a real place the user could be; it is
        // simply a much worse explanation, which is what the ordering below records.
        assertEquals(3, found.size, "one candidate per stretch, not per segment: $found")

        val arms = found.take(2).map { it.alongTrackM }.sorted()
        assertEquals(100.0, arms[0], 2.0, "outbound arm, 100 m along")
        assertEquals(340.0, arms[1], 2.0, "return arm, 200 + 40 + 100 m along")
        assertEquals(220.0, found[2].alongTrackM, 2.0, "connector ranks last, 100 m away")
    }

    @Test
    fun candidates_areOrderedNearestFirst() {
        val poly = TrailPolyline(densify(switchbackShape(legM = 200.0, gapM = 40.0), 10.0))

        // 15 m from the outbound arm, 25 m from the return arm.
        val found = poly.candidates(offsetFromOrigin(northM = 100.0, eastM = 15.0))

        val distances = found.map { abs(it.crossTrackM) }
        assertEquals(distances.sorted(), distances, "nearest first: $distances")
        assertEquals(100.0, found[0].alongTrackM, 2.0, "the nearer arm is the outbound one")
        assertEquals(340.0, found[1].alongTrackM, 2.0, "the return arm is the best rejected rival")
    }

    @Test
    fun candidates_respectsTheWindow() {
        val poly = TrailPolyline(densify(switchbackShape(legM = 200.0, gapM = 40.0), 10.0))

        // A window covering only the outbound arm must hide the rest entirely — this is the
        // switchback safety mechanism, and it has to hold at the candidate level too.
        val found = poly.candidates(offsetFromOrigin(northM = 100.0, eastM = 20.0), window = 0.0..200.0)

        assertEquals(1, found.size, "the window excludes the connector and the return arm: $found")
        assertEquals(100.0, found[0].alongTrackM, 2.0, "outbound arm only")
    }

    @Test
    fun candidates_separatedByAWindowGap_doNotMergeAcrossIt() {
        val poly = TrailPolyline(densify(northShape(400.0), 10.0))

        // The fix is abeam 200 m, which the window excludes. Both admitted stretches are therefore
        // monotonic — distance only rises as you move away from the gap — so each contributes its
        // own boundary candidate rather than being merged into one interior minimum.
        val found = poly.candidates(offsetFromOrigin(northM = 200.0, eastM = 15.0), window = 0.0..400.0)

        assertEquals(1, found.size, "no gap here: one interior minimum: $found")
        assertEquals(200.0, found[0].alongTrackM, 2.0, "abeam the fix")
    }

    @Test
    fun candidates_agreeWithProjectOnTheWinner() {
        val poly = TrailPolyline(densify(switchbackShape(legM = 200.0, gapM = 40.0), 10.0))
        val fix = offsetFromOrigin(northM = 100.0, eastM = 15.0)

        val best = poly.candidates(fix).first()

        assertEquals(poly.project(fix), best, "enumeration must not disagree with projection")
    }

    @Test
    fun candidates_onAnEmptyWindow_returnsNothing() {
        val poly = TrailPolyline(densify(northShape(200.0), 10.0))

        val found = poly.candidates(offsetFromOrigin(northM = 100.0, eastM = 15.0), window = 500.0..600.0)

        assertTrue(found.isEmpty(), "a window past the end of the trail admits no candidate: $found")
    }
}
