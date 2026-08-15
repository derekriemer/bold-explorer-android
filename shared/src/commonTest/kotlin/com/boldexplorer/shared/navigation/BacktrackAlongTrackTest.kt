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
 * "You may be going the wrong way" keyed on along-track regression **reported by the windowed
 * matcher**, rather than on distance-to-target or on an independent projection.
 *
 * Two field reports shaped this file, and they are different bugs:
 *
 * 1. Walking straight down a trail where the recording turns 90° right, the app announced *wrong
 *    way* at the corner. The user had not reversed — they had missed a turn, which is an off-trail
 *    condition wearing the wrong label. That is why the rule watches along-track rather than
 *    `distanceToTargetM`, which grows whenever the user passes any turn or carries a stale target.
 *
 * 2. (ADR 0001, S5a — 2026-08-12, walk 2 at 16:07.) On a trail that doubles back, the user was
 *    ~120 m along and therefore physically 11–18 m from the trail's own *start*. This detector's
 *    own **unwindowed** `polyline.project()` snapped to the trail head, read 113 m of regression,
 *    counted to three and announced wrong way. The windowed matcher, given the identical fixes,
 *    never left ~113 m — 0 was not in its window. The detector no longer projects for itself; it
 *    consumes [TrailMatch.confirmedAlongM] and so inherits the window, the gate and the ladder.
 */
class BacktrackAlongTrackTest {
    private fun coordinator() = TrailGuidanceCoordinator(TestScope())

