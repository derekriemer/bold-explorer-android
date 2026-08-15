package com.boldexplorer.shared.navigation.scenario

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.navigation.MatchState
import com.boldexplorer.shared.navigation.MatchTuning
import com.boldexplorer.shared.navigation.ProgressTracker
import com.boldexplorer.shared.navigation.TrailFollowerState
import com.boldexplorer.shared.navigation.TrailGuidanceCoordinator
import com.boldexplorer.shared.navigation.TrailMatch
import com.boldexplorer.shared.navigation.TrailPoint
import com.boldexplorer.shared.navigation.TrailPolyline
import com.boldexplorer.shared.navigation.TravelDirection
import kotlinx.coroutines.test.TestScope

/**
 * One recorded trail-follow session, replayed against the navigation core.
 *
 * Synthetic fixtures test what someone thought to imagine. Every navigation failure this project has
 * actually fixed — the switchback teleport, the reversed spoken course, the noise-floor false
 * positive — was found in field data and only then reduced to a synthetic case. Scenarios keep the
 * field data itself in the suite, so a regression that only shows up on real geometry has somewhere
 * to be caught.
 *
 * Positions are **metres in a local frame**, never coordinates: see `tools/build-scenario.py` for
 * what the export strips and why the replay is unaffected by it.
 */
interface WalkScenario {
    val label: String

    /** The direction the walk was actually followed in. [trailMetres] is always in recorded order. */
    val direction: TravelDirection

    /** Trail vertices as (east, north) metres from the fixtures' synthetic origin, recorded order. */
    val trailMetres: List<Pair<Double, Double>>

    val fixes: List<ScenarioFix>
}

/**
 * One GPS fix from a recorded walk.
 *
 * @param tMs milliseconds since the session started, preserving the real intervals — which matter,
 *   because the matcher's window is sized from elapsed time.
 */
data class ScenarioFix(
    val tMs: Long,
    val eastM: Double,
    val northM: Double,
    val accuracyM: Double?,
    val speedMps: Double?,
    val courseDeg: Double?,
)

/** What the stack decided on one fix. */
data class ScenarioStep(
    val fix: ScenarioFix,
    val matchState: MatchState,
    val matchDisposition: String,
    val confirmedAlongM: Double?,
    val desiredCourseDeg: Double?,
    val distanceToTargetM: Double?,
    val offTrailDisposition: String?,
    val offTrailFired: Boolean,
    val backtrackDisposition: String?,
    val backtrackFired: Boolean,
)

/** The whole replay, with the questions a test wants to ask about it. */
class ScenarioResult(
    val scenario: WalkScenario,
    val steps: List<ScenarioStep>,
    val polyline: TrailPolyline,
) {
    val offTrailAlerts: List<ScenarioStep> get() = steps.filter { it.offTrailFired }
    val backtrackAlerts: List<ScenarioStep> get() = steps.filter { it.backtrackFired }

    val matchedFraction: Double
        get() = steps.count { it.matchState == MatchState.Matched }.toDouble() / steps.size

    /** Along-track actually confirmed over the walk, as a fraction of the trail's length. */
    val coverageFraction: Double
        get() {
            val confirmed = steps.mapNotNull { it.confirmedAlongM }
            if (confirmed.isEmpty()) return 0.0
            return (confirmed.max() - confirmed.min()) / polyline.totalLengthM
        }

    /** A compact, greppable line per fix. Printed on failure so a diff is readable in a terminal. */
    fun transcript(steps: List<ScenarioStep> = this.steps): String =
        steps.joinToString("\n") {
            "${it.fix.tMs / 1000}s ${it.matchState} along=${it.confirmedAlongM?.metres()} " +
                "course=${it.desiredCourseDeg?.metres()} off=${it.offTrailDisposition} " +
                "back=${it.backtrackDisposition} [${it.matchDisposition}]"
        }

    /** [count] steps either side of [step], for reading what led up to a surprise. */
    fun around(
        step: ScenarioStep,
        count: Int = 4,
    ): String {
        val i = steps.indexOf(step)
        return transcript(steps.subList(maxOf(0, i - count), minOf(steps.size, i + count + 1)))
    }

    private fun Double.metres(): String {
        val rounded = (this * 10).toLong() / 10.0
        return rounded.toString()
    }
}

