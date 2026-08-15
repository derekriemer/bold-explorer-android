package com.boldexplorer.audio

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.navigation.MatchEvidence
import com.boldexplorer.shared.navigation.MatchEvidenceRecorder
import com.boldexplorer.shared.navigation.ProgressTracker
import com.boldexplorer.shared.navigation.TrailMatch
import com.boldexplorer.shared.navigation.TrailPolyline
import com.boldexplorer.shared.navigation.TravelDirection

/**
 * The continuous matcher for the followed trail: same fixes as the live follower, one [TrailMatch]
 * per fix.
 *
 * It ran in pure shadow for S4 — recording what the replacement *would* have decided so a field
 * walk produced attributable evidence rather than a changed app judged from memory. Since S5a it
 * has one real consumer: wrong-way detection reads [TrailMatch.confirmedAlongM] instead of
 * projecting for itself, because an unwindowed projection teleports across a switchback. Everything
 * else — distance, telemetry, completion, speech — still comes from the legacy `TrailFollower`
 * until S5.
 *
 * Because a user-facing alert now depends on it, matching runs whenever a follow is active. The
 * `TRAIL_MATCH` logging is what the Debug-screen switch gates; see [ShadowMatchMonitor].
 *
 * @param points the trail in **recorded order**, never reversed. Reverse traversal is expressed by
 *   [direction] instead, so `alongTrackM` means the same thing for every walk of a trail and two
 *   logs of opposite traversals can be overlaid.
 */
class TrailMatcher(
    points: List<LatLng>,
    val direction: TravelDirection,
) {
    /** The trail in recorded order. Shared with guidance so along-track means one thing app-wide. */
    val polyline = TrailPolyline(points)
    private val tracker = ProgressTracker(polyline, direction)
    private val evidence = MatchEvidenceRecorder(polyline)

    /** The most recent match, for the debug readout and for wrong-way. Null before the first fix. */
    var lastMatch: TrailMatch? = null
        private set

    private var lastEvidence: MatchEvidence? = null

    /**
     * Matches [sample] against the trail. Never throws on trail geometry.
     *
     * The evidence recorder runs here rather than with the logging, because it carries a rate
     * baseline between fixes and would measure across the gap if it were skipped. Building the log
     * *record* is [logEntryFor]'s job and happens only if something will read it.
     */
    fun onFix(sample: LocationSample): TrailMatch {
        val match = tracker.onFix(sample)
        lastMatch = match
        lastEvidence = evidence.observe(sample, match)
        return match
    }

    /**
     * The `TRAIL_MATCH` record for the fix [onFix] just matched.
     *
     * Separate from [onFix] because it is the expensive half — a 27-key map and three formatted
     * strings — and the Debug switch exists precisely to not pay for it. Building it unconditionally
     * and discarding it when the switch was off made that switch save the file write and nothing
     * else, which is not what its documentation claimed.
     */
    fun logEntryFor(
        sample: LocationSample,
        match: TrailMatch,
    ): AudioLogEntry? = lastEvidence?.let { trailMatchLogEntry(sample, match, it) }
}
