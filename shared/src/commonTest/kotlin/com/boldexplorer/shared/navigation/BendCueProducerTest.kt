package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.settings.Units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BendCueProducerTest {
    private fun latFor(m: Double) = 40.0 + m / 111_194.9

    private fun lonOffsetFor(m: Double) = m / 111_194.9

    /** 100 m north, then 200 m east — corner at along-track 100, well within default scan range. */
    private fun corner(): TrailPolyline {
        val north = (0..5).map { LatLng(latFor(it * 20.0), -105.0) }
        val east = (1..10).map { LatLng(latFor(100.0), -105.0 + lonOffsetFor(it * 20.0)) }
        return TrailPolyline(north + east)
    }

    @Test
    fun unconfirmedPositionBails() {
        val producer = BendCueProducer()
        val cue = producer.onFix(0L, corner(), null, TravelDirection.Forward, Units.IMPERIAL)
        assertNull(cue.speech)
        assertEquals("bail:unconfirmed", cue.disposition)
    }

    @Test
    fun announcesTheCornerOnce() {
        val producer = BendCueProducer()
        val poly = corner()
        val cue = producer.onFix(0L, poly, 0.0, TravelDirection.Forward, Units.IMPERIAL)
        assertTrue(cue.speech != null, "a 90 degree corner ahead should speak")
        assertTrue(cue.speech!!.contains("right"), "north-then-east is a right turn: ${cue.speech}")
    }

    @Test
    fun doesNotReannounceTheSameCornerEveryFix() {
        // The hairpin-re-fires-every-fix bug this producer exists to prevent: the scan straddles
        // the same corner on many consecutive fixes as the walker approaches it. Dedup is checked
        // ahead of the speech throttle, so these stay attributed to "already_announced" even though
        // the fixes are only a second or two apart -- the throttle is a separate, later concern.
        val producer = BendCueProducer()
        val poly = corner()
        val first = producer.onFix(0L, poly, 0.0, TravelDirection.Forward, Units.IMPERIAL)
        assertTrue(first.speech != null)

        val second = producer.onFix(1_000L, poly, 20.0, TravelDirection.Forward, Units.IMPERIAL)
        assertNull(second.speech, "same corner, already announced")
        assertEquals("bail:already_announced", second.disposition)

        val third = producer.onFix(2_000L, poly, 80.0, TravelDirection.Forward, Units.IMPERIAL)
        assertNull(third.speech, "still the same corner, still already announced")
    }

    /** A right turn at along-track 60, then a left turn at along-track 160 -- two real bends. */
    private fun twoCorners(): TrailPolyline {
        val north1 = (0..3).map { LatLng(latFor(it * 20.0), -105.0) } // 0, 20, 40, 60
        val east = (1..5).map { LatLng(latFor(60.0), -105.0 + lonOffsetFor(it * 20.0)) } // 80..160
        val north2 = (1..3).map { LatLng(latFor(60.0 + it * 20.0), -105.0 + lonOffsetFor(100.0)) } // 180..220
        return TrailPolyline(north1 + east + north2)
    }

    @Test
    fun throttlesAGenuinelyDifferentBendSpokenTooSoonAfterTheLast() {
        // Defence in depth against the actual field bug (2026-09-02): even when the anchor dedup
        // correctly sees a genuinely different, never-before-announced turn (not an unstable
        // re-detection of the same one), a second announcement within the speech interval must
        // still be held back -- two real, close-together turns should not be able to fire back to
        // back within a few seconds of each other.
        val producer = BendCueProducer()
        val poly = twoCorners()

        val first = producer.onFix(0L, poly, 0.0, TravelDirection.Forward, Units.IMPERIAL)
        assertTrue(first.speech != null, "the right turn at 60 should announce")

        // Past the first corner; the second (left, at 160) is now the next bend -- a genuinely new
        // anchor, so the dedup alone would let it through. Only 5 s later.
        val tooSoon = producer.onFix(5_000L, poly, 80.0, TravelDirection.Forward, Units.IMPERIAL)
        assertNull(tooSoon.speech, "a different real turn, but only 5 s after the last announcement")
        assertTrue(tooSoon.disposition.startsWith("bail:throttled"), tooSoon.disposition)

        // Past the speech interval: the second corner still hasn't been announced, so it fires now.
        val later = producer.onFix(30_000L, poly, 80.0, TravelDirection.Forward, Units.IMPERIAL)
        assertTrue(later.speech != null, "the throttle only delays it, it doesn't drop it")
        assertTrue(later.speech!!.contains("left"), later.speech)
    }

    @Test
    fun selfCorrectsAfterABacktrackPastTheAnnouncedCorner() {
        // The scenario raised in review: if alongTrackM was wrong for some reason and later
        // corrects (or the walker genuinely backtracks), the "already announced" mark must not
        // survive falling behind the anchor -- otherwise a real re-approach can never re-announce.
        // Timestamps are spaced past the speech-interval throttle so that throttle isn't what's
        // under test here.
        val producer = BendCueProducer()
        val poly = corner()

        val approaching = producer.onFix(0L, poly, 0.0, TravelDirection.Forward, Units.IMPERIAL)
        assertTrue(approaching.speech != null, "corner announced on approach")

        // Confirmed position corrects/backtracks to well before the corner.
        val backtracked = producer.onFix(30_000L, poly, 0.0, TravelDirection.Forward, Units.IMPERIAL)
        assertNull(backtracked.speech, "no new information yet -- still approaching the same corner")

        // Walk all the way past the corner and far down the east leg: the anchor is now behind.
        producer.onFix(60_000L, poly, 250.0, TravelDirection.Forward, Units.IMPERIAL)

        // Backtrack past the corner again, to before it, and re-approach.
        val reapproaching = producer.onFix(90_000L, poly, 0.0, TravelDirection.Forward, Units.IMPERIAL)
        assertTrue(
            reapproaching.speech != null,
            "having fallen behind the corner again, a genuine re-approach must be announced again",
        )
    }

    @Test
    fun selfCorrectsEvenAfterReachingTheAtTurnStage() {
        // The reset-on-fall-behind guarantee (class doc) must hold no matter how far a corner's
        // staged announcement got -- a full re-approach after backtracking past it starts the whole
        // sequence over, not resume mid-stage.
        val producer = BendCueProducer()
        val poly = corner()

        producer.onFix(0L, poly, 0.0, TravelDirection.Forward, Units.IMPERIAL)
        val atTurn = producer.onFix(30_000L, poly, 99.0, TravelDirection.Forward, Units.IMPERIAL)
        assertTrue(atTurn.disposition.startsWith("speak:at_turn_"), "reached the final stage first")

        // Walk well past the corner, then backtrack to before it again.
        producer.onFix(60_000L, poly, 250.0, TravelDirection.Forward, Units.IMPERIAL)
        val reapproaching = producer.onFix(90_000L, poly, 0.0, TravelDirection.Forward, Units.IMPERIAL)
        assertTrue(reapproaching.speech != null, "a genuine re-approach starts the sequence over")
        assertTrue(reapproaching.disposition.startsWith("speak:approach_"), reapproaching.disposition)
    }

    @Test
    fun resetForgetsTheAnnouncedAnchor() {
        val producer = BendCueProducer()
        val poly = corner()
        producer.onFix(0L, poly, 0.0, TravelDirection.Forward, Units.IMPERIAL)
        producer.reset()
        val cue = producer.onFix(1_000L, poly, 0.0, TravelDirection.Forward, Units.IMPERIAL)
        assertTrue(cue.speech != null, "reset clears the dedup mark and the throttle; a new follow announces fresh")
    }

    @Test
    fun progressesThroughApproachCloseAndAtTurnForTheSameCorner() {
        // #124: a single distant approach cue read as "missed" once the walker actually reached the
        // corner with nothing further said. Close range (8m) and at-anchor (2m) are the defaults.
        val producer = BendCueProducer()
        val poly = corner()

        val approach = producer.onFix(0L, poly, 0.0, TravelDirection.Forward, Units.IMPERIAL)
        assertTrue(approach.speech != null, "approach cue at 100m out")
        assertTrue(approach.disposition.startsWith("speak:approach_"), approach.disposition)

        // Still approaching, not yet within close range (8m) -- no new stage due.
        val stillApproaching = producer.onFix(1_000L, poly, 80.0, TravelDirection.Forward, Units.IMPERIAL)
        assertNull(stillApproaching.speech, "20m out, not within close range yet")

        // Within close range (7m out): the close-confirmation stage, not throttled even though this
        // is only 2s after the approach cue -- stage progression on an already-tracked anchor is
        // exempt from the cross-anchor throttle.
        val close = producer.onFix(2_000L, poly, 93.0, TravelDirection.Forward, Units.IMPERIAL)
        assertTrue(close.speech != null, "close-range cue must not be throttled")
        assertTrue(close.disposition.startsWith("speak:close_"), close.disposition)
        assertTrue(close.speech!!.contains("coming up"), close.speech)

        // Within at-anchor range (1m out): the final stage, also not throttled.
        val atTurn = producer.onFix(3_000L, poly, 99.0, TravelDirection.Forward, Units.IMPERIAL)
        assertTrue(atTurn.speech != null, "at-turn cue must not be throttled")
        assertTrue(atTurn.disposition.startsWith("speak:at_turn_"), atTurn.disposition)
        assertEquals("Turn right", atTurn.speech)

        // All three stages given -- nothing left to say for this anchor.
        val done = producer.onFix(4_000L, poly, 100.0, TravelDirection.Forward, Units.IMPERIAL)
        assertNull(done.speech, "every stage already given for this anchor")
        assertEquals("bail:already_announced", done.disposition)
    }

    @Test
    fun skipsStraightToAtTurnWhenAFixLandsPastCloseRange() {
        // A sparse fix sequence (fast walking pace, or a dropped fix) can land past the close-range
        // window entirely -- nothing is gained by a "coming up" cue for a corner already reached, so
        // this jumps directly from approach to at-turn rather than getting stuck expecting close first.
        val producer = BendCueProducer()
        val poly = corner()

        producer.onFix(0L, poly, 0.0, TravelDirection.Forward, Units.IMPERIAL)
        val jumped = producer.onFix(1_000L, poly, 99.0, TravelDirection.Forward, Units.IMPERIAL)
        assertTrue(jumped.speech != null)
        assertTrue(jumped.disposition.startsWith("speak:at_turn_"), jumped.disposition)
    }

    @Test
    fun aGentleBendUnderThresholdStaysSilent() {
        // A very shallow deflection -- not gentle enough to be filtered by scan range, but well
        // under the angle threshold once measured.
        val points =
            (0..30).map { i ->
                val m = i * 10.0
                // A barely-perceptible drift east as it goes north -- sagitta tiny, turn angle tiny.
                LatLng(latFor(m), -105.0 + lonOffsetFor(m * 0.01))
            }
        val poly = TrailPolyline(points)
        val producer = BendCueProducer()
        val cue = producer.onFix(0L, poly, 0.0, TravelDirection.Forward, Units.IMPERIAL)
        assertNull(cue.speech, "a near-straight drift is not a turn worth announcing")
    }
}
