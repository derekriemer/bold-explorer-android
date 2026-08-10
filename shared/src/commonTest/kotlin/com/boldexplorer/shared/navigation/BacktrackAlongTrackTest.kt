package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "You may be going the wrong way" keyed on along-track regression rather than distance-to-target.
 *
 * The field report that motivated this: walking straight down a trail where the recording turns
 * 90° right, the app announced *wrong way* at the corner. The user had not reversed — they had
 * missed a turn, which is an off-trail condition wearing the wrong label.
 *
 * The old rule watched `distanceToTargetM` grow by 2 m across 3 fixes. Walking past any turn, or
 * simply having a stale target behind you, satisfies that in about three seconds — so wrong-way
 * consistently pre-empted off-trail, which needs cross-track past a 20 m gate and takes far longer.
 *
 * Along-track regression means what the announcement claims: the user's projected position on the
 * trail is moving backwards. Missing a turn plateaus along-track (the nearest point stays at the
 * corner) without reversing it, so it is correctly left to off-trail.
 */
class BacktrackAlongTrackTest {
    private fun coordinator() = TrailGuidanceCoordinator(TestScope())

    private fun latFor(m: Double) = m / 111_194.9

    private fun lonFor(m: Double) = m / 111_194.9

    /** North 100 m from (0,0), then a hard right turn and 100 m east. */
    private val cornerTrail =
        TrailFollowerState.Active(
            waypoints =
                listOf(
                    TrailPoint(1, "Start", 0.0, 0.0),
                    TrailPoint(2, "Corner", latFor(100.0), 0.0),
                    TrailPoint(3, "End", latFor(100.0), lonFor(100.0)),
                ),
            currentIndex = 2,
            thresholdM = 15.0,
        )

    private fun at(
        timestampMs: Long,
        northM: Double,
        eastM: Double = 0.0,
    ) = LocationSample(
        lat = latFor(northM),
        lon = lonFor(eastM),
        accuracy = 5.0,
        timestamp = timestampMs,
    )

    /** Guidance whose distance-to-target grows — what the old rule keyed on. */
    private fun guidanceWithDistance(distanceM: Double) =
        TrailGuidanceState(
            targetIndex = 2,
            targetName = "End",
            total = 3,
            distanceToTargetM = distanceM,
            desiredCourseDeg = 90.0,
            relativeDeg = 0.0,
            courseIsFresh = true,
        )

    @Test
    fun walkingStraightPastAHardRightTurn_doesNotClaimWrongWay() {
        // The field scenario. The user continues north while the trail turns east. Their projected
        // position pins at the corner and stops advancing — it never goes backwards — so this is
        // off-trail's business, not wrong-way's.
        val c = coordinator()
        c.resetThrottle(at(0, northM = 0.0))
        var distance = 100.0
        for ((i, north) in listOf(105.0, 115.0, 125.0, 135.0, 145.0).withIndex()) {
            distance += 10.0 // distance to the eastern target grows on every fix
            val eval =
                c.evaluateBacktrack(
                    cornerTrail,
                    at(100_000L + i * 1_000L, northM = north),
                    guidanceWithDistance(distance),
                )
            if (eval != null) {
                assertFalse(
                    eval.fired,
                    "fix $i claimed wrong way while walking past a turn: ${eval.disposition}",
                )
            }
        }
    }

    @Test
    fun genuinelyWalkingBackAlongTheTrail_firesWrongWay() {
        // The case the announcement is actually for: retreating along the trail the user came up.
        val c = coordinator()
        c.resetThrottle(at(0, northM = 80.0))
        var fired = false
        for ((i, north) in listOf(70.0, 60.0, 50.0, 40.0).withIndex()) {
            val eval =
                c.evaluateBacktrack(
                    cornerTrail,
                    at(100_000L + i * 1_000L, northM = north),
                    guidanceWithDistance(100.0 + i * 10.0),
                )
            if (eval?.fired == true) fired = true
        }
        assertTrue(fired, "walking back down the trail must eventually announce wrong way")
    }

    @Test
    fun staleTargetDoesNotProduceAFalseWrongWay() {
        // The stale-target false positive, the same root cause as the old off-trail defect: the
        // user advances correctly along the trail while distance to a target behind them grows.
        val c = coordinator()
        c.resetThrottle(at(0, northM = 10.0))
        var distance = 50.0
        for ((i, north) in listOf(20.0, 30.0, 40.0, 50.0, 60.0).withIndex()) {
            distance += 10.0
            val eval =
                c.evaluateBacktrack(
                    cornerTrail,
                    at(100_000L + i * 1_000L, northM = north),
                    guidanceWithDistance(distance),
                )
            if (eval != null) {
                assertFalse(
                    eval.fired,
                    "fix $i claimed wrong way while advancing correctly: ${eval.disposition}",
                )
            }
        }
    }

    @Test
    fun smallJitterDoesNotCountAsRegression() {
        // GPS noise moves the projection back and forth by a metre or two; only movement past the
        // noise floor counts.
        val c = coordinator()
        c.resetThrottle(at(0, northM = 50.0))
        var fired = false
        for ((i, north) in listOf(49.5, 50.2, 49.4, 50.1, 49.6).withIndex()) {
            val eval =
                c.evaluateBacktrack(cornerTrail, at(100_000L + i * 1_000L, northM = north), guidanceWithDistance(100.0))
            if (eval?.fired == true) fired = true
        }
        assertFalse(fired, "sub-metre jitter must not read as walking backwards")
    }

    @Test
    fun alongTrackIsReportedForTheLog() {
        val c = coordinator()
        c.resetThrottle(at(0, northM = 80.0))
        c.evaluateBacktrack(cornerTrail, at(100_000, northM = 70.0), guidanceWithDistance(100.0))
        val eval = assertNotNull(c.evaluateBacktrack(cornerTrail, at(101_000, northM = 60.0), guidanceWithDistance(110.0)))
        assertNotNull(eval.alongTrackM, "along-track must be logged")
        assertNotNull(eval.prevAlongTrackM, "previous along-track must be logged")
        assertNotNull(eval.distanceToTargetM, "distance is still logged, for replay comparison")
    }
}
