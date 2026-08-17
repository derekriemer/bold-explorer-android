package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.LocationSample

/**
 * Everything that matching one followed trail needs, created together and kept together.
 *
 * ## Why this type exists
 *
 * The pieces here — the polyline, the declared direction, the tracker and the evidence recorder —
 * were previously assembled by the caller, in four steps that nothing tied together: build a
 * matcher, hand its polyline and direction to the coordinator, arrange for the matcher to run
 * before the detectors on each fix, then thread its result into every detector call. Each step was
 * silently optional, and skipping one degraded navigation rather than failing:
 *
 * - no adoption → guidance fell back to the adjacent-segment bearing, the density-sensitive answer
 *   that ADR 0001 S5 exists to remove
 * - no matcher → wrong-way and off-trail bailed for the whole walk
 * - detectors called before the match → every decision made on the previous fix's position
 * - a direction passed separately from the one the trail was adopted with → sign-inverted wrong-way
 *
 * All four were found in review on 2026-08-15, in a codebase with one follow path. The redesign
 * adds more (ADR 0001 S6/S7), and the failures are invisible outside a field log.
 *
 * Constructing this is therefore the only way to start a follow, and holding one is the only way to
 * match a fix. What used to be an ordering convention is now the shape of the type.
 *
 * @param points the trail in **recorded order**, never reversed — [direction] carries the traversal
 *   instead, so `alongTrackM` means the same thing for every walk of a trail.
 * @param tuning the matching constants; a parameter so a recorded walk can be replayed against
 *   candidate values offline. See [MatchTuning].
 */
class FollowSession(
    points: List<LatLng>,
    val direction: TravelDirection,
    tuning: MatchTuning = MatchTuning.DEFAULT,
) {
    val polyline = TrailPolyline(points)
    private val tracker = ProgressTracker(polyline, direction, tuning)
    private val evidence = MatchEvidenceRecorder(polyline)

    /** The most recent match. Null before the first fix. */
    var lastMatch: TrailMatch? = null
        private set

    /**
     * The evidence behind [lastMatch], for the field log.
     *
     * Recorded on every fix even when nothing will be written, because the recorder carries a rate
     * baseline between fixes and would otherwise measure across the gap.
     */
    var lastEvidence: MatchEvidence? = null
        private set

    /** Smoothed ground speed from the tracker, or null before the first fix carrying one. */
    val speedMps: Double? get() = tracker.speedMps

    /** Matches [sample] against the trail. Never throws on trail geometry. */
    fun onFix(sample: LocationSample): TrailMatch {
        val match = tracker.onFix(sample)
        lastMatch = match
        lastEvidence = evidence.observe(sample, match)
        return match
    }

    /**
     * Trail remaining ahead of [alongTrackM], toward the end being walked to.
     *
     * Direction-signed, so it counts down to the start on a reverse follow. Clamped at zero because
     * overshooting the end is arrival, not negative progress.
     */
    fun remainingM(alongTrackM: Double): Double =
        when (direction) {
            TravelDirection.Forward -> polyline.totalLengthM - alongTrackM
            TravelDirection.Reverse -> alongTrackM
        }.coerceAtLeast(0.0)
}

/**
 * What one GPS fix produced, before the follower and the detectors run on it.
 *
 * @property smoothedHeading the confidence-gated smoothed heading, for driving [TrailFollower].
 * @property match this fix's match, or `null` when no trail is being followed.
 * @property evidence the match's supporting evidence, for the log. Null whenever [match] is.
 */
data class FixOutcome(
    val smoothedHeading: SmoothedHeading?,
    val match: TrailMatch?,
    val evidence: MatchEvidence?,
)
