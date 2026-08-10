package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.deltaAngle
import com.boldexplorer.shared.model.LocationSample
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desired trail course must reflect where the trail actually goes, not where one recorded
 * segment happens to point.
 *
 * Field report: walking a straight road, guidance alternated "slight left" / "slight right". The
 * cause is not bad GPX — it is that `desiredTrailCourseDeg` returned the bearing of the single
 * adjacent segment. Recorded at walking density, consecutive vertices carry metres of GPS noise, so
 * segment-to-segment bearing swings by tens of degrees on a perfectly straight path.
 *
 * This matters more than the wording suggests: the same `relativeDeg` drives the directional
 * beacon's pan and pitch, so a jittering desired course swings the earcon too.
 *
 * The fix is a chord over a fixed *physical* baseline, which averages the noise out and is
 * density-invariant by construction.
 */
class DesiredCourseStabilityTest {
    private fun latFor(m: Double) = m / 111_194.9

    private fun lonFor(m: Double) = m / 111_194.9

    /**
     * A dead-straight due-north path recorded with lateral GPS noise, alternating side to side.
     * The physical trail is straight; only the recording wobbles.
     */
    private fun noisyStraightTrail(): List<TrailPoint> =
        (0..30).map { i ->
            val lateralNoiseM = if (i % 2 == 0) 4.0 else -4.0
            TrailPoint(i.toLong() + 1, "p$i", latFor(i * 8.0), lonFor(lateralNoiseM))
        }

    private fun sampleAt(northM: Double) =
        LocationSample(lat = latFor(northM), lon = 0.0, accuracy = 5.0, timestamp = 100_000L)

    @Test
    fun straightTrailRecordedWithNoise_yieldsAStableNorthwardCourse() {
        val points = noisyStraightTrail()
        val polyline = TrailPolyline(points.map { LatLng(it.lat, it.lon) })

        val courses = mutableListOf<Double>()
        for (index in 8..20) {
            val active = TrailFollowerState.Active(points, currentIndex = index, thresholdM = 15.0)
            val guidance =
                assertNotNull(
                    TrailGuidance.compute(active, sampleAt(index * 8.0), null, polyline),
                    "guidance at index $index",
                )
            courses += assertNotNull(guidance.desiredCourseDeg, "desired course at index $index")
        }

        // Every reading should point roughly north despite the recording's ±4 m wobble.
        for (c in courses) {
            assertTrue(
                abs(deltaAngle(0.0, c)) < 12.0,
                "desired course should stay near north on a straight road, got $c (all: $courses)",
            )
        }
    }

    @Test
    fun desiredCourseDoesNotAlternateSideToSide() {
        // The audible symptom, stated directly: consecutive readings must not swing across the
        // straight-ahead axis, because that is what produces "slight left / slight right" and what
        // swings the beacon's pan.
        val points = noisyStraightTrail()
        val polyline = TrailPolyline(points.map { LatLng(it.lat, it.lon) })

        var worstSwingDeg = 0.0
        var previous: Double? = null
        for (index in 8..20) {
            val active = TrailFollowerState.Active(points, currentIndex = index, thresholdM = 15.0)
            val course =
                assertNotNull(
                    TrailGuidance.compute(active, sampleAt(index * 8.0), null, polyline)?.desiredCourseDeg,
                )
            previous?.let { worstSwingDeg = maxOf(worstSwingDeg, abs(deltaAngle(it, course))) }
            previous = course
        }
        assertTrue(worstSwingDeg < 15.0, "worst fix-to-fix swing was $worstSwingDeg deg")
    }

    @Test
    fun withoutAPolyline_fallsBackToTheAdjacentSegment() {
        // The polyline is optional so existing callers keep working. Passing none must not crash
        // or return null; it simply gives the old, noisier answer.
        val points = noisyStraightTrail()
        val active = TrailFollowerState.Active(points, currentIndex = 10, thresholdM = 15.0)
        val guidance = assertNotNull(TrailGuidance.compute(active, sampleAt(80.0), null, null))
        assertNotNull(guidance.desiredCourseDeg, "fallback still produces a course")
    }

    @Test
    fun onAGenuineBend_theCourseStillTurns() {
        // Stability must not become blindness: a real 90 degree turn has to move the course.
        val points =
            (0..12).map { i -> TrailPoint(i.toLong() + 1, "n$i", latFor(i * 10.0), 0.0) } +
                (1..12).map { i -> TrailPoint(100L + i, "e$i", latFor(120.0), lonFor(i * 10.0)) }
        val polyline = TrailPolyline(points.map { LatLng(it.lat, it.lon) })

        val before =
            assertNotNull(
                TrailGuidance.compute(
                    TrailFollowerState.Active(points, currentIndex = 5, thresholdM = 15.0),
                    sampleAt(50.0),
                    null,
                    polyline,
                )?.desiredCourseDeg,
            )
        val after =
            assertNotNull(
                TrailGuidance.compute(
                    TrailFollowerState.Active(points, currentIndex = 18, thresholdM = 15.0),
                    LocationSample(lat = latFor(120.0), lon = lonFor(60.0), accuracy = 5.0, timestamp = 100_000L),
                    null,
                    polyline,
                )?.desiredCourseDeg,
            )
        assertTrue(abs(deltaAngle(0.0, before)) < 15.0, "before the bend should read north, got $before")
        assertTrue(abs(deltaAngle(90.0, after)) < 15.0, "after the bend should read east, got $after")
    }
}
