package com.boldexplorer.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioFocusControllerTest {
    @Test
    fun rareMode_requestsAndReleasesPerCue() {
        val actions = mutableListOf<String>()
        val entries = mutableListOf<AudioLogEntry>()
        var nowMs = 1_000L
        val backend = RecordingFocusBackend(actions, AudioFocusRequestResult.GRANTED)
        val controller = AudioFocusController(backend, FakeAudioEventLog(entries::add)) { nowMs }

        val mode = controller.requestForCue(cue = "DirectionalBeacon")
        assertEquals(CueAudioMode.DUCK, mode)
        nowMs = 1_125L
        controller.releaseAfterCue()

        assertEquals(listOf("focus-request", "focus-abandon"), actions)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.kind == AudioLogEntry.Kind.AUDIO_FOCUS })
        assertEquals("result=GRANTED", entries[0].outputs)
        assertEquals("result=ABANDONED, heldMs=125", entries[1].outputs)

        // The next cue requests fresh — nothing was left held.
        val secondMode = controller.requestForCue(cue = "DirectionalBeacon")
        assertEquals(CueAudioMode.DUCK, secondMode)
        assertEquals(listOf("focus-request", "focus-abandon", "focus-request"), actions)
    }

    @Test
    fun deniedRequest_fallsBackToMixingWithoutDroppingTheCue() {
        val actions = mutableListOf<String>()
        val entries = mutableListOf<AudioLogEntry>()
        val backend = RecordingFocusBackend(actions, AudioFocusRequestResult.FAILED)
        val controller = AudioFocusController(backend, FakeAudioEventLog(entries::add)) { 1_000L }

        val mode = controller.requestForCue(cue = "AlignmentPing")

        assertEquals(CueAudioMode.MIX_FOCUS_DENIED, mode)
        assertEquals(listOf("focus-request"), actions)
        assertEquals(1, entries.size)
        assertEquals("result=FAILED", entries.single().outputs)
    }

    @Test
    fun frequentMode_holdsLeaseAcrossConsecutiveCuesWithoutASecondRequest() {
        val actions = mutableListOf<String>()
        val entries = mutableListOf<AudioLogEntry>()
        val backend = RecordingFocusBackend(actions, AudioFocusRequestResult.GRANTED)
        val controller = AudioFocusController(backend, FakeAudioEventLog(entries::add)) { 1_000L }

        controller.setFrequentMode(true)
        val first = controller.requestForCue(cue = "AlignmentPing")
        controller.releaseAfterCue() // no-op while frequent
        val second = controller.requestForCue(cue = "AlignmentPing")
        controller.releaseAfterCue()

        assertEquals(CueAudioMode.DUCK, first)
        assertEquals(CueAudioMode.DUCK, second)
        assertEquals(listOf("focus-request"), actions, "one request covers the whole run")
    }

    @Test
    fun leavingFrequentMode_releasesTheHeldLease() {
        val actions = mutableListOf<String>()
        val entries = mutableListOf<AudioLogEntry>()
        val backend = RecordingFocusBackend(actions, AudioFocusRequestResult.GRANTED)
        val controller = AudioFocusController(backend, FakeAudioEventLog(entries::add)) { 1_000L }
        var pausedReasons = mutableListOf<String>()
        controller.onFocusLostOrModeChange = { pausedReasons.add(it) }

        controller.setFrequentMode(true)
        controller.requestForCue(cue = "AlignmentPing")
        controller.setFrequentMode(false)

        assertEquals(listOf("focus-request", "focus-abandon"), actions)
        assertEquals(listOf("mode_change_to_rare"), pausedReasons)
        assertFalse(controller.frequentModeActive)
    }

    @Test
    fun rareCueDuringAFrequentRun_usesTheHeldLeaseAndDoesNotReleaseIt() {
        val actions = mutableListOf<String>()
        val entries = mutableListOf<AudioLogEntry>()
        val backend = RecordingFocusBackend(actions, AudioFocusRequestResult.GRANTED)
        val controller = AudioFocusController(backend, FakeAudioEventLog(entries::add)) { 1_000L }

        controller.setFrequentMode(true)
        controller.requestForCue(cue = "AlignmentPing")
        controller.releaseAfterCue()
        // A Rare cue (e.g. DirectionalBeacon) interleaved mid-run must not force a release.
        val mode = controller.requestForCue(cue = "DirectionalBeacon")
        controller.releaseAfterCue()

        assertEquals(CueAudioMode.DUCK, mode)
        assertEquals(listOf("focus-request"), actions, "still holding — no second request, no release")
    }

    @Test
    fun lostTransient_clearsHoldingAndSignalsWithoutEndingTheSession() {
        val actions = mutableListOf<String>()
        val entries = mutableListOf<AudioLogEntry>()
        var capturedOnFocusChange: ((AudioFocusChange) -> Unit)? = null
        val backend =
            object : AudioFocusBackend {
                override fun requestTransientMayDuck(onFocusChange: (AudioFocusChange) -> Unit): AudioFocusRequestResult {
                    actions += "focus-request"
                    capturedOnFocusChange = onFocusChange
                    return AudioFocusRequestResult.GRANTED
                }

                override fun abandonTransientMayDuck() {
                    actions += "focus-abandon"
                }
            }
        val controller = AudioFocusController(backend, FakeAudioEventLog(entries::add)) { 1_000L }
        val pausedReasons = mutableListOf<String>()
        controller.onFocusLostOrModeChange = { pausedReasons.add(it) }

        controller.setFrequentMode(true)
        controller.requestForCue(cue = "AlignmentPing")
        assertTrue(controller.frequentModeActive)

        capturedOnFocusChange?.invoke(AudioFocusChange.LostTransient)

        assertEquals(listOf("focus_lost"), pausedReasons)
        assertTrue(controller.frequentModeActive, "losing focus does not end frequent mode")
        // Deliberately no abandon(): staying registered is what makes Android's automatic
        // AUDIOFOCUS_GAIN callback land once the transient interrupter releases.
        assertEquals(listOf("focus-request"), actions)

        // The next cue does NOT re-request (#108: repeated re-requests during an outstanding
        // transient loss get silently throttled by the platform's own background-focus-request
        // hardening, field-confirmed) — still registered, waiting for the automatic regain.
        val mode = controller.requestForCue(cue = "AlignmentPing")
        assertEquals(CueAudioMode.SUPPRESSED_FOCUS_LOST, mode)
        assertEquals(listOf("focus-request"), actions, "no new request while still awaiting regain")
    }

    @Test
    fun awaitingRegain_eventuallyResumesOnceGainedArrivesWithoutEverReRequesting() {
        val actions = mutableListOf<String>()
        val entries = mutableListOf<AudioLogEntry>()
        var capturedOnFocusChange: ((AudioFocusChange) -> Unit)? = null
        val backend =
            object : AudioFocusBackend {
                override fun requestTransientMayDuck(onFocusChange: (AudioFocusChange) -> Unit): AudioFocusRequestResult {
                    actions += "focus-request"
                    capturedOnFocusChange = onFocusChange
                    return AudioFocusRequestResult.GRANTED
                }

                override fun abandonTransientMayDuck() {
                    actions += "focus-abandon"
                }
            }
        val controller = AudioFocusController(backend, FakeAudioEventLog(entries::add)) { 1_000L }

        controller.setFrequentMode(true)
        controller.requestForCue(cue = "AlignmentPing")
        capturedOnFocusChange?.invoke(AudioFocusChange.LostTransient)

        // Several cues fire while the interruption is still ongoing (#108's field repro:
        // AlignmentPing at ~1 Hz for several seconds) — none of them should re-request.
        repeat(5) {
            assertEquals(CueAudioMode.SUPPRESSED_FOCUS_LOST, controller.requestForCue(cue = "AlignmentPing"))
        }
        assertEquals(listOf("focus-request"), actions, "no re-requests while awaiting regain")

        capturedOnFocusChange?.invoke(AudioFocusChange.Gained)
        val mode = controller.requestForCue(cue = "AlignmentPing")

        assertEquals(CueAudioMode.DUCK, mode)
        assertEquals(listOf("focus-request"), actions, "regain resumes without any new request")
    }

    @Test
    fun permanentLoss_abandonsCleanlyUnlikeTransientLoss() {
        val actions = mutableListOf<String>()
        val entries = mutableListOf<AudioLogEntry>()
        var capturedOnFocusChange: ((AudioFocusChange) -> Unit)? = null
        val backend =
            object : AudioFocusBackend {
                override fun requestTransientMayDuck(onFocusChange: (AudioFocusChange) -> Unit): AudioFocusRequestResult {
                    actions += "focus-request"
                    capturedOnFocusChange = onFocusChange
                    return AudioFocusRequestResult.GRANTED
                }

                override fun abandonTransientMayDuck() {
                    actions += "focus-abandon"
                }
            }
        val controller = AudioFocusController(backend, FakeAudioEventLog(entries::add)) { 1_000L }
        val pausedReasons = mutableListOf<String>()
        controller.onFocusLostOrModeChange = { pausedReasons.add(it) }

        controller.setFrequentMode(true)
        controller.requestForCue(cue = "AlignmentPing")

        capturedOnFocusChange?.invoke(AudioFocusChange.Lost)

        assertEquals(listOf("focus_lost"), pausedReasons)
        assertTrue(controller.frequentModeActive, "permanent loss still doesn't end frequent mode")
        // Unlike LostTransient, a permanent loss abandons right away — no automatic GAIN is coming.
        assertEquals(listOf("focus-request", "focus-abandon"), actions)
    }

    @Test
    fun gained_setsHoldingBackToTrueWithoutANewRequest() {
        val actions = mutableListOf<String>()
        val entries = mutableListOf<AudioLogEntry>()
        var capturedOnFocusChange: ((AudioFocusChange) -> Unit)? = null
        val backend =
            object : AudioFocusBackend {
                override fun requestTransientMayDuck(onFocusChange: (AudioFocusChange) -> Unit): AudioFocusRequestResult {
                    actions += "focus-request"
                    capturedOnFocusChange = onFocusChange
                    return AudioFocusRequestResult.GRANTED
                }

                override fun abandonTransientMayDuck() {
                    actions += "focus-abandon"
                }
            }
        val controller = AudioFocusController(backend, FakeAudioEventLog(entries::add)) { 1_000L }

        controller.setFrequentMode(true)
        controller.requestForCue(cue = "AlignmentPing")
        capturedOnFocusChange?.invoke(AudioFocusChange.LostTransient)
        capturedOnFocusChange?.invoke(AudioFocusChange.Gained)

        // Regained without us re-requesting — the next cue sees "already holding".
        val mode = controller.requestForCue(cue = "AlignmentPing")

        assertEquals(CueAudioMode.DUCK, mode)
        assertEquals(listOf("focus-request"), actions, "regain needs no new request")
    }

    @Test
    fun close_releasesAndClearsFrequentMode() {
        val actions = mutableListOf<String>()
        val entries = mutableListOf<AudioLogEntry>()
        val backend = RecordingFocusBackend(actions, AudioFocusRequestResult.GRANTED)
        val controller = AudioFocusController(backend, FakeAudioEventLog(entries::add)) { 1_000L }

        controller.setFrequentMode(true)
        controller.requestForCue(cue = "AlignmentPing")
        controller.close()

        assertEquals(listOf("focus-request", "focus-abandon"), actions)
        assertFalse(controller.frequentModeActive)
    }

    private class RecordingFocusBackend(
        private val actions: MutableList<String>,
        private val requestResult: AudioFocusRequestResult,
    ) : AudioFocusBackend {
        override fun requestTransientMayDuck(onFocusChange: (AudioFocusChange) -> Unit): AudioFocusRequestResult {
            actions += "focus-request"
            return requestResult
        }

        override fun abandonTransientMayDuck() {
            actions += "focus-abandon"
        }
    }
}
