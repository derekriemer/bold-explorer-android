package com.boldexplorer.audio

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AudioFocusControllerTest {
    @Test
    fun duckMode_holdsFocusAcrossThePlaybackOperation() =
        runTest {
            val actions = mutableListOf<String>()
            val entries = mutableListOf<AudioLogEntry>()
            var nowMs = 1_000L
            val backend = RecordingFocusBackend(actions, AudioFocusRequestResult.GRANTED)
            val controller = AudioFocusController(backend, FakeAudioEventLog(entries::add)) { nowMs }

            val mode =
                controller.play(duckAudioEnabled = true, cue = "DirectionalBeacon") {
                    actions += "playback-start"
                    nowMs = 1_125L
                    actions += "playback-complete"
                }

            assertEquals(CueAudioMode.DUCK, mode)
            assertEquals(
                listOf("focus-request", "playback-start", "playback-complete", "focus-abandon"),
                actions,
                "focus must bracket completed playback, not merely dispatch",
            )
            assertEquals(2, entries.size)
            assertTrue(entries.all { it.kind == AudioLogEntry.Kind.AUDIO_FOCUS })
            assertEquals("result=GRANTED", entries[0].outputs)
            assertEquals("result=ABANDONED, heldMs=125", entries[1].outputs)
        }

    @Test
    fun mixMode_neverTouchesAudioFocus() =
        runTest {
            val actions = mutableListOf<String>()
            val entries = mutableListOf<AudioLogEntry>()
            val backend = RecordingFocusBackend(actions, AudioFocusRequestResult.GRANTED)
            val controller = AudioFocusController(backend, FakeAudioEventLog(entries::add)) { 1_000L }

            val mode =
                controller.play(duckAudioEnabled = false, cue = "TrailComplete") {
                    actions += "playback"
                }

            assertEquals(CueAudioMode.MIX, mode)
            assertEquals(listOf("playback"), actions)
            assertTrue(entries.isEmpty(), "mixing has no focus transition to log")
        }

    @Test
    fun deniedDuckRequest_fallsBackToMixingWithoutDroppingTheCue() =
        runTest {
            val actions = mutableListOf<String>()
            val entries = mutableListOf<AudioLogEntry>()
            val backend = RecordingFocusBackend(actions, AudioFocusRequestResult.FAILED)
            val controller = AudioFocusController(backend, FakeAudioEventLog(entries::add)) { 1_000L }

            val mode =
                controller.play(duckAudioEnabled = true, cue = "AlignmentPing") {
                    actions += "playback"
                }

            assertEquals(CueAudioMode.MIX_FOCUS_DENIED, mode)
            assertEquals(listOf("focus-request", "playback"), actions)
            assertEquals(1, entries.size)
            assertEquals("result=FAILED", entries.single().outputs)
        }

    @Test
    fun duckMode_releasesFocusWhenPlaybackFails() =
        runTest {
            val actions = mutableListOf<String>()
            val backend = RecordingFocusBackend(actions, AudioFocusRequestResult.GRANTED)
            val controller = AudioFocusController(backend, FakeAudioEventLog {}) { 1_000L }

            assertFailsWith<IllegalStateException> {
                controller.play(duckAudioEnabled = true, cue = "WrongVector") {
                    actions += "playback-failed"
                    error("AudioTrack failed")
                }
            }

            assertEquals(listOf("focus-request", "playback-failed", "focus-abandon"), actions)
        }

    private class RecordingFocusBackend(
        private val actions: MutableList<String>,
        private val requestResult: AudioFocusRequestResult,
    ) : AudioFocusBackend {
        override fun requestTransientMayDuck(): AudioFocusRequestResult {
            actions += "focus-request"
            return requestResult
        }

        override fun abandonTransientMayDuck() {
            actions += "focus-abandon"
        }
    }
}
