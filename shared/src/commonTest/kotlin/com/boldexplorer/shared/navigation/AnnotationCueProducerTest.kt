package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.model.TrailAnnotation
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.settings.Units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnotationCueProducerTest {
    private val bench = RouteAnnotation(1L, "Bench", alongTrackM = 100.0, signedCrossTrackM = 6.0)
    private val pavilion = RouteAnnotation(2L, "Pavilion", alongTrackM = 300.0, signedCrossTrackM = -80.0)

    private fun producer(vararg a: RouteAnnotation) =
        AnnotationCueProducer(a.toList(), TravelDirection.Forward)

    @Test
    fun itAnnouncesOnApproachNotOnArrival() {
        // Being told you are level with the bench means you are past it by the time you react.
        val p = producer(bench)

        assertTrue(p.onFix(60.0, speedMps = 1.3, units = Units.IMPERIAL).isEmpty(), "still 40 m off")
        val cues = p.onFix(90.0, speedMps = 1.3, units = Units.IMPERIAL)
        assertEquals(1, cues.size)
        assertTrue(cues.single().startsWith("Bench ahead"), "got ${cues.single()}")
    }

    @Test
    fun itSaysWhichSideAndHowFar() {
        val cues = producer(bench).onFix(92.0, speedMps = 1.3, units = Units.IMPERIAL)
        assertEquals("Bench ahead, 20 feet, on your right", cues.single())
    }

    @Test
    fun aDistantOneIsPhrasedAsAnAside() {
        // A phrasing boundary, not a silence boundary: mis-tuning changes how it sounds, never
        // whether the walker learns it is there.
        val cues = producer(pavilion).onFix(292.0, speedMps = 1.3, units = Units.IMPERIAL)
        assertTrue(cues.single().startsWith("Off to your left, 262 feet: Pavilion"), "got ${cues.single()}")
    }

    @Test
    fun itAnnouncesEachAnnotationOnce() {
        // Without this, GPS jitter around the lead boundary re-announces the same mark every fix.
        val p = producer(bench)
        p.onFix(90.0, 1.3, Units.IMPERIAL)

        assertTrue(p.onFix(91.0, 1.3, Units.IMPERIAL).isEmpty())
        assertTrue(p.onFix(89.0, 1.3, Units.IMPERIAL).isEmpty(), "jitter backwards must not re-fire")
    }

    @Test
    fun theLeadGrowsWithSpeedAndIsClamped() {
        // Lead time is what the walker experiences; a runner needs more warning in metres.
        assertTrue(producer(bench).onFix(70.0, speedMps = 4.0, units = Units.IMPERIAL).isNotEmpty(),
            "at 4 m/s, 8 s of lead is 32 m — inside the 10-40 m clamp — so 30 m out should fire")
        assertTrue(producer(bench).onFix(70.0, speedMps = 0.0, units = Units.IMPERIAL).isEmpty(),
            "stationary falls back to the 10 m floor, not to silence forever")
    }

    @Test
    fun theLeadIsCappedForAFastMover() {
        // At 10 m/s, 8 s of lead would be 80 m — well past the 40 m cap — so the lead used is the
        // cap itself, and a mark 50 m out (beyond the cap) must not fire yet.
        assertTrue(producer(bench).onFix(50.0, speedMps = 10.0, units = Units.IMPERIAL).isEmpty(),
            "50 m out is beyond the 40 m cap even at this speed")
        assertTrue(producer(bench).onFix(65.0, speedMps = 10.0, units = Units.IMPERIAL).isNotEmpty(),
            "35 m out is inside the 40 m cap")
    }

    @Test
    fun aReverseFollowApproachesFromTheOtherSide() {
        val p = AnnotationCueProducer(listOf(bench), TravelDirection.Reverse)

        assertTrue(p.onFix(140.0, 1.3, Units.IMPERIAL).isEmpty(), "40 m before it, walking backwards")
        assertTrue(p.onFix(110.0, 1.3, Units.IMPERIAL).isNotEmpty())
    }

    @Test
    fun aMarkPassedDuringADropoutIsAnnouncedLateAndHedged() {
        // The precedent is TrailComplete(hedged): say it, and say that you are unsure — rather than
        // asserting, or saying nothing, which reads the same as "there was nothing there".
        val p = producer(bench)

        val cues = p.onReacquired(fromAlongTrackM = 40.0, toAlongTrackM = 180.0,
            predictionErrorM = null, units = Units.IMPERIAL)

        assertEquals(1, cues.size)
        assertTrue(cues.single().startsWith("You passed Bench"), "got ${cues.single()}")
        assertTrue(cues.single().contains("back"), "distance behind is the actionable part")
    }

    @Test
    fun anUntrustworthyRejoinClaimsNothingInEitherPredictionDirection() {
        // A big prediction error means we may have rejoined somewhere else entirely, and "you
        // passed the bench" may simply be false. A confident false statement about position is the
        // worst thing this app can say.
        val untrustedError = NavigationPolicy.REJOIN_TRUSTED_ERROR_M + 1.0

        listOf(untrustedError, -untrustedError).forEach { predictionErrorM ->
            val cues = producer(bench).onReacquired(40.0, 180.0, predictionErrorM, Units.IMPERIAL)

            assertTrue(cues.isEmpty(), "prediction error $predictionErrorM must suppress the cue")
        }
    }

    @Test
    fun aRejoinAtTheTrustedErrorBoundaryStillReportsThePassedMark() {
        val threshold = NavigationPolicy.REJOIN_TRUSTED_ERROR_M

        listOf(threshold, -threshold).forEach { predictionErrorM ->
            val cues = producer(bench).onReacquired(40.0, 180.0, predictionErrorM, Units.IMPERIAL)

            assertEquals(1, cues.size, "prediction error $predictionErrorM is still trusted")
        }
    }

    @Test
    fun aMarkAnnouncedNormallyIsNotAnnouncedAgainOnReacquisition() {
        val p = producer(bench)
        p.onFix(90.0, 1.3, Units.IMPERIAL)

        assertTrue(p.onReacquired(40.0, 180.0, null, Units.IMPERIAL).isEmpty())
    }

    @Test
    fun aLollipopAnnotationIsAnnouncedOnBothStickPasses() {
        val polyline = TrailPolyline(LollipopFixture.points)
        val stickMarker =
            Waypoint(
                id = 71L,
                name = "Footbridge",
                lat = offsetFromOrigin(northM = LollipopFixture.MID_STICK_M, eastM = 0.0).lat,
                lon = offsetFromOrigin(northM = LollipopFixture.MID_STICK_M, eastM = 0.0).lon,
                elevM = null,
                description = null,
                createdAt = 0L,
            )
        val annotations =
            routeAnnotationsForFollow(
                polyline = polyline,
                annotations = listOf(TrailAnnotation(9L, 1L, stickMarker, segmentIndex = 0, offsetM = 0.0, createdAt = 0L)),
                recordedPoints = emptyList(),
                isRecorded = true,
            )

        assertEquals(2, annotations.size, "the stick is walked outward and back")
        val cues = AnnotationCueProducer(annotations, TravelDirection.Forward)
        val spoken = annotations.sortedBy { it.alongTrackM }.flatMap { landmark ->
            cues.onFix(landmark.alongTrackM - 5.0, speedMps = 1.3, units = Units.IMPERIAL)
        }

        assertEquals(2, spoken.size)
        assertTrue(spoken.all { it.startsWith("Footbridge ahead") })
    }

    @Test
    fun recordedNamedVerticesSpeakButTrackPointsStaySilent() {
        val geometry = listOf(offsetFromOrigin(0.0, 0.0), offsetFromOrigin(100.0, 0.0), offsetFromOrigin(200.0, 0.0))
        val polyline = TrailPolyline(geometry)
        val points =
            listOf(
                TrailPoint(1L, "GPS start", geometry[0].lat, geometry[0].lon, kind = Waypoint.KIND_TRACK_POINT),
                TrailPoint(2L, "Old gate", geometry[1].lat, geometry[1].lon),
                TrailPoint(3L, "GPS end", geometry[2].lat, geometry[2].lon, kind = Waypoint.KIND_TRACK_POINT),
            )
        val landmarks = routeAnnotationsForFollow(polyline, emptyList(), points, isRecorded = true)

        assertEquals(listOf("Old gate"), landmarks.map { it.name })
        val forward = AnnotationCueProducer(landmarks, TravelDirection.Forward)
        val reverse = AnnotationCueProducer(landmarks, TravelDirection.Reverse)
        assertEquals(1, forward.onFix(95.0, 1.3, Units.IMPERIAL).size)
        assertEquals(1, reverse.onFix(105.0, 1.3, Units.IMPERIAL).size)
    }
}
