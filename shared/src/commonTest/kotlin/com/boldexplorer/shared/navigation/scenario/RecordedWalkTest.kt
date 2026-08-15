package com.boldexplorer.shared.navigation.scenario

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recorded walks, replayed against the navigation core.
 *
 * Every assertion here comes from what the walk is **known to have been**, established from the
 * geometry, and not from what the code currently does. A test written from observed output only
 * pins today's behaviour; these can fail because the behaviour is wrong.
 *
 * The first scenario is a worked example of why that distinction matters. It was labelled "a walk
 * that finished the trail, so nothing should announce wrong way" — which sounds obvious and is
 * false. Replaying it produced an alert, and checking the geometry showed the user really did walk
 * 38 m back down the trail before turning around. The label was wrong, the alert was right, and a
 * test written to the label would have wasted an afternoon chasing a correct detector.
 */
class RecordedWalkTest {
    /** Milliseconds bracketing the verified reversal in [ReverseWalkWithOneReversal]. */
    private val reversalWindow = 460_000L..515_000L

    @Test
    fun aGenuineReversalIsReported() {
        // 2026-08-02, 472s-502s: along-track falls 544 m -> 506 m across six consecutive fixes at
        // 1.4 m/s, with accuracy near 2.7 m and cross-track near 1 m. Not noise, not a switchback,
        // not a stale target — the user walked back down the trail and then resumed.
        val result = ScenarioRunner.run(ReverseWalkWithOneReversal)

        val alerts = result.backtrackAlerts
        assertEquals(
            1,
            alerts.size,
            "expected exactly one wrong-way alert; got ${alerts.map { "${it.fix.tMs / 1000}s" }}\n" +
                alerts.firstOrNull()?.let { result.around(it) }.orEmpty(),
        )
        assertTrue(
            alerts.single().fix.tMs in reversalWindow,
            "the alert landed at ${alerts.single().fix.tMs / 1000}s, outside the verified reversal\n" +
                result.around(alerts.single()),
        )
    }

    @Test
    fun theUserNeverLeftTheLineSoOffTrailStaysSilent() {
        // The same session, and the improvement worth pinning: the build of the day announced "you
        // may be off trail" one second after the wrong-way alert. Cross-track never exceeded ~6 m
        // on the entire walk. That alert came from the bearing angle, which is exactly what
        // off-trail detection was rebuilt to stop doing.
        val result = ScenarioRunner.run(ReverseWalkWithOneReversal)

        assertTrue(
            result.offTrailAlerts.isEmpty(),
            "off-trail on a walk that stayed on the line:\n" +
                result.offTrailAlerts.joinToString("\n") { result.around(it) },
        )
    }

    @Test
    fun theReverseWalkIsTrackedFromEndToEnd() {
        // "No alert" is trivially satisfiable by losing the user entirely, so assert the matcher
        // actually followed them the length of the trail.
        val result = ScenarioRunner.run(ReverseWalkWithOneReversal)

        assertTrue(
            result.coverageFraction > 0.9,
            "a completed walk should cover the trail; covered ${(result.coverageFraction * 100).toInt()}%",
        )
        assertTrue(
            result.matchedFraction > 0.8,
            "matched on only ${(result.matchedFraction * 100).toInt()}% of fixes",
        )
    }

    @Test
    fun anUneventfulWalkStaysSilent() {
        // The control. Nothing happened on this walk and the build of the day said nothing either,
        // so any alert here is a false positive introduced since.
        val result = ScenarioRunner.run(CleanForwardWalk)

        assertTrue(
            result.offTrailAlerts.isEmpty() && result.backtrackAlerts.isEmpty(),
            "an uneventful walk produced alerts:\n" +
                (result.offTrailAlerts + result.backtrackAlerts).joinToString("\n") { result.around(it) },
        )
    }

    @Test
    fun theLoopWalkIsTrackedAndDoesNotClaimAReversal() {
        // A loop is where continuity earns its keep: the start and end are near each other, so a
        // matcher resolving position by proximity alone would jump between them.
        val result = ScenarioRunner.run(LakeLoopReverse)

        assertTrue(
            result.backtrackAlerts.isEmpty(),
            "wrong-way on a completed loop at ${result.backtrackAlerts.map { "${it.fix.tMs / 1000}s" }}",
        )
        assertTrue(
            result.matchedFraction > 0.8,
            "matched on only ${(result.matchedFraction * 100).toInt()}% of fixes",
        )
    }

    // ── Walks the owner labelled while walking them (2026-08-12) ──────────────────

