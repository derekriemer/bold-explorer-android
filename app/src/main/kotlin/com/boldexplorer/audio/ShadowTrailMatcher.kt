package com.boldexplorer.audio

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.navigation.MatchEvidenceRecorder
import com.boldexplorer.shared.navigation.ProgressTracker
import com.boldexplorer.shared.navigation.TrailMatch
import com.boldexplorer.shared.navigation.TrailPolyline
import com.boldexplorer.shared.navigation.TravelDirection

/**
 * The continuous matcher running in shadow mode: same fixes as the live follower, no influence on
 * anything the user hears.
 *
 * Shadow-mode duplication is deliberate and temporary. The live `TrailFollower` keeps driving
 * guidance while this records what the replacement *would* have decided, so a field walk produces
 * attributable evidence rather than a changed app whose new behaviour has to be judged from memory.
 * Nothing here may feed back into guidance before that walk happens.
 *
 * @param points the trail in **recorded order**, never reversed. Reverse traversal is expressed by
 *   [direction] instead, so `alongTrackM` means the same thing for every walk of a trail and two
 *   logs of opposite traversals can be overlaid.
 */
class ShadowTrailMatcher(
    points: List<LatLng>,
    direction: TravelDirection,
) {
    private val polyline = TrailPolyline(points)
    private val tracker = ProgressTracker(polyline, direction)
    private val evidence = MatchEvidenceRecorder(polyline)

    /** The most recent match, for the debug readout. Null before the first fix. */
    var lastMatch: TrailMatch? = null
        private set

    /** Matches [sample] and returns the record to append. Never throws on trail geometry. */
    fun onFix(sample: LocationSample): AudioLogEntry {
        val match = tracker.onFix(sample)
        lastMatch = match
        return trailMatchLogEntry(sample, match, evidence.observe(sample, match))
    }
}
