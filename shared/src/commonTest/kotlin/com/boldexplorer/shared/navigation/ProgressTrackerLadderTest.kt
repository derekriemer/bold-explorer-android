package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.model.LocationSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reacquisition ladder: `Matched → Uncertain → Lost → Unconfirmed → Matched`.
 *
 * The widening window is the *preferred* mechanism, because continuity is the only signal that
 * separates switchback arms and out-and-back passes. But a window that widens without limit
 * silently becomes a global scan while still claiming the continuity that made it safe, so past a
 * horizon bounded in **both** metres and seconds the tracker has to admit it no longer knows.
 *
 * The discipline that makes global reacquisition acceptable is that its result stays
 * [MatchState.Unconfirmed] until displacement corroborates it. The scan is cheap; trusting the scan
 * is what is expensive.
 */
class ProgressTrackerLadderTest {
    private fun straightTrail() = TrailPolyline(densify(northShape(600.0), 10.0))

    private val TrailMatch.confirmed: Double get() = assertNotNull(confirmedAlongM, "no confirmed position")

    /** Acquires cleanly at 200 m along, at the given speed, and returns the tracker. */
    private fun trackerAt200(speedMps: Double): ProgressTracker {
        val tracker = ProgressTracker(straightTrail())
        tracker.onFix(sampleAt(northM = 200.0, eastM = 0.0, timestampMs = 0, speedMps = speedMps))
        return tracker
    }

    @Test
    fun horizon_expiresOnDistanceWithTimeToSpare() {
        // 5 m/s off-trail: 120 m of reckoning accrues in ~24 s, well inside the 90 s bound.
        val tracker = trackerAt200(speedMps = 5.0)

        var lost: TrailMatch? = null
        for (i in 1..40) {
            val m = tracker.onFix(sampleAt(200.0 + i * 5.0, eastM = 90.0, timestampMs = i * 1_000L, speedMps = 5.0))
            if (m.state == MatchState.Lost) {
                lost = m
                break
            }
        }

        val match = assertNotNull(lost, "the distance bound must expire even though time has not")
        assertEquals("lost:reckoning_expired_m", match.disposition, "expired on metres, not seconds")
        assertTrue(match.uncertainSec < NavigationPolicy.RECKONING_HORIZON_S, "time bound had room left")
    }

    @Test
    fun horizon_expiresOnTimeWithDistanceToSpare() {
        // Stationary with bad GPS: seconds burn without metres.
        val tracker = trackerAt200(speedMps = 0.0)

        var lost: TrailMatch? = null
        for (i in 1..120) {
            val m = tracker.onFix(sampleAt(200.0, eastM = 90.0, timestampMs = i * 1_000L, speedMps = 0.0))
            if (m.state == MatchState.Lost) {
                lost = m
                break
            }
        }

        val match = assertNotNull(lost, "the time bound must expire even though no distance accrued")
        assertEquals("lost:reckoning_expired_s", match.disposition, "expired on seconds, not metres")
    }

    @Test
    fun lost_preservesConfirmedProgressAsAValue() {
        val tracker = trackerAt200(speedMps = 0.0)

        var lost: TrailMatch? = null
        for (i in 1..120) {
            val m = tracker.onFix(sampleAt(200.0, eastM = 90.0, timestampMs = i * 1_000L, speedMps = 0.0))
            if (m.state == MatchState.Lost) {
                lost = m
                break
            }
        }

        // Only the role of confirmedAlongM in bounding the search ends. Consumers keep reading it,
        // hedged — clearing it here would satisfy a plausible reading of "discards" and silently
        // break the dog fixture's premise.
        assertEquals(200.0, assertNotNull(lost).confirmed, 2.0, "the value survives Lost")
    }

