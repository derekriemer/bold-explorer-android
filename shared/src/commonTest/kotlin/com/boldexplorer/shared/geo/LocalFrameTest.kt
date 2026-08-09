package com.boldexplorer.shared.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Angle-convention tests for the local ENU frame.
 *
 * These fail silently by reflection rather than loudly by crash — a compass/unit-circle mix-up
 * still produces plausible numbers — so each row of the convention table in
 * docs/adr/0001-continuous-trail-matching.md gets an explicit test.
 */
class LocalFrameTest {
    @Test
    fun bearingDeg_cardinalUnitVectors_useCompassConvention() {
        // x = east, y = north. Compass bearings are zero-at-north, clockwise.
        // This is the test that catches atan2(y, x) written where atan2(x, y) is meant.
        assertEquals(0.0, bearingDeg(Vec2(x = 0.0, y = 1.0)), TOL, "north")
        assertEquals(90.0, bearingDeg(Vec2(x = 1.0, y = 0.0)), TOL, "east")
        assertEquals(180.0, bearingDeg(Vec2(x = 0.0, y = -1.0)), TOL, "south")
        assertEquals(270.0, bearingDeg(Vec2(x = -1.0, y = 0.0)), TOL, "west")
    }

    @Test
    fun unitVec_cardinalBearings_pointAlongFrameAxes() {
        // North is +y, East is +x — the inverse of the table above.
        assertEquals(0.0, unitVec(0.0).x, TOL, "north x")
        assertEquals(1.0, unitVec(0.0).y, TOL, "north y")
        assertEquals(1.0, unitVec(90.0).x, TOL, "east x")
        assertEquals(0.0, unitVec(90.0).y, TOL, "east y")
    }

    @Test
    fun bearingDeg_andUnitVec_roundTrip() {
        // Includes bearings in all four quadrants so a sign or quadrant error cannot hide.
        for (b in listOf(0.0, 17.0, 90.0, 143.0, 180.0, 221.0, 270.0, 359.5)) {
            assertEquals(b, bearingDeg(unitVec(b)), 1e-6, "round-trip at $b")
        }
    }

    @Test
    fun toLocal_atOrigin_isZero() {
        val origin = LatLng(40.0, -105.0)
        val v = LocalFrame(origin).toLocal(origin)
        assertEquals(0.0, v.x, TOL, "x")
        assertEquals(0.0, v.y, TOL, "y")
    }

    @Test
    fun toLocal_dueNorth_isPositiveNorthOnly() {
        // Magnitude checked against haversine rather than a hand-computed constant, so an x/y
        // swap cannot hide behind a number that merely looks plausible.
        val origin = LatLng(40.0, -105.0)
        val north = LatLng(40.001, -105.0)
        val v = LocalFrame(origin).toLocal(north)
        assertEquals(0.0, v.x, 1e-6, "east component of a due-north offset")
        assertEquals(haversineDistanceMeters(origin, north), v.y, 0.05, "north component")
    }

    @Test
    fun toLocal_dueEast_isPositiveEastOnly() {
        val origin = LatLng(40.0, -105.0)
        val east = LatLng(40.0, -104.999)
        val v = LocalFrame(origin).toLocal(east)
        assertEquals(0.0, v.y, 1e-6, "north component of a due-east offset")
        assertEquals(haversineDistanceMeters(origin, east), v.x, 0.05, "east component")
    }

    @Test
    fun toLocal_southWest_isNegativeInBothAxes() {
        val origin = LatLng(40.0, -105.0)
        val v = LocalFrame(origin).toLocal(LatLng(39.999, -105.001))
        assertTrue(v.x < 0.0, "west should be negative x, got ${v.x}")
        assertTrue(v.y < 0.0, "south should be negative y, got ${v.y}")
    }

    @Test
    fun toLocal_toLatLng_roundTripsToIdentity() {
        val frame = LocalFrame(LatLng(40.0, -105.0))
        for (p in listOf(
            LatLng(40.0, -105.0),
            LatLng(40.01, -105.02),
            LatLng(39.98, -104.97),
        )) {
            val back = frame.toLatLng(frame.toLocal(p))
            assertEquals(p.lat, back.lat, 1e-9, "lat round-trip for $p")
            assertEquals(p.lon, back.lon, 1e-9, "lon round-trip for $p")
        }
    }

    @Test
    fun centredOn_anchorsFrameAtCentroid() {
        // Centroid, not point 0 — it halves worst-case scale error over the trail's extent.
        val points = listOf(LatLng(40.0, -105.0), LatLng(40.2, -105.0), LatLng(40.4, -105.0))
        val frame = LocalFrame.centredOn(points)
        assertEquals(40.2, frame.origin.lat, 1e-9, "centroid lat")
        assertEquals(-105.0, frame.origin.lon, 1e-9, "centroid lon")
    }

    private companion object {
        const val TOL = 1e-9
    }
}
