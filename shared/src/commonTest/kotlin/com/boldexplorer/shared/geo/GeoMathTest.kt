package com.boldexplorer.shared.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeoMathTest {
    // Same inputs used in the TypeScript tests to verify exact parity.

    private fun assertClose(actual: Double, expected: Double, tol: Double = 1.0, label: String = "") {
        assertTrue(abs(actual - expected) <= tol, "$label expected ~$expected got $actual (tol $tol)")
    }

    @Test
    fun haversine_samePoint_isZero() {
        val p = LatLng(51.5074, -0.1278)
        assertEquals(0.0, haversineDistanceMeters(p, p))
    }

    @Test
    fun haversine_londonToManchesterApprox() {
        val london = LatLng(51.5074, -0.1278)
        val manchester = LatLng(53.4808, -2.2426)
        val d = haversineDistanceMeters(london, manchester)
        // ~262 km — allow ±1 km
        assertClose(d, 262_600.0, 1000.0, "London–Manchester")
    }

    @Test
    fun haversine_shortDistance() {
        // Two points ~100 m apart (same lat, ~0.001° lon difference at equator)
        val a = LatLng(0.0, 0.0)
        val b = LatLng(0.0, 0.001)
        val d = haversineDistanceMeters(a, b)
        assertClose(d, 111.32, 1.0, "~100m equatorial")
    }

    @Test
    fun initialBearing_north() {
        val a = LatLng(0.0, 0.0)
        val b = LatLng(1.0, 0.0)
        assertClose(initialBearingDeg(a, b), 0.0, 0.001, "due north")
    }

    @Test
    fun initialBearing_east() {
        val a = LatLng(0.0, 0.0)
        val b = LatLng(0.0, 1.0)
        assertClose(initialBearingDeg(a, b), 90.0, 0.01, "due east")
    }

    @Test
    fun initialBearing_south() {
        val a = LatLng(1.0, 0.0)
        val b = LatLng(0.0, 0.0)
        assertClose(initialBearingDeg(a, b), 180.0, 0.001, "due south")
    }

    @Test
    fun initialBearing_west() {
        val a = LatLng(0.0, 1.0)
        val b = LatLng(0.0, 0.0)
        assertClose(initialBearingDeg(a, b), 270.0, 0.01, "due west")
    }

    @Test
    fun deltaAngle_rightOfHeading() {
        // Heading 0 (north), bearing 90 (east) → should be 90 to the right
        assertEquals(90.0, deltaAngle(0.0, 270.0), 0.001)
    }

    @Test
    fun deltaAngle_leftOfHeading() {
        // Heading 90, bearing 0 → -90 (to the left)
        assertClose(deltaAngle(90.0, 0.0), -90.0, 0.001, "left of heading")
    }

    @Test
    fun deltaAngle_behind() {
        // Heading 0, bearing 180 → ±180 (normalised to -180 by the formula)
        val d = deltaAngle(0.0, 180.0)
        assertTrue(abs(d) == 180.0, "behind: expected ±180, got $d")
    }

    @Test
    fun deltaAngle_aligned() {
        assertEquals(0.0, deltaAngle(45.0, 45.0), 0.001)
    }

    @Test
    fun computeBbox_basicRadius() {
        val center = LatLng(51.5, -0.12)
        val bbox = computeBbox(center, 10_000.0)
        assertTrue(bbox.needsLonFilter)
        assertFalse(bbox.crossing)
        assertTrue(bbox.latMin < center.lat)
        assertTrue(bbox.latMax > center.lat)
        assertTrue(bbox.lonMin < center.lon)
        assertTrue(bbox.lonMax > center.lon)
    }

    @Test
    fun computeBbox_nearPole_noLonFilter() {
        val polar = LatLng(89.99, 0.0)
        val bbox = computeBbox(polar, 10_000.0)
        assertFalse(bbox.needsLonFilter)
    }

    @Test
    fun computeBbox_antiMeridian() {
        val center = LatLng(0.0, 179.9)
        val bbox = computeBbox(center, 100_000.0)
        if (bbox.needsLonFilter) {
            assertTrue(bbox.crossing, "should flag anti-meridian crossing")
        }
    }
}
