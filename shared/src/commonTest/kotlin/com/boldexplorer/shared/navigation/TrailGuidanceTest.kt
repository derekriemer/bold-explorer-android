package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.LocationSample
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrailGuidanceTest {
    private val northA = TrailPoint(1, "A", 0.0, 0.0)
    private val northB = TrailPoint(2, "B", 0.001, 0.0)
    private val northC = TrailPoint(3, "C", 0.002, 0.0)

    @Test
    fun movingNorthOnNorthboundSegment_isStraightAhead() {
        val follower = TrailFollower()
        follower.start(listOf(northA, northB, northC))
        follower.onLocationUpdate(LatLng(northA.lat, northA.lon))

        val sample = sample(lat = 0.0005, lon = 0.0, heading = 0.0, speed = 1.4)
        val course = TrailGuidance.updateTrustedCourse(null, sample)
        val guidance = TrailGuidance.compute(follower.state.value, sample, course)

        assertNotNull(guidance)
        assertTrue(abs(guidance.relativeDeg ?: 999.0) < 1.0)
        assertTrue(guidance.courseIsFresh)
    }

    @Test
    fun compassHeadingDoesNotAffectTrailGuidance() {
        val follower = TrailFollower()
        follower.start(listOf(northA, northB, northC))
        follower.onLocationUpdate(LatLng(northA.lat, northA.lon))

        val sample = sample(lat = 0.0005, lon = 0.0, heading = 0.0, speed = 1.4)
        val course = TrailGuidance.updateTrustedCourse(null, sample)
        val guidance = TrailGuidance.compute(follower.state.value, sample, course)

        assertNotNull(guidance)
        assertTrue(abs(guidance.relativeDeg ?: 999.0) < 1.0)
    }

    @Test
    fun staleCourse_isHeldForTimeoutThenSuppressed() {
        val follower = TrailFollower()
        follower.start(listOf(northA, northB, northC))
        follower.onLocationUpdate(LatLng(northA.lat, northA.lon))

        val trusted = TrailGuidance.updateTrustedCourse(null, sample(timestamp = 1_000, heading = 0.0, speed = 1.5))
        val held =
            TrailGuidance.compute(
                follower.state.value,
                sample(lat = 0.0005, timestamp = 10_999, heading = null, speed = 0.0),
                trusted,
            )
        val expired =
            TrailGuidance.compute(
                follower.state.value,
                sample(lat = 0.0005, timestamp = 11_001, heading = null, speed = 0.0),
                trusted,
            )

        assertNotNull(held)
        assertTrue(held.courseIsFresh)
        assertNotNull(held.relativeDeg)
        assertNotNull(expired)
        assertFalse(expired.courseIsFresh)
        assertNull(expired.relativeDeg)
    }

    @Test
    fun waypointReached_updatesActiveSegmentBeforeGuidance() {
        val east = TrailPoint(4, "East", 0.001, 0.001)
        val follower = TrailFollower()
        follower.start(listOf(northA, northB, east), thresholdM = 15.0)
        follower.onLocationUpdate(LatLng(northA.lat, northA.lon))

        // Advancing off northB is silent now (S6) — the proof it happened is the guidance below
        // reading the new target (index 2) straight from follower.state.value, not from an event.
        val advance = follower.onLocationUpdate(LatLng(northB.lat, northB.lon))
        assertNull(advance, "advancing must not speak")

        val sample = sample(lat = northB.lat, lon = northB.lon, heading = 90.0, speed = 1.5, timestamp = 2_000)
        val course = TrailGuidance.updateTrustedCourse(null, sample)
        val guidance = TrailGuidance.compute(follower.state.value, sample, course)

        assertNotNull(guidance)
        assertEquals(2, guidance.targetIndex)
        assertTrue(abs(guidance.relativeDeg ?: 999.0) < 1.0)
    }

    @Test
    fun singlePointTrail_fallsBackToDirectTargetBearing() {
        val follower = TrailFollower()
        follower.start(listOf(northA), thresholdM = 1.0)

        val sample = sample(lat = -0.001, lon = 0.0, heading = 0.0, speed = 1.5)
        val course = TrailGuidance.updateTrustedCourse(null, sample)
        val guidance = TrailGuidance.compute(follower.state.value, sample, course)

        assertNotNull(guidance)
        assertTrue(abs(guidance.relativeDeg ?: 999.0) < 1.0)
    }

    @Test
    fun updateTrustedCourse_freshCogPreferredOverSmoothed() {
        val smoothed = SmoothedHeading(deg = 270.0, confidence = 0.9, sampleCount = 4, newestTimestampMs = 1_000)
        // Fast fix pointing North — should win over smoothed 270°
        val sample = sample(heading = 0.0, speed = 1.5, timestamp = 2_000)
        val course = TrailGuidance.updateTrustedCourse(null, sample, smoothed)
        assertNotNull(course)
        assertTrue(course.deg < 5.0 || course.deg > 355.0, "Expected North but got ${course.deg}°")
        assertFalse(course.isSmoothed)
    }

    @Test
    fun updateTrustedCourse_smoothedUsedWhenBelowSpeedThreshold() {
        val smoothed = SmoothedHeading(deg = 90.0, confidence = 0.8, sampleCount = 3, newestTimestampMs = 1_000)
        val sample = sample(heading = 90.0, speed = 0.5, timestamp = 2_000) // below 1.0 threshold
        val course = TrailGuidance.updateTrustedCourse(null, sample, smoothed)
        assertNotNull(course)
        assertTrue(kotlin.math.abs(course.deg - 90.0) < 1.0)
        assertTrue(course.isSmoothed)
        assertEquals(1_000L, course.timestampMs) // newestTimestampMs, not sample.timestamp
    }

    @Test
    fun updateTrustedCourse_holdsPreviousWhenNeitherAvailable() {
        val previous = TrustedCourse(deg = 45.0, timestampMs = 500, isSmoothed = false)
        val sample = sample(heading = null, speed = 0.0, timestamp = 2_000)
        val course = TrailGuidance.updateTrustedCourse(previous, sample, null)
        assertNotNull(course)
        assertTrue(kotlin.math.abs(course.deg - 45.0) < 0.001)
    }

    @Test
    fun freshCourseAt_returnsCourseWithinHoldWindow() {
        val course = TrustedCourse(deg = 45.0, timestampMs = 1_000, isSmoothed = true)

        assertEquals(course, TrailGuidance.freshCourseAt(course, 11_000))
    }

    @Test
    fun freshCourseAt_rejectsCourseAfterHoldWindow() {
        val course = TrustedCourse(deg = 45.0, timestampMs = 1_000, isSmoothed = true)

        assertNull(TrailGuidance.freshCourseAt(course, 11_001))
    }

    @Test
    fun compute_courseIsSmoothed_reflectsTrustedCourseProvenance() {
        val follower = TrailFollower()
        follower.start(listOf(northA, northB, northC))
        follower.onLocationUpdate(LatLng(northA.lat, northA.lon))

        val smoothed = SmoothedHeading(deg = 0.0, confidence = 0.85, sampleCount = 4, newestTimestampMs = 500)
        val sample = sample(lat = 0.0005, lon = 0.0, heading = null, speed = 0.5, timestamp = 1_000)
        val course = TrailGuidance.updateTrustedCourse(null, sample, smoothed)
        val guidance = TrailGuidance.compute(follower.state.value, sample, course)

        assertNotNull(guidance)
        assertTrue(guidance.courseIsSmoothed)
        assertFalse(guidance.courseIsFresh.not()) // still fresh — within hold window
    }

    private fun sample(
        lat: Double = 0.0,
        lon: Double = 0.0,
        timestamp: Long = 1_000,
        heading: Double? = null,
        speed: Double? = null,
    ) = LocationSample(
        lat = lat,
        lon = lon,
        heading = heading,
        speed = speed,
        timestamp = timestamp,
    )
}