    /** The follower's view of [points]; only its `Active`-ness is read by backtrack now. */
    private fun activeFor(
        points: List<LatLng>,
        currentIndex: Int = 1,
    ) = TrailFollowerState.Active(
        waypoints = points.mapIndexed { i, p -> TrailPoint(i.toLong(), "p$i", p.lat, p.lon) },
        currentIndex = currentIndex,
        thresholdM = 15.0,
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

    /**
     * Walks [fixes] through a real [ProgressTracker] and the detector together, as the ViewModel
     * does: one fix, one match, one evaluation.
     */
    private fun walk(
        points: List<LatLng>,
        fixes: List<LocationSample>,
        direction: TravelDirection = TravelDirection.Forward,
        distanceM: (Int) -> Double = { 100.0 + it * 10.0 },
    ): List<BacktrackEvaluation> {
        val c = coordinator()
        val polyline = TrailPolyline(points)
        val tracker = ProgressTracker(polyline, direction)
        val active = activeFor(points)
        c.startFollow(polyline, direction)
        c.resetThrottle(fixes.first().copy(timestamp = 0L))
        return fixes.mapIndexedNotNull { i, fix ->
            c.evaluateBacktrack(active, fix, guidanceWithDistance(distanceM(i)), tracker.onFix(fix))
        }
    }

    private fun assertNeverFires(
        evals: List<BacktrackEvaluation>,
        why: String,
    ) {
        val fired = evals.filter { it.fired }
        assertTrue(fired.isEmpty(), "$why — fired with ${fired.map { it.disposition }}")
    }

    // ── The S5a field case ────────────────────────────────────────────────────────

    /**
     * A switchback with 12 m between its arms, walked down the return arm. Every fix is nearer to
     * the *outbound* arm — and so to a far smaller along-track value — than to the arm the user is
     * really on. An unwindowed projection reads that as ~250 m of regression in three fixes.
     */
    @Test
    fun switchbackTeleportDoesNotClaimWrongWay() {
        val points = densify(switchbackShape(legM = 150.0, gapM = 12.0), spacingM = 5.0)
        val returnArmEastM = 12.0
        // Acquire on the return arm, then walk south down it. The east offsets drift across the
        // gap, exactly as 25 m-accuracy fixes did in the field.
        val fixes =
            listOf(60.0 to 12.0, 50.0 to 9.0, 40.0 to 3.0, 30.0 to 2.0, 20.0 to 3.0)
                .mapIndexed { i, (northM, eastM) ->
                    sampleAt(northM = northM, eastM = eastM, timestampMs = 100_000L + i * 2_000L, accuracyM = 12.0)
                }
        assertTrue(returnArmEastM > 0.0)

        val evals = walk(points, fixes)

        assertNeverFires(evals, "walking down the return arm of a switchback is not going backwards")
        val last = assertNotNull(evals.lastOrNull(), "the detector must still evaluate")
        assertTrue(
            last.alongTrackM!! > 250.0,
            "the match must stay on the return arm, not snap to the trail head: ${last.alongTrackM}",
        )
    }

    // ── The original corner case ──────────────────────────────────────────────────

    @Test
    fun walkingStraightPastAHardRightTurn_doesNotClaimWrongWay() {
        // The user continues north while the trail turns east. Their projected position pins at the
        // corner and stops advancing — it never goes backwards — so this is off-trail's business.
        val points = densify(cornerShape(legM = 100.0), spacingM = 5.0)
        val fixes =
            listOf(85.0, 95.0, 105.0, 115.0, 125.0).mapIndexed { i, northM ->
                sampleAt(northM = northM, eastM = 0.0, timestampMs = 100_000L + i * 1_000L)
            }

        assertNeverFires(walk(points, fixes), "walking past a turn is a missed turn, not a reversal")
    }

    @Test
    fun staleTargetDoesNotProduceAFalseWrongWay() {
        // The stale-target false positive: the user advances correctly while distance to a target
        // behind them grows.
        val points = densify(northShape(400.0), spacingM = 5.0)
        val fixes =
            listOf(20.0, 30.0, 40.0, 50.0, 60.0).mapIndexed { i, northM ->
                sampleAt(northM = northM, eastM = 0.0, timestampMs = 100_000L + i * 1_000L)
            }

        assertNeverFires(walk(points, fixes), "advancing correctly must never read as wrong way")
    }

    @Test
    fun smallJitterDoesNotCountAsRegression() {
        // GPS noise moves the projection back and forth by a metre or two; only movement past the
        // noise floor counts.
        val points = densify(northShape(400.0), spacingM = 5.0)
        val fixes =
            listOf(49.5, 50.2, 49.4, 50.1, 49.6).mapIndexed { i, northM ->
                sampleAt(northM = northM, eastM = 0.0, timestampMs = 100_000L + i * 1_000L)
            }

        assertNeverFires(walk(points, fixes), "sub-metre jitter must not read as walking backwards")
    }

    @Test
    fun genuinelyWalkingBackAlongTheTrail_firesWrongWay() {
        // The case the announcement is actually for: retreating along the trail the user came up.
        val points = densify(northShape(400.0), spacingM = 5.0)
        val fixes =
            listOf(80.0, 70.0, 60.0, 50.0).mapIndexed { i, northM ->
                sampleAt(northM = northM, eastM = 0.0, timestampMs = 100_000L + i * 1_000L)
            }

        val evals = walk(points, fixes)

        assertTrue(evals.any { it.fired }, "walking back down the trail must eventually announce wrong way")
    }

    // ── Direction is a session parameter, so regression has a sign ─────────────────

    @Test
    fun reverseTraversalCountingDownDoesNotClaimWrongWay() {
        // `alongTrackM` is always in recorded order, so a correct reverse walk counts *down*.
        // Reading that as regression would announce wrong way for an entire reverse follow.
        val points = densify(northShape(400.0), spacingM = 5.0)
        val fixes =
            listOf(300.0, 290.0, 280.0, 270.0, 260.0).mapIndexed { i, northM ->
                sampleAt(northM = northM, eastM = 0.0, timestampMs = 100_000L + i * 1_000L, courseDeg = 180.0)
            }

        assertNeverFires(
            walk(points, fixes, direction = TravelDirection.Reverse),
            "counting down is correct progress under Reverse",
        )
    }

    @Test
    fun reverseTraversalCountingUpFiresWrongWay() {
        val points = densify(northShape(400.0), spacingM = 5.0)
        val fixes =
            listOf(260.0, 270.0, 280.0, 290.0).mapIndexed { i, northM ->
                sampleAt(northM = northM, eastM = 0.0, timestampMs = 100_000L + i * 1_000L, courseDeg = 0.0)
            }

        val evals = walk(points, fixes, direction = TravelDirection.Reverse)

        assertTrue(evals.any { it.fired }, "walking back up the trail on a reverse follow is going the wrong way")
    }

    // ── The detector inherits the ladder's states ─────────────────────────────────

    @Test
    fun aFrozenMatchIsNotEvidenceOfAnything() {
        // While the tracker is Uncertain, `confirmedAlongM` is frozen by design. Neither its
        // stillness nor its eventual resumption may be read as movement.
        val c = coordinator()
        val points = densify(northShape(400.0), spacingM = 5.0)
        val active = activeFor(points)
        c.startFollow(TrailPolyline(points), TravelDirection.Forward)
        val sample = sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 100_000L)
        c.resetThrottle(sample.copy(timestamp = 0L))

        val evals =
            listOf(
                matchAt(120.0, MatchState.Matched),
                matchAt(120.0, MatchState.Uncertain),
                matchAt(120.0, MatchState.Uncertain),
                matchAt(120.0, MatchState.Lost),
            ).mapIndexedNotNull { i, match ->
                c.evaluateBacktrack(
                    active,
                    sample.copy(timestamp = 100_000L + i * 1_000L),
                    guidanceWithDistance(100.0),
                    match,
                )
            }

        assertNeverFires(evals, "a frozen match is not movement")
        assertTrue(
            evals.drop(1).all { it.disposition.startsWith("hold:match_") },
            "a non-Matched ladder state must say so in the log: ${evals.map { it.disposition }}",
        )
    }

