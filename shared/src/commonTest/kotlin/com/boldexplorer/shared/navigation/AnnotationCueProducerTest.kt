package com.boldexplorer.shared.navigation

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
            "at 4 m/s, 8 s of lead is past the 40 m cap, so 30 m out should fire")
        assertTrue(producer(bench).onFix(70.0, speedMps = 0.0, units = Units.IMPERIAL).isEmpty(),
            "stationary falls back to the 10 m floor, not to silence forever")
    }

    @Test
    fun aReverseFollowApproachesFromTheOtherSide() {
        val p = AnnotationCueProducer(listOf(bench), TravelDirection.Reverse)

        assertTrue(p.onFix(140.0, 1.3, Units.IMPERIAL).isEmpty(), "40 m before it, walking backwards")
        assertTrue(p.onFix(110.0, 1.3, Units.IMPERIAL).isNotEmpty())
    }
}
