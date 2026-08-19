package com.boldexplorer.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class CueOutputLifecycleTest {
    @Test
    fun cueOutput_logsOneBoundedTrackLifetime() {
        val entries = mutableListOf<AudioLogEntry>()
        var nowMs = 1_000L
        val lifecycle = CueOutputLifecycle(FakeAudioEventLog(entries::add)) { nowMs }

        val lease = lifecycle.started("DirectionalBeacon", preRollMs = 60)
        nowMs = 1_175L
        lifecycle.stopped(lease, reason = "cue_complete")

        assertEquals(2, entries.size)
        assertEquals(AudioLogEntry.Kind.AUDIO_OUTPUT, entries[0].kind)
        assertEquals("state=STARTED", entries[0].outputs)
        assertEquals("state=STOPPED, reason=cue_complete, activeMs=175", entries[1].outputs)
    }

    @Test
    fun unavailableTrack_isLoggedWithoutClaimingPlaybackStarted() {
        val entries = mutableListOf<AudioLogEntry>()
        val lifecycle = CueOutputLifecycle(FakeAudioEventLog(entries::add)) { 1_000L }

        lifecycle.unavailable("AccuracyBeacon", reason = "track_create_failed")

        assertEquals(1, entries.size)
        assertEquals(AudioLogEntry.Kind.AUDIO_OUTPUT, entries.single().kind)
        assertEquals("state=UNAVAILABLE, reason=track_create_failed", entries.single().outputs)
    }
}
