package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.deltaAngle
import kotlinx.coroutines.test.TestScope
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The direction guidance speaks — and the direction the beacon pans to — is taken at the place the
 * *windowed matcher* says the user is, not at whichever piece of trail is nearest.
 *
 * ADR 0001 S5. This is the same defect S5a removed from wrong-way detection, in a worse place.
 * `desiredTrailCourseDeg` centres its chord on an unwindowed `polyline.project()`, so on a trail
 * that doubles back the bearing is measured on the arm the user is *not* on. Replaying the
 * 2026-08-12 corpus against the shipped rule:
 *
 * ```
 *   walk1_session3_trail12_reverse    5.6% of Matched fixes, spoken direction ≥45° wrong
 *   walk2_session2_trail12_reverse   17.1% of Matched fixes, worst cases 162°
 * ```
 *
 * 162° is not "slightly off". It is telling a blind user to turn around at the moment they are
 * walking correctly, and it is the same session where the wrong-way alert fired for the same
 * underlying reason.
 */
class GuidanceFromMatchTest {
    /** A switchback with 12 m between its arms: north 150 m, east 12 m, south 150 m. */
    private val points = densify(switchbackShape(legM = 150.0, gapM = 12.0), spacingM = 5.0)
    private val polyline = TrailPolyline(points)

    /** Recorded order: arm 1 runs north (bearing 0), arm 3 runs south (bearing 180). */
    private val returnArmAlongM = 150.0 + 12.0 + 90.0

    private fun activeAt(
        currentIndex: Int,
        waypoints: List<LatLng> = points,
    ) = TrailFollowerState.Active(
        waypoints = waypoints.mapIndexed { i, p -> TrailPoint(i.toLong(), "p$i", p.lat, p.lon) },
        currentIndex = currentIndex,
        thresholdM = 15.0,
    )

    /**
     * On the return arm at 60 m north, but drifted 3 m off it toward the outbound arm — so the
     * nearest point on the whole trail is on the *outbound* arm, 9 m away, and an unwindowed
     * projection reads ~60 m along instead of ~270 m.
     */
    private fun fixOnReturnArm() = sampleAt(northM = 60.0, eastM = 3.0, timestampMs = 100_000L, accuracyM = 12.0)

    private fun courseAt(
        alongTrackM: Double?,
        direction: TravelDirection,
        currentIndex: Int,
    ): Double {
        val guidance =
            assertNotNull(
                TrailGuidance.compute(
                    activeAt(currentIndex),
                    fixOnReturnArm(),
                    trustedCourse = null,
                    polyline = polyline,
                    alongTrackM = alongTrackM,
                    direction = direction,
                ),
                "guidance",
            )
        return assertNotNull(guidance.desiredCourseDeg, "desired course")
    }

    @Test
    fun onASwitchbackTheCourseIsTakenWhereTheMatcherSaysTheUserIs() {
        // Walking south down the return arm, so the trail ahead runs south.
        val course = courseAt(returnArmAlongM, TravelDirection.Forward, currentIndex = points.size - 1)

        assertTrue(
            abs(deltaAngle(180.0, course)) < 15.0,
            "the return arm runs south; guidance said $course",
        )
    }

    @Test
    fun theUnwindowedProjectionIsWhatUsedToGetThisWrong() {
        // Pins the size of the bug rather than only the fix: the nearest point on the whole trail
        // is on the outbound arm, whose course is due north — 180° from the truth.
        val nearestAlongM = assertNotNull(polyline.project(LatLng(fixOnReturnArm().lat, fixOnReturnArm().lon))).alongTrackM

        assertTrue(nearestAlongM < 100.0, "the global nearest point is on the outbound arm, at $nearestAlongM m")

        val wrongCourse = courseAt(nearestAlongM, TravelDirection.Forward, currentIndex = points.size - 1)
        assertTrue(
            abs(deltaAngle(180.0, wrongCourse)) > 150.0,
            "projecting unwindowed should reverse the course; got $wrongCourse",
        )
    }

    @Test
    fun underReverseTheCourseFlipsWithTravel() {
        // Same physical place, followed the other way: the user walks *north* up the return arm.
        // alongTrackM is in recorded order either way — direction is what says which way is ahead.
        val course = courseAt(returnArmAlongM, TravelDirection.Reverse, currentIndex = 0)

        assertTrue(
            abs(deltaAngle(0.0, course)) < 15.0,
            "under Reverse the user walks up the return arm, heading north; guidance said $course",
        )
    }

    @Test
    fun aFrozenPositionStopsSteeringOnceTheMatchIsLost() {
        // `confirmedAlongM` keeps its value through `Uncertain` and `Lost` — that is deliberate, and
        // the dog fixture depends on it. But a value frozen 90 seconds ago on the *outbound* arm
        // must not still be choosing which way to point: the chord gets taken there, and the spoken
        // direction stays 180° from travel. That is the S5 defect again, reached through state
        // rather than through an unwindowed projection.
        val coordinator = TrailGuidanceCoordinator(TestScope())
        coordinator.startFollow(polyline, TravelDirection.Forward)
        val active = activeAt(points.size - 1)
        val sample = fixOnReturnArm()

        val stale = matchOnOutboundArm(MatchState.Lost)
        val course =
            assertNotNull(
                coordinator.computeGuidance(active, sample, stale)?.desiredCourseDeg,
                "guidance must still be produced",
            )

        assertTrue(
            abs(deltaAngle(0.0, course)) > 90.0,
            "a Lost match froze the course on the outbound arm; guidance said $course",
        )
    }

    @Test
    fun anUncertainMatchStillSteers() {
        // The other half. `Uncertain` is a brief gap, and the ADR is explicit that consumers keep
        // reading the frozen value there — going blind on every momentary dropout would be worse
        // than steering by a position a few seconds old.
        val coordinator = TrailGuidanceCoordinator(TestScope())
        coordinator.startFollow(polyline, TravelDirection.Forward)

        val course =
            assertNotNull(
                coordinator
                    .computeGuidance(activeAt(points.size - 1), fixOnReturnArm(), matchAtAlong(returnArmAlongM, MatchState.Uncertain))
                    ?.desiredCourseDeg,
            )

        assertTrue(
            abs(deltaAngle(180.0, course)) < 15.0,
            "an Uncertain match should still steer by its frozen position; guidance said $course",
        )
    }

    private fun matchOnOutboundArm(state: MatchState) = matchAtAlong(60.0, state)

    private fun matchAtAlong(
        alongM: Double,
        state: MatchState,
    ) = TrailMatch(
        state = state,
        confirmedAlongM = alongM,
        predictedAlongM = alongM,
        position = null,
        chosen = null,
        bestRejected = null,
        unmatchedCount = 0,
        uncertainSec = 0.0,
        travelledM = 0.0,
        scanKind = ScanKind.Windowed,
        windowM = null,
        budgetM = 0.0,
        predictionErrorM = null,
        disposition = "test",
    )

    @Test
    fun withNoMatchYetTheCourseFallsBackToLocalGeometryRatherThanGuessing() {
        // Before acquisition there is no windowed answer. The adjacent-segment fallback is noisy,
        // but it is local: it cannot teleport to the far arm the way a global projection can.
        val guidance =
            TrailGuidance.compute(
                activeAt(currentIndex = 3),
                fixOnReturnArm(),
                trustedCourse = null,
                polyline = polyline,
                alongTrackM = null,
                direction = TravelDirection.Forward,
            )

        assertNotNull(guidance?.desiredCourseDeg, "guidance must still be available before the first match")
    }
}
