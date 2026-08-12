package com.boldexplorer.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A span that loses its closing bracket is worse than no span, so the pairing is what these pin.
 *
 * [AudioEventLog] needs a Context, so these exercise [MarkerSpanRecorder] through a recording
 * double. That is enough: what matters here is which entries get written and how they pair, not how
 * they reach the disk.
 */
class MarkerSpanRecorderTest {
    private val written = mutableListOf<AudioLogEntry>()
    private val recorder = MarkerSpanRecorder(FakeAudioEventLog(written::add))

    private fun starts() = written.filter { it.trigger == MarkerSpanRecorder.TRIGGER_START }

    private fun ends() = written.filter { it.trigger == MarkerSpanRecorder.TRIGGER_END }

    @Test
    fun aSpanWritesAPairedStartAndEnd() {
        recorder.start("stop", nowMs = 1_000L)
        assertEquals("stop", recorder.openSpan.value?.label)
        recorder.end(nowMs = 245_000L)
        assertNull(recorder.openSpan.value)

        assertEquals(1, starts().size)
        assertEquals(1, ends().size)
        assertEquals(1_000L, starts().single().timestampMs)
        assertEquals(245_000L, ends().single().timestampMs)
        // Same span id on both halves, so pairs survive being read out of a file with other entries
        // interleaved between them — which, over four minutes at 1 Hz, they always will be.
        assertTrue(starts().single().outputs.contains("span=1"))
        assertTrue(ends().single().outputs.contains("span=1"))
        assertTrue(ends().single().outputs.contains("duration_ms=244000"))
    }

    @Test
    fun endingWithNothingOpenWritesNothing() {
        // A double press must not leave a stray end that a reader would pair with the wrong start.
        recorder.end(nowMs = 5_000L)
        assertTrue(written.isEmpty())
    }

    @Test
    fun startingASecondSpanClosesTheFirst() {
        // Nesting is deliberately unsupported, but the outer span still has to be closed rather than
        // dropped: losing data is a worse failure than an unexpected bracket.
        recorder.start("first", nowMs = 1_000L)
        recorder.start("second", nowMs = 10_000L)
        assertEquals("second", recorder.openSpan.value?.label)
        assertEquals(1, ends().size, "the first span was closed")
        assertEquals("first", ends().single().note)
        assertEquals(10_000L, ends().single().timestampMs, "closed at the moment the second opened")
        assertEquals(2, starts().size)
        assertTrue(starts()[1].outputs.contains("span=2"), "the new span gets its own id")
    }

    @Test
    fun aClockThatGoesBackwardsCannotProduceANegativeDuration() {
        recorder.start("odd", nowMs = 10_000L)
        recorder.end(nowMs = 9_000L)
        assertTrue(ends().single().outputs.contains("duration_ms=0"))
    }

    @Test
    fun theAnnouncementHasNoClockInIt() {
        // The live region carrying this must fire on open and close, and at no other time — see the
        // GPS degradation countdown, which announced itself once a second for thirty minutes.
        val open = OpenSpan(label = "bad gps", startedAtMs = 1_000L)
        assertEquals(spanAnnouncement(open), spanAnnouncement(open.copy()))
        assertTrue(spanAnnouncement(open).contains("bad gps"))
        assertTrue(spanAnnouncement(null).contains("No marked section"))
    }

    @Test
    fun theButtonSaysWhatPressingItWillDo() {
        assertEquals("Start marked section", spanButtonLabel(null))
        assertEquals("End: bad gps", spanButtonLabel(OpenSpan("bad gps", 0L)))
    }

    @Test
    fun elapsedTimeIsSpokenCoarsely() {
        assertEquals("45 seconds", spanElapsedText(45_000L))
        assertEquals("1 minute", spanElapsedText(60_000L))
        assertEquals("4 minutes 4 seconds", spanElapsedText(244_000L))
        assertEquals("0 seconds", spanElapsedText(-5L))
    }
}
