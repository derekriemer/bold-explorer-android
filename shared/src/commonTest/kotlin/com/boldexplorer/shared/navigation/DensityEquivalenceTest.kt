package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.deltaAngle
import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.test.TestScope
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The executable form of the #35 requirement: **the same physical trail must behave the same
 * whether its geometry was recorded every 2 m, 10 m, or 30 m.**
 *
 * GPX import applies no decimation, so recorded density is arbitrary and unbounded — a trail walked
 * with a 1 Hz recorder yields vertices metres apart, while a curated route yields them tens of
 * metres apart. Under the old per-trackpoint state machine that difference changed how often the
 * app spoke. Under continuous projection it must change nothing at all.
 *
 * Every quantity asserted here is continuous. Nothing reads a vertex count or a vertex index, which
 * is precisely the property that makes the invariance possible.
 */
class DensityEquivalenceTest {
    private val densities = listOf(2.0, 10.0, 30.0)

    private fun latDegFor(m: Double) = m / 111_194.9

    private fun lonDegFor(m: Double) = m / (111_194.9 * 0.766)

    @Test
    fun totalLength_isIndependentOfSampling() {
        val lengths = densities.map { TrailPolyline(densify(northShape(600.0), it)).totalLengthM }
        for (l in lengths) {
            assertEquals(lengths.first(), l, 0.5, "total lengths across densities: $lengths")
        }
    }

    @Test
    fun alongTrackAndCrossTrack_areIndependentOfSampling_onAStraightTrail() {
        // Probe points offset from the line at several distances along it.
        val probes =
            listOf(
                LatLng(40.0 + latDegFor(100.0), -105.0 + lonDegFor(7.0)),
                LatLng(40.0 + latDegFor(300.0), -105.0 - lonDegFor(15.0)),
                LatLng(40.0 + latDegFor(550.0), -105.0),
            )
        for (probe in probes) {
            val results =
                densities.map { spacing ->
                    assertNotNull(TrailPolyline(densify(northShape(600.0), spacing)).project(probe))
                }
            val reference = results.first()
            for ((i, r) in results.withIndex()) {
                assertEquals(
                    reference.alongTrackM,
                    r.alongTrackM,
                    0.5,
                    "alongTrackM at density ${densities[i]} m for $probe",
                )
                assertEquals(
                    reference.crossTrackM,
                    r.crossTrackM,
                    0.5,
                    "crossTrackM at density ${densities[i]} m for $probe",
                )
            }
        }
    }

    @Test
    fun crossTrackSign_isIndependentOfSampling() {
        // A sign flip would be a wrong-direction cue, so it gets its own assertion rather than
        // riding on the tolerance of the magnitude check.
        val right = LatLng(40.0 + latDegFor(300.0), -105.0 + lonDegFor(20.0))
        val left = LatLng(40.0 + latDegFor(300.0), -105.0 - lonDegFor(20.0))
        for (spacing in densities) {
            val poly = TrailPolyline(densify(northShape(600.0), spacing))
            assertTrue(
                assertNotNull(poly.project(right)).crossTrackM > 0.0,
                "east of a northward trail must read right-positive at density $spacing m",
            )
            assertTrue(
                assertNotNull(poly.project(left)).crossTrackM < 0.0,
                "west of a northward trail must read left-negative at density $spacing m",
            )
        }
    }

    @Test
    fun alongTrackAndCrossTrack_areIndependentOfSampling_aroundACorner() {
        // A corner is where naive resampling would differ most, since a cut corner changes both
        // the along-track coordinate and the distance to the trail.
        val probe = LatLng(40.0 + latDegFor(195.0), -105.0 + lonDegFor(18.0))
        val results =
            densities.map { spacing ->
                assertNotNull(TrailPolyline(densify(cornerShape(200.0), spacing)).project(probe))
            }
        val reference = results.first()
        for ((i, r) in results.withIndex()) {
            assertEquals(reference.alongTrackM, r.alongTrackM, 1.0, "alongTrackM at ${densities[i]} m")
            assertEquals(reference.crossTrackM, r.crossTrackM, 1.0, "crossTrackM at ${densities[i]} m")
        }
    }

    @Test
    fun chordBearing_isIndependentOfSampling() {
        val bearings =
            densities.map { spacing ->
                assertNotNull(
                    TrailPolyline(densify(cornerShape(200.0), spacing)).chordBearingAt(100.0, baselineM = 40.0),
                )
            }
        for (b in bearings) {
            assertTrue(
                abs(deltaAngle(bearings.first(), b)) < 1.0,
                "chord bearings across densities: $bearings",
            )
        }
    }

    @Test
    fun sagitta_isIndependentOfSampling() {
        val sagittas =
            densities.map { spacing ->
                TrailPolyline(densify(cornerShape(200.0), spacing)).sagittaOver(120.0, lookaheadM = 160.0)
            }
        for (s in sagittas) {
            assertEquals(sagittas.first(), s, 2.0, "sagittas across densities: $sagittas")
        }
        assertTrue(sagittas.first() > 20.0, "the corner must actually register, got ${sagittas.first()}")
    }

