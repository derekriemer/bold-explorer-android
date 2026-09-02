package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Endpoint completion as a policy of its own.
 *
 * The defect: completion had no code path. `TrailComplete` was emitted by `fireAdvance` whenever
 * *any* advancement mechanism fired while already on the last waypoint — including the projection
 * branch, which needs only `t >= 0.9` along the final segment and `d <= threshold * 4`. On a 300 m
 * final leg that completes the trail 30 m out, which matches the ~65 ft field observation.
 *
 * Completion is now its own check against the trail's end, using a radius that **tightens** with
 * good GPS and is **capped** so poor GPS can never widen it:
 *
 *     radius = min(CEILING, max(FLOOR, SIGMA_FACTOR × accuracy))
 *
 * Android reports accuracy as a 68% (1σ) horizontal radius, so a factor of 2 is roughly 95%
 * containment rather than an arbitrary multiplier. Above [NavigationPolicy.COMPLETION_HEDGE_ABOVE_M]
 * the announcement hedges instead of asserting.
 */
class TrailCompletionTest {
    private fun latFor(m: Double) = m / 111_194.9

    private val start = TrailPoint(1, "Start", 0.0, 0.0)

    /** Trail whose final leg is 300 m — long enough for the old projection rule to complete early. */
    private fun longFinalLeg(): List<TrailPoint> = listOf(start, TrailPoint(2, "End", latFor(300.0), 0.0))

    /** A fix [metresBeforeEnd] short of the 300 m endpoint, on the line. */
    private fun beforeEnd(metresBeforeEnd: Double) = LatLng(latFor(300.0 - metresBeforeEnd), 0.0)

    private fun followerAtFinalLeg(): TrailFollower =
        TrailFollower().apply { start(longFinalLeg(), fromIndex = 1) }

    /**
     * A session that has walked far enough for a completion to be believable. Every test here is
     * about the *radius*, so they all grant the travel evidence the radius is gated on; the guard
     * itself is tested by [coldFollowAtTheEndDoesNotComplete] and in `CompletionFromMatchTest`.
     */
    private val walked = CompletionEvidence(pastTheEnd = false, travelled = true)

    @Test
    fun longFinalLeg_doesNotCompleteThirtyMetresOut() {
        // The reported bug, as a test. 30 m short of a 300 m final leg puts the projection fraction
        // at exactly 0.9, which used to complete the trail.
        val f = followerAtFinalLeg()
        assertNull(
            f.onLocationUpdate(beforeEnd(30.0), accuracyM = 5.0, completion = walked),
            "must not complete 30 m short of the endpoint",
        )
    }

    @Test
    fun completesInsideTheEndpointRadius() {
        val f = followerAtFinalLeg()
        val event = f.onLocationUpdate(beforeEnd(4.0), accuracyM = 3.0, completion = walked)
        assertIs<TrailFollowerEvent.TrailComplete>(event, "should complete 4 m from the end")
    }

    @Test
    fun goodAccuracyTightensTheRadius() {
        // 10 m out with 3 m accuracy gives a 6 m radius, so it must not complete...
        val tight = followerAtFinalLeg()
        assertNull(tight.onLocationUpdate(beforeEnd(10.0), accuracyM = 3.0, completion = walked), "6 m radius at 10 m out")

        // ...while 10 m accuracy gives the full 15 m ceiling, which does.
        val loose = followerAtFinalLeg()
        assertIs<TrailFollowerEvent.TrailComplete>(
            loose.onLocationUpdate(beforeEnd(10.0), accuracyM = 10.0, completion = walked),
            "15 m radius at 10 m out",
        )
    }

    @Test
    fun poorAccuracyNeverWidensBeyondTheCeiling() {
        // The bounded-uncertainty rule: 30 m accuracy would give a 60 m radius unbounded. Capped at
        // the ceiling, 20 m out still does not complete. Bad GPS must produce uncertainty, never a
        // larger acceptance region.
        val f = followerAtFinalLeg()
        assertNull(f.onLocationUpdate(beforeEnd(20.0), accuracyM = 30.0, completion = walked), "capped at the ceiling")
    }

    @Test
    fun poorAccuracyHedgesTheAnnouncement() {
        val hedged = followerAtFinalLeg()
        val event = hedged.onLocationUpdate(beforeEnd(8.0), accuracyM = 30.0, completion = walked)
        assertIs<TrailFollowerEvent.TrailComplete>(event)
        assertTrue(event.hedged, "30 m accuracy must not assert arrival flatly")
    }

