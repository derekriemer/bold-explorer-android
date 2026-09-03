package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.deltaAngle
import kotlin.math.abs

/**
 * The next turn ahead of [alongTrackM], as [BendDetector.findNextBend] reports it.
 *
 * @property anchorAlongTrackM the curvature-max vertex's along-track position — a fixed property
 *   of the trail's geometry, not of this particular fix. Callers should de-duplicate repeated
 *   announcements by comparing this (within [BendTuning.anchorToleranceM]), not by distance, since
 *   distance changes every fix even for the same physical turn.
 * @property distanceAheadM how far from the current position to [anchorAlongTrackM], always ≥ 0
 *   regardless of [TravelDirection].
 * @property turnDeg signed net turn in the walker's own direction of travel — positive = right,
 *   negative = left, in [deltaAngle]'s convention. Directly usable with [TurnSeverity.of].
 */
data class Bend(
    val anchorAlongTrackM: Double,
    val distanceAheadM: Double,
    val turnDeg: Double,
)

/**
 * Tuning for [BendDetector], invented rather than tuned (ADR 0001, S8) — see [NavigationPolicy]'s
 * `TURN_*` constants for what each of these defaults to and why. A parameter, like [MatchTuning],
 * so a recorded walk can be replayed against candidate values rather than by walking it again.
 */
data class BendTuning(
    val scanRangeM: Double = NavigationPolicy.TURN_SCAN_RANGE_M,
    val baselineM: Double = NavigationPolicy.TURN_BASELINE_M,
    val angleThresholdDeg: Double = NavigationPolicy.TURN_ANGLE_THRESHOLD_DEG,
    val anchorToleranceM: Double = NavigationPolicy.TURN_ANCHOR_TOLERANCE_M,
    val speechIntervalMs: Long = NavigationPolicy.TURN_SPEECH_INTERVAL_MS,
) {
    companion object {
        val DEFAULT = BendTuning()
    }
}

/**
 * Finds the next turn ahead of a walker following a trail (ADR 0001, S8).
 *
 * Walks the trail's actual vertices within [BendTuning.scanRangeM], nearest-first, testing each as
 * a candidate curvature peak: a window of [BendTuning.baselineM] centred *on that vertex* is asked
 * for its worst departure ([TrailPolyline.worstDepartureOver]), and the vertex qualifies only if
 * that window's own peak lands back on itself — otherwise some other, truer peak is nearby, and
 * that vertex (checked in its own turn) is the one to report, not this one.
 *
 * That self-check is what makes the *first* qualifying vertex found genuinely the *nearest* one:
 * an earlier design slid a fixed window forward in fixed steps and returned whichever window first
 * crossed threshold, which is not the same thing — a sharp bend farther away can cross threshold
 * from a partial, off-centre capture before a gentler, nearer bend's window is centred well enough
 * on it to register at all. Found in the field (2026-09-02): on a stretch with a right turn
 * shortly before a left one, the detector kept reporting the (sharper) left turn, sometimes ahead
 * of the (nearer, gentler) right one, and re-fired continuously as which window "won" flickered
 * fix to fix.
 *
 * Departure (sagitta), not summed `|Δbearing|` per vertex (grows without bound with recording
 * density) and not signed net turn alone (reads zero across an S-bend that returns to its original
 * heading) — matching [TrailPolyline.sagittaOver]'s own rationale — is what finds *which* vertex is
 * a genuine local curvature peak (the self-check above). Once a peak vertex is found, whether it's
 * worth reporting at all, and its reported magnitude and direction, are both measured the human way:
 * the signed net turn between the chords just before and just after it, gated against
 * [BendTuning.angleThresholdDeg]. Earlier this qualified vertices on the sagitta's own metres
 * instead (an arbitrary proxy tied to [BendTuning.baselineM]); field data (2026-09-02) showed that
 * proxy only reliably crossed for turns approaching ~90°, silently skipping real, gentler turns
 * underfoot in favour of a farther, already-announced one. Gating on the angle directly — the same
 * quantity already computed to report the turn's direction — removes the mismatch, and means a
 * sub-threshold vertex is skipped in favour of the next candidate rather than blocking it.
 *
 * Deliberately stateless and side-effect-free: it always answers "what is the next turn from here"
 * for whatever [alongTrackM] it is given. Deciding whether that turn has already been announced —
 * and re-deriving that decision fresh from the current confirmed position on every fix, so a
 * matcher correction (a bad initial acquisition self-correcting, a backtrack) can never leave a
 * stale "already announced" mark behind — is [BendCueProducer]'s job, not this function's.
 *
 * @param alongTrackM the *confirmed* along-track position (`TrailMatch.confirmedAlongM`), never
 *   `TrailFollower.currentIndex` — the whole reason this self-corrects the way [alongTrackM]
 *   itself does.
 */
object BendDetector {
    fun findNextBend(
        polyline: TrailPolyline,
        alongTrackM: Double,
        direction: TravelDirection,
        tuning: BendTuning = BendTuning.DEFAULT,
    ): Bend? {
        val half = tuning.baselineM / 2.0
        val candidateIndices =
            when (direction) {
                TravelDirection.Forward ->
                    (0 until polyline.size)
                        .asSequence()
                        .filter { polyline.cumulativeM[it] > alongTrackM }
                        .takeWhile { polyline.cumulativeM[it] <= alongTrackM + tuning.scanRangeM }

                TravelDirection.Reverse ->
                    (polyline.size - 1 downTo 0)
                        .asSequence()
                        .filter { polyline.cumulativeM[it] < alongTrackM }
                        .takeWhile { polyline.cumulativeM[it] >= alongTrackM - tuning.scanRangeM }
            }

        for (i in candidateIndices) {
            val vertexM = polyline.cumulativeM[i]
            val candidate = polyline.worstDepartureOver(vertexM - half, tuning.baselineM) ?: continue
            // Not this vertex's own peak -- some other vertex nearby is, and it is either already
            // behind us in this scan (nearer, so it would have qualified first) or still ahead
            // (farther, and will get its own turn to self-check when the scan reaches it).
            if (candidate.alongTrackM != vertexM) continue

            val before = polyline.chordBearingAt(vertexM - half, tuning.baselineM)
            val after = polyline.chordBearingAt(vertexM + half, tuning.baselineM)
            // Too close to a trail end to measure both sides: fall back to the departure's own
            // sign for a coarse direction rather than reporting nothing. crossTrackRightM (which
            // departureM is) is right-positive for the *chord*, but a vertex that bulges right of
            // the straight chord is what a walker turns *left* at — see worstDepartureOver's tests
            // for the geometric reasoning — so the fallback direction is the departure's sign,
            // negated.
            val recordedTurnDeg =
                when {
                    before != null && after != null -> deltaAngle(before, after)
                    candidate.departureM > 0 -> -90.0
                    else -> 90.0
                }
            if (abs(recordedTurnDeg) < tuning.angleThresholdDeg) continue

            // Recorded order is the walker's own direction under Forward; Reverse walks it
            // backwards, which mirrors every turn — see desiredTrailCourseDeg's identical
            // adjustment in TrailGuidance.kt for the same reasoning applied to a single bearing.
            val turnDeg = if (direction == TravelDirection.Reverse) -recordedTurnDeg else recordedTurnDeg

            return Bend(
                anchorAlongTrackM = vertexM,
                distanceAheadM = abs(vertexM - alongTrackM),
                turnDeg = turnDeg,
            )
        }
        return null
    }
}
