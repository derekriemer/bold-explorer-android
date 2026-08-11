package com.boldexplorer.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Core matching behaviour: acquisition, steady tracking, and the freeze that protects progress when
 * geometry stops corroborating it.
 *
 * The invariant these exist to defend is that `confirmedAlongM` moves **only** when a fix actually
 * projects onto the trail within gate. Dead reckoning produces `predictedAlongM`, which ranks tied
 * candidates and nothing else. Conflating the two is the failure that advances a user past a fork
 * they never reached and then "reacquires" onto the wrong branch with a prior that makes the wrong
 * branch look corroborated.
 */
class ProgressTrackerTest {
    private fun straightTrail() = TrailPolyline(densify(northShape(400.0), 10.0))

    /**
     * Confirmed progress, asserted present.
     *
     * `confirmedAlongM` is nullable because before acquisition there genuinely is no
     * geometry-corroborated position — that is a state the type is meant to express, not a
     * convenience. These tests all run after a successful acquisition, so they unwrap it.
     */
    private val TrailMatch.confirmed: Double get() = assertNotNull(confirmedAlongM, "no confirmed position")

    private val TrailMatch.predicted: Double get() = assertNotNull(predictedAlongM, "no prediction")

    @Test
    fun firstFix_acquiresAndMatches() {
        val tracker = ProgressTracker(straightTrail())

        val match = tracker.onFix(sampleAt(northM = 100.0, eastM = 3.0, timestampMs = 0))

        assertEquals(MatchState.Matched, match.state, "a clean first fix acquires immediately")
        assertEquals(100.0, match.confirmed, 2.0, "acquired abeam the fix")
        assertEquals(ScanKind.Global, match.scanKind, "no prior exists yet, so the first scan is global")
    }

    @Test
    fun walkingForward_advancesConfirmedAlongM() {
        val tracker = ProgressTracker(straightTrail())
        tracker.onFix(sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0))

        val match = tracker.onFix(sampleAt(northM = 101.4, eastM = 0.0, timestampMs = 1_000))

        assertEquals(MatchState.Matched, match.state, "still matched")
        assertEquals(101.4, match.confirmed, 2.0, "progress follows the walk")
        assertEquals(ScanKind.Windowed, match.scanKind, "a prior exists, so the scan is windowed")
    }

    @Test
    fun aFixBeyondTheGate_goesUncertainAndFreezesProgress() {
        val tracker = ProgressTracker(straightTrail())
        val acquired = tracker.onFix(sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0))

        val match = tracker.onFix(sampleAt(northM = 110.0, eastM = 80.0, timestampMs = 1_000))

        assertEquals(MatchState.Uncertain, match.state, "80 m off trail is not a match")
        assertEquals(
            acquired.confirmed,
            match.confirmed,
            0.0,
            "confirmed progress must be frozen exactly, not merely close",
        )
    }

    @Test
    fun confirmedAlongM_isByteIdenticalAcrossAnEntireUncertainRun() {
        val tracker = ProgressTracker(straightTrail())
        val acquired = tracker.onFix(sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0))

        // Twenty seconds of fixes far off the trail. Prediction advances throughout; progress
        // must not. Asserted on the value rather than on the state label, because a regression
        // here would most likely keep the label correct and move the number.
        var last = acquired
        for (i in 1..20) {
            last = tracker.onFix(sampleAt(northM = 100.0 + i, eastM = 90.0, timestampMs = i * 1_000L))
            assertEquals(
                acquired.confirmed,
                last.confirmed,
                0.0,
                "confirmed progress moved at fix $i, state=${last.state}",
            )
        }
        assertTrue(
            last.predicted > acquired.confirmed,
            "prediction should have advanced meanwhile: ${last.predicted}",
        )
    }

    @Test
    fun returningToTheTrail_rematchesAndResumesProgress() {
        val tracker = ProgressTracker(straightTrail())
        tracker.onFix(sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0))
        tracker.onFix(sampleAt(northM = 105.0, eastM = 80.0, timestampMs = 1_000))

        val match = tracker.onFix(sampleAt(northM = 108.0, eastM = 2.0, timestampMs = 2_000))

        assertEquals(MatchState.Matched, match.state, "back on the trail")
        assertEquals(108.0, match.confirmed, 2.0, "progress resumes where geometry says")
    }

    @Test
    fun uncertainRun_widensTheWindowAroundTheLastConfirmedPosition() {
        val tracker = ProgressTracker(straightTrail())
        tracker.onFix(sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0))

        val early = tracker.onFix(sampleAt(northM = 100.0, eastM = 90.0, timestampMs = 1_000))
        val later = tracker.onFix(sampleAt(northM = 100.0, eastM = 90.0, timestampMs = 6_000))

        assertTrue(later.budgetM > early.budgetM, "budget must grow: ${early.budgetM} -> ${later.budgetM}")
        val window = assertNotNull(later.windowM, "an uncertain fix still has a search window")
        assertEquals(
            later.confirmed,
            (window.start + window.endInclusive) / 2.0,
            0.5,
            "the window stays centred on confirmed progress, never on the prediction",
        )
    }

    @Test
    fun travelledM_accumulatesOnlyConfirmedMovement() {
        val tracker = ProgressTracker(straightTrail())
        tracker.onFix(sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0))
        val walked = tracker.onFix(sampleAt(northM = 120.0, eastM = 0.0, timestampMs = 5_000))

        val drifted = tracker.onFix(sampleAt(northM = 200.0, eastM = 90.0, timestampMs = 10_000))

        assertEquals(20.0, walked.travelledM, 2.0, "20 m of confirmed walking")
        assertEquals(
            walked.travelledM,
            drifted.travelledM,
            0.0,
            "an unmatched fix contributes no travel, however far it appears to move",
        )
    }

    @Test
    fun beforeAnyFix_thereIsNoMatch() {
        val tracker = ProgressTracker(straightTrail())

        assertNull(tracker.match, "a tracker that has seen no fix has nothing to report")
    }

    @Test
    fun match_exposesTheLatestResult() {
        val tracker = ProgressTracker(straightTrail())

        val returned = tracker.onFix(sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0))

        assertSame(returned, tracker.match, "the returned match is the tracker's current state")
    }
}