    @Test
    fun globalScan_yieldsUnconfirmedRatherThanMatched() {
        val tracker = trackerAt200(speedMps = 0.0)
        repeat(120) { tracker.onFix(sampleAt(200.0, eastM = 90.0, timestampMs = (it + 1) * 1_000L, speedMps = 0.0)) }

        // Back on the trail 300 m further along — geometrically perfect, but unearned.
        val match = tracker.onFix(sampleAt(500.0, eastM = 0.0, timestampMs = 200_000L, speedMps = 1.4))

        assertEquals(MatchState.Unconfirmed, match.state, "a global match constrains nothing yet")
        assertEquals(ScanKind.Global, match.scanKind, "the scan that produced it was global")
        assertEquals(200.0, match.confirmed, 2.0, "progress has NOT jumped to the unconfirmed candidate")
        assertEquals(500.0, assertNotNull(match.chosen).alongTrackM, 2.0, "the candidate is carried, not committed")
    }

    @Test
    fun corroboratingDisplacement_promotesToMatched() {
        val tracker = trackerAt200(speedMps = 0.0)
        repeat(120) { tracker.onFix(sampleAt(200.0, eastM = 90.0, timestampMs = (it + 1) * 1_000L, speedMps = 0.0)) }
        tracker.onFix(sampleAt(500.0, eastM = 0.0, timestampMs = 200_000L, speedMps = 5.0))

        // Walk on along the trail; displacement now agrees with the candidate.
        var last: TrailMatch? = null
        for (i in 1..10) {
            last = tracker.onFix(sampleAt(500.0 + i * 5.0, eastM = 0.0, timestampMs = 200_000L + i * 1_000L, speedMps = 5.0))
            if (last.state == MatchState.Matched) break
        }

        val match = assertNotNull(last)
        assertEquals(MatchState.Matched, match.state, "corroborated by displacement: ${match.disposition}")
        assertTrue(match.confirmed > 500.0, "progress finally commits to the reacquired position")
    }

    @Test
    fun promotionAfterReacquisition_doesNotCountAsTravel() {
        val tracker = trackerAt200(speedMps = 0.0)
        repeat(120) { tracker.onFix(sampleAt(200.0, eastM = 90.0, timestampMs = (it + 1) * 1_000L, speedMps = 0.0)) }
        tracker.onFix(sampleAt(500.0, eastM = 0.0, timestampMs = 200_000L, speedMps = 5.0))

        var last: TrailMatch? = null
        for (i in 1..10) {
            last = tracker.onFix(sampleAt(500.0 + i * 5.0, eastM = 0.0, timestampMs = 200_000L + i * 1_000L, speedMps = 5.0))
            if (last.state == MatchState.Matched) break
        }

        // The 300 m jump from 200 to 500 was a reacquisition, not walking. Counting it would let a
        // single bad reacquisition satisfy the completion travel guard outright.
        assertTrue(
            assertNotNull(last).travelledM < 100.0,
            "a reacquisition jump must not count as travel: ${last?.travelledM}",
        )
    }

    @Test
    fun failedCorroboration_returnsToLostWithoutRescanning() {
        val tracker = trackerAt200(speedMps = 0.0)
        repeat(120) { tracker.onFix(sampleAt(200.0, eastM = 90.0, timestampMs = (it + 1) * 1_000L, speedMps = 0.0)) }
        val unconfirmed = tracker.onFix(sampleAt(500.0, eastM = 0.0, timestampMs = 200_000L, speedMps = 1.4))
        assertEquals(MatchState.Unconfirmed, unconfirmed.state, "precondition: we are corroborating")

        val failed = tracker.onFix(sampleAt(505.0, eastM = 90.0, timestampMs = 201_000L, speedMps = 1.4))

        assertEquals(MatchState.Lost, failed.state, "the candidate did not survive")
        assertEquals("lost:corroboration_failed", failed.disposition, "and says so")

        // Immediately afterwards there must be no new global scan — the cooldown is what stops a
        // genuinely-off-trail user from scanning on every fix at 1 Hz forever.
        val next = tracker.onFix(sampleAt(510.0, eastM = 0.0, timestampMs = 202_000L, speedMps = 1.4))
        assertEquals(ScanKind.None, next.scanKind, "no rescan during cooldown")
        assertEquals("hold:rescan_cooldown", next.disposition, "and says so")
    }

