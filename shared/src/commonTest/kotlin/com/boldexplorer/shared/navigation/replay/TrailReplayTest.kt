package com.boldexplorer.shared.navigation.replay

import com.boldexplorer.shared.navigation.MatchState
import com.boldexplorer.shared.navigation.MatchTuning
import com.boldexplorer.shared.navigation.TrailPolyline
import com.boldexplorer.shared.navigation.northShape
import com.boldexplorer.shared.navigation.sampleAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The replay harness reports numbers that will be used to change shipping constants, so the numbers
 * themselves need to be trustworthy before any of them are believed. These pin the metrics against
 * walks whose correct answer is known by construction.
 */
class TrailReplayTest {
    private val trail = TrailPolyline(northShape(lengthM = 400.0))

    /** A clean walk straight up the trail at 1.2 m/s, one fix a second. */
    private fun cleanWalk(count: Int = 120) =
        (0 until count).map { i ->
            sampleAt(northM = i * 1.2, eastM = 0.0, timestampMs = 1_000L + i * 1000L)
        }

    @Test
    fun aCleanWalkMatchesThroughout() {
        val report = TrailReplay.run(trail, cleanWalk())
        assertTrue(
            report.matchedFraction > 0.95,
            "a walk straight along the trail should match; got ${report.matchedFraction}",
        )
        assertTrue(report.maxCrossWhileMatchedM < 2.0, "got ${report.maxCrossWhileMatchedM}")
        assertEquals(0, report.reacquisitions.size, "nothing was lost, so nothing was reacquired")
    }

    @Test
    fun theSameWalkUnderTwoTuningsIsComparable() {
        // The whole point of the harness: identical input, different constants, different numbers.
        // The walk is offset 3 m from the centreline, because a walk dead on it matches under any
        // gate at all and would compare two tunings that never actually disagreed.
        val walk =
            (0 until 120).map { i ->
                sampleAt(northM = i * 1.2, eastM = 3.0, timestampMs = 1_000L + i * 1000L)
            }
        val tight = MatchTuning.DEFAULT.copy(matchGateBaseM = 1.0, matchGateCapM = 1.0, matchGateAccuracyFactor = 0.0)
        assertTrue(
            TrailReplay.run(trail, walk, tuning = tight).matchedFraction <
                TrailReplay.run(trail, walk, tuning = MatchTuning.DEFAULT).matchedFraction,
            "a 1 m gate must match a 3 m-offset walk less often than the 25 m default",
        )
    }

    @Test
    fun walkingAwayAndBackIsCountedAsOneReacquisition() {
        val out = cleanWalk(40)
        val lastTs = out.last().timestamp
        val leftAtNorthM = 39 * 1.2
        // Straight out sideways, well past any gate, then straight back to where we left.
        val away =
            (1..60).map { i ->
                sampleAt(northM = leftAtNorthM, eastM = i * 3.0, timestampMs = lastTs + i * 1000L)
            }
        val back =
            (1..60).map { i ->
                sampleAt(northM = leftAtNorthM, eastM = 180.0 - i * 3.0, timestampMs = lastTs + 60_000L + i * 1000L)
            }
        // Then keep walking *along* the trail. Arriving back on it is not reacquisition: from Lost
        // the tracker still owes a global scan (behind its rescan cooldown) and then corroboration
        // by displacement. On the real walk that took 26 s, so a return leg that merely touches the
        // trail and stops would be asserting that reacquisition is instant — which it must not be.
        val resumeTs = back.last().timestamp
        val resumed =
            (1..90).map { i ->
                sampleAt(northM = leftAtNorthM + i * 1.2, eastM = 0.0, timestampMs = resumeTs + i * 1000L)
            }
        val report = TrailReplay.run(trail, out + away + back + resumed)
        assertTrue(report.stateCounts.getOrElse(MatchState.Lost) { 0 } > 0, "going 180 m off trail should reach Lost")
        assertEquals(1, report.recoveries.size, "one departure and one return is one recovery")
        assertTrue(report.recoveries.single().latencySec > 0.0)
        assertTrue(report.recoveries.single().reachedLost, "going 180 m off trail bottoms out at Lost")
    }

    @Test
    fun standingStillIsNotCountedAsVertexPinning() {
        // Vertex pinning is along-track freezing *while moving*. A stationary user also reports the
        // same along value every fix, and calling that a defect would bury the real signal under
        // every rest stop.
        val still = (0 until 60).map { i -> sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 1_000L + i * 1000L) }
        assertEquals(0, TrailReplay.run(trail, still).vertexPinnedFixes)
    }

    @Test
    fun aBriefDipToUncertainIsAFlapNotARecovery() {
        // Session 3 of the 2026-08-12 walk reported a median recovery of 2 s because three 2-second
        // wobbles were averaged in with real recoveries of 112 s and 156 s. Only bottoming out at
        // Lost costs a global scan and corroboration, so only that counts as a recovery.
        val walk =
            (0 until 90).map { i ->
                // One fix well outside the gate, then straight back onto the trail.
                val east = if (i == 45) 40.0 else 0.0
                sampleAt(northM = i * 1.2, eastM = east, timestampMs = 1_000L + i * 1000L)
            }
        val report = TrailReplay.run(trail, walk)
        assertEquals(0, report.stateCounts.getOrElse(MatchState.Lost) { 0 }, "one bad fix must not reach Lost")
        assertEquals(1, report.flaps.size, "the dip to Uncertain and back is a flap")
        assertEquals(0, report.recoveries.size, "and it is not a recovery")
        assertEquals(0.0, report.medianRecoverySec, "so it must not show up in the recovery median")
    }

    @Test
    fun anEmptyWalkReportsNothingRatherThanDividingByZero() {
        val report = TrailReplay.run(trail, emptyList())
        assertEquals(0, report.fixes)
        assertEquals(0.0, report.matchedFraction)
        assertEquals(0.0, report.maxCrossWhileMatchedM)
        assertEquals(0.0, report.medianRecoverySec)
    }
}
