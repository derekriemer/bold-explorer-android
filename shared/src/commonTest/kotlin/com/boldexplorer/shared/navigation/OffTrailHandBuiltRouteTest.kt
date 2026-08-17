package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A hand-built route's polyline between waypoints is invented — nobody walked the straight lines
 * connecting them. Cross-track distance from that polyline is therefore not evidence of leaving the
 * path; it is evidence that the path is not a line. Firing "you may be off trail" there tells a
 * blind walker they are lost when they are not.
 *
 * The predicate is [FollowSession.isRecorded] — whether the trail has track points of its own, the
 * same rule `WaypointRepositoryImpl.attach` (ADR 0001, S5b) already uses to decide vertex vs.
 * annotation. `evaluateOffTrail` bails on it *before* the grace/shadow machinery runs at all: a
 * hand-built route has nothing meaningful to shadow, so letting the detectors run would pollute the
 * field logs the shadow exists to produce.
 *
 * Shaped like [OffTrailCrossTrackTest.Harness]: a real [ProgressTracker] runs beside the
 * coordinator so the off-trail evaluator reads a real matched candidate, acquiring on the line a
 * second before the first evaluated fix, as follow-start does.
 */
class OffTrailHandBuiltRouteTest {
    private inner class Harness(isRecorded: Boolean) {
        val coordinator = TrailGuidanceCoordinator(TestScope())
        private val points = active.waypoints.map { LatLng(it.lat, it.lon) }
        private val polyline = TrailPolyline(points)
        private val tracker = ProgressTracker(polyline)
        private var acquired = false

        init {
            coordinator.startFollow(points, TravelDirection.Forward, isRecorded = isRecorded)
        }

        fun resetThrottle(sample: LocationSample) = coordinator.resetThrottle(sample)

        fun evaluateOffTrail(sample: LocationSample): OffTrailEvaluation? {
            if (!acquired) {
                tracker.onFix(sample(sample.timestamp - 1_000, eastM = 0.0))
                acquired = true
            }
            return coordinator.evaluateOffTrail(active, sample, onCourseGuidance(), tracker.onFix(sample))
        }
    }

    private fun coordinatorFor(isRecorded: Boolean) = Harness(isRecorded)

    /** Trail running due north from (0,0) for ~111 m. At the equator, 1° lon ≈ 111 195 m. */
    private val active =
        TrailFollowerState.Active(
            waypoints = listOf(TrailPoint(1, "A", 0.0, 0.0), TrailPoint(2, "B", 0.001, 0.0)),
            currentIndex = 1,
            thresholdM = 10.0,
        )

    private fun lonFor(m: Double) = m / 111_194.9

    private fun sample(
        timestampMs: Long,
        eastM: Double,
        accuracyM: Double = 5.0,
    ) = LocationSample(
        lat = 0.0005,
        lon = lonFor(eastM),
        accuracy = accuracyM,
        timestamp = timestampMs,
    )

    /** 40 m off the due-north trail — comfortably over gate on either fixture. */
    private fun fixWellOffTrail(timestampMs: Long) = sample(timestampMs, eastM = 40.0)

    private fun onCourseGuidance() =
        TrailGuidanceState(
            targetIndex = 1,
            targetName = "B",
            total = 2,
            distanceToTargetM = 80.0,
            desiredCourseDeg = 0.0,
            relativeDeg = 5.0,
            courseIsFresh = true,
        )

    @Test
    fun aHandBuiltRouteDoesNotCallYouOffTrail() {
        // Nobody walked the straight line between two hand-placed waypoints, so departing from it
        // is evidence that the path is not a line — not that the walker left the path.
        val c = coordinatorFor(isRecorded = false)
        c.resetThrottle(sample(0, eastM = 0.0))
        var last: OffTrailEvaluation? = null
        repeat(6) { i -> last = c.evaluateOffTrail(fixWellOffTrail(100_000L + i * 1_000L)) }

        assertFalse(assertNotNull(last).fired)
        assertEquals("bail:hand_built_route", last!!.disposition)
        assertEquals("bail:hand_built_route", last!!.shadowDisposition)
    }

    @Test
    fun aRecordedTrailStillDoes() {
        // The polyline *is* the walked path here, so cross-track means what the detector thinks.
        val c = coordinatorFor(isRecorded = true)
        c.resetThrottle(sample(0, eastM = 0.0))
        var fired = false
        repeat(6) { i -> if (c.evaluateOffTrail(fixWellOffTrail(100_000L + i * 1_000L))?.fired == true) fired = true }

        assertTrue(fired, "a recorded trail's off-trail detector must still fire when well off the line")
    }
}