    @Test
    fun goodAccuracyAssertsTheAnnouncement() {
        val confident = followerAtFinalLeg()
        val event = confident.onLocationUpdate(beforeEnd(4.0), accuracyM = 3.0, completion = walked)
        assertIs<TrailFollowerEvent.TrailComplete>(event)
        assertTrue(!event.hedged, "3 m accuracy can state arrival plainly")
    }

    @Test
    fun projectionStillAdvancesMidTrail() {
        // The change must not disable the projection branch generally — only stop it *completing*
        // a trail. A 300 m leg between two mid-trail waypoints must still advance at t = 0.9.
        val f =
            TrailFollower().apply {
                start(
                    listOf(
                        start,
                        TrailPoint(2, "Middle", latFor(300.0), 0.0),
                        TrailPoint(3, "End", latFor(600.0), 0.0),
                    ),
                    fromIndex = 1,
                )
            }
        // Advancing off a mid-trail waypoint is silent now (S6) — the event itself is gone, so the
        // proof that projection still fired is the index moving to the new target, not an event.
        val event = f.onLocationUpdate(LatLng(latFor(270.0), 0.0), accuracyM = 5.0, completion = walked)
        assertNull(event, "advancing must not speak")
        assertEquals(2, (f.state.value as TrailFollowerState.Active).currentIndex, "mid-trail projection must still advance")
    }

    @Test
    fun coldFollowAtTheEndDoesNotComplete() {
        // The radius is not a second, ungated route to completion. Arming (ADR 0002) can anchor at
        // the walker's actual position, so a follow begun at a loop's trailhead — which is also its
        // final track point — starts with the index at the end and the user inside the 5–6 m
        // radius. Ungated, the first fix announces the trail complete before a step is taken.
        val f = followerAtFinalLeg()
        assertNull(
            f.onLocationUpdate(beforeEnd(2.0), accuracyM = 3.0, completion = CompletionEvidence.None),
            "completed on the first fix of a follow that had walked nothing",
        )
    }

    @Test
    fun radialArrivalStillCompletesAtTheEnd() {
        // Belt and braces: standing essentially on the final waypoint completes regardless of the
        // projection change, so the trail can always be finished by walking to its end.
        val f = followerAtFinalLeg()
        assertIs<TrailFollowerEvent.TrailComplete>(
            f.onLocationUpdate(beforeEnd(1.0), accuracyM = 5.0, completion = walked),
            "arriving at the endpoint completes",
        )
    }

    // #67: the radial "endpoint" route above completes on raw GPS distance to the last waypoint —
    // it does not itself consult the trail match. A walker who is geometrically close to the
    // endpoint's coordinates but whose match is not Matched (a shortcut, a dead-reckoned drift)
    // must not hear a flat "trail complete" just because the fix itself was accurate.

    @Test
    fun uncertainMatchHedgesEvenWithGoodAccuracy() {
        val f = followerAtFinalLeg()
        val event =
            f.onLocationUpdate(beforeEnd(1.0), accuracyM = 3.0, completion = walked, matchState = MatchState.Uncertain)
        assertIs<TrailFollowerEvent.TrailComplete>(event)
        assertTrue(event.hedged, "a non-Matched state must hedge even when accuracy alone would not")
    }

    @Test
    fun lostMatchHedgesEvenWithGoodAccuracy() {
        val f = followerAtFinalLeg()
        val event =
            f.onLocationUpdate(beforeEnd(1.0), accuracyM = 3.0, completion = walked, matchState = MatchState.Lost)
        assertIs<TrailFollowerEvent.TrailComplete>(event)
        assertTrue(event.hedged, "Lost must hedge even when accuracy alone would not")
    }

    @Test
    fun matchedStateWithGoodAccuracyStillAssertsPlainly() {
        val f = followerAtFinalLeg()
        val event =
            f.onLocationUpdate(beforeEnd(1.0), accuracyM = 3.0, completion = walked, matchState = MatchState.Matched)
        assertIs<TrailFollowerEvent.TrailComplete>(event)
        assertTrue(!event.hedged, "Matched plus good accuracy can still state arrival plainly")
    }

    @Test
    fun noMatchStateDoesNotHedgeOnItsOwn() {
        // Callers with no matcher in play (matchState = null, the default) get the old,
        // accuracy-only behaviour unchanged.
        val f = followerAtFinalLeg()
        val event = f.onLocationUpdate(beforeEnd(1.0), accuracyM = 3.0, completion = walked)
        assertIs<TrailFollowerEvent.TrailComplete>(event)
        assertTrue(!event.hedged, "null matchState must not itself force a hedge")
    }
}