/**
 * Replays a [WalkScenario] through the matcher and the guidance coordinator together, exactly as
 * `GpsViewModel` drives them: match first, then guidance, then the detectors.
 *
 * The follower's target is pinned to the far end of the trail rather than advanced. `currentIndex`
 * is on its way out (ADR 0001, S7) and nothing asserted here depends on it — pinning keeps
 * `distanceToTargetM` meaning "distance to the end", which is stable to reason about, instead of
 * reproducing a state machine these scenarios exist to outlive.
 */
object ScenarioRunner {
    fun run(
        scenario: WalkScenario,
        tuning: MatchTuning = MatchTuning.DEFAULT,
    ): ScenarioResult {
        val points = scenario.trailMetres.map { (east, north) -> offsetFromOrigin(northM = north, eastM = east) }
        val polyline = TrailPolyline(points)
        val tracker = ProgressTracker(polyline, scenario.direction, tuning)
        val coordinator = TrailGuidanceCoordinator(TestScope())
        coordinator.startFollow(polyline, scenario.direction)

        // Travel order, which is what the follower is given, so the pinned target is the end the
        // user is walking toward rather than the one behind them.
        val travelOrder = if (scenario.direction == TravelDirection.Reverse) points.reversed() else points
        val active =
            TrailFollowerState.Active(
                waypoints = travelOrder.mapIndexed { i, p -> TrailPoint(i.toLong(), "p$i", p.lat, p.lon) },
                currentIndex = travelOrder.lastIndex,
                thresholdM = 15.0,
            )

        val steps =
            scenario.fixes.map { fix ->
                val sample = fix.toSample()
                val match: TrailMatch = tracker.onFix(sample)
                coordinator.updateTrustedCourse(sample)
                val guidance = coordinator.computeGuidance(active, sample, match)
                val offTrail = coordinator.evaluateOffTrail(active, sample, guidance, match)
                val backtrack = coordinator.evaluateBacktrack(active, sample, guidance, match, scenario.direction)
                ScenarioStep(
                    fix = fix,
                    matchState = match.state,
                    matchDisposition = match.disposition,
                    confirmedAlongM = match.confirmedAlongM,
                    desiredCourseDeg = guidance?.desiredCourseDeg,
                    distanceToTargetM = guidance?.distanceToTargetM,
                    offTrailDisposition = offTrail?.disposition,
                    offTrailFired = offTrail?.fired ?: false,
                    backtrackDisposition = backtrack?.disposition,
                    backtrackFired = backtrack?.fired ?: false,
                )
            }
        return ScenarioResult(scenario, steps, polyline)
    }

    private fun ScenarioFix.toSample(): LocationSample {
        val point = offsetFromOrigin(northM = northM, eastM = eastM)
        return LocationSample(
            lat = point.lat,
            lon = point.lon,
            accuracy = accuracyM,
            heading = courseDeg,
            speed = speedMps,
            timestamp = tMs,
        )
    }

    /**
     * Metres from the synthetic origin, back to a [LatLng].
     *
     * The exact inverse of what `tools/build-scenario.py` does, and the same origin and constants
     * the hand-built fixtures use, so the two kinds of test sit in one coordinate system.
     */
    private fun offsetFromOrigin(
        northM: Double,
        eastM: Double,
    ): LatLng = LatLng(ANCHOR_LAT + northM / M_PER_DEG_LAT, ANCHOR_LON + eastM / M_PER_DEG_LON)

    private const val M_PER_DEG_LAT = 111_194.9
    private const val M_PER_DEG_LON = 111_194.9 * 0.766
    private const val ANCHOR_LAT = 40.0
    private const val ANCHOR_LON = -105.0
}
