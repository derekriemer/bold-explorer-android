package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.deltaAngle
import com.boldexplorer.shared.model.LocationSample
import kotlin.math.abs

data class TrustedCourse(
    val deg: Double,
    val timestampMs: Long,
    val isSmoothed: Boolean = false,
)

data class TrailGuidanceState(
    val targetIndex: Int,
    val targetName: String,
    val total: Int,
    /**
     * Trail remaining ahead of the confirmed along-track position (`TrailPolyline.remainingM`),
     * never straight-line distance to `TrailFollower.currentIndex`'s waypoint — the same defect
     * class [desiredCourseDeg]'s doc explains for direction. A spoken cue used to pair this field
     * (then a raw haversine to whatever track point `currentIndex` happened to be on — as close as
     * 1 m since the recording-density change) with [relativeDeg]'s course-tangent direction in the
     * same sentence, which could read as two different answers to "how am I doing" (field-reported
     * 2026-09-02: "beacon made a 1 o'clock sound but talk did not"). Null under the same condition
     * [desiredCourseDeg] is null — no confirmed matcher position, no trustworthy distance either.
     */
    val distanceToTargetM: Double?,
    /** Null when the matcher has no trustworthy position from which to derive a trail course. */
    val desiredCourseDeg: Double?,
    val relativeDeg: Double?,
    val courseIsFresh: Boolean,
    val courseIsSmoothed: Boolean = false,
)

object TrailGuidance {
    const val MIN_TRUSTED_SPEED_MPS = 1.0
    const val TRUSTED_COURSE_HOLD_MS = 10_000L

    /**
     * Update the trusted course from a new GPS fix.
     *
     * Priority:
     * 1. Instantaneous COG at speed ≥ [MIN_TRUSTED_SPEED_MPS] → fresh, unsmoothed course.
     * 2. [smoothedHeading] (already confidence-gated by [GpsHeadingSmoother]) → smoothed course,
     *    timestamped to the newest sample that contributed so hold-expiry reflects motion age.
     * 3. Neither → hold [previous] (up to [TRUSTED_COURSE_HOLD_MS]).
     */
    fun updateTrustedCourse(
        previous: TrustedCourse?,
        sample: LocationSample,
        smoothedHeading: SmoothedHeading? = null,
    ): TrustedCourse? {
        val heading = sample.heading
        val speed = sample.speed ?: 0.0
        return when {
            heading != null && speed >= MIN_TRUSTED_SPEED_MPS -> {
                TrustedCourse(((heading % 360.0) + 360.0) % 360.0, sample.timestamp, isSmoothed = false)
            }

            smoothedHeading != null -> {
                TrustedCourse(smoothedHeading.deg, smoothedHeading.newestTimestampMs, isSmoothed = true)
            }

            else -> {
                previous
            }
        }
    }

    fun freshCourseAt(
        trustedCourse: TrustedCourse?,
        timestampMs: Long,
    ): TrustedCourse? =
        trustedCourse?.takeIf {
            timestampMs - it.timestampMs <= TRUSTED_COURSE_HOLD_MS
        }

    fun compute(
        followState: TrailFollowerState,
        sample: LocationSample,
        trustedCourse: TrustedCourse?,
        polyline: TrailPolyline? = null,
        alongTrackM: Double? = null,
        direction: TravelDirection = TravelDirection.Forward,
        /**
         * The match's raw `confirmedAlongM`, unconditional on match state — deliberately not the
         * same value as [alongTrackM], which the caller already withholds outside Matched/Uncertain
         * so a stale or wrong-arm position can't steer direction. Distance doesn't carry that same
         * "which arm" hazard the way a course bearing does, and [TrailGuidanceState.distanceToTargetM]
         * is meant to survive a Lost span exactly as `confirmedAlongM` itself does elsewhere in this
         * codebase (`GpsViewModel`'s trail-remaining row, hedged rather than hidden — #67). Defaults
         * to [alongTrackM] for callers that only ever have the one value.
         */
        confirmedAlongM: Double? = alongTrackM,
    ): TrailGuidanceState? {
        val active = followState as? TrailFollowerState.Active ?: return null
        val target = active.currentTarget
        val desiredCourse = desiredTrailCourseDeg(polyline, alongTrackM, direction)
        val freshCourse = freshCourseAt(trustedCourse, sample.timestamp)

        return TrailGuidanceState(
            targetIndex = active.currentIndex,
            targetName = target.name,
            total = active.waypoints.size,
            distanceToTargetM = polyline?.let { p -> confirmedAlongM?.let { a -> p.remainingM(a, direction) } },
            desiredCourseDeg = desiredCourse,
            relativeDeg = desiredCourse?.let { course -> freshCourse?.let { deltaAngle(it.deg, course) } },
            courseIsFresh = desiredCourse != null && freshCourse != null,
            courseIsSmoothed = desiredCourse != null && freshCourse?.isSmoothed == true,
        )
    }

    fun isMajorCorrection(relativeDeg: Double): Boolean = abs(relativeDeg) >= 60.0

    private fun desiredTrailCourseDeg(
        polyline: TrailPolyline?,
        alongTrackM: Double?,
        direction: TravelDirection,
    ): Double? {
        // Preferred: the bearing of a chord over a fixed *physical* baseline ahead of the user.
        //
        // A chord over NavigationPolicy.COURSE_BASELINE_M averages recording noise out and is
        // density-invariant, so the same physical road behaves identically whether recorded every
        // 2 m or every 30 m.
        //
        // [alongTrackM] comes from the windowed matcher and is never re-derived here. Projecting
        // for itself is what made this measure the chord on the wrong arm of a switchback and speak
        // a course up to 162° from the truth (ADR 0001, S5) — the same defect S5a removed from
        // wrong-way detection, in the one place where being wrong steers the user directly.
        // No confirmed matcher position means no trustworthy answer. The old index-based fallback
        // was density-sensitive and could point down the wrong arm of a switchback, precisely when
        // a confident bearing is most harmful. Silence until the matcher has an along-track answer.
        val trustedPolyline = polyline ?: return null
        val trustedAlongM = alongTrackM ?: return null

        // The chord is measured over the trail *ahead of the user*, which is toward increasing
        // along-track only under Forward. `alongTrackM` is always in recorded order, so under
        // Reverse the baseline is taken behind the recorded direction and the resulting bearing
        // is reversed to face travel.
        val half = NavigationPolicy.COURSE_BASELINE_M / 2.0
        val centreM = trustedAlongM + half * direction.sign
        return trustedPolyline
            .chordBearingAt(centreM, baselineM = NavigationPolicy.COURSE_BASELINE_M)
            ?.let { if (direction == TravelDirection.Reverse) (it + 180.0) % 360.0 else it }
    }
}
