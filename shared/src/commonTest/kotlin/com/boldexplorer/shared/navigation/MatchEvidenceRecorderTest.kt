package com.boldexplorer.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Course and divergence evidence, recorded alongside every match and constraining nothing.
 *
 * Course is deliberately excluded from the search window: on a switchback, arms A and C are
 * parallel and same-direction, so course passes for exactly the confusion most needing rejection,
 * and a signal that endorses the worst case must not be allowed to widen or bias the search.
 *
 * But that is an argument about the *window*, not about the evidence. On a retrace — out-and-back,
 * lollipop stem — the two candidate positions have **opposite** tangents, so course separates them
 * cleanly. It is therefore a strong candidate corroborator, and this recorder exists so the
 * promote-or-drop decision can be made against field data rather than assumption.
 *
 * The load-bearing part is that agreement is recorded for **both** the chosen and the best-rejected
 * candidate. Course agreement for the winner alone proves nothing on replay; the *split* between
 * the two is what says whether course would have discriminated or abstained.
 */
class MatchEvidenceRecorderTest {
    private fun northTrail() = TrailPolyline(densify(northShape(400.0), 10.0))

    private fun recorderOnNorthTrail(): Pair<ProgressTracker, MatchEvidenceRecorder> {
        val poly = northTrail()
        return ProgressTracker(poly) to MatchEvidenceRecorder(poly)
    }

    @Test
    fun tangent_onANorthTrail_pointsNorth() {
        val (tracker, recorder) = recorderOnNorthTrail()
        val sample = sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0, courseDeg = 0.0)

        val evidence = recorder.observe(sample, tracker.onFix(sample))

