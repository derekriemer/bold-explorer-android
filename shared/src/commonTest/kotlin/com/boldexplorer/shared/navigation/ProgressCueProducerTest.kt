package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.settings.Units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressCueProducerTest {
    private fun latFor(m: Double) = m / 111_194.9

    private val straight = TrailPolyline((0..30).map { LatLng(latFor(it * 20.0), 0.0) })

    private fun produce(
        producer: ProgressCueProducer,
        nowMs: Long,
        remainingM: Double? = 400.0,
        direction: TravelDirection = TravelDirection.Forward,
        lastSpokeAtMs: Long = Long.MIN_VALUE,
        alongTrackM: Double? = 100.0,
        matchLost: Boolean = false,
    ) = producer.onFix(nowMs, straight, alongTrackM = alongTrackM, remainingM = remainingM,
        direction = direction, units = Units.IMPERIAL, lastSpokeAtMs = lastSpokeAtMs, matchLost = matchLost)

    @Test
    fun theEarconRunsFasterThanTheSpeech() {
        val producer = ProgressCueProducer()

        val first = produce(producer, 0L)
        assertTrue(first.earcon, "the first fix establishes presence")
        assertNotNull(first.speech)

        assertFalse(produce(producer, 2_000L).earcon, "inside the earcon interval")
        assertTrue(produce(producer, 5_000L).earcon)
        assertNull(produce(producer, 5_000L).speech, "speech is slower than the beep")
        assertNotNull(produce(producer, 15_000L).speech)
    }

    @Test
    fun theSpeechIsRemainingDistanceInTheUsersUnits() {
        // Metres are internal and are spoken nowhere; the default user is on imperial.
        val cue = produce(ProgressCueProducer(), 0L, remainingM = 2000.0)
        assertEquals("1.2 miles to go", cue.speech)
    }

    @Test
    fun itYieldsToAnythingThatJustSpoke() {
        // The progress cue is the most frequent thing in the mix and must never talk over an alert.
        val producer = ProgressCueProducer()
        val cue = produce(producer, 20_000L, lastSpokeAtMs = 18_000L)

        assertNull(cue.speech, "something spoke 2 s ago")
        assertTrue(cue.earcon, "the beep still carries presence — it does not collide with speech")
    }

    @Test
    fun itStaysQuietWhereTheTrailIsNotStraight() {
        // A corner deserves the bend cue S8 will add, not a distance readout.
        val north = (0..10).map { LatLng(latFor(it * 20.0), 0.0) }
        val east = (1..10).map { LatLng(latFor(200.0), it * 20.0 / 111_194.9) }
        val corner = TrailPolyline(north + east)
        val producer = ProgressCueProducer()

        // 170, not 160: the corner vertex sits at exactly cumulative 200 m, and
        // TrailPolyline.sagittaOver only inspects vertices *strictly* inside the lookahead window —
        // at 160 m the window's far edge (160 + STRAIGHT_LOOKAHEAD_M = 200) lands exactly on the
        // corner, which the strict inequality then excludes, defeating the very case this test
        // means to cover. 170 m puts the corner substantially inside [170, 210).
        val cue = producer.onFix(0L, corner, alongTrackM = 170.0, remainingM = 200.0,
            direction = TravelDirection.Forward, units = Units.IMPERIAL, lastSpokeAtMs = Long.MIN_VALUE,
            matchLost = false)

        assertNull(cue.speech, "a corner is 30 m ahead")
    }

    @Test
    fun aReverseFollowIsNotSilencedByACornerItHasAlreadyPassed() {
        // Same corner trail as above: north 0..200 m, then east. At 170 m the corner (200 m) sits
        // at a *higher* along-track reading, which is behind a reverse follow, not ahead of it — so
        // the progress cue must not be suppressed by it.
        val north = (0..10).map { LatLng(latFor(it * 20.0), 0.0) }
        val east = (1..10).map { LatLng(latFor(200.0), it * 20.0 / 111_194.9) }
        val corner = TrailPolyline(north + east)
        val producer = ProgressCueProducer()

        val cue = producer.onFix(0L, corner, alongTrackM = 170.0, remainingM = 170.0,
            direction = TravelDirection.Reverse, units = Units.IMPERIAL, lastSpokeAtMs = Long.MIN_VALUE,
            matchLost = false)

        assertNotNull(cue.speech, "the corner is behind a reverse follow, not ahead")
    }

    @Test
    fun theEarconFiresBeforeTheMatchHasEverConfirmedAPosition() {
        // A follow started 200 m from the trailhead: alongTrackM is null because nothing has been
        // confirmed yet. Silence here reads to a blind user as the app having crashed, so the
        // earcon — presence, not position — must still fire.
        val cue = produce(ProgressCueProducer(), 0L, alongTrackM = null, remainingM = null)

        assertTrue(cue.earcon, "presence does not require a confirmed position")
        assertNull(cue.speech, "there is nothing to report yet")
    }

    @Test
    fun theEarconFiresWhileTheMatchIsLost() {
        // ADR 0001: "the progress earcon keeps its cadence but changes character" while lost —
        // it must not go silent just because the match currently disagrees with itself.
        val cue = produce(ProgressCueProducer(), 0L, matchLost = true)

        assertTrue(cue.earcon, "the earcon must not go silent during a dropout")
    }

    @Test
    fun speechIsSuppressedWhileTheMatchIsLostButTheEarconStillFires() {
        // confirmedAlongM/remainingM freeze the instant the match stops being Matched, so reading
        // them out during a dropout is reading out a stale number with full confidence.
        val cue = produce(ProgressCueProducer(), 0L, matchLost = true)

        assertNull(cue.speech, "a frozen distance must not be read out as if it were current")
        assertTrue(cue.earcon, "the earcon is unaffected by the speech gate")
    }

    @Test
    fun speechStillFiresInTheOrdinaryMatchedCase() {
        val cue = produce(ProgressCueProducer(), 0L, matchLost = false, alongTrackM = 100.0, remainingM = 400.0)

        assertNotNull(cue.speech, "matched, confirmed, and nothing else suppressing it")
    }
}
