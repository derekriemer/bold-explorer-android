package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Grace stops being an early return and becomes a flag (ADR 0001, S6).
 *
 * The point is measurement without exposure: the evaluators run every fix and emit a disposition,
 * so the next walk's log says what dropping grace *would* have done — while what is spoken does not
 * change at all. The loose alternative, shipping it live and reading the log afterwards, risks
 * discovering a false-positive storm several kilometres from home, alone.
 *
 * Both grace windows get the same treatment, so both are covered here even though only off-trail
 * appears in the design doc's example test.
 *
 * The fixtures below build their own geometry and guidance rather than reusing
 * [OffTrailCrossTrackTest.Harness] or [BacktrackAlongTrackTest]'s helpers verbatim: those are
 * `private` to their own test classes and Kotlin does not let a sibling file reach in. The general
 * geometry builders in `TrailFixtures.kt` (`northShape`, `sampleAt`, `densify`, …) are package-level
 * and *are* shared — the backtrack fixtures below use them directly rather than re-deriving them.
 */
class GraceShadowTest {
    // ── Off-trail ────────────────────────────────────────────────────────────────────

    /**
     * Off-trail's own harness, shaped like [OffTrailCrossTrackTest.Harness]: a real
     * [ProgressTracker] runs beside the coordinator, and it acquires on the line a second before
     * the first evaluated fix — what follow-start does. Off-trail reads the cross-track of the
     * candidate the matcher chose (ADR 0001, S5), so it cannot be exercised without one.
     */
    private inner class OffTrailHarness {
        val coordinator = TrailGuidanceCoordinator(TestScope())
        private val points = active.waypoints.map { LatLng(it.lat, it.lon) }
        private val polyline = TrailPolyline(points)
        private val tracker = ProgressTracker(polyline)
        private var acquired = false

        init {
            coordinator.startFollow(points, TravelDirection.Forward)
        }

        var shadowAlertsAudible: Boolean
            get() = coordinator.shadowAlertsAudible
            set(value) {
                coordinator.shadowAlertsAudible = value
            }

        fun resetThrottle(sample: LocationSample) = coordinator.resetThrottle(sample)

        fun evaluateOffTrail(sample: LocationSample): OffTrailEvaluation? {
            if (!acquired) {
                tracker.onFix(offTrailSample(sample.timestamp - 1_000, eastM = 0.0))
                acquired = true
            }
            return coordinator.evaluateOffTrail(active, sample, staleTargetGuidance(), tracker.onFix(sample))
        }
    }

    private val active =
        TrailFollowerState.Active(
            waypoints = listOf(TrailPoint(1, "A", 0.0, 0.0), TrailPoint(2, "B", 0.001, 0.0)),
            currentIndex = 1,
            thresholdM = 10.0,
        )

    private fun lonFor(m: Double) = m / 111_194.9

    /** A fix east of the due-north trail from (0,0). */
    private fun offTrailSample(
        timestampMs: Long,
        eastM: Double,
        accuracyM: Double = 5.0,
    ) = LocationSample(
        lat = 0.0005,
        lon = lonFor(eastM),
        accuracy = accuracyM,
        timestamp = timestampMs,
    )

    /** A stale target behind the user, so the bearing angle corroborates and selects the fast path. */
    private fun staleTargetGuidance() =
        TrailGuidanceState(
            targetIndex = 1,
            targetName = "B",
            total = 2,
            distanceToTargetM = 300.0,
            desiredCourseDeg = 0.0,
            relativeDeg = 175.0,
            courseIsFresh = true,
        )

    // resetThrottle is armed well past t=0 so that, once inside grace, the alert cooldown — measured
    // from the 0L "never fired" sentinel and independent of the grace window — has already elapsed.
    // At t=0 the two could never both hold: the cooldown needs 45 s from zero and the off-trail
    // grace window here lasts only 30 s, so a fix could never be both "inside grace" and "past
    // cooldown" — which would make it impossible to observe the switch actually letting one through.
    private val armedAtMs = 1_000_000L
    private val primingFixMs = armedAtMs + 10_000L
    private val secondFixMs = armedAtMs + 20_000L

