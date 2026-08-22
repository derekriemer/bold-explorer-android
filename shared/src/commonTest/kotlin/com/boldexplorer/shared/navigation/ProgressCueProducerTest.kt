package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.settings.Units
import kotlin.test.Test
import kotlin.test.assertEquals
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
        travelled: Boolean = true,
    ) = producer.onFix(nowMs, straight, alongTrackM = alongTrackM, remainingM = remainingM,
        direction = direction, units = Units.IMPERIAL, lastSpokeAtMs = lastSpokeAtMs, matchLost = matchLost,
        travelled = travelled)

    @Test
    fun theSpeechCadenceHoldsAfterTheFirstFix() {
        val producer = ProgressCueProducer()

        assertNotNull(produce(producer, 0L).speech)
        assertNull(produce(producer, 5_000L).speech, "speech cadence is 15 s")
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
            matchLost = false, travelled = true)

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
            matchLost = false, travelled = true)

        assertNotNull(cue.speech, "the corner is behind a reverse follow, not ahead")
    }

    @Test
    fun speechIsSilentBeforeTheMatchHasEverConfirmedAPosition() {
        // A follow started 200 m from the trailhead: alongTrackM is null because nothing has been
        // confirmed yet.
        val cue = produce(ProgressCueProducer(), 0L, alongTrackM = null, remainingM = null)

        assertNull(cue.speech, "there is nothing to report yet")
    }

    @Test
    fun speechIsSuppressedWhileTheMatchIsLost() {
        // confirmedAlongM/remainingM freeze the instant the match stops being Matched, so reading
        // them out during a dropout is reading out a stale number with full confidence.
        val cue = produce(ProgressCueProducer(), 0L, matchLost = true)

        assertNull(cue.speech, "a frozen distance must not be read out as if it were current")
    }

    @Test
    fun speechStillFiresInTheOrdinaryMatchedCase() {
        val cue = produce(ProgressCueProducer(), 0L, matchLost = false, alongTrackM = 100.0, remainingM = 400.0)

        assertNotNull(cue.speech, "matched, confirmed, and nothing else suppressing it")
    }

    // #91: a follow that armed near the wrong end of a loop (the #80 case) can read a near-zero
    // remaining distance on its very first fix, before the walker has gone anywhere. A "0 feet to
    // go" reading is only trustworthy on the same travel evidence that would let completion fire.

    @Test
    fun zeroDistanceIsSilencedWithoutTravelEvidence() {
        val cue = produce(ProgressCueProducer(), 0L, remainingM = 0.05, travelled = false)

        assertNull(cue.speech, "no confirmed travel this session; a near-zero reading is not trustworthy")
    }

    @Test
    fun zeroDistanceStaysSilentAcrossRepeatedFixesWhileUndertravelled() {
        // The false-arrival bug was five *consecutive* announcements, not just the first one.
        val producer = ProgressCueProducer()
        produce(producer, 0L, remainingM = 0.05, travelled = false)

        val cue = produce(producer, 15_000L, remainingM = 0.05, travelled = false)

        assertNull(cue.speech, "still no travel evidence on the next cadence tick")
    }

    @Test
    fun zeroDistanceSpeaksOnceTravelEvidenceClears() {
        // The legitimate-arrival case: the walker actually walked the trail, so a near-zero reading
        // right before completion fires is exactly the information they need.
        val cue = produce(ProgressCueProducer(), 0L, remainingM = 0.05, travelled = true)

        assertNotNull(cue.speech, "confirmed travel backs up the near-zero reading")
    }

    @Test
    fun nonZeroRemainingIgnoresTravelEvidence() {
        // The guard is specific to the "nothing left" claim; an ordinary distance readout must not
        // start requiring travel evidence it never needed before.
        val cue = produce(ProgressCueProducer(), 0L, remainingM = 400.0, travelled = false)

        assertNotNull(cue.speech, "travel evidence only gates a zero-distance reading")
    }

    // #85: silence used to be indistinguishable between four causes. Each bail now names itself.

    @Test
    fun dispositionExplainsANotDueBail() {
        val producer = ProgressCueProducer()
        produce(producer, 0L)

        assertEquals("bail:not_due", produce(producer, 5_000L).disposition)
    }

    @Test
    fun dispositionExplainsAYieldBail() {
        val cue = produce(ProgressCueProducer(), 20_000L, lastSpokeAtMs = 18_000L)

        assertEquals("bail:yield_2000ms", cue.disposition)
    }

    @Test
    fun dispositionExplainsAMatchLostBail() {
        val cue = produce(ProgressCueProducer(), 0L, matchLost = true)

        assertEquals("bail:match_lost", cue.disposition)
    }

    @Test
    fun dispositionExplainsAnUnconfirmedBail() {
        val cue = produce(ProgressCueProducer(), 0L, alongTrackM = null, remainingM = null)

        assertEquals("bail:unconfirmed", cue.disposition)
    }

    @Test
    fun dispositionExplainsANotStraightBailWithTheSagittaValue() {
        // Same corner fixture as itStaysQuietWhereTheTrailIsNotStraight, so the sagitta value in the
        // disposition is a real, non-trivial number, not zero.
        val north = (0..10).map { LatLng(latFor(it * 20.0), 0.0) }
        val east = (1..10).map { LatLng(latFor(200.0), it * 20.0 / 111_194.9) }
        val corner = TrailPolyline(north + east)

        val cue = ProgressCueProducer().onFix(0L, corner, alongTrackM = 170.0, remainingM = 200.0,
            direction = TravelDirection.Forward, units = Units.IMPERIAL, lastSpokeAtMs = Long.MIN_VALUE,
            matchLost = false, travelled = true)

        assertTrue(cue.disposition.startsWith("bail:not_straight_sagitta_"), cue.disposition)
        assertTrue(cue.disposition.endsWith("m_over_4m"), cue.disposition)
    }

    @Test
    fun dispositionExplainsAnUntrustedZeroBail() {
        val cue = produce(ProgressCueProducer(), 0L, remainingM = 0.05, travelled = false)

        assertEquals("bail:untrusted_zero_remaining_0m", cue.disposition)
    }

    @Test
    fun dispositionRecordsTheSpokenDistanceOnSuccess() {
        val cue = produce(ProgressCueProducer(), 0L, remainingM = 640.0)

        assertEquals("speak:remaining_640m", cue.disposition)
    }
}
