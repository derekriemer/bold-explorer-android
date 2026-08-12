package com.boldexplorer.shared.navigation.replay

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.navigation.MatchState
import com.boldexplorer.shared.navigation.MatchTuning
import com.boldexplorer.shared.navigation.ProgressTracker
import com.boldexplorer.shared.navigation.TrailPolyline
import com.boldexplorer.shared.navigation.TravelDirection
import kotlin.math.abs

/**
 * Replays a recorded walk through [ProgressTracker] under a given [MatchTuning].
 *
 * ## What this is for, and what it is not
 *
 * ADR 0001 gates S5 on a field walk because the S4 constants were invented rather than measured.
 * The walk of 2026-08-12 produced 1886 fixes logged with their raw positions attached, which turns
 * "what would a different `RECKONING_HORIZON_S` have done" from a second walk into a function call.
 *
 * This is a **comparison** tool, not an optimiser. There is no labelled ground truth for a walk —
 * nobody recorded, per fix, where the user actually was — so the report cannot score a tuning as
 * correct. What it can do is count the things the walk showed going wrong, so two tunings can be
 * held against each other and against the reviewer's memory of the walk. Reading the numbers is
 * still a human job; anything that claimed otherwise would be inventing the ground truth it lacks.
 */
object TrailReplay {
    /**
     * Runs [samples] against [polyline] and reports what happened.
     *
     * @param samples the walk in arrival order. Out-of-order fixes would corrupt the ladder's
     *   notion of elapsed time, so they are the caller's problem to sort.
     */
    fun run(
        polyline: TrailPolyline,
        samples: List<LocationSample>,
        direction: TravelDirection = TravelDirection.Forward,
        tuning: MatchTuning = MatchTuning.DEFAULT,
    ): ReplayReport {
        val tracker = ProgressTracker(polyline, direction, tuning)
        val stateCounts = mutableMapOf<MatchState, Int>()
        val dispositions = mutableMapOf<String, Int>()
        val crossWhileMatched = mutableListOf<Double>()
        val reacquisitions = mutableListOf<Reacquisition>()

        var pinnedFixes = 0
        var longestPinM = 0.0
        var previousAlong: Double? = null
        var previousPoint: LatLng? = null
        var pinRunM = 0.0

        // A reacquisition is only interesting once the tracker has held a match and lost it. The
        // very first acquisition of a session is not a recovery from anything.
        var everMatched = false
        var lostAtMs: Long? = null
        var lostAlongM: Double? = null
        var reachedLost = false

        for (sample in samples) {
            val match = tracker.onFix(sample)
            val point = LatLng(sample.lat, sample.lon)
            stateCounts[match.state] = (stateCounts[match.state] ?: 0) + 1
            dispositions[match.disposition] = (dispositions[match.disposition] ?: 0) + 1

            val chosenAlong = match.chosen?.alongTrackM
            if (match.state == MatchState.Matched) {
                match.chosen?.let { crossWhileMatched.add(abs(it.crossTrackM)) }

                // Vertex pinning: at a sharp apex an entire wedge of off-trail space projects onto
                // the corner, so along-track freezes while the walker keeps moving and cross-track
                // silently becomes radial distance to the apex. Exact equality is the right test —
                // this is the same vertex returned, not a near-miss.
                val movedM = previousPoint?.let { haversineDistanceMeters(it, point) } ?: 0.0
                if (chosenAlong != null && chosenAlong == previousAlong && movedM > MOVED_EPSILON_M) {
                    pinnedFixes++
                    pinRunM += movedM
                    if (pinRunM > longestPinM) longestPinM = pinRunM
                } else {
                    pinRunM = 0.0
                }

                if (everMatched && lostAtMs != null) {
                    reacquisitions.add(
                        Reacquisition(
                            lostAtMs = lostAtMs,
                            recoveredAtMs = sample.timestamp,
                            alongJumpM = match.confirmedAlongM?.let { now -> lostAlongM?.let { abs(now - it) } },
                            reachedLost = reachedLost,
                        ),
                    )
                    lostAtMs = null
                    lostAlongM = null
                    reachedLost = false
                }
                everMatched = true
            } else {
                pinRunM = 0.0
                if (everMatched && lostAtMs == null) {
                    lostAtMs = sample.timestamp
                    lostAlongM = match.confirmedAlongM
                }
                // Reaching Lost is what separates a recovery from a wobble: only from Lost does the
                // tracker owe a global scan and corroboration before it may believe anything again.
                if (match.state == MatchState.Lost) reachedLost = true
            }

            previousAlong = chosenAlong
            previousPoint = point
        }

        return ReplayReport(
            tuning = tuning,
            fixes = samples.size,
            stateCounts = stateCounts.toMap(),
            dispositionCounts = dispositions.toMap(),
            crossTrackWhileMatchedM = crossWhileMatched.sorted(),
            reacquisitions = reacquisitions.toList(),
            vertexPinnedFixes = pinnedFixes,
            longestVertexPinM = longestPinM,
        )
    }