    /**
     * One physical walk: up the 200 m north leg, round the corner, out along the east leg, holding
     * a metre off the line. Deliberately the same *positions* regardless of how the trail beneath
     * them was recorded.
     *
     * 10 s between fixes at ~20 m each. The step has to stay inside the matcher's window, and the
     * window is sized from elapsed time and declared speed — a 20 m stride at 1 Hz is a vehicle,
     * and the matcher is right to refuse to track it.
     */
    private fun walkFixes(): List<LocationSample> {
        val northLeg = (1..9).map { it * 20.0 to 1.0 }
        val corner = listOf(200.0 to 0.0)
        val eastLeg = (1..9).map { 200.0 to it * 20.0 }
        return (northLeg + corner + eastLeg).mapIndexed { i, (northM, eastM) ->
            sampleAt(
                northM = northM,
                eastM = eastM,
                timestampMs = 100_000L + i * 10_000L,
                accuracyM = 5.0,
                speedMps = 2.0,
                courseDeg = null,
            )
        }
    }

    /** Runs [walkFixes] through the whole stack against [spacingM]-sampled geometry. */
    private fun decisionsAt(spacingM: Double): List<Decision> {
        val points = densify(cornerShape(200.0), spacingM)
        val polyline = TrailPolyline(points)
        val tracker = ProgressTracker(polyline)
        val coordinator = TrailGuidanceCoordinator(TestScope())
        coordinator.startFollow(points, TravelDirection.Forward)
        // The last vertex is the same physical point at every density — densify preserves it — so
        // distance-to-target is comparable too, not just the continuous quantities.
        val active =
            TrailFollowerState.Active(
                waypoints = points.mapIndexed { i, p -> TrailPoint(i.toLong(), "p$i", p.lat, p.lon) },
                currentIndex = points.size - 1,
                thresholdM = 15.0,
            )

        return walkFixes().map { fix ->
            val match = tracker.onFix(fix)
            coordinator.updateTrustedCourse(fix)
            val guidance = coordinator.computeGuidance(active, fix, match)
            Decision(
                courseDeg = guidance?.desiredCourseDeg,
                distanceToTargetM = guidance?.distanceToTargetM,
                offTrail = coordinator.evaluateOffTrail(active, fix, guidance, match)?.disposition,
                backtrack =
                    coordinator
                        .evaluateBacktrack(active, fix, guidance, match)
                        ?.disposition,
            )
        }
    }

    private data class Decision(
        val courseDeg: Double?,
        val distanceToTargetM: Double?,
        val offTrail: String?,
        val backtrack: String?,
    )

    @Test
    fun guidanceDecisions_areIndependentOfSampling() {
        // The half of the #35 requirement that only became assertable at S5, when guidance and the
        // detectors stopped deriving position for themselves. Dispositions are compared exactly:
        // they carry the rounded metres a decision was made on, so an equal string means the same
        // decision reached the same way, not merely the same outcome.
        val byDensity = densities.map { it to decisionsAt(it) }
        val (referenceDensity, reference) = byDensity.first()

        for ((spacing, decisions) in byDensity.drop(1)) {
            assertEquals(
                reference.size,
                decisions.size,
                "fix count at ${spacing}m vs ${referenceDensity}m",
            )
            for ((i, d) in decisions.withIndex()) {
                val r = reference[i]
                assertEquals(r.offTrail, d.offTrail, "off-trail disposition at fix $i, density $spacing m")
                assertEquals(r.backtrack, d.backtrack, "backtrack disposition at fix $i, density $spacing m")
                assertEquals(
                    assertNotNull(r.distanceToTargetM),
                    assertNotNull(d.distanceToTargetM),
                    1.0,
                    "distance to target at fix $i, density $spacing m",
                )
                assertTrue(
                    abs(deltaAngle(assertNotNull(r.courseDeg), assertNotNull(d.courseDeg))) < 2.0,
                    "desired course at fix $i: ${r.courseDeg} at $referenceDensity m vs ${d.courseDeg} at $spacing m",
                )
            }
        }
    }

    @Test
    fun theWalkFixtureActuallyExercisesTheCorner() {
        // Guards the test above from passing vacuously. If every fix produced the same course, or
        // the detectors never got past "no match", equality across densities would mean nothing.
        val decisions = decisionsAt(2.0)
        val courses = decisions.mapNotNull { it.courseDeg }

        assertTrue(courses.size == decisions.size, "every fix must produce a course")
        assertTrue(
            courses.any { abs(deltaAngle(0.0, it)) < 20.0 } && courses.any { abs(deltaAngle(90.0, it)) < 20.0 },
            "the walk must turn from north to east, got $courses",
        )
        assertTrue(
            decisions.all { it.offTrail?.startsWith("bail:on_line") == true },
            "a walk a metre off the line must read as on-line throughout: ${decisions.map { it.offTrail }}",
        )
    }

    @Test
    fun densify_preservesOriginalCorners() {
        // Guards the fixture itself. If resampling dropped the corner vertex, every test above
        // would be comparing subtly different shapes and the suite would be measuring nothing.
        val shape = cornerShape(200.0)
        for (spacing in densities) {
            val poly = TrailPolyline(densify(shape, spacing))
            // The corner sits 200 m along; a cut corner would shorten the trail.
            assertEquals(400.0, poly.totalLengthM, 1.0, "corner preserved at density $spacing m")
        }
    }
}