    /**
     * A coordinator with an off-trail follow active, grace armed, and one qualifying fix already
     * evaluated — one short of the two-fix fast-path threshold the stale-target angle selects.
     */
    private fun coordinatorInGrace(): OffTrailHarness {
        val h = OffTrailHarness()
        h.resetThrottle(offTrailSample(armedAtMs, eastM = 0.0))
        // Builds sustain evidence toward the fast-path threshold without risking anything being
        // spoken: shadowAlertsAudible defaults false, so this fix cannot fire regardless of the maths.
        h.evaluateOffTrail(offTrailSample(primingFixMs, eastM = 35.0))
        return h
    }

    /** The second qualifying fix, well off trail, still inside the off-trail grace window. */
    private fun fixWellOffTrail(): LocationSample = offTrailSample(secondFixMs, eastM = 35.0)

    @Test
    fun anEvaluationIsProducedInsideGraceRatherThanSkipped() {
        val c = coordinatorInGrace()
        val eval = c.evaluateOffTrail(fixWellOffTrail())

        assertNotNull(eval, "inside grace the evaluator used to return null and log nothing")
        assertTrue(eval.suppressedByGrace)
        assertFalse(eval.fired, "grace still gates what is spoken — that must not change")
    }

    @Test
    fun theDispositionIsRecordedInsideGrace() {
        val eval = assertNotNull(coordinatorInGrace().evaluateOffTrail(fixWellOffTrail()))
        assertTrue(eval.disposition.isNotBlank(), "the log line is the whole deliverable")
    }

    // ── Two questions, two fields (spec correction, re-review 2026-08-17) ──────────────
    //
    // `disposition` answers "what actually happened" and can never contradict `fired`, because it is
    // built from whichever track decided it. `shadowDisposition` always answers the counterfactual —
    // "what would removing grace have produced" — regardless of `fired` or the switch. Counting
    // `shadow:would_fire` in `shadowDisposition` is how a walk answers that question; the first
    // version of this split answered it by prefix-matching a single `disposition` string, which let
    // a genuinely spoken alert log as `bail:cooldown_...` (the shadow's cooldown, not the live one
    // that actually decided to speak) — found in re-review.

    @Test
    fun aFixStoppedOnlyByGraceHasADivergentShadowDisposition() {
        val eval = assertNotNull(coordinatorInGrace().evaluateOffTrail(fixWellOffTrail()))

        assertTrue(eval.suppressedByGrace)
        assertFalse(eval.fired)
        // `disposition` describes live, which is frozen: one fix's worth of over-gate evidence,
        // never persisted, can never reach the two-fix threshold on its own.
        assertEquals(
            "hold:xt_35m_converging_1of2",
            eval.disposition,
            "disposition must describe live, which has not accumulated anything durable during grace",
        )
        // `shadowDisposition` describes the counterfactual: every real condition was met on the
        // shadow track, and only grace withheld it — that must be visible somewhere in the log.
        assertEquals("shadow:would_fire_xt_35m_converging", eval.shadowDisposition)
    }

    @Test
    fun theDebugSwitchLetsTheShadowBeHeard() {
        // Measuring from a log tells you the count; it does not tell you what the walk sounds like.
        // The owner asked to be able to hear it, having judged the risk acceptable — so the switch
        // exists, defaults off, and changes nothing else.
        val c = coordinatorInGrace()
        c.shadowAlertsAudible = true

        val eval = assertNotNull(c.evaluateOffTrail(fixWellOffTrail()))

        assertTrue(eval.suppressedByGrace, "the shadow still reports that grace would have muted it")
        assertTrue(eval.fired, "but it is spoken, because the switch is on")
        // With the switch on, `disposition` reads shadow (the track that decided `fired`), so it
        // now uses the `fire:` spelling — proving `disposition` and `fired` cannot diverge.
        assertEquals(
            "fire:xt_35m_converging",
            eval.disposition,
            "with the switch on, disposition reads the same track that decided fired",
        )
        // `shadowDisposition` keeps its own dedicated spelling regardless of the switch — it answers
        // the counterfactual question, not "what got spoken".
        assertEquals("shadow:would_fire_xt_35m_converging", eval.shadowDisposition)
    }

