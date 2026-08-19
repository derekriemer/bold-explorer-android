package com.boldexplorer.shared.navigation

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** ADR 0002 — where a follow anchors when the trail passes the walker's position more than once. */
class FollowArmingTest {
    private val lollipop = TrailPolyline(LollipopFixture.points)

    private fun resolved(result: ArmingResult): ArmingResult.Resolved =
        result as? ArmingResult.Resolved ?: fail("expected Resolved, got $result")

    private fun assertAlong(
        expectedM: Double,
        actualM: Double,
        toleranceM: Double = 6.0,
    ) = assertTrue(
        abs(expectedM - actualM) <= toleranceM,
        "expected along-track ${expectedM}m, got ${actualM}m",
    )

    private fun fork(result: ArmingResult): ArmingResult.Fork =
        result as? ArmingResult.Fork ?: fail("expected Fork, got $result")

    private fun arm(
        polyline: TrailPolyline,
        direction: TravelDirection,
        northM: Double,
        eastM: Double = 0.0,
        accuracyM: Double? = 5.0,
    ) = FollowArming.resolve(polyline, direction, offsetFromOrigin(northM, eastM), accuracyM)

    @Test
    fun forwardAtTheTrailhead_anchorsAtTheStart() {
        val result =
            FollowArming.resolve(
                polyline = lollipop,
                direction = TravelDirection.Forward,
                location = offsetFromOrigin(northM = 0.0, eastM = 0.0),
                accuracyM = 5.0,
            )

        val anchor = resolved(result)
        assertAlong(0.0, anchor.anchor.alongTrackM)
        assertEquals(ArmReason.OnTrail, anchor.reason)
    }

    @Test
    fun reverseAtTheTrailhead_anchorsAtTheFarEnd() {
        // The 0 candidate has nothing behind it, so a reverse follow from it would complete at once.
        val anchor = resolved(arm(lollipop, TravelDirection.Reverse, northM = 0.0))

        assertAlong(LollipopFixture.TOTAL_M, anchor.anchor.alongTrackM)
        assertEquals(ArmReason.OnTrail, anchor.reason)
    }

    @Test
    fun forwardMidStick_offersTheLoopAndTheWalkOut() {
        val options = fork(arm(lollipop, TravelDirection.Forward, northM = LollipopFixture.MID_STICK_M)).options

        assertEquals(2, options.size)
        assertAlong(LollipopFixture.MID_STICK_M, options[0].anchor.alongTrackM)
        assertAlong(LollipopFixture.TOTAL_M - LollipopFixture.MID_STICK_M, options[1].anchor.alongTrackM)
        assertTrue(options[0].revisitsStartPoint, "the loop option comes back past here")
        assertTrue(!options[1].revisitsStartPoint, "the walk out does not")
    }

    @Test
    fun reverseMidStick_offersTheWalkBackAndTheLoopBackwards() {
        val options = fork(arm(lollipop, TravelDirection.Reverse, northM = LollipopFixture.MID_STICK_M)).options

        assertEquals(2, options.size)
        assertAlong(LollipopFixture.MID_STICK_M, options[0].remainingM)
        assertAlong(LollipopFixture.TOTAL_M - LollipopFixture.MID_STICK_M, options[1].remainingM)
    }

    @Test
    fun forwardAtTheJunction_offersTheLoopAndTheStick() {
        val options = fork(arm(lollipop, TravelDirection.Forward, northM = 950.0)).options

        assertEquals(2, options.size)
        assertAlong(LollipopFixture.JUNCTION_OUT_M, options[0].anchor.alongTrackM)
        assertAlong(LollipopFixture.JUNCTION_BACK_M, options[1].anchor.alongTrackM)
        assertAlong(LollipopFixture.TOTAL_M - LollipopFixture.JUNCTION_OUT_M, options[0].remainingM)
        assertAlong(LollipopFixture.TOTAL_M - LollipopFixture.JUNCTION_BACK_M, options[1].remainingM)
    }

    @Test
    fun reverseAtTheJunction_offersBothArms() {
        val options = fork(arm(lollipop, TravelDirection.Reverse, northM = 950.0)).options

        assertEquals(2, options.size)
        assertAlong(LollipopFixture.JUNCTION_OUT_M, options[0].remainingM)
        assertAlong(LollipopFixture.JUNCTION_BACK_M, options[1].remainingM)
    }

    @Test
    fun jitteredJunction_stillForks_becauseTheStrandsAreOnePathRecordedTwice() {
        val jittered = TrailPolyline(LollipopFixture.jittered)

        val options = fork(FollowArming.resolve(jittered, TravelDirection.Forward, offsetFromOrigin(950.0, 0.0), 5.0)).options

        assertEquals(2, options.size, "15 m between the strands is inside the same-path radius")
    }

    @Test
    fun jitteredJunction_withATightSamePathRadius_losesTheSecondContinuation() {
        // The regression the constant exists to prevent: too narrow, and the fork silently disappears.
        val jittered = TrailPolyline(LollipopFixture.jittered)

        val result =
            FollowArming.resolve(
                jittered,
                TravelDirection.Forward,
                offsetFromOrigin(950.0, 0.0),
                accuracyM = 5.0,
                tuning = ArmingTuning(samePathBaseM = 5.0, samePathCapM = 5.0),
            )

        resolved(result)
    }

