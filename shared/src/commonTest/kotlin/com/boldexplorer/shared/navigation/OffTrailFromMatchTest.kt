package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Off-trail measures its cross-track against the arm the matcher says the user is on.
 *
 * The third and last consumer of an unwindowed projection (ADR 0001, S5; S5a did wrong-way, S5 did
 * the desired course). Its failure is the quiet one: at a switchback the *other* arm is close, so
 * an unwindowed projection reports a small cross-track and the alert never fires for someone who
 * has walked off their own arm.
 *
 * ## The ladder decides whether there is anything to measure
 *
 * `Uncertain` frequently means "the best candidate is past the match gate", which is the off-trail
 * condition itself — it is evidence, not a reason for silence. Under `Lost` and `Unconfirmed` the
 * only candidate came from a global scan and may be a different part of the trail entirely, so
 * there is no honest cross-track to report and the detector says so.
 *
 * (This reads the accepted ADR's "off-trail alerts suppressed" under uncertainty as pairing with a
 * distinct uncertain earcon that does not exist yet. Confirmed with the owner, 2026-08-15.)
 */
class OffTrailFromMatchTest {
    private fun coordinator() = TrailGuidanceCoordinator(TestScope())

    /** Arms 40 m apart, so a user well off one arm is still near the other. */
    private val points = densify(switchbackShape(legM = 150.0, gapM = 40.0), spacingM = 5.0)
    private val polyline = TrailPolyline(points)

    private val active =
        TrailFollowerState.Active(
            waypoints = points.mapIndexed { i, p -> TrailPoint(i.toLong(), "p$i", p.lat, p.lon) },
            currentIndex = 1,
            thresholdM = 15.0,
        )

    private fun guidance(relativeDeg: Double? = 0.0) =
        TrailGuidanceState(
            targetIndex = 1,
            targetName = "B",
            total = points.size,
            distanceToTargetM = 50.0,
            desiredCourseDeg = 0.0,
            relativeDeg = relativeDeg,
            courseIsFresh = true,
        )

