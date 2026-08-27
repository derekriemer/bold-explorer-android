package com.boldexplorer.audio

import com.boldexplorer.shared.audio.CueCadence
import kotlin.test.Test
import kotlin.test.assertEquals

class CueOutputLifecycleTest {
    @Test
    fun session_logsStartAndEnd() {
        val entries = mutableListOf<AudioLogEntry>()
        val lifecycle = CueOutputLifecycle(FakeAudioEventLog(entries::add)) { 1_000L }

        lifecycle.sessionStarted()
        lifecycle.sessionEnded("navigation_stop")

        assertEquals(2, entries.size)
        assertEquals(AudioLogEntry.Kind.AUDIO_OUTPUT, entries[0].kind)
        assertEquals("state=SESSION_STARTED", entries[0].outputs)
        assertEquals("state=SESSION_ENDED, reason=navigation_stop", entries[1].outputs)
    }

    @Test
    fun track_logsOneBoundedLifetimeAcrossReopens() {
        val entries = mutableListOf<AudioLogEntry>()
        var nowMs = 1_000L
        val lifecycle = CueOutputLifecycle(FakeAudioEventLog(entries::add)) { nowMs }

        val lease = lifecycle.trackOpened("session_open", warmupBudgetMs = 3_000L)
        nowMs = 1_175L
        lifecycle.trackClosed(lease, reason = "reopen_after_error")

        assertEquals(2, entries.size)
        assertEquals("state=TRACK_OPENED", entries[0].outputs)
        assertEquals("state=TRACK_CLOSED, activeMs=175", entries[1].outputs)
    }

    @Test
    fun trackPaused_isDistinctFromTrackClosed() {
        val entries = mutableListOf<AudioLogEntry>()
        val lifecycle = CueOutputLifecycle(FakeAudioEventLog(entries::add)) { 1_000L }

        lifecycle.trackPaused("focus_lost")

        assertEquals(1, entries.size)
        assertEquals("focus_lost", entries.single().trigger)
        assertEquals("state=TRACK_PAUSED", entries.single().outputs)
    }

    @Test
    fun cuePlayed_carriesCueCadenceAndOutcomeIndependentOfTrackChurn() {
        val entries = mutableListOf<AudioLogEntry>()
        val lifecycle = CueOutputLifecycle(FakeAudioEventLog(entries::add)) { 1_000L }

        lifecycle.cuePlayed("AlignmentPing", CueCadence.Frequent, outcome = "timeout")

        val entry = entries.single()
        assertEquals("AlignmentPing", entry.trigger)
        assertEquals("action=cue_play, cadence=Frequent", entry.inputs)
        assertEquals("state=CUE_OUTCOME, outcome=timeout", entry.outputs)
    }

    @Test
    fun unavailableTrack_isLoggedWithoutClaimingPlaybackStarted() {
        val entries = mutableListOf<AudioLogEntry>()
        val lifecycle = CueOutputLifecycle(FakeAudioEventLog(entries::add)) { 1_000L }

        lifecycle.unavailable("AlignmentPing", reason = "track_create_failed")

        assertEquals(1, entries.size)
        assertEquals(AudioLogEntry.Kind.AUDIO_OUTPUT, entries.single().kind)
        assertEquals("state=UNAVAILABLE, reason=track_create_failed", entries.single().outputs)
    }
}
