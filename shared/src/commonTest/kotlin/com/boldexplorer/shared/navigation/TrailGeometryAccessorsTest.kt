package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.deltaAngle
import com.boldexplorer.shared.geo.haversineDistanceMeters
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the density-invariant geometry accessors that S8 bend detection will consume.
 *
 * The governing requirement is that the same physical trail behaves the same whether its geometry
 * was recorded every 2 m or every 30 m. Every accessor here is therefore parameterized by
 * *distance*, never by vertex index or vertex count.
 */
class TrailGeometryAccessorsTest {
    private fun latDegFor(m: Double) = m / 111_194.9

    private fun lonDegFor(m: Double) = m / (111_194.9 * 0.766)

    /** Straight due-north trail of [lengthM], sampled every [spacingM]. */
    private fun northLine(
        lengthM: Double,
        spacingM: Double,
    ): List<LatLng> {
        val n = (lengthM / spacingM).toInt()
        return (0..n).map { LatLng(40.0 + latDegFor(it * spacingM), -105.0) }
    }

    @Test
    fun positionAt_zero_isTheFirstVertex() {
        val pts = northLine(500.0, 50.0)
        val poly = TrailPolyline(pts)
        assertEquals(0.0, haversineDistanceMeters(pts.first(), poly.positionAt(0.0)), 0.1, "start")
    }

    @Test
    fun positionAt_totalLength_isTheLastVertex() {
        val pts = northLine(500.0, 50.0)
        val poly = TrailPolyline(pts)
        assertEquals(
            0.0,
            haversineDistanceMeters(pts.last(), poly.positionAt(poly.totalLengthM)),
            0.1,
            "end",
        )
    }

    @Test
    fun positionAt_interpolatesWithinASegment() {
        val poly = TrailPolyline(northLine(500.0, 100.0))
        // 150 m along a 100 m-spaced ladder is the midpoint of the second segment.
        val p = poly.positionAt(150.0)
        assertEquals(150.0, haversineDistanceMeters(LatLng(40.0, -105.0), p), 0.5, "150 m along")
    }

    @Test
    fun positionAt_clampsOutOfRangeInput() {
        val poly = TrailPolyline(northLine(500.0, 50.0))
        assertEquals(
            0.0,
            haversineDistanceMeters(poly.positionAt(0.0), poly.positionAt(-1_000.0)),
            0.1,
            "below zero clamps to the start",
        )
        assertEquals(
            0.0,
            haversineDistanceMeters(poly.positionAt(poly.totalLengthM), poly.positionAt(99_999.0)),
            0.1,
            "beyond the end clamps to the end",
        )
    }

    @Test
    fun chordBearingAt_onDueNorthTrail_isNorth() {
        val poly = TrailPolyline(northLine(500.0, 25.0))
        val bearing = assertNotNull(poly.chordBearingAt(250.0, baselineM = 40.0))
        assertEquals(0.0, deltaAngle(0.0, bearing), 0.5, "due north")
    }

    @Test
    fun chordBearingAt_onDueEastTrail_isEast() {
        val pts = (0..20).map { LatLng(40.0, -105.0 + lonDegFor(it * 25.0)) }
        val poly = TrailPolyline(pts)
        val bearing = assertNotNull(poly.chordBearingAt(250.0, baselineM = 40.0))
        assertEquals(0.0, deltaAngle(90.0, bearing), 0.5, "due east")
    }

    @Test
    fun chordBearingAt_isDensityInvariant() {
        // The core #35 property, applied to bearing: the same physical line sampled at 2 m and at
        // 30 m must report the same local direction.
        val dense = TrailPolyline(northLine(500.0, 2.0))
        val sparse = TrailPolyline(northLine(500.0, 30.0))
        val a = assertNotNull(dense.chordBearingAt(250.0, baselineM = 40.0))
        val b = assertNotNull(sparse.chordBearingAt(250.0, baselineM = 40.0))
        assertEquals(0.0, deltaAngle(a, b), 1.0, "dense=$a sparse=$b")
    }

    @Test
    fun sagittaOver_straightLine_isZero() {
        val poly = TrailPolyline(northLine(500.0, 25.0))
        assertEquals(0.0, poly.sagittaOver(100.0, lookaheadM = 100.0), 0.5, "a straight line bulges nowhere")
    }

    @Test
    fun sagittaOver_rightAngleCorner_isLarge() {
        // 200 m north then 200 m east. Over a window spanning the corner the polyline departs a
        // long way from its own chord.
        val north = (0..20).map { LatLng(40.0 + latDegFor(it * 10.0), -105.0) }
        val east = (1..20).map { LatLng(40.0 + latDegFor(200.0), -105.0 + lonDegFor(it * 10.0)) }
        val poly = TrailPolyline(north + east)
        assertTrue(
            poly.sagittaOver(120.0, lookaheadM = 160.0) > 20.0,
            "corner should bulge well off the chord, got ${poly.sagittaOver(120.0, lookaheadM = 160.0)}",
        )
    }

    @Test
    fun sagittaOver_catchesAnSBend_whereNetTurnDoesNot() {
        // The reason sagitta is the primary detector rather than signed net turn: an S-bend
        // returns to its original heading, so net turn is ~0 while the path is plainly not
        // straight. A detector using only net turn would call this a straight section and stay
        // silent through it.
        // A lateral jog: due north, diagonal right for 100 m of offset, then due north again.
        // Heading at the end equals heading at the start, so net turn is zero by construction.
        val pts =
            buildList {
                for (i in 0..20) add(LatLng(40.0 + latDegFor(i * 10.0), -105.0))
                for (i in 1..20) add(LatLng(40.0 + latDegFor(200.0 + i * 10.0), -105.0 + lonDegFor(i * 5.0)))
                for (i in 1..20) add(LatLng(40.0 + latDegFor(400.0 + i * 10.0), -105.0 + lonDegFor(100.0)))
            }
        val poly = TrailPolyline(pts)

        val startBearing = assertNotNull(poly.chordBearingAt(50.0, baselineM = 40.0))
        val endBearing = assertNotNull(poly.chordBearingAt(poly.totalLengthM - 50.0, baselineM = 40.0))
        val netTurn = abs(deltaAngle(startBearing, endBearing))
        assertTrue(netTurn < 5.0, "the jog nets out to no turn, got $netTurn")

        val sagitta = poly.sagittaOver(150.0, lookaheadM = 350.0)
        assertTrue(sagitta > 5.0, "but it plainly bulges off its chord, got $sagitta")
    }

    @Test
    fun sagittaOver_isDensityInvariant() {
        fun bend(spacingM: Double): TrailPolyline {
            val n = (200.0 / spacingM).toInt()
            val leg1 = (0..n).map { LatLng(40.0 + latDegFor(it * spacingM), -105.0) }
            val leg2 =
                (1..n).map {
                    LatLng(40.0 + latDegFor(200.0), -105.0 + lonDegFor(it * spacingM))
                }
            return TrailPolyline(leg1 + leg2)
        }
        val dense = bend(2.0).sagittaOver(120.0, lookaheadM = 160.0)
        val sparse = bend(20.0).sagittaOver(120.0, lookaheadM = 160.0)
        assertEquals(dense, sparse, 2.0, "dense=$dense sparse=$sparse")
    }
}