    /** Below this, a "move" is GPS noise rather than walking, and pinning would be a false positive. */
    private const val MOVED_EPSILON_M = 1.0
}

/**
 * One Matched → (not Matched) → Matched cycle.
 *
 * [reachedLost] separates the two very different things this shape covers. A dip to `Uncertain` and
 * straight back is a *flap* — a fix or two outside the gate, costing a second. Reaching `Lost` means
 * the prior stopped constraining anything, and getting back requires a global scan behind its
 * cooldown plus corroboration by displacement. On the 2026-08-12 walk, averaging the two together
 * reported a median recovery of 2 s for a session whose real recoveries took 112 s and 156 s.
 */
data class Reacquisition(
    val lostAtMs: Long,
    val recoveredAtMs: Long,
    /** How far `confirmedAlongM` moved across the gap. Large is not wrong — a rejoin elsewhere is. */
    val alongJumpM: Double?,
    /** Whether the ladder actually bottomed out at [MatchState.Lost] rather than merely wobbling. */
    val reachedLost: Boolean,
) {
    val latencySec: Double get() = (recoveredAtMs - lostAtMs) / 1000.0
}

/**
 * What one tuning did to one walk.
 *
 * Every field is a count or a distribution rather than a verdict, because a walk carries no ground
 * truth to be judged against — see [TrailReplay].
 */
data class ReplayReport(
    val tuning: MatchTuning,
    val fixes: Int,
    val stateCounts: Map<MatchState, Int>,
    val dispositionCounts: Map<String, Int>,
    /** Sorted, so percentiles are cheap. */
    val crossTrackWhileMatchedM: List<Double>,
    val reacquisitions: List<Reacquisition>,
    val vertexPinnedFixes: Int,
    val longestVertexPinM: Double,
) {
    val matchedFraction: Double
        get() = if (fixes == 0) 0.0 else (stateCounts[MatchState.Matched] ?: 0).toDouble() / fixes

    /**
     * The furthest off-trail the matcher was willing to call `Matched`.
     *
     * The headline number for the gate cap. A value above [
     * com.boldexplorer.shared.navigation.NavigationPolicy.OFF_TRAIL_FAR_M] means the matcher claims
     * a match where the app is simultaneously telling the user they may be off trail.
     */
    val maxCrossWhileMatchedM: Double get() = crossTrackWhileMatchedM.lastOrNull() ?: 0.0

    fun crossPercentile(p: Int): Double {
        if (crossTrackWhileMatchedM.isEmpty()) return 0.0
        val i = ((p / 100.0) * (crossTrackWhileMatchedM.size - 1)).toInt()
        return crossTrackWhileMatchedM[i]
    }

    /** Cycles that bottomed out at [MatchState.Lost] — the ones that cost the user real time. */
    val recoveries: List<Reacquisition> get() = reacquisitions.filter { it.reachedLost }

    /** Cycles that dipped only to [MatchState.Uncertain]. Cheap, but a lot of them means a jittery gate. */
    val flaps: List<Reacquisition> get() = reacquisitions.filter { !it.reachedLost }

    /** Median over [recoveries] only. Including flaps hides the recoveries behind a pile of 2 s wobbles. */
    val medianRecoverySec: Double
        get() {
            if (recoveries.isEmpty()) return 0.0
            val sorted = recoveries.map { it.latencySec }.sorted()
            return sorted[sorted.size / 2]
        }
}
