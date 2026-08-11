package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.LocationSample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Continuous trail matching: where the user is along a [TrailPolyline], how much that is trusted,
 * and how hard to search when it stops being trusted.
 *
 * This is the replacement for "which recorded point am I trying to reach next". Progress is the
 * continuous scalar `confirmedAlongM` rather than an index, so skipped points, forward jumps and
 * trail re-entry stop being special cases, and guidance stops depending on how densely the trail
 * happened to be recorded. See `docs/adr/0001-continuous-trail-matching.md`.
 *
 * ## Continuity is primary; geometry refines it
 *
 * Cross-track distance alone separates none of the hard cases: an out-and-back's two passes sit 0 m
 * apart, as do a lollipop's stem passes and a loop's closure point. Only temporal continuity is
 * reliable across all of them, which is why the search is bounded by a window around the last
 * *confirmed* position rather than by geometry alone:
 *
 * ```
 * budget = maxSpeed × min(uncertainSec, T_CAP) + K × accuracy
 * window = confirmedAlongM ± budget          // centred on CONFIRMED, never on predicted
 * ```
 *
 * That single rule rejects switchback teleports (large Δalong over ~1 s), rejects the out-and-back
 * and loop-closure double-matches no purely geometric test can, accepts a genuine
 * leave-and-reenter-ahead after minutes of walking, and handles slow walking for free. The window
 * is load-bearing twice: it is the switchback safety mechanism *and* the reason a 10k-point trail
 * stays viable at 1 Hz.
 *
 * ## Prediction ranks, geometry constrains, displacement confirms
 *
 * Two scalars, and they are not interchangeable. [TrailMatch.confirmedAlongM] advances only when a
 * fix actually projects onto the trail within gate, and it is the only value any consumer reads.
 * [TrailMatch.predictedAlongM] is extrapolation; it breaks ties between candidates and is never
 * persisted as progress.
 *
 * This is what protects the user who stops to look at rocks, pets a dog, or walks down to the lake
 * for lunch. Reckoning-as-progress would advance them past a fork they never reached, and geometry
 * would then "reacquire" onto the wrong branch with a corrupted prior that makes the wrong branch
 * look corroborated. Here progress simply stays where geometry last saw them, and the widening
 * window still contains the truth when they wander back.
 *
 * Accepted cost: a long no-fix stretch freezes distance-remaining rather than coasting it. Hedged
 * and frozen beats confident and wrong.
 *
 * ## The reacquisition ladder
 *
 * ```
 * Matched     → windowed projection around confirmedAlongM
 * Uncertain   → no candidate in gate; progress frozen, window widens around the last known-good
 * Lost        → reckoning horizon expired (metres OR seconds); prior no longer constrains anything
 * Unconfirmed → global scan produced a candidate; it drives nothing until displacement corroborates
 * ```
 *
 * The horizon is a hard bound rather than a preference. A window that widens without limit silently
 * becomes a global scan while still claiming the continuity that made it safe; past the horizon the
 * tracker says plainly that it no longer has enough confidence in prior progress to constrain
 * candidates by it. The global scan itself is cheap — the polyline is in memory and a 10k-segment
 * scan is sub-millisecond. The expensive thing was never the scan; it is trusting its result, which
 * is what [MatchState.Unconfirmed] exists to withhold.
 *
 * This tracker is a pure function of the fixes it is given. It performs no I/O and never queries the
 * database: reacquisition searches [polyline], which already holds the followed trail's geometry.
 *
 * @param polyline the followed trail, in recorded order. Immutable for the session.
 * @param travelDirection which way the user declared they are walking. Fixed for the session.
 */
