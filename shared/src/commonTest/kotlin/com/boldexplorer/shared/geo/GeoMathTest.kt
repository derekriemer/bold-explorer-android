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
        assertEquals(90.0, deltaAngle(0.0, 90.0), 0.001)
    }

    @Test
    fun deltaAngle_leftOfHeading() {
        // Heading 90, bearing 0 → -90 (to the left)
        assertClose(deltaAngle(90.0, 0.0), -90.0, 0.001, "left of heading")
    }

    @Test
    fun deltaAngle_signConvention_positiveIsClockwise() {
        // Contract: positive = bearing is clockwise from heading (target to the right).
        // BearingComputer.toRelative and any UI display of this delta must agree.
        // If this formula is ever changed, BearingComputerTest.toRelative_consistentWithDeltaAngle
        // will also fail, forcing both to be updated together.
        assertTrue(deltaAngle(0.0, 90.0) > 0,   "East of North should be positive (right)")
        assertTrue(deltaAngle(90.0, 0.0) < 0,   "North of East should be negative (left)")
        assertTrue(deltaAngle(0.0, 270.0) < 0,  "West of North should be negative (left)")
        assertTrue(deltaAngle(270.0, 0.0) > 0,  "North of West should be positive (right)")
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

    // ── segmentFraction ──────────────────────────────────────────────────────────

    private val segA = LatLng(0.0, 0.0)
    private val segB = LatLng(0.001, 0.0) // ~111 m north

    @Test
    fun segmentFraction_atA_isZero() {
        assertClose(segmentFraction(segA, segA, segB), 0.0, tol = 0.01)
    }

    @Test
    fun segmentFraction_atB_isOne() {
        assertClose(segmentFraction(segB, segA, segB), 1.0, tol = 0.01)
    }

    @Test
    fun segmentFraction_atMidpoint_isHalf() {
        val mid = LatLng(0.0005, 0.0)
        assertClose(segmentFraction(mid, segA, segB), 0.5, tol = 0.01)
    }

    @Test
    fun segmentFraction_pastB_greaterThanOne() {
        val past = LatLng(0.0012, 0.0)
        assertTrue(segmentFraction(past, segA, segB) > 1.0)
    }

    @Test
    fun segmentFraction_beforeA_negative() {
        val before = LatLng(-0.0001, 0.0)
        assertTrue(segmentFraction(before, segA, segB) < 0.0)
    }

    @Test
    fun segmentFraction_degenerate_returnsZero() {
        // a == b — no valid segment
        assertClose(segmentFraction(segA, segA, segA), 0.0, tol = 0.01)
    }

    // ── distanceToSegmentMeters ──────────────────────────────────────────────────

    // N-S segment: segA(0,0) → segB(0.001,0), ~111m long.

    @Test
    fun distanceToSegment_pointOnSegment_isNearZero() {
        val mid = LatLng(0.0005, 0.0) // midpoint, exactly on segment
        assertClose(distanceToSegmentMeters(mid, segA, segB), 0.0, tol = 0.5)
    }

    @Test
    fun distanceToSegment_perpendicularToMidpoint() {
        // Point 100m east of midpoint (0.0005, 0): lon ≈ 0.001° ≈ 111m at equator
        val east = LatLng(0.0005, 0.001)
        assertClose(distanceToSegmentMeters(east, segA, segB), 111.32, tol = 2.0, label = "perpendicular")
    }

    @Test
    fun distanceToSegment_pastEnd_clampsToEndpoint() {
        val past = LatLng(0.002, 0.0) // past segB
        val expected = haversineDistanceMeters(past, segB)
        assertClose(distanceToSegmentMeters(past, segA, segB), expected, tol = 0.1, label = "past end")
    }

    @Test
    fun distanceToSegment_beforeStart_clampsToStartpoint() {
        val before = LatLng(-0.001, 0.0) // before segA
        val expected = haversineDistanceMeters(before, segA)
        assertClose(distanceToSegmentMeters(before, segA, segB), expected, tol = 0.1, label = "before start")
    }

    @Test
    fun distanceToSegment_degenerate_isPointDistance() {
        // segStart == segEnd — degenerate segment; result should equal point-to-point distance.
        val p = LatLng(0.001, 0.001)
        val expected = haversineDistanceMeters(p, segA)
        assertClose(distanceToSegmentMeters(p, segA, segA), expected, tol = 0.1, label = "degenerate")
    }

    // ── angleDifferenceDeg ───────────────────────────────────────────────────────

    @Test
    fun angleDifference_same_isZero() {
        assertClose(angleDifferenceDeg(0.0, 0.0), 0.0, tol = 0.001)
    }

    @Test
    fun angleDifference_opposite_is180() {
        assertClose(angleDifferenceDeg(0.0, 180.0), 180.0, tol = 0.001)
    }

    @Test
    fun angleDifference_wrapsAround() {
        // 350° and 10° are 20° apart across the 0/360 boundary.
        assertClose(angleDifferenceDeg(350.0, 10.0), 20.0, tol = 0.001)
    }

    @Test
    fun angleDifference_rightAngle() {
        assertClose(angleDifferenceDeg(90.0, 270.0), 180.0, tol = 0.001)
    }

    @Test
    fun angleDifference_isSymmetric() {
        val ab = angleDifferenceDeg(10.0, 350.0)
        val ba = angleDifferenceDeg(350.0, 10.0)
        assertClose(ab, ba, tol = 0.001, label = "symmetry")
    }

    // ── distance3DMeters ─────────────────────────────────────────────────────────

    @Test
    fun distance3D_noElevation_isHorizDist() {
        assertClose(distance3DMeters(100.0, 0.0), 100.0, tol = 0.001)
    }

    @Test
    fun distance3D_pythagorean3_4_5() {
        assertClose(distance3DMeters(3.0, 4.0), 5.0, tol = 0.001)
    }

    @Test
    fun distance3D_negativeElevDelta_stillPositive() {
        // Sign of elevDelta should not matter — result is sqrt(x²+y²).
        assertClose(distance3DMeters(3.0, -4.0), 5.0, tol = 0.001)
    }
}