    @Test
    fun aDeliberateExcursionOffTheTrailIsReported() {
        // 01:19:33, mid-walk: "making a big old loop off trail. Will be back on earlier stretch
        // eventually". The user left the trail on purpose and said so, so the eleven alerts the
        // build of the day produced from 1458s onward were right. This is the one scenario here
        // that asserts an alert *must* happen on real geometry.
        val result = ScenarioRunner.run(DeliberateOffTrailLoop)
        val excursion = 1_450_000L..2_100_000L

        val during = result.offTrailAlerts.filter { it.fix.tMs in excursion }
        assertTrue(
            during.isNotEmpty(),
            "the user was deliberately off trail here and heard about it; we said nothing. " +
                "alerts anywhere: ${result.offTrailAlerts.map { it.fix.tMs / 1000 }}",
        )
    }

    @Test
    fun theMarkedFalseWrongWayAlertsAreNotReproduced() {
        // 01:37:40, mid-walk: "weird wrong way fired". Two wrong-way alerts eight seconds apart —
        // 422s and 441s here — inside a span where accuracy was deliberately degraded to 25 m. The
        // user called them wrong at the time, which is as close to ground truth as this corpus gets.
        val result = ScenarioRunner.run(DegradedReverseFalseAlarms)
        val degraded = 371_000L..463_000L

        val during = result.backtrackAlerts.filter { it.fix.tMs in degraded }
        assertTrue(
            during.isEmpty(),
            "reproduced a wrong-way alert the user called wrong:\n" + during.joinToString("\n") { result.around(it) },
        )
    }

    @Test
    fun theDegradedWalkStillReportsBeingOffTheLine() {
        // The companion to the assertion above, which silence alone would satisfy. The owner spent
        // this session deliberately provoking switchback drift: cross-track runs to 20 m at p90 and
        // 35 m at its worst *while matched*, and the build of the day announced off-trail six times.
        // Being off the line is the one thing this walk unambiguously was, so we have to say so.
        val result = ScenarioRunner.run(DegradedReverseFalseAlarms)

        assertTrue(
            result.offTrailAlerts.isNotEmpty(),
            "a walk that spent minutes 20-35 m off the line produced no off-trail alert",
        )
    }

    // Deliberately not asserted on this scenario: match rate and coverage. Measured at 52% matched
    // and 35% of the trail covered, and both are the honest answer rather than a defect — the owner
    // was standing around a switchback provoking drift, then degrading the fixes, so `Uncertain` is
    // what the ladder is *for*. A threshold here would be a judgement about the walk, not the code,
    // and would break the moment someone tuned the matcher in a way this session happens to dislike.

    @Test
    fun aWalkPushedOffTheTrailDoesAlert() {
        // Guards the three assertions above, which are all of the form "no alert" and would pass
        // just as happily against a detector that never fires at all. Take the walk that must stay
        // silent, displace every fix 40 m sideways, and it must stop being silent.
        val result = ScenarioRunner.run(CleanForwardWalk.displacedBy(eastM = 40.0))

        assertTrue(
            result.offTrailAlerts.isNotEmpty(),
            "a walk 40 m off the trail produced no off-trail alert:\n" + result.transcript(result.steps.take(12)),
        )
    }

    /** The same walk, every fix moved sideways. The trail, and therefore the truth, is unchanged. */
    private fun WalkScenario.displacedBy(eastM: Double): WalkScenario {
        val original = this
        return object : WalkScenario {
            override val label = "${original.label}+${eastM.toInt()}m"
            override val direction = original.direction
            override val trailMetres = original.trailMetres
            override val fixes = original.fixes.map { it.copy(eastM = it.eastM + eastM) }
        }
    }

    @Test
    fun everyScenarioProducesADecisionForEveryFix() {
        // Guards the harness rather than the code. A scenario that silently produced no steps, or
        // no guidance, would satisfy every "no alert" assertion above while testing nothing.
        val all = listOf(ReverseWalkWithOneReversal, CleanForwardWalk, LakeLoopReverse, DeliberateOffTrailLoop, DegradedReverseFalseAlarms)
        for (scenario in all) {
            val result = ScenarioRunner.run(scenario)
            assertEquals(scenario.fixes.size, result.steps.size, "${scenario.label}: steps lost")
            assertTrue(
                result.steps.count { it.desiredCourseDeg != null } > result.steps.size / 2,
                "${scenario.label}: guidance produced a course on fewer than half the fixes",
            )
        }
    }
}