class ProgressTracker(
    private val polyline: TrailPolyline,
    private val travelDirection: TravelDirection = TravelDirection.Forward,
) {
    /** The most recent match, or `null` before the first fix. */
    var match: TrailMatch? = null
        private set

    private var state: MatchState = MatchState.Lost
    private var confirmedAlongM: Double? = null
    private var confirmedPosition: TrailPosition? = null
    private var predictedAlongM: Double? = null

    /** Reckoned distance since the last confirmed match — one half of the horizon. */
    private var reckonedM: Double = 0.0

    /** Confirmed, contiguous along-track travel this session. Gates completion. */
    private var travelledM: Double = 0.0

    private var unmatchedCount: Int = 0
    private var lastFixMs: Long? = null
    private var lastConfirmedMs: Long? = null
    private var speedEmaMps: Double? = null

    /** Timestamp of the last global scan. A timestamp, deliberately, not a counter — see below. */
    private var lastGlobalScanMs: Long? = null

    private var unconfirmedAlongM: Double? = null
    private var unconfirmedSinceMs: Long? = null
    private var corroboratedM: Double = 0.0

    private val directionSign: Double
        get() = if (travelDirection == TravelDirection.Reverse) -1.0 else 1.0

    /**
     * Matches one GPS fix against the trail and advances the ladder.
     *
     * @return the result, which is also available afterwards as [match].
     */
    fun onFix(sample: LocationSample): TrailMatch {
        val ts = sample.timestamp
        val dtSec = lastFixMs?.let { max(0.0, (ts - it) / 1000.0) } ?: 0.0
        lastFixMs = ts
        updateSpeed(sample.speed)

        val point = LatLng(sample.lat, sample.lon)
        val accuracyM = sample.accuracy
        val gateM =
            NavigationPolicy.widenWithAccuracy(
                baseM = NavigationPolicy.MATCH_GATE_BASE_M,
                factor = NavigationPolicy.MATCH_GATE_ACCURACY_FACTOR,
                accuracyM = accuracyM,
                capM = NavigationPolicy.MATCH_GATE_CAP_M,
            )

        // No prior exists at follow-start, so the first match is necessarily a global scan.
        if (confirmedAlongM == null) return acquire(point, ts, gateM, accuracyM)

        // Prediction advances on every fix, matched or not. It is never progress.
        val stepM = (speedEmaMps ?: 0.0) * dtSec
        predictedAlongM = (predictedAlongM ?: confirmedAlongM!!) + directionSign * stepM
        reckonedM += stepM

        val uncertainSec = max(0.0, (ts - (lastConfirmedMs ?: ts)) / 1000.0)

        // The horizon is bounded in BOTH metres and seconds because the two trade places with
        // speed: a stationary user with bad GPS burns seconds without metres, a fast-moving one
        // burns metres without seconds.
        if (state == MatchState.Matched || state == MatchState.Uncertain) {
            val overDistance = reckonedM > NavigationPolicy.RECKONING_HORIZON_M
            val overTime = uncertainSec > NavigationPolicy.RECKONING_HORIZON_S
            if (overDistance || overTime) {
                state = MatchState.Lost
                unmatchedCount++
                // Entering Lost is its own event. Note confirmedAlongM keeps its value here and
                // consumers keep reading it — only its role in bounding the search ends.
                return emit(
                    chosen = null,
                    bestRejected = null,
                    uncertainSec = uncertainSec,
                    scanKind = ScanKind.None,
                    windowM = null,
                    budgetM = 0.0,
                    disposition = if (overDistance) "lost:reckoning_expired_m" else "lost:reckoning_expired_s",
                )
            }
        }

        return when (state) {
            MatchState.Lost -> attemptGlobalScan(point, ts, gateM, uncertainSec)
            MatchState.Unconfirmed -> attemptCorroboration(point, ts, gateM, accuracyM, uncertainSec)
            else -> attemptWindowed(point, ts, gateM, accuracyM, uncertainSec)
        }
    }

    /** Normal operation: search a bounded window around the last confirmed position. */
    private fun attemptWindowed(
        point: LatLng,
        ts: Long,
        gateM: Double,
        accuracyM: Double?,
        uncertainSec: Double,
    ): TrailMatch {
        val confirmed = confirmedAlongM!!
        val budgetM = budgetFor(uncertainSec, accuracyM)
        val windowM = (confirmed - budgetM)..(confirmed + budgetM)
        val found = polyline.candidates(point, windowM)
        val chosen = rank(found, byPrediction = true)
        val rejected = found.firstOrNull { it !== chosen }

        if (chosen != null && abs(chosen.crossTrackM) <= gateM) {
            return confirm(
                position = chosen,
                ts = ts,
                contiguous = true,
                bestRejected = rejected,
                uncertainSec = uncertainSec,
                scanKind = ScanKind.Windowed,
                windowM = windowM,
                budgetM = budgetM,
                disposition = "match:xt_${chosen.crossTrackM.metres()}",
            )
        }

        // Progress freezes here. Only prediction and the budget move, so the window widens around
        // the last known-good position rather than sliding off it.
        state = MatchState.Uncertain
        unmatchedCount++
        return emit(
            chosen = chosen,
            bestRejected = rejected,
            uncertainSec = uncertainSec,
            scanKind = ScanKind.Windowed,
            windowM = windowM,
            budgetM = budgetM,
            disposition =
                if (chosen == null) {
                    "uncertain:no_candidate_in_window"
                } else {
                    "uncertain:xt_${chosen.crossTrackM.metres()}_over_gate_${gateM.metres()}"
                },
        )
    }

    /**
     * Reacquisition: search the whole polyline, with nothing to constrain it.
     *
     * The cooldown is a state transition rather than a counter reset, because the failure mode is
     * *frequency*: a user genuinely off the trail fails corroboration every time, and gating on
     * [unmatchedCount] would let a single stray match re-arm the scan on the very next fix.
     */
    private fun attemptGlobalScan(
        point: LatLng,
        ts: Long,
        gateM: Double,
        uncertainSec: Double,
    ): TrailMatch {
        val since = lastGlobalScanMs
        if (since != null && (ts - since) / 1000.0 < NavigationPolicy.RESCAN_COOLDOWN_S) {
            return emit(
                chosen = null,
                bestRejected = null,
                uncertainSec = uncertainSec,
                scanKind = ScanKind.None,
                windowM = null,
                budgetM = 0.0,
                disposition = "hold:rescan_cooldown",
            )
        }
        lastGlobalScanMs = ts

        val found = polyline.candidates(point, window = null)
        // Prediction is not consulted here: past the horizon it carries no information, and using
        // it would bias reacquisition toward exactly the stale prior we just declared worthless.
        val chosen = rank(found, byPrediction = false)
        val rejected = found.firstOrNull { it !== chosen }

        if (chosen == null || abs(chosen.crossTrackM) > gateM) {
            return emit(
                chosen = chosen,
                bestRejected = rejected,
                uncertainSec = uncertainSec,
                scanKind = ScanKind.Global,
                windowM = null,
                budgetM = 0.0,
                disposition = "lost:no_candidate_global",
            )
        }

        state = MatchState.Unconfirmed
        unconfirmedAlongM = chosen.alongTrackM
        unconfirmedSinceMs = ts
        corroboratedM = 0.0
        return emit(
            chosen = chosen,
            bestRejected = rejected,
            uncertainSec = uncertainSec,
            scanKind = ScanKind.Global,
            windowM = null,
            budgetM = 0.0,
            disposition = "unconfirmed:global_scan",
        )
    }

    /**
     * A global match constrains nothing until displacement agrees with it.
     *
     * Failure returns to [MatchState.Lost] without re-arming the scan — the cooldown timestamp set
     * when the scan ran is what governs the next attempt.
     */
    private fun attemptCorroboration(
        point: LatLng,
        ts: Long,
        gateM: Double,
        accuracyM: Double?,
        uncertainSec: Double,
    ): TrailMatch {
        val anchorM = unconfirmedAlongM!!
        val sinceSec = max(0.0, (ts - (unconfirmedSinceMs ?: ts)) / 1000.0)
        val budgetM = budgetFor(sinceSec, accuracyM)
        val windowM = (anchorM - budgetM)..(anchorM + budgetM)
        val found = polyline.candidates(point, windowM)
        val chosen = rank(found, byPrediction = false)
        val rejected = found.firstOrNull { it !== chosen }

        if (chosen == null || abs(chosen.crossTrackM) > gateM) {
            state = MatchState.Lost
            unconfirmedAlongM = null
            unconfirmedSinceMs = null
            corroboratedM = 0.0
            unmatchedCount++
            return emit(
                chosen = chosen,
                bestRejected = rejected,
                uncertainSec = uncertainSec,
                scanKind = ScanKind.Windowed,
                windowM = windowM,
                budgetM = budgetM,
                disposition = "lost:corroboration_failed",
            )
        }

        corroboratedM += abs(chosen.alongTrackM - anchorM)
        unconfirmedAlongM = chosen.alongTrackM
        unconfirmedSinceMs = ts

        if (corroboratedM >= NavigationPolicy.CORROBORATION_M) {
            return confirm(
                position = chosen,
                ts = ts,
                // A reacquisition is a jump, not walking: it must not count toward the travel that
                // gates completion.
                contiguous = false,
                bestRejected = rejected,
                uncertainSec = uncertainSec,
                scanKind = ScanKind.Windowed,
                windowM = windowM,
                budgetM = budgetM,
                disposition = "match:corroborated_${corroboratedM.metres()}",
            )
        }

        return emit(
            chosen = chosen,
            bestRejected = rejected,
            uncertainSec = uncertainSec,
            scanKind = ScanKind.Windowed,
            windowM = windowM,
            budgetM = budgetM,
            disposition = "unconfirmed:corroborating_${corroboratedM.metres()}",
        )
    }

    /**
     * The first match of a session, which has no prior to constrain it.
     *
     * On a loop — or any trail whose ends are within GPS accuracy of each other — the fix projects
     * onto two candidates with near-zero cross-track, at `0` and at `totalLengthM`. Cross-track
     * cannot choose between them and continuity does not exist yet, so the declared travel
     * direction breaks the tie: it is precisely the information the geometry lacks.
     *
     * Acquisition commits directly to [MatchState.Matched] rather than going through corroboration.
     * Requiring displacement here would mean saying nothing at all at follow-start, and the tie is
     * already guarded twice — by the direction preference here, and by the confirmed-travel
     * requirement on completion, which is what makes a wrong choice harmless rather than merely
     * unlikely.
     */
    private fun acquire(
        point: LatLng,
        ts: Long,
        gateM: Double,
        accuracyM: Double?,
    ): TrailMatch {
        val found = polyline.candidates(point, window = null)
        val chosen = tieBreakByDirection(found)
        val rejected = found.firstOrNull { it !== chosen }

        if (chosen == null || abs(chosen.crossTrackM) > gateM) {
            state = MatchState.Lost
            unmatchedCount++
            return emit(
                chosen = chosen,
                bestRejected = rejected,
                uncertainSec = 0.0,
                scanKind = ScanKind.Global,
                windowM = null,
                budgetM = 0.0,
                disposition = "lost:acquisition_failed",
            )
        }

        return confirm(
            position = chosen,
            ts = ts,
            contiguous = false,
            bestRejected = rejected,
            uncertainSec = 0.0,
            scanKind = ScanKind.Global,
            windowM = null,
            budgetM = 0.0,
            disposition = "acquire:${travelDirection.name.lowercase()}_xt_${chosen.crossTrackM.metres()}",
        )
    }

    /** Commits [position] as geometry-corroborated progress. The only place progress moves. */
    private fun confirm(
        position: TrailPosition,
        ts: Long,
        contiguous: Boolean,
        bestRejected: TrailPosition?,
        uncertainSec: Double,
        scanKind: ScanKind,
        windowM: ClosedFloatingPointRange<Double>?,
        budgetM: Double,
        disposition: String,
    ): TrailMatch {
        val previous = confirmedAlongM
        if (contiguous && previous != null) travelledM += abs(position.alongTrackM - previous)

        // Only meaningful where geometry has just returned after an absence; a steady run of
        // matches resets prediction every fix, so the error would be trivially ~0.
        val predictionErrorM =
            if (state != MatchState.Matched) predictedAlongM?.let { it - position.alongTrackM } else null

        confirmedAlongM = position.alongTrackM
        confirmedPosition = position
        predictedAlongM = position.alongTrackM
        reckonedM = 0.0
        lastConfirmedMs = ts
        unmatchedCount = 0
        unconfirmedAlongM = null
        unconfirmedSinceMs = null
        corroboratedM = 0.0
        state = MatchState.Matched

        return emit(
            chosen = position,
            bestRejected = bestRejected,
            uncertainSec = uncertainSec,
            scanKind = scanKind,
            windowM = windowM,
            budgetM = budgetM,
            disposition = disposition,
            predictionErrorM = predictionErrorM,
        )
    }

    /**
     * Ranks candidates, optionally letting prediction break a tie.
     *
     * Prediction is a *weak* signal and is confined to genuine ties: a candidate that is plainly
     * nearer wins on geometry alone. Widening this into a general bias would let prediction error
     * walk the answer away from the user's true position.
     */
    private fun rank(
        found: List<TrailPosition>,
        byPrediction: Boolean,
    ): TrailPosition? {
        val best = found.firstOrNull() ?: return null
        if (!byPrediction) return best
        val predicted = predictedAlongM ?: return best
        return found
            .filter { abs(it.crossTrackM) - abs(best.crossTrackM) <= NavigationPolicy.CANDIDATE_TIE_M }
            .minByOrNull { abs(it.alongTrackM - predicted) } ?: best
    }

    /** Tie-break for acquisition: prefer the end of the trail the declared direction starts from. */
    private fun tieBreakByDirection(found: List<TrailPosition>): TrailPosition? {
        val best = found.firstOrNull() ?: return null
        val tied =
            found.filter { abs(it.crossTrackM) - abs(best.crossTrackM) <= NavigationPolicy.CANDIDATE_TIE_M }
        return when (travelDirection) {
            TravelDirection.Forward -> tied.minByOrNull { it.alongTrackM }
            TravelDirection.Reverse -> tied.maxByOrNull { it.alongTrackM }
        } ?: best
    }

    /**
     * `maxSpeed × min(elapsed, T_CAP) + K × accuracy`.
     *
     * `maxSpeed` derives from *observed* speed rather than being a global constant. A walking-tuned
     * constant would put a cyclist outside the window on every fix; a vehicle-tuned one would admit
     * candidates hundreds of metres away for a walker who has moved four metres, destroying the
     * switchback protection the window exists to provide.
     */
    private fun budgetFor(
        elapsedSec: Double,
        accuracyM: Double?,
    ): Double {
        val maxSpeedMps =
            max(NavigationPolicy.MIN_MAX_SPEED_MPS, (speedEmaMps ?: 0.0) * NavigationPolicy.SPEED_MARGIN_FACTOR)
        val elapsed = min(elapsedSec, NavigationPolicy.BUDGET_ELAPSED_CAP_S)
        return maxSpeedMps * elapsed + NavigationPolicy.BUDGET_ACCURACY_FACTOR * (accuracyM ?: 0.0)
    }

    private fun updateSpeed(speedMps: Double?) {
        val observed = speedMps?.takeIf { it.isFinite() && it >= 0.0 } ?: return
        val previous = speedEmaMps
        speedEmaMps =
            if (previous == null) observed else previous + NavigationPolicy.SPEED_EMA_ALPHA * (observed - previous)
    }

    private fun emit(
        chosen: TrailPosition?,
        bestRejected: TrailPosition?,
        uncertainSec: Double,
        scanKind: ScanKind,
        windowM: ClosedFloatingPointRange<Double>?,
        budgetM: Double,
        disposition: String,
        predictionErrorM: Double? = null,
    ): TrailMatch {
        val result =
            TrailMatch(
                state = state,
                confirmedAlongM = confirmedAlongM,
                predictedAlongM = predictedAlongM,
                position = confirmedPosition,
                chosen = chosen,
                bestRejected = bestRejected,
                unmatchedCount = unmatchedCount,
                uncertainSec = uncertainSec,
                travelledM = travelledM,
                scanKind = scanKind,
                windowM = windowM,
                budgetM = budgetM,
                predictionErrorM = predictionErrorM,
                disposition = disposition,
            )
        match = result
        return result
    }
}

/** Renders a metre quantity for a disposition string: whole metres, no locale, no allocation fuss. */
private fun Double.metres(): String = "${roundToInt()}m"
