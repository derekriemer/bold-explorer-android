package com.boldexplorer.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #69: a trail is a walked path, so a jump far beyond its own typical spacing is a data error.
 *
 * The failure this catches is silent and the app stays confident throughout, which is the worst
 * shape a defect can have for someone navigating by audio.
 */
class TrailGapCheckTest {
    /** A densely recorded trail, ~11 m spacing, like the one the field case came from. */
    private fun walked(points: Int = 40) = densify(northShape(points * 11.0), spacingM = 11.0)

    @Test
    fun aWalkedTrailHasNoImplausibleGaps() {
        assertTrue(TrailGapCheck.findGaps(walked()).isEmpty(), "a trail that was simply walked must not be flagged")
    }

    @Test
    fun aPointAppendedFarFromTheTrailIsFlagged() {
        // The `dos` case: 1031 m from its predecessor against ~11 m median spacing.
        val trail = walked() + offsetFromOrigin(northM = 1031.0 + 40 * 11.0, eastM = 0.0)

        val gaps = TrailGapCheck.findGaps(trail)

        assertEquals(1, gaps.size, "exactly one jump, at the end")
        assertEquals(trail.size - 2, gaps.single().afterIndex, "the gap is between the last two points")
        assertTrue(gaps.single().gapM > 1000.0, "gap measured at ${gaps.single().gapM} m")
    }

    @Test
    fun aRoutineGpsDropoutIsNotFlagged() {
        // Dense recording, one 90 m hole where the fix was lost. That is real walking, and a check
        // that fires on it is a check nobody reads. The absolute floor is what protects this case —
        // 20x an ~11 m median would flag it on its own.
        val before = densify(northShape(200.0), spacingM = 11.0)
        val after = (1..18).map { offsetFromOrigin(northM = 290.0 + it * 11.0, eastM = 0.0) }

        assertTrue(TrailGapCheck.findGaps(before + after).isEmpty(), "a 90 m dropout is walking, not an error")
    }

    @Test
    fun aSparseCuratedRouteIsJudgedAgainstItsOwnSpacing() {
        // Legs of 400 m are normal here, so the bar has to scale with the recording rather than
        // being one number for every trail.
        val sparse = (0..10).map { offsetFromOrigin(northM = it * 400.0, eastM = 0.0) }

        assertTrue(TrailGapCheck.findGaps(sparse).isEmpty(), "long legs are normal on a curated route")
    }

    @Test
    fun aTrailTooShortToHaveATypicalSpacingClaimsNothing() {
        val twoPoints = listOf(offsetFromOrigin(0.0, 0.0), offsetFromOrigin(5000.0, 0.0))

        assertTrue(TrailGapCheck.findGaps(twoPoints).isEmpty(), "two points cannot establish a median")
    }

    // ── The signals that only exist when points carry times ───────────────────────

    /** Timestamps a second apart, matching [walked]'s point count. */
    private fun steadyTimes(
        count: Int,
        stepMs: Long = 1_000L,
    ) = (0 until count).map { 1_000_000L + it * stepMs }

    @Test
    fun aPointSplicedInFromAnotherSessionIsFlaggedAsBackwardsTime() {
        // Trail 13's shape: an 8 km jump whose later point was recorded five hours *earlier* — two
        // sessions spliced together. The distance is what makes it worth reporting; the clock
        // running backwards is what says it was never one walk.
        val trail = walked() + offsetFromOrigin(northM = 8_192.0 + 40 * 11.0, eastM = 0.0)
        val times = steadyTimes(trail.size - 1) + (900_000L)

        val gaps = TrailGapCheck.findGaps(trail, times)

        assertEquals(1, gaps.size)
        assertEquals(GapReason.BackwardsTime, gaps.single().reason)
    }

    @Test
    fun batchInsertedPointsSharingATimestampAreNotFlagged() {
        // `created_at` is when the row was written, not when the fix was taken, so an import or any
        // bulk write gives a run of points one millisecond apart. Judged on speed alone that reads
        // as hundreds of metres per second for ordinary 1-11 m steps — eighteen consecutive
        // "impossible" hops on one real trail. Distance is what decides a gap is suspect at all.
        val trail = walked()
        val sameInstant = List(trail.size) { 1_000_000L }

        assertTrue(
            TrailGapCheck.findGaps(trail, sameInstant).isEmpty(),
            "a batch of points written at once is not a trail full of teleports",
        )
    }

    @Test
    fun aFixThatTeleportedAndCameBackIsFlaggedOnSpeed() {
        // A single wild fix five seconds after its neighbour and 282 m away — 56 m/s — then back
        // where it was a second later. The distance rule alone calls both hops implausible gaps;
        // the speed says what actually happened, which is that one fix moved rather than the walker.
        val walkedSoFar = walked(20)
        val outlier = offsetFromOrigin(northM = 20 * 11.0 + 282.0, eastM = 0.0)
        val trail = walkedSoFar + outlier + walkedSoFar.last()
        val afterWalk = 1_000_000L + walkedSoFar.size * 1_000L
        val times = steadyTimes(walkedSoFar.size) + listOf(afterWalk + 5_000L, afterWalk + 6_000L)

        val gaps = TrailGapCheck.findGaps(trail, times)

        assertEquals(2, gaps.size, "out and back is two hops")
        assertTrue(
            gaps.all { it.reason == GapReason.ImpossibleSpeed },
            "both hops are speed failures, not distance ones: ${gaps.map { it.reason }}",
        )
    }

    @Test
    fun aPointAppendedLongAfterwardsIsStillCaughtByDistance() {
        // `dos`: 1031 m at 78 minutes is 0.22 m/s — an ordinary walking pace, so speed says nothing.
        // The distance rule is what catches the case #69 was written for, which is why time is an
        // additional signal rather than a replacement.
        val trail = walked() + offsetFromOrigin(northM = 1031.0 + 40 * 11.0, eastM = 0.0)
        val times = steadyTimes(trail.size - 1) + (1_000_000L + trail.size * 1_000L + 78 * 60 * 1_000L)

        val gaps = TrailGapCheck.findGaps(trail, times)

        assertEquals(1, gaps.size)
        assertEquals(GapReason.ImplausibleDistance, gaps.single().reason)
        assertTrue(gaps.single().impliedSpeedMps!! < 1.0, "a walking pace, which is why distance has to carry this")
    }

    @Test
    fun timesAreOptionalAndTheGeometryCheckStandsWithoutThem() {
        val trail = walked() + offsetFromOrigin(northM = 1031.0 + 40 * 11.0, eastM = 0.0)

        assertEquals(GapReason.ImplausibleDistance, TrailGapCheck.findGaps(trail).single().reason)
    }
}