    @Test
    fun aReacquisitionJumpIsNotABacktrack() {
        // Geometry returning after an absence can legitimately land far behind the last confirmed
        // position — a rejoin elsewhere, not a reversal. The fix that reacquires re-baselines
        // instead of being compared against a value from before the gap.
        val c = coordinator()
        val points = densify(northShape(400.0), spacingM = 5.0)
        val active = activeFor(points)
        c.startFollow(TrailPolyline(points), TravelDirection.Forward)
        val sample = sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 100_000L)
        c.resetThrottle(sample.copy(timestamp = 0L))

        val evals =
            listOf(
                matchAt(300.0, MatchState.Matched),
                matchAt(300.0, MatchState.Lost),
                // Reacquired 250 m behind, all at once. `predictionErrorM` is set exactly on the
                // fix where geometry returns.
                matchAt(50.0, MatchState.Matched, predictionErrorM = 250.0),
                matchAt(52.0, MatchState.Matched),
            ).mapIndexedNotNull { i, match ->
                c.evaluateBacktrack(
                    active,
                    sample.copy(timestamp = 100_000L + i * 1_000L),
                    guidanceWithDistance(100.0),
                    match,
                )
            }

        assertNeverFires(evals, "a rejoin elsewhere is not a reversal")
        assertEquals(
            "hold:reacquired",
            evals[2].disposition,
            "the reacquiring fix must re-baseline rather than compare",
        )
    }

    // ── The noise floor has to scale with the noise ───────────────────────────────

    @Test
    fun jitterAtPoorAccuracyDoesNotClaimWrongWay() {
        // 2026-08-12, walk 2 at 16:07:32–35, replayed. The user was standing still (0.8 m/s or
        // less) under 25 m reported accuracy, and the *windowed* confirmed position still wandered
        // 4–12 m per fix. Three of those in a row cleared a flat 2 m floor and would have announced
        // wrong way to someone who had not moved.
        val c = coordinator()
        val points = densify(northShape(400.0), spacingM = 5.0)
        val active = activeFor(points)
        c.startFollow(TrailPolyline(points), TravelDirection.Forward)
        c.resetThrottle(sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0L))

        val evals =
            listOf(120.0, 115.7, 103.2, 96.5).mapIndexedNotNull { i, alongM ->
                c.evaluateBacktrack(
                    active,
                    sampleAt(
                        northM = 100.0,
                        eastM = 0.0,
                        timestampMs = 100_000L + i * 2_000L,
                        accuracyM = 25.0,
                        speedMps = 0.1,
                    ),
                    guidanceWithDistance(100.0),
                    matchAt(alongM, MatchState.Matched),
                )
            }

        assertNeverFires(evals, "GPS noise at 25 m accuracy is not evidence of walking backwards")
        assertTrue(evals.all { it.consecutiveCount == 0 }, "jitter inside the floor must not count")
    }

    @Test
    fun atGoodAccuracyASmallRegressionStillCounts() {
        // The floor must not simply be raised: with a clean fix, a few metres back is real.
        val c = coordinator()
        val points = densify(northShape(400.0), spacingM = 5.0)
        val active = activeFor(points)
        c.startFollow(TrailPolyline(points), TravelDirection.Forward)
        c.resetThrottle(sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0L))

        val evals =
            listOf(120.0, 116.0, 112.0, 108.0).mapIndexedNotNull { i, alongM ->
                c.evaluateBacktrack(
                    active,
                    sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 100_000L + i * 2_000L, accuracyM = 4.0),
                    guidanceWithDistance(100.0),
                    matchAt(alongM, MatchState.Matched),
                )
            }

        assertTrue(evals.any { it.fired }, "4 m per fix at 4 m accuracy is a genuine reversal")
    }

    @Test
    fun theFloorUsedIsReportedForTheLog() {
        val c = coordinator()
        val points = densify(northShape(400.0), spacingM = 5.0)
        val active = activeFor(points)
        c.startFollow(TrailPolyline(points), TravelDirection.Forward)
        c.resetThrottle(sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 0L))

        val eval =
            assertNotNull(
                c.evaluateBacktrack(
                    active,
                    sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 100_000L, accuracyM = 25.0),
                    guidanceWithDistance(100.0),
                    matchAt(120.0, MatchState.Matched),
                ),
            )

        assertEquals(12.5, eval.noiseFloorM, "half of 25 m accuracy, above the 2 m base")
    }

    @Test
    fun withNoMatchThereIsNothingToDecideOn() {
        val c = coordinator()
        val points = densify(northShape(400.0), spacingM = 5.0)
        val active = activeFor(points)
        c.startFollow(TrailPolyline(points), TravelDirection.Forward)
        val sample = sampleAt(northM = 100.0, eastM = 0.0, timestampMs = 100_000L)
        c.resetThrottle(sample.copy(timestamp = 0L))

        val eval = assertNotNull(c.evaluateBacktrack(active, sample, guidanceWithDistance(100.0), null))

        assertFalse(eval.fired)
        assertEquals("bail:no_match", eval.disposition)
    }

    // ── The log has to carry the values the decision was made on ──────────────────

    @Test
    fun alongTrackIsReportedForTheLog() {
        val points = densify(northShape(400.0), spacingM = 5.0)
        val fixes =
            listOf(80.0, 70.0).mapIndexed { i, northM ->
                sampleAt(northM = northM, eastM = 0.0, timestampMs = 100_000L + i * 1_000L)
            }

        val eval = walk(points, fixes).last()

        assertNotNull(eval.alongTrackM, "along-track must be logged")
        assertNotNull(eval.prevAlongTrackM, "previous along-track must be logged")
        assertNotNull(eval.distanceToTargetM, "distance is still logged, for replay comparison")
        assertEquals(MatchState.Matched, eval.matchState, "the ladder state that gated the decision")
    }

    @Test
    fun previousDistanceToTargetIsThePreviousFixesValue() {
        // It used to be assigned before the evaluation was built, so every logged line showed the
        // two as identical and the log could not show a trend at all.
        val points = densify(northShape(400.0), spacingM = 5.0)
        val fixes =
            listOf(80.0, 70.0).mapIndexed { i, northM ->
                sampleAt(northM = northM, eastM = 0.0, timestampMs = 100_000L + i * 1_000L)
            }

        val eval = walk(points, fixes, distanceM = { 100.0 + it * 10.0 }).last()

        assertEquals(110.0, eval.distanceToTargetM)
        assertEquals(100.0, eval.prevDistanceToTargetM, "the previous fix's distance, not this one's")
    }
}

/** A [TrailMatch] carrying only the fields the backtrack detector reads. */
private fun matchAt(
    alongM: Double?,
    state: MatchState,
    predictionErrorM: Double? = null,
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
    predictionErrorM = predictionErrorM,
    disposition = "test",
)
