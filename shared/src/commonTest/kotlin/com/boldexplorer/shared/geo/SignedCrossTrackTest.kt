package com.boldexplorer.shared.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the sign of cross-track offset to the repo's existing right-positive convention.
 *
 * [deltaAngle] documents *positive = target is to the right of heading*. The naive planar cross
 * product is left-positive and therefore has the opposite sign, so [crossTrackRightM] negates it.
 * Getting this wrong produces guidance that is wrong by a reflection — "the trail is to your right"
 * when it is to your left — which is a wrong-direction cue for a blind user, not a cosmetic bug,
 * and it never crashes.
 */
class SignedCrossTrackTest {
    @Test
    fun userEastOfNorthwardSegment_isPositive() {
        // The sanity check from the ADR, as a literal test: north-pointing segment, user due east,
        // therefore user is on the RIGHT, therefore positive.
        val a = Vec2(0.0, 0.0)
        val b = Vec2(0.0, 100.0) // due north
        val p = Vec2(10.0, 50.0) // 10 m east of the segment, halfway along
        assertEquals(10.0, crossTrackRightM(p, a, b), 1e-9, "user due east of a northward segment")
    }

    @Test
    fun userWestOfNorthwardSegment_isNegative() {
        val a = Vec2(0.0, 0.0)
        val b = Vec2(0.0, 100.0)
        val p = Vec2(-10.0, 50.0)
        assertEquals(-10.0, crossTrackRightM(p, a, b), 1e-9, "user due west of a northward segment")
    }

    @Test
    fun userOnSegment_isZero() {
        val a = Vec2(0.0, 0.0)
        val b = Vec2(0.0, 100.0)
        assertEquals(0.0, crossTrackRightM(Vec2(0.0, 50.0), a, b), 1e-9, "on the line")
    }

    @Test
    fun signAgreesWithDeltaAngle_forTheSameGeometry() {
        // The cross-cutting check: a right-hand offset and a right-hand bearing delta must not
        // disagree in sign, or planar and spherical code will contradict each other.
        val a = Vec2(0.0, 0.0)
        val b = Vec2(0.0, 100.0)
        val segmentBearing = bearingDeg(Vec2(b.x - a.x, b.y - a.y)) // due north = 0

        for (east in listOf(-30.0, -5.0, 5.0, 30.0)) {
            val p = Vec2(east, 50.0)
            val crossTrack = crossTrackRightM(p, a, b)
            // Bearing from the nearest point on the segment out to the user.
            val toUser = bearingDeg(Vec2(p.x, 0.0))
            val delta = deltaAngle(segmentBearing, toUser)
            assertTrue(
                (crossTrack > 0.0) == (delta > 0.0),
                "east=$east: crossTrackRightM=$crossTrack and deltaAngle=$delta disagree in sign",
            )
        }
    }

    @Test
    fun degenerateZeroLengthSegment_isDistanceToThePoint() {
        // A zero-length segment has no direction, so there is no meaningful side. Must not divide
        // by zero; magnitude should still be the distance to the degenerate point.
        val a = Vec2(0.0, 0.0)
        val result = crossTrackRightM(Vec2(3.0, 4.0), a, a)
        assertTrue(result.isFinite(), "must not divide by zero, got $result")
        assertEquals(5.0, kotlin.math.abs(result), 1e-9, "magnitude is distance to the point")
    }
}