    @Test
    fun offTrail_anchorsAtTheTraversalStartAndSaysSo() {
        val anchor = resolved(arm(lollipop, TravelDirection.Forward, northM = 475.0, eastM = 200.0))

        assertAlong(0.0, anchor.anchor.alongTrackM)
        assertEquals(ArmReason.OffTrail, anchor.reason)
    }

    @Test
    fun noFix_anchorsAtTheTraversalStartAndSaysSo() {
        val anchor =
            resolved(FollowArming.resolve(lollipop, TravelDirection.Reverse, location = null, accuracyM = null))

        assertAlong(LollipopFixture.TOTAL_M, anchor.anchor.alongTrackM)
        assertEquals(ArmReason.NoFix, anchor.reason)
    }

    @Test
    fun onATrailWalkedOnce_thereIsNothingToAsk() {
        val straight = TrailPolyline(densify(northShape(500.0), spacingM = 10.0))

        val anchor = resolved(FollowArming.resolve(straight, TravelDirection.Forward, offsetFromOrigin(250.0, 0.0), 5.0))

        assertAlong(250.0, anchor.anchor.alongTrackM)
    }

    @Test
    fun aShortWalkBackIsStillAWalk() {
        val options = fork(arm(lollipop, TravelDirection.Reverse, northM = 30.0)).options

        assertEquals(2, options.size)
        assertAlong(30.0, options[0].remainingM)
        assertAlong(LollipopFixture.TOTAL_M - 30.0, options[1].remainingM)
    }

    @Test
    fun standingAtTheFinishWithAPoorFix_doesNotOfferAFollowThatIsAlreadyOver() {
        // Radius is min(15, max(5, 2 x 6)) = 12, so the 8 m candidate would complete on the first fix.
        // What survives is the *return* strand under the walker's feet — 8 m short of the far end,
        // not at it. Standing here is 8 m from the trailhead on either strand.
        val anchor = resolved(arm(lollipop, TravelDirection.Reverse, northM = 8.0, accuracyM = 6.0))

        assertAlong(LollipopFixture.TOTAL_M - 8.0, anchor.anchor.alongTrackM)
    }

    @Test
    fun standingAtTheFinishWithAGoodFix_earnsTheShortFollow() {
        // Radius is 5, so the 8 m walk back survives and the walker is asked which they meant.
        val options = fork(arm(lollipop, TravelDirection.Reverse, northM = 8.0, accuracyM = 2.0)).options

        assertAlong(8.0, options[0].remainingM)
    }

    // ── Adversarial: the same-ground rule itself ─────────────────────────────────────────────

    @Test
    fun aLoopTooShortForAnAlongTrackRuleStillOffersBothContinuations() {
        val small = TrailPolyline(ArmingShapes.smallLoop)

        val options =
            fork(
                FollowArming.resolve(
                    small,
                    TravelDirection.Forward,
                    offsetFromOrigin(ArmingShapes.SMALL_LOOP_MID_STICK_M, 0.0),
                    accuracyM = 5.0,
                ),
            ).options

        assertEquals(2, options.size, "130 m apart along the trail, 0 m apart on it")
    }

    @Test
    fun switchbackArmsFarEnoughApartAreNotOffered() {
        val wide = TrailPolyline(ArmingShapes.switchback(gapM = 22.0))

        val anchor = resolved(FollowArming.resolve(wide, TravelDirection.Forward, offsetFromOrigin(100.0, 0.0), 5.0))

        assertAlong(100.0, anchor.anchor.alongTrackM)
    }

    @Test
    fun theSameSwitchbackWithAPoorFixIsOfferedAgain() {
        // Nothing about the ground changed; the radius widened because the fix got worse.
        val wide = TrailPolyline(ArmingShapes.switchback(gapM = 22.0))

        val options =
            fork(FollowArming.resolve(wide, TravelDirection.Forward, offsetFromOrigin(100.0, 0.0), accuracyM = 20.0)).options

        assertEquals(2, options.size)
    }

    @Test
    fun armsTooCloseToTellApartAreBothOffered() {
        // ADR 0002 section 5: unfixable by tuning, and asked rather than guessed.
        val tight = TrailPolyline(ArmingShapes.switchback(gapM = 10.0))

        val options =
            fork(FollowArming.resolve(tight, TravelDirection.Forward, offsetFromOrigin(100.0, 0.0), 5.0)).options

        assertEquals(2, options.size)
    }

    @Test
    fun aFigureEightCrossingOffersExactlyTwo() {
        val eight = TrailPolyline(ArmingShapes.figureEight)

        val options =
            fork(FollowArming.resolve(eight, TravelDirection.Forward, offsetFromOrigin(0.0, 0.0), 5.0)).options

        assertEquals(2, options.size)
    }

    @Test
    fun groundCoveredThreeTimesOffersThree() {
        val triple = TrailPolyline(ArmingShapes.triplePass)

        val options =
            fork(
                FollowArming.resolve(
                    triple,
                    TravelDirection.Forward,
                    offsetFromOrigin(ArmingShapes.TRIPLE_PASS_MID_STEM_M, 0.0),
                    accuracyM = 5.0,
                ),
            ).options

        assertEquals(3, options.size, "nothing in the resolver caps the answer at two")
    }
}
