package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.deltaAngle
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
