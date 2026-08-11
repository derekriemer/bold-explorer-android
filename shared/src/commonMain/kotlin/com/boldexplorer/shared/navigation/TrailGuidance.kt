package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.deltaAngle
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.geo.initialBearingDeg
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
    val distanceToTargetM: Double,
    val desiredCourseDeg: Double,
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
    ): TrailGuidanceState? {
        val active = followState as? TrailFollowerState.Active ?: return null
        val target = active.waypoints.getOrNull(active.currentIndex) ?: return null
        val location = LatLng(sample.lat, sample.lon)
        val targetLocation = LatLng(target.lat, target.lon)
        val desiredCourse = desiredTrailCourseDeg(active, location, polyline) ?: return null
        val freshCourse = freshCourseAt(trustedCourse, sample.timestamp)

        return TrailGuidanceState(
            targetIndex = active.currentIndex,
            targetName = target.name,
            total = active.waypoints.size,
            distanceToTargetM = haversineDistanceMeters(location, targetLocation),
            desiredCourseDeg = desiredCourse,
            relativeDeg = freshCourse?.let { deltaAngle(it.deg, desiredCourse) },
            courseIsFresh = freshCourse != null,
            courseIsSmoothed = freshCourse?.isSmoothed ?: false,
        )
    }

    fun isMajorCorrection(relativeDeg: Double): Boolean = abs(relativeDeg) >= 60.0

    private fun desiredTrailCourseDeg(
        active: TrailFollowerState.Active,
        location: LatLng,
        polyline: TrailPolyline?,
    ): Double? {
        // Preferred: the bearing of a chord over a fixed *physical* baseline ahead of the user.
        //
        // The adjacent-segment fallback below is correct only when vertices are far enough apart
        // that recording noise is small relative to spacing. At walking density it is not: a few
        // metres of lateral GPS error across an 8 m segment swings the bearing by 45 degrees, so a
        // straight road produced alternating "slight left" / "slight right" — and swung the
        // directional beacon's pan by the same amount, since it reads this via relativeDeg.
        //
        // A chord over NavigationPolicy.COURSE_BASELINE_M averages that noise out and is
        // density-invariant, so the same physical road behaves identically whether recorded every
        // 2 m or every 30 m.
        if (polyline != null) {
            val alongM = polyline.project(location)?.alongTrackM
            if (alongM != null) {
                polyline
                    .chordBearingAt(
                        alongM + NavigationPolicy.COURSE_BASELINE_M / 2.0,
                        baselineM = NavigationPolicy.COURSE_BASELINE_M,
                    )?.let { return it }
            }
        }

        val points = active.waypoints
        val target = points.getOrNull(active.currentIndex) ?: return null
        val targetLocation = LatLng(target.lat, target.lon)

        if (active.currentIndex > 0) {
            val prev = points[active.currentIndex - 1]
            return initialBearingDeg(LatLng(prev.lat, prev.lon), targetLocation)
        }

        val next = points.getOrNull(active.currentIndex + 1)
        if (next != null) {
            return initialBearingDeg(targetLocation, LatLng(next.lat, next.lon))
        }

        return initialBearingDeg(location, targetLocation)
    }
}
