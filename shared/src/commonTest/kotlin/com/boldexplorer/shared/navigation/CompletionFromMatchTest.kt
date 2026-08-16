package com.boldexplorer.shared.navigation

import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Completion asks the matcher whether the user has reached the end, rather than asking
 * `currentIndex` whether it happens to be pointing at the last waypoint.
 *
 * S2 replaced a projection branch that completed a 300 m final leg 30 m early with a radial check
 * that tightens with good GPS. That fixed the early firing and left the opposite hole: with the
 * projection branch gone there was no "walked past the end" catch at all, and `completionRadiusM`
 * is 5–6 m at a good fix. Stroll past the end without passing within 5 m of it and `TrailComplete`
 * never fires — the follow does not end and guidance keeps steering at a waypoint behind you.
 *
 * Found independently by code review and by the PR bot on #71. The fix is the one ADR 0001 already
 * specified under Verification: complete on along-track, which needs the match as source of truth.
 */
class CompletionFromMatchTest {
    private fun coordinator() = TrailGuidanceCoordinator(TestScope())

    /** Walks [toM] metres along a [lengthM]-metre due-north trail, a fix every 5 m. */
    private fun walk(
        coordinator: TrailGuidanceCoordinator,
        lengthM: Double,
        toM: Double,
        fromM: Double = 0.0,
    ) {
        var t = 100_000L
        var d = fromM
        while (d <= toM) {
            coordinator.onFix(sampleAt(northM = d, eastM = 0.0, timestampMs = t, accuracyM = 5.0, speedMps = 1.4))
            d += 5.0
            t += 4_000L
        }
    }

    @Test
    fun walkingPastTheEndCompletesTheTrail() {
        // The overshoot case. The user never comes within the completion radius of the final
        // waypoint — they walk straight through it and keep going.
        val c = coordinator()
        val points = densify(northShape(200.0), spacingM = 5.0)
        c.startFollow(points, TravelDirection.Forward)

        walk(c, lengthM = 200.0, toM = 215.0)

        assertTrue(c.hasReachedTheEnd(), "walked 15 m past the end and completion never fired")
    }

    @Test
    fun standingAtTheEndWhenTheFollowStartsDoesNotComplete() {
        // A loop's start is also its end, and a follow can legitimately begin there. Without a
        // travel guard, adopting the trail and standing still would announce "trail complete"
        // before the user had walked a step.
        val c = coordinator()
        val points = densify(northShape(200.0), spacingM = 5.0)
        c.startFollow(points, TravelDirection.Forward)

        walk(c, lengthM = 200.0, toM = 210.0, fromM = 205.0)

        assertFalse(c.hasReachedTheEnd(), "completed a trail the user had not walked")
    }

    @Test
    fun aShortTrailCanStillBeCompleted() {
        // The guard scales to the trail: a flat minimum would make any trail shorter than it
        // impossible to finish, which is the same "constant that does not scale" failure the ADR
        // exists to remove.
        val c = coordinator()
        val points = densify(northShape(40.0), spacingM = 5.0)
        c.startFollow(points, TravelDirection.Forward)

        walk(c, lengthM = 40.0, toM = 55.0)

        assertTrue(c.hasReachedTheEnd(), "a 40 m trail must still be completable")
    }

    @Test
    fun underReverseTheEndIsTheStartOfTheRecordedOrder() {
        // alongTrackM is always in recorded order, so a reverse walk finishes at 0.
        val c = coordinator()
        val points = densify(northShape(200.0), spacingM = 5.0)
        c.startFollow(points, TravelDirection.Reverse)

        var t = 100_000L
        var d = 200.0
        while (d >= -15.0) {
            c.onFix(sampleAt(northM = d, eastM = 0.0, timestampMs = t, accuracyM = 5.0, speedMps = 1.4))
            d -= 5.0
            t += 4_000L
        }

        assertTrue(c.hasReachedTheEnd(), "a reverse walk ends at the recorded start")
    }

    @Test
    fun aWalkStillInProgressDoesNotComplete() {
        val c = coordinator()
        val points = densify(northShape(200.0), spacingM = 5.0)
        c.startFollow(points, TravelDirection.Forward)

        walk(c, lengthM = 200.0, toM = 120.0)

        assertFalse(c.hasReachedTheEnd(), "completed halfway along the trail")
    }
}
