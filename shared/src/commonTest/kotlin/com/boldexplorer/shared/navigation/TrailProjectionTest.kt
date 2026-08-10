package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [TrailPolyline.project].
 *
 * The windowed cases carry the most weight. The search window is load-bearing twice — it is the
 * performance mechanism that keeps a 10k-point trail viable, and it is the *switchback safety*
 * mechanism that stops a fix snapping onto a geometrically-near arm the user has not walked. A test
 * that only exercised the global scan would miss the second job entirely.
 */
class TrailProjectionTest {
    /** Due-north ladder from (40, -105); ~111.2 m per 0.001°, so ~1112 m for 10 segments. */
    private fun northLadder(count: Int = 11): List<LatLng> = (0 until count).map { LatLng(40.0 + it * 0.001, -105.0) }

    /** Roughly [m] metres of latitude, for building offsets that read as distances. */
    private fun latDegFor(m: Double) = m / 111_194.9

    /** Roughly [m] metres of longitude at latitude 40. */
    private fun lonDegFor(m: Double) = m / (111_194.9 * 0.766)

    @Test
    fun pointOnTheLine_hasNearZeroCrossTrack() {
        val poly = TrailPolyline(northLadder())
        val p = LatLng(40.0035, -105.0) // exactly on the ladder, between vertices 3 and 4
        val pos = assertNotNull(poly.project(p), "should project")
        assertEquals(0.0, pos.crossTrackM, 0.05, "cross-track on the line")
        assertEquals(
            haversineDistanceMeters(LatLng(40.0, -105.0), p),
            pos.alongTrackM,
            0.5,
            "along-track equals distance from the start",
        )
    }

    @Test
    fun pointEastOfNorthwardTrail_hasPositiveCrossTrack() {
        // Travelling north, east is the right-hand side. Must agree with crossTrackRightM.
        val poly = TrailPolyline(northLadder())
        val pos = assertNotNull(poly.project(LatLng(40.0035, -105.0 + lonDegFor(12.0))))
        assertEquals(12.0, pos.crossTrackM, 0.5, "12 m to the right of travel")
    }

    @Test
    fun pointWestOfNorthwardTrail_hasNegativeCrossTrack() {
        val poly = TrailPolyline(northLadder())
        val pos = assertNotNull(poly.project(LatLng(40.0035, -105.0 - lonDegFor(12.0))))
        assertEquals(-12.0, pos.crossTrackM, 0.5, "12 m to the left of travel")
    }

    @Test
    fun pointBeyondTheEnd_clampsToTotalLength() {
        val poly = TrailPolyline(northLadder())
        val beyond = LatLng(40.010 + latDegFor(50.0), -105.0)
        val pos = assertNotNull(poly.project(beyond))
        assertEquals(poly.totalLengthM, pos.alongTrackM, 0.5, "clamped to the far endpoint")
        assertTrue(abs(pos.crossTrackM) > 40.0, "distance is to the endpoint, got ${pos.crossTrackM}")
    }

    @Test
    fun pointBeforeTheStart_clampsToZero() {
        val poly = TrailPolyline(northLadder())
        val before = LatLng(40.0 - latDegFor(50.0), -105.0)
        val pos = assertNotNull(poly.project(before))
        assertEquals(0.0, pos.alongTrackM, 0.5, "clamped to the near endpoint")
    }

    @Test
    fun snappedPoint_liesOnTheTrail() {
        val poly = TrailPolyline(northLadder())
        val pos = assertNotNull(poly.project(LatLng(40.0035, -105.0 + lonDegFor(12.0))))
        // The snapped point is the foot of the projection, so it should be ~12 m from the fix and
        // ~0 m from the trail line itself.
        val backOnTrail = assertNotNull(poly.project(pos.snapped))
        assertEquals(0.0, backOnTrail.crossTrackM, 0.05, "snapped point is on the trail")
        assertEquals(pos.alongTrackM, backOnTrail.alongTrackM, 0.5, "and at the same along-track")
    }

    @Test
    fun window_restrictsTheScanToItsRange() {
        // The point sits beside vertex 1 (~111 m along), but the window only admits the far end.
        // Projection must return a far-end match rather than the globally nearest one.
        val poly = TrailPolyline(northLadder())
        val nearStart = LatLng(40.001, -105.0 + lonDegFor(5.0))

        val global = assertNotNull(poly.project(nearStart), "global scan")
        assertTrue(global.alongTrackM < 200.0, "global scan finds the near match, got ${global.alongTrackM}")

        val windowed = assertNotNull(poly.project(nearStart, 900.0..1200.0), "windowed scan")
        assertTrue(
            windowed.alongTrackM >= 900.0,
            "windowed scan must stay inside the window, got ${windowed.alongTrackM}",
        )
    }

    @Test
    fun switchbackArms_windowKeepsTheUserOnTheArmTheyWalked() {
        // Two parallel arms 8 m apart joined by a hairpin — the case the ADR calls out. A fix
        // between the arms is geometrically ambiguous; the window is what resolves it, and it must
        // resolve toward the arm the user has actually walked rather than the nearer one.
        val out = (0..10).map { LatLng(40.0 + it * 0.0005, -105.0) }
        val back = (10 downTo 0).map { LatLng(40.0 + it * 0.0005, -105.0 + lonDegFor(8.0)) }
        val poly = TrailPolyline(out + back)
        val outboundLength = poly.cumulativeM[10]

        // Sitting between the two arms, slightly nearer the return arm.
        val ambiguous = LatLng(40.0025, -105.0 + lonDegFor(5.0))

        val onOutbound = assertNotNull(poly.project(ambiguous, 0.0..outboundLength))
        assertTrue(
            onOutbound.alongTrackM <= outboundLength,
            "window confined to the outbound arm must return an outbound match, got ${onOutbound.alongTrackM}",
        )
    }

    @Test
    fun emptyWindow_returnsNull() {
        // A window past the end of the trail admits no segment. Null is the honest answer; the
        // caller decides whether that means Uncertain or a widening search.
        val poly = TrailPolyline(northLadder())
        assertEquals(null, poly.project(LatLng(40.005, -105.0), 5_000.0..6_000.0), "no segment in window")
    }

    @Test
    fun singlePointPolyline_projectsToTheOnlyVertex() {
        val poly = TrailPolyline(listOf(LatLng(40.0, -105.0)))
        val pos = assertNotNull(poly.project(LatLng(40.0 + latDegFor(10.0), -105.0)))
        assertEquals(0.0, pos.alongTrackM, 1e-9, "along-track")
        assertEquals(10.0, abs(pos.crossTrackM), 0.5, "distance to the only vertex")
    }
}
