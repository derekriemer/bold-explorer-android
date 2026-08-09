package com.boldexplorer.shared.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the error of [LocalFrame]'s equirectangular approximation.
 *
 * These are characterization tests: they exist to *measure* where the flat frame stops being
 * accurate rather than to assume it never does. The bounds below are set just above measured
 * values, so a regression in the projection maths trips them, and the measured error is reported in
 * every message so the headroom is visible without re-deriving it.
 *
 * Relative error grows roughly as `halfExtentRad × tan(latitude)`, which is why the bounds widen
 * with both extent and latitude.
 */
class LocalFramePinningTest {
    /**
     * Worst relative disagreement between in-frame planar distance and [haversineDistanceMeters]
     * over a grid of points spanning [extentM], centred at [latDeg].
     */
    private fun maxRelativeError(
        latDeg: Double,
        extentM: Double,
    ): Double {
        val halfDeg = (extentM / 2.0) / (EARTH_R * DEG_TO_RAD)
        val lonScale = 1.0 / max(cos(latDeg * DEG_TO_RAD), EPS_COS_LAT_POLE)
        val frame = LocalFrame(LatLng(latDeg, 0.0))
        val offsets = listOf(-1.0, -0.5, 0.0, 0.5, 1.0)
        val pts =
            buildList {
                for (dy in offsets) {
                    for (dx in offsets) {
                        add(LatLng(latDeg + dy * halfDeg, dx * halfDeg * lonScale))
                    }
                }
            }
        var worst = 0.0
        for (i in pts.indices) {
            for (j in i + 1 until pts.size) {
                val hav = haversineDistanceMeters(pts[i], pts[j])
                if (hav < 1.0) continue
                val a = frame.toLocal(pts[i])
                val b = frame.toLocal(pts[j])
                val flat = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
                worst = max(worst, abs(flat - hav) / hav)
            }
        }
        return worst
    }

    @Test
    fun inFrameDistance_agreesWithHaversine_atTrailExtents() {
        // 1 km and 10 km bracket any realistic trail. Bounds are set just above measured worst
        // case, which occurs at the highest latitude: error tracks halfExtentRad × tan(latitude).
        //
        // Measured worst case (70° latitude): 0.022% at 1 km, 0.216% at 10 km. On a 500 m trail
        // that is roughly 1 cm; on a 10 km trail roughly 20 m, still under typical GPS accuracy
        // and far under the rounding in any spoken distance.
        for (lat in listOf(0.0, 40.0, 60.0, 70.0)) {
            for ((extent, bound) in listOf(1_000.0 to 5e-4, 10_000.0 to 3e-3)) {
                val err = maxRelativeError(lat, extent)
                assertTrue(
                    err < bound,
                    "lat=$lat extent=${extent.toInt()}m: relative error ${err * 100}% exceeds ${bound * 100}%",
                )
            }
        }
    }

    @Test
    fun inFrameDistance_at50km_isCharacterised() {
        // Far beyond any trail. Documents where the approximation starts to matter rather than
        // claiming support: measured 0.33% at 40°, ~1.1% at 70°. A trail this long would want a
        // per-segment frame, and this test is what would flag that.
        for (lat in listOf(0.0, 40.0, 60.0, 70.0)) {
            val err = maxRelativeError(lat, 50_000.0)
            assertTrue(
                err < 1.5e-2,
                "lat=$lat extent=50000m: relative error ${err * 100}% exceeds 1.5%",
            )
        }
    }

    @Test
    fun roundTrip_acrossAntiMeridian_isIdentity() {
        // The case the crossing/noCrossing query split exists to handle today. Longitude
        // unwrapping should make it unremarkable.
        val frame = LocalFrame(LatLng(64.0, 179.99))
        for (p in listOf(
            LatLng(64.0, 179.99),
            LatLng(64.01, -179.98),
            LatLng(63.99, 179.95),
        )) {
            val back = frame.toLatLng(frame.toLocal(p))
            assertEquals(p.lat, back.lat, 1e-9, "lat round-trip for $p")
            assertEquals(p.lon, back.lon, 1e-9, "lon round-trip for $p")
        }
    }

    @Test
    fun antiMeridian_neighbouringPointsAreMetresApart_notHalfTheGlobe() {
        // The actual bug longitude unwrapping prevents: a naive lon difference would read 359.98°.
        val frame = LocalFrame(LatLng(64.0, 179.99))
        val v = frame.toLocal(LatLng(64.0, -179.99))
        assertTrue(abs(v.x) < 2_000.0, "expected a small east offset across the seam, got ${v.x} m")
    }

    @Test
    fun centroidOrigin_beatsFirstPoint_onLongNorthSouthTrail() {
        // Pins the centroid choice against a well-meaning future simplification to points.first().
        val pts = (0..40).map { LatLng(60.0 + it * 0.01, -105.0) }
        val centroid = LocalFrame.centredOn(pts)
        val firstPoint = LocalFrame(pts.first())

        fun worstError(frame: LocalFrame): Double {
            var worst = 0.0
            for (i in pts.indices) {
                for (j in i + 1 until pts.size) {
                    val hav = haversineDistanceMeters(pts[i], pts[j])
                    if (hav < 1.0) continue
                    val a = frame.toLocal(pts[i])
                    val b = frame.toLocal(pts[j])
                    val flat = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
                    worst = max(worst, abs(flat - hav) / hav)
                }
            }
            return worst
        }
        assertTrue(
            worstError(centroid) <= worstError(firstPoint),
            "centroid ${worstError(centroid)} should not be worse than first-point ${worstError(firstPoint)}",
        )
    }

    @Test
    fun centredOn_singlePoint_anchorsThere() {
        val p = LatLng(40.0, -105.0)
        val frame = LocalFrame.centredOn(listOf(p))
        assertEquals(p.lat, frame.origin.lat, 1e-12, "lat")
        assertEquals(p.lon, frame.origin.lon, 1e-12, "lon")
    }

    @Test
    fun nearPolarLatitude_doesNotBlowUp() {
        // cos(lat) approaches zero; EPS_COS_LAT_POLE is what keeps toLatLng finite.
        val frame = LocalFrame(LatLng(89.9999, 0.0))
        val v = frame.toLocal(LatLng(89.9999, 1.0))
        assertTrue(v.x.isFinite(), "east component must stay finite near the pole, got ${v.x}")
        val back = frame.toLatLng(v)
        assertTrue(back.lat.isFinite() && back.lon.isFinite(), "round-trip must stay finite")
    }
}