    private fun matchWith(
        state: MatchState,
        crossTrackM: Double?,
        alongTrackM: Double = 260.0,
    ): TrailMatch {
        val chosen =
            crossTrackM?.let {
                TrailPosition(
                    segmentIndex = 0,
                    fraction = 0.0,
                    alongTrackM = alongTrackM,
                    crossTrackM = it,
                    snapped = points.first(),
                )
            }
        return TrailMatch(
            state = state,
            confirmedAlongM = alongTrackM,
            predictedAlongM = alongTrackM,
            position = chosen,
            chosen = chosen,
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
    }

    private fun sample(timestampMs: Long) =
        LocationSample(lat = points[30].lat, lon = points[30].lon, accuracy = 5.0, timestamp = timestampMs)

    @Test
    fun beingPastTheMatchGateIsEvidenceOfBeingOffTrail_notAReasonForSilence() {
        // 30 m off the arm the user is on is past the match gate at 5 m accuracy, so the tracker is
        // Uncertain. That is precisely the condition the alert exists to report.
        val c = coordinator()
        c.startFollow(points, TravelDirection.Forward)

        val evals =
            (0..4).mapNotNull { i ->
                c.evaluateOffTrail(active, sample(100_000L + i * 1_000L), guidance(), matchWith(MatchState.Uncertain, 30.0))
            }

        assertTrue(evals.any { it.fired }, "30 m off trail must alert: ${evals.map { it.disposition }}")
    }

    @Test
    fun aSustainedExcursionKeepsAlertingAfterTheMatcherGivesUp() {
        // The case that matters most and is easiest to lose. A walker who leaves the trail and keeps
        // going trips the reckoning horizon — 120 m or 90 s — and the tracker goes `Lost`, retrying
        // a global scan every 30 s and failing every time, because they are not near the trail.
        // Gating off-trail on `Matched`/`Uncertain` therefore silences it permanently for exactly
        // the person who is most off-trail. The candidate a global scan produces is the nearest
        // point on the whole trail, which is a *lower bound* on how far off they are: if even that
        // exceeds the gate, they are off trail, and saying so cannot be a false positive.
        val c = coordinator()
        c.startFollow(points, TravelDirection.Forward)

        val evals =
            (0..6).mapNotNull { i ->
                c.evaluateOffTrail(active, sample(100_000L + i * 1_000L), guidance(), matchWith(MatchState.Lost, 60.0))
            }

        assertTrue(evals.any { it.fired }, "60 m off trail and Lost must still alert: ${evals.map { it.disposition }}")
    }

    @Test
    fun withNoCandidateAtAllThereIsNothingToMeasure() {
        // A `Lost` fix inside the rescan cooldown carries no candidate. There is genuinely no
        // cross-track to report then, and inventing one would mean projecting unwindowed again.
        val c = coordinator()
        c.startFollow(points, TravelDirection.Forward)

        val eval =
            assertNotNull(
                c.evaluateOffTrail(active, sample(100_000L), guidance(), matchWith(MatchState.Lost, null)),
            )

        assertEquals("bail:no_window", eval.disposition)
        assertEquals(0, eval.consecutiveCount)
    }

    @Test
    fun withNoMatchAtAllThereIsNoCrossTrack() {
        val c = coordinator()
        c.startFollow(points, TravelDirection.Forward)

        val eval = assertNotNull(c.evaluateOffTrail(active, sample(100_000L), guidance(), null))

        assertEquals("bail:no_window", eval.disposition)
    }

    @Test
    fun aGapInTheEvidenceDoesNotReArmTheAlert() {
        // The counter and the cooldown answer different questions. Resetting the count when a fix
        // stops qualifying is right; clearing the *cooldown* with it means one interrupting fix
        // re-arms the alert, and under degraded GPS the ladder flaps often enough to do that
        // repeatedly — fire, one gap, two over-gate fixes, fire again, about every three seconds.
        val c = coordinator()
        c.startFollow(points, TravelDirection.Forward)

        val fired =
            (0..4)
                .mapNotNull { i ->
                    c.evaluateOffTrail(active, sample(100_000L + i * 1_000L), guidance(), matchWith(MatchState.Matched, 30.0))
                }.filter { it.fired }
        assertTrue(fired.isNotEmpty(), "precondition: the alert fires")

        // One fix with no candidate at all — the ladder flapped — then straight back over gate.
        c.evaluateOffTrail(active, sample(106_000L), guidance(), matchWith(MatchState.Lost, null))
        val after =
            (0..4).mapNotNull { i ->
                c.evaluateOffTrail(active, sample(107_000L + i * 1_000L), guidance(), matchWith(MatchState.Matched, 30.0))
            }

        assertTrue(
            after.none { it.fired },
            "re-alerted inside the cooldown after one interrupting fix: ${after.map { it.disposition }}",
        )
    }

    @Test
    fun crossTrackSideFlipsWithDeclaredDirection() {
        // The sign is "right of travel", and travel reverses. A user to the right of the recorded
        // direction is to the *left* of a reverse walk; the logged sign has to say so, since it is
        // what a field log is read for.
        val forward = coordinator().also { it.startFollow(points, TravelDirection.Forward) }
        val reverse = coordinator().also { it.startFollow(points, TravelDirection.Reverse) }

        val f = assertNotNull(forward.evaluateOffTrail(active, sample(100_000L), guidance(), matchWith(MatchState.Matched, 12.0)))
        val r = assertNotNull(reverse.evaluateOffTrail(active, sample(100_000L), guidance(), matchWith(MatchState.Matched, 12.0)))

        assertEquals(12.0, f.crossTrackM)
        assertEquals(-12.0, r.crossTrackM, "right of recorded order is left of a reverse walk")
    }

    @Test
    fun theMagnitudeIsWhatGatesRegardlessOfSide() {
        val c = coordinator()
        c.startFollow(points, TravelDirection.Reverse)

        val evals =
            (0..4).mapNotNull { i ->
                c.evaluateOffTrail(active, sample(100_000L + i * 1_000L), guidance(), matchWith(MatchState.Matched, -30.0))
            }

        assertTrue(evals.any { it.fired }, "a left-hand departure is still a departure")
    }
}