    @Test
    fun aFixStoppedByTheRealCooldownIsDistinctFromGraceShadow() {
        // Same shape as offTrail_respectsCooldownAfterFiring in TrailGuidanceCoordinatorTest, but
        // pinning the exact disposition string: a fix stopped by the real 45 s cooldown — nothing to
        // do with grace, which has already expired — must still say `bail:cooldown_...`, not
        // `shadow:...`. Outside grace `live` and `shadow` are in lockstep, so both fields agree.
        val c = OffTrailHarness()
        c.resetThrottle(offTrailSample(0, eastM = 0.0)) // off-trail grace expires at 30_000
        c.evaluateOffTrail(offTrailSample(100_000, eastM = 35.0)) // count 1
        val fired = assertNotNull(c.evaluateOffTrail(offTrailSample(101_000, eastM = 35.0))) // count 2, fires
        assertTrue(fired.fired, "precondition: this fix must actually fire")

        val cooled = assertNotNull(c.evaluateOffTrail(offTrailSample(102_000, eastM = 35.0)))

        assertFalse(cooled.suppressedByGrace, "grace expired long before t=100_000")
        assertFalse(cooled.fired)
        assertEquals("bail:cooldown_1000ms", cooled.disposition)
        assertEquals("bail:cooldown_1000ms", cooled.shadowDisposition)
    }

    @Test
    fun theSwitchDefaultsOff() {
        assertFalse(TrailGuidanceCoordinator(TestScope()).shadowAlertsAudible)
    }

    // ── State must not escape the grace window (Critical, review 2026-08-17) ──────────
    //
    // Running the evaluator every fix (above) is only half of Requirement A. The other half is that
    // the counters it mutates while doing so must not let evidence gathered *during* grace decide
    // whether the fix right *after* grace expires fires. The pre-S6 early return froze every counter
    // for the whole window, by construction, because it sat before any of them were touched; once the
    // evaluator started running unconditionally, a single shared counter set inherited whatever grace
    // had accumulated. This is the self-contained regression test for that; see also
    // TrailGuidanceCoordinatorTest.offTrail_suppressedDuringGraceWindow, which pins the same property
    // against a different fixture.

    @Test
    fun postGraceFixDoesNotInheritInGraceEvidence() {
        // One qualifying fix gathered entirely inside grace — one short of the two-fix fast-path
        // threshold. If that count carried over, the first post-grace fix would supply the second
        // and fire; the pre-S6 code's first post-grace fix always started counting from zero instead.
        val c = coordinatorInGrace()

        val afterGrace = assertNotNull(c.evaluateOffTrail(offTrailSample(armedAtMs + 31_000L, eastM = 35.0)))

        assertFalse(afterGrace.suppressedByGrace, "grace must actually have expired for this to be the regression")
        assertFalse(afterGrace.fired, "must not fire on carried-over in-grace evidence, switch off")
        // The other half of the property (re-review, 2026-08-17): the shadow's evidence must
        // *survive* the grace boundary rather than being reset at it — a "tidy up the shadow when
        // grace expires" edit would keep every assertion above green while quietly destroying the
        // measurement. `live` restarted at zero (asserted above); `shadow` did not, and reaches
        // wouldFire on this very fix (it needed only one more over-gate fix after the one gathered
        // in coordinatorInGrace).
        assertEquals("shadow:would_fire_xt_35m_converging", afterGrace.shadowDisposition)
    }

