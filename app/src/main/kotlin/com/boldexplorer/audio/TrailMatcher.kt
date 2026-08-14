package com.boldexplorer.audio

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.LocationSample
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
    private val polyline = TrailPolyline(points)
    private val tracker = ProgressTracker(polyline, direction)
    private val evidence = MatchEvidenceRecorder(polyline)

    /** The most recent match, for the debug readout and for wrong-way. Null before the first fix. */
    var lastMatch: TrailMatch? = null
        private set

    /** Matches [sample] and returns the record to append. Never throws on trail geometry. */
    fun onFix(sample: LocationSample): AudioLogEntry {
        val match = tracker.onFix(sample)
        lastMatch = match
        return trailMatchLogEntry(sample, match, evidence.observe(sample, match))
    }
}