        assertEquals(0.0, assertNotNull(evidence.tangentDeg), 1.0, "a due-north trail has bearing 0")
    }

    @Test
    fun courseAgreement_isZeroWhenWalkingAlongTheTrail() {
        val (tracker, recorder) = recorderOnNorthTrail()
        val sample = sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0, courseDeg = 0.0)

        val evidence = recorder.observe(sample, tracker.onFix(sample))

        assertEquals(0.0, assertNotNull(evidence.courseAgreementDeg), 1.0, "walking the way the trail goes")
    }

    @Test
    fun courseAgreement_isNegativeWhenTheTrailIsToTheLeft() {
        val (tracker, recorder) = recorderOnNorthTrail()
        // Facing east on a north trail: north is 90° to the user's LEFT, and the repo's deltaAngle
        // is right-positive, so agreement must be negative. A sign error here fails silently by
        // reflection, which is why it is asserted rather than assumed.
        val sample = sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0, courseDeg = 90.0)

        val evidence = recorder.observe(sample, tracker.onFix(sample))

        assertEquals(-90.0, assertNotNull(evidence.courseAgreementDeg), 1.0, "trail is to the left")
    }

    @Test
    fun course_belowWalkingSpeed_isMarkedInvalid() {
        val (tracker, recorder) = recorderOnNorthTrail()
        val sample = sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0, speedMps = 0.2, courseDeg = 0.0)

        val evidence = recorder.observe(sample, tracker.onFix(sample))

        // GPS course over ground is noise at a standstill. Validity has to be recorded explicitly
        // or the replay analysis will read jitter as evidence — and this is also why course can
        // never become the sole discriminator: it vanishes exactly when someone stops.
        assertFalse(evidence.courseValid, "course is not trustworthy at 0.2 m/s")
    }

    @Test
    fun course_atWalkingSpeed_isMarkedValid() {
        val (tracker, recorder) = recorderOnNorthTrail()
        val sample = sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0, speedMps = 1.4, courseDeg = 0.0)

        val evidence = recorder.observe(sample, tracker.onFix(sample))

        assertTrue(evidence.courseValid, "1.4 m/s is a walk")
    }

    @Test
    fun crossTrackRate_isSignedAndPerSecond() {
        val (tracker, recorder) = recorderOnNorthTrail()
        val first = sampleAt(northM = 100.0, eastM = 4.0, timestampMs = 0)
        recorder.observe(first, tracker.onFix(first))

        val second = sampleAt(northM = 102.0, eastM = 14.0, timestampMs = 2_000)
        val evidence = recorder.observe(second, tracker.onFix(second))

        // Diverging to the right at 10 m over 2 s. Signed rate is density-independent and needs no
        // target — unlike bearing-to-target, whose staleness caused the original off-trail defect.
        assertEquals(5.0, assertNotNull(evidence.crossTrackRateMps), 0.5, "diverging right at 5 m/s")
    }

    @Test
    fun crossTrackRate_isAbsentOnTheFirstFix() {
        val (tracker, recorder) = recorderOnNorthTrail()
        val sample = sampleAt(northM = 100.0, eastM = 4.0, timestampMs = 0)

        val evidence = recorder.observe(sample, tracker.onFix(sample))

        assertNull(evidence.crossTrackRateMps, "a rate needs two samples")
    }

    @Test
    fun bothCandidates_carryTheirOwnTangentAndAgreement() {
        val poly = TrailPolyline(densify(switchbackShape(legM = 200.0, gapM = 40.0), 10.0))
        val tracker = ProgressTracker(poly)
        val recorder = MatchEvidenceRecorder(poly)

        // Midway between two parallel, opposite-direction arms, walking north.
        val sample = sampleAt(northM = 100.0, eastM = 20.0, timestampMs = 0, courseDeg = 0.0)
        val evidence = recorder.observe(sample, tracker.onFix(sample))

        assertEquals(0.0, assertNotNull(evidence.tangentDeg), 2.0, "outbound arm runs north")
        assertEquals(180.0, assertNotNull(evidence.rejectedTangentDeg), 2.0, "the return arm runs south")

        // This split is the whole point: 180° apart means course WOULD have discriminated here.
        // On same-direction parallel geometry the split collapses to ~0 and course abstains, and
        // the distribution of this quantity across a real walk is what the promote-or-drop
        // decision gets made on.
        val chosen = assertNotNull(evidence.courseAgreementDeg)
        val rejected = assertNotNull(evidence.rejectedCourseAgreementDeg)
        assertEquals(0.0, chosen, 2.0, "course agrees with the outbound arm")
        assertEquals(180.0, kotlin.math.abs(rejected), 2.0, "and opposes the return arm")
    }

    @Test
    fun anOverGateFix_stillRecordsWhatTheMatcherConsidered() {
        val (tracker, recorder) = recorderOnNorthTrail()
        val first = sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0)
        recorder.observe(first, tracker.onFix(first))

        // Far enough off that the candidate is rejected by the gate.
        val second = sampleAt(northM = 100.0, eastM = 90.0, timestampMs = 1_000)
        val match = tracker.onFix(second)
        val evidence = recorder.observe(second, match)

        // The evidence describes what the matcher *considered*, not only what it accepted —
        // a rejected candidate is exactly what a replay needs in order to ask whether the
        // rejection was right.
        assertEquals(MatchState.Uncertain, match.state, "precondition: the candidate was rejected")
        assertEquals(0.0, assertNotNull(evidence.tangentDeg), 1.0, "the considered candidate's tangent")
    }

    @Test
    fun aFixWithNoCandidateAtAll_recordsNoTangent() {
        val recorder = MatchEvidenceRecorder(northTrail())

        // The scan produced nothing at all — the shape of a fix held back by the rescan cooldown.
        val evidence =
            recorder.observe(
                sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0),
                TrailMatch(
                    state = MatchState.Lost,
                    confirmedAlongM = 100.0,
                    predictedAlongM = 100.0,
                    position = null,
                    chosen = null,
                    bestRejected = null,
                    unmatchedCount = 9,
                    uncertainSec = 120.0,
                    travelledM = 0.0,
                    scanKind = ScanKind.None,
                    windowM = null,
                    budgetM = 0.0,
                    predictionErrorM = null,
                    disposition = "hold:rescan_cooldown",
                ),
            )

        assertNull(evidence.tangentDeg, "no candidate means no tangent to agree with")
        assertNull(evidence.courseAgreementDeg, "and nothing to agree with it")
    }
}