    // ── Backtrack gets the same treatment ───────────────────────────────────────────

    private fun backtrackActive(points: List<LatLng>) =
        TrailFollowerState.Active(
            waypoints = points.mapIndexed { i, p -> TrailPoint(i.toLong(), "p$i", p.lat, p.lon) },
            currentIndex = 1,
            thresholdM = 15.0,
        )

    private fun backtrackGuidance() =
        TrailGuidanceState(
            targetIndex = 2,
            targetName = "End",
            total = 3,
            distanceToTargetM = 100.0,
            desiredCourseDeg = 0.0,
            relativeDeg = 0.0,
            courseIsFresh = true,
        )

    /** A [TrailMatch] carrying only what the backtrack detector reads. */
    private fun matchAt(alongM: Double) =
        TrailMatch(
            state = MatchState.Matched,
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

    /**
     * A coordinator with a backtrack follow active, grace armed, and three fixes already evaluated:
     * one baseline (the first fix has nothing to regress from yet) and two regressions, bringing the
     * count to 2 — one short of [NavigationPolicy.BACKTRACK_CONSECUTIVE_THRESHOLD] (3), the same
     * "one fix from firing" shape as [coordinatorInGrace].
     */
    private fun coordinatorInGraceForBacktrack(): Triple<TrailGuidanceCoordinator, TrailFollowerState.Active, LocationSample> {
        val points = northShape(400.0)
        val active = backtrackActive(points)
        val c = TrailGuidanceCoordinator(TestScope())
        c.startFollow(points, TravelDirection.Forward)
        c.resetThrottle(sampleAt(northM = 100.0, eastM = 0.0, timestampMs = armedAtMs))

        // First fix only baselines prevAlongTrackM (there is nothing to regress from yet); the next
        // two are the regressions that bring the count to 2, one short of sustained.
        listOf(120.0, 110.0, 100.0).forEachIndexed { i, alongM ->
            c.evaluateBacktrack(
                active,
                sampleAt(northM = 100.0, eastM = 0.0, timestampMs = primingFixMs + i * 1_000L),
                backtrackGuidance(),
                matchAt(alongM),
            )
        }
        val nextFix = sampleAt(northM = 100.0, eastM = 0.0, timestampMs = primingFixMs + 3_000L)
        return Triple(c, active, nextFix)
    }

    @Test
    fun backtrackEvaluationIsProducedInsideGraceRatherThanSkipped() {
        val (c, active, fix) = coordinatorInGraceForBacktrack()

        val eval = assertNotNull(c.evaluateBacktrack(active, fix, backtrackGuidance(), matchAt(90.0)))

        assertTrue(eval.suppressedByGrace)
        assertFalse(eval.fired, "grace still gates what is spoken — that must not change")
        // `disposition` describes live, which is frozen throughout this fixture: it never persists a
        // regression, so it can only ever be a fresh baseline (count 0) on the one fix it is peeked
        // for. `shadowDisposition` describes the counterfactual, which did accumulate to threshold —
        // pinned per review, since half the log's disposition vocabulary (this string, and the
        // cooldown bail below) had no test at all.
        assertEquals(
            "bail:count_0_of_3",
            eval.disposition,
            "disposition must describe live, which has not accumulated anything durable during grace",
        )
        assertEquals("shadow:would_fire", eval.shadowDisposition)
    }

    @Test
    fun backtrackDebugSwitchLetsTheShadowBeHeard() {
        val (c, active, fix) = coordinatorInGraceForBacktrack()
        c.shadowAlertsAudible = true

        val eval = assertNotNull(c.evaluateBacktrack(active, fix, backtrackGuidance(), matchAt(90.0)))

        assertTrue(eval.suppressedByGrace, "the shadow still reports that grace would have muted it")
        assertTrue(eval.fired, "but it is spoken, because the switch is on")
        assertEquals(
            "FIRING",
            eval.disposition,
            "with the switch on, disposition reads the same track that decided fired",
        )
        assertEquals("shadow:would_fire", eval.shadowDisposition)
    }

    @Test
    fun backtrackFixStoppedByTheRealCooldownIsDistinctFromGraceShadow() {
        // Pinned per review, same reasoning as the off-trail version: a fix stopped by the real 45 s
        // cooldown — nothing to do with grace, which has already expired — must still say
        // `bail:cooldown_...`, not `shadow:would_fire`.
        val points = northShape(400.0)
        val active = backtrackActive(points)
        val c = TrailGuidanceCoordinator(TestScope())
        c.startFollow(points, TravelDirection.Forward)
        c.resetThrottle(sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0L)) // backtrack grace expires at 20_000

        // Baseline, then two regressions (count 0 → 1 → 2) — mirrors genuinelyWalkingBackAlongTheTrail_
        // firesWrongWay in BacktrackAlongTrackTest: the threshold is 3, so the fourth fix is the one
        // that actually fires.
        listOf(120.0, 110.0, 100.0).forEachIndexed { i, alongM ->
            c.evaluateBacktrack(
                active,
                sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 100_000L + i * 1_000L),
                backtrackGuidance(),
                matchAt(alongM),
            )
        }
        val fired =
            assertNotNull(
                c.evaluateBacktrack(
                    active,
                    sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 103_000L),
                    backtrackGuidance(),
                    matchAt(90.0),
                ),
            )
        assertTrue(fired.fired, "precondition: this fix must actually fire")

        val cooled =
            assertNotNull(
                c.evaluateBacktrack(
                    active,
                    sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 104_000L),
                    backtrackGuidance(),
                    matchAt(80.0),
                ),
            )

        assertFalse(cooled.suppressedByGrace, "grace expired long before t=100_000")
        assertFalse(cooled.fired)
        assertEquals("bail:cooldown_1000ms", cooled.disposition)
        assertEquals("bail:cooldown_1000ms", cooled.shadowDisposition)
    }

    // ── State must not escape the grace window (Critical, review 2026-08-17) ──────────

    @Test
    fun backtrackPostGraceFixDoesNotInheritInGraceEvidence() {
        // Two regressions gathered entirely inside grace — one short of
        // BACKTRACK_CONSECUTIVE_THRESHOLD (3). If that count carried over, the fix right after grace
        // expires would supply the third and fire; the pre-S6 code's first post-grace fix could only
        // re-baseline against it (prevAlongTrackM was null at grace exit), never regress on the same
        // fix. This is the self-contained regression test for the backtrack half of the Critical the
        // 2026-08-17 review found — see also the off-trail version above, and
        // TrailGuidanceCoordinatorTest.backtrack_suppressedDuringGraceWindow for a fixture too small
        // to reproduce it on its own.
        val (c, active, _) = coordinatorInGraceForBacktrack()

        val afterGrace =
            assertNotNull(
                c.evaluateBacktrack(
                    active,
                    sampleAt(northM = 100.0, eastM = 0.0, timestampMs = armedAtMs + 21_000L), // past the 20 s grace
                    backtrackGuidance(),
                    matchAt(90.0),
                ),
            )

        assertFalse(afterGrace.suppressedByGrace, "grace must actually have expired for this to be the regression")
        assertFalse(afterGrace.fired, "must not fire on carried-over in-grace evidence, switch off")
        // The other half of the property (re-review, 2026-08-17): see the matching assertion in
        // postGraceFixDoesNotInheritInGraceEvidence — the shadow's evidence must survive the grace
        // boundary rather than being reset at it. `shadow` accumulated to threshold entirely inside
        // the window (baseline + two regressions in coordinatorInGraceForBacktrack) and reaches
        // wouldFire on this very fix, its third real regression.
        assertEquals("shadow:would_fire", afterGrace.shadowDisposition)
    }
}