    @Test
    fun aUserWalkingAwayForFiveMinutes_scansGloballyAtMostOncePerCooldown() {
        val tracker = trackerAt200(speedMps = 1.4)

        var globalScans = 0
        for (i in 1..300) {
            // Steadily further from the trail, never within gate.
            val m = tracker.onFix(sampleAt(200.0, eastM = 90.0 + i, timestampMs = i * 1_000L, speedMps = 1.4))
            if (m.scanKind == ScanKind.Global) globalScans++
        }

        val ceiling = (300.0 / NavigationPolicy.RESCAN_COOLDOWN_S).toInt() + 1
        assertTrue(globalScans <= ceiling, "expected at most $ceiling global scans, got $globalScans")
        assertTrue(globalScans > 0, "but it must keep trying occasionally")
    }

    @Test
    fun theDogFixture_progressSurvivesAFourMinuteStopAndNeverPassesTheFork() {
        // Walk to 200 m, stop for four minutes with noisy fixes well off the trail, then resume.
        // A fork sits at 260 m; reacquisition must never land past it.
        val tracker = trackerAt200(speedMps = 1.4)
        val forkM = 260.0

        var worstConfirmed = 200.0

        fun observe(m: TrailMatch) {
            worstConfirmed = maxOf(worstConfirmed, m.confirmed)
        }

        for (i in 1..240) {
            observe(tracker.onFix(sampleAt(200.0, eastM = 45.0, timestampMs = i * 1_000L, speedMps = 0.05)))
        }
        val atResume = tracker.match!!
        assertEquals(200.0, atResume.confirmed, 2.0, "four minutes stopped must not move progress")

        // Resume walking from where they actually stood.
        for (i in 1..40) {
            observe(
                tracker.onFix(
                    sampleAt(200.0 + i * 1.4, eastM = 0.0, timestampMs = 240_000L + i * 1_000L, speedMps = 1.4),
                ),
            )
        }

        assertTrue(
            worstConfirmed < forkM,
            "progress must never reach the fork at $forkM m; peak was $worstConfirmed",
        )
    }

    @Test
    fun theLakeFixture_anOffTrailExcursionNeverInflatesProgress() {
        // Leave the trail at 200 m, walk 150 m off it and back, rejoin at 205 m. The tracker must
        // never claim ~350 m — the distance walked is not the distance progressed.
        val tracker = trackerAt200(speedMps = 1.4)

        var worstConfirmed = 200.0
        var t = 0L

        fun step(sample: LocationSample) {
            val m = tracker.onFix(sample)
            worstConfirmed = maxOf(worstConfirmed, m.confirmed)
        }

        for (i in 1..150) {
            t += 1_000
            step(sampleAt(200.0, eastM = i.toDouble(), timestampMs = t, speedMps = 1.4))
        }
        for (i in 149 downTo 0) {
            t += 1_000
            step(sampleAt(205.0, eastM = i.toDouble(), timestampMs = t, speedMps = 1.4))
        }

        assertTrue(worstConfirmed < 260.0, "progress must not inflate to the walked distance: $worstConfirmed")

        // And the rejoin must eventually be confirmed near where they actually rejoined.
        var last: TrailMatch? = null
        for (i in 1..40) {
            t += 1_000
            last = tracker.onFix(sampleAt(205.0 + i * 1.4, eastM = 0.0, timestampMs = t, speedMps = 1.4))
        }
        val match = assertNotNull(last)
        assertEquals(MatchState.Matched, match.state, "rejoining the trail must eventually confirm")
        assertTrue(match.confirmed in 200.0..280.0, "confirmed near the rejoin point, got ${match.confirmed}")
    }
}
