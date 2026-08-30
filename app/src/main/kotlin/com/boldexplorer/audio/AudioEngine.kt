package com.boldexplorer.audio

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.boldexplorer.shared.audio.CueCadence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.sin

private const val SAMPLE_RATE = 44100
private const val AMPLITUDE = 0.6f

// Safety net for #109: on at least one device/route, AlignmentPing's playbackHeadPosition never
// reached its cue's end frame — the completion-wait loop below spun until navigation stopped,
// holding toneMutex (and, transitively, AudioFocusController's lease) for up to 87s and silently
// blocking every other cue behind it. 2s is generous next to the longest cue (WrongVector, ~300ms)
// but short enough that a genuinely stuck track can never again hang the shared pipeline for more
// than a couple of seconds.
private const val COMPLETION_TIMEOUT_MS = 2_000L

// #114: after this many consecutive completion-timeouts on the same session-scoped track, treat it
// as wedged and recreate it, even without an exception. Not field-tuned — a guess.
private const val CONSECUTIVE_TIMEOUT_ESCALATION_THRESHOLD = 3

// Silence chunk written a) repeatedly by the frequent-mode filler between real cues, and b) by
// warmUpUntilActive() while confirming a paused/fresh track has actually been reactivated. Short
// enough that a real cue's write (sharing toneMutex) never waits long behind one in flight.
private const val SILENCE_CHUNK_MS = 100
private const val SILENCE_CHUNK_FRAMES = SAMPLE_RATE * SILENCE_CHUNK_MS / 1000

// #114: how long warmUpUntilActive() will keep writing silence and polling playbackHeadPosition for
// proof a paused/fresh track has been reactivated, before giving up. Field-observed reactivation
// latency ranged 400ms-2.3s in one trace; this replaces what used to be a fixed 60ms pre-roll guess
// (proven insufficient — see the class doc) with a bound generous enough to actually cover that
// range, at the cost of worse-case per-cue latency when a track really is stuck.
private const val WARMUP_TIMEOUT_MS = 3_000L

// #114: minimum wall-clock time warmUpUntilActive() insists on before treating confirmation as done,
// even if playbackHeadPosition moves sooner. AudioTrack.write() returns once there's buffer space,
// not once real time has actually elapsed — on a just-reactivated, empty-buffer track the very first
// write can be accepted (and position can tick) almost instantly, well under the physical settling
// time some hardware genuinely needs (field-confirmed clipped/dropped starts on a bone-conduction
// headset). This restores the old fixed pre-roll's floor as a minimum *underneath* the adaptive
// confirmation, instead of the confirmation replacing it outright.
private const val MIN_WARMUP_MS = 60L

/**
 * Session-scoped streaming earcon output (#114/#108).
 *
 * One [AudioTrack] is created lazily on the first cue of a session and kept open across every cue
 * dispatched until the session ends ([stop]) or the track errors/wedges — reopening a
 * Bluetooth-routed stream on every single cue is the mechanism #114's stall theory targets.
 *
 * **Three prior fixes for the remaining stall were tried and field-disproven before this one; see
 * #114's issue history for the traces.** Leaving a frequent-cadence run's track in
 * `PLAYSTATE_PLAYING` between cues (relying on natural underrun-to-silence) let AudioFlinger disable
 * the track — confirmed via `AudioFlinger`/`AudioTrack` logcat, on the plain speaker, not just
 * Bluetooth. Switching to an explicit `pause()`/`play()` cycle between every cue did *not* fix it:
 * the same `prepareTracks_l BUFFER TIMEOUT: ... due to underrun` eviction still fired repeatedly for
 * an explicitly-paused track, just without the extra `restartIfDisabled()` recovery tax layered on
 * top — AudioFlinger's periodic sweep does not appear to distinguish "app paused this on purpose"
 * from "app let this starve." [ensureSilenceFiller] fixes that for frequent-mode runs by never
 * letting the track go idle at all — confirmed field-clean. But rare-mode cues (`DirectionalBeacon`)
 * pause for the whole gap between cues (5s+), always longer than AudioFlinger's sweep interval, so
 * every rare-mode cue after the first resumes from evicted — and a **fixed** 60ms pre-roll guess
 * (the original mitigation for a "newly opened route") was field-disproven too: the actual
 * reactivation latency is genuinely variable (400ms-2.3s in one trace), racing a fixed guess against
 * a variable recovery time is exactly why it looked intermittent (some cues heard, most not) rather
 * than reliably broken. [warmUpUntilActive] replaces the guess with a confirmation: keep writing
 * silence and polling `playbackHeadPosition` for actual forward movement — proof AudioFlinger has
 * reactivated the track — before writing the real tone, bounded by its own timeout separate from the
 * tone's own completion wait. Field-confirmed the confirmation alone isn't sufficient either: on a
 * bone-conduction headset, position could tick forward — satisfying the confirmation — faster than
 * the hardware had actually physically settled, clipping the start of the very next real tone.
 * [MIN_WARMUP_MS] restores the old fixed pre-roll's floor as a minimum *underneath* the adaptive
 * confirmation rather than the confirmation replacing it outright. Not fully understood *why* this
 * device evicts so eagerly; every fix here routes around the confirmed symptom, not a mechanism
 * verified end to end.
 */
@Singleton
class AudioEngine
    @Inject
    constructor(
        private val outputLifecycle: CueOutputLifecycle,
    ) {
        private val toneMutex = Mutex()

        @Volatile
        private var activeSession: AudioSession? = null

        // Set just before an async pause (focus loss / mode change) that might race an in-flight
        // playCue()'s completion poll, so that poll's resulting timeout isn't mistaken for a wedged
        // track. Cleared at the start of the next playCue().
        @Volatile
        private var pausedExternally = false

        // Set once per session by open(); used to launch the silence filler. Not itself a signal of
        // whether the filler should be running — ensureSilenceFiller()/stopSilenceFiller() own that.
        @Volatile
        private var sessionScope: CoroutineScope? = null

        @Volatile
        private var fillerJob: Job? = null

        private val audioFormat =
            AudioFormat
                .Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

        private val silenceChunk = FloatArray(SILENCE_CHUNK_FRAMES * 2)

        /** Starts a session: resets bookkeeping. No track is created until the first cue. */
        fun open(scope: CoroutineScope) {
            activeSession = null
            pausedExternally = false
            sessionScope = scope
            outputLifecycle.sessionStarted()
        }

        /** Ends the session: closes and releases whatever track is active, if any. */
        fun stop() {
            stopSilenceFiller()
            sessionScope = null
            activeSession?.let { closeSession(it, reason = "navigation_stop") }
            outputLifecycle.sessionEnded("navigation_stop")
        }

        /** Immediate, synchronous — safe to call from the AudioManager focus-listener thread. */
        fun pauseForFocusLoss() = pauseCurrentTrackExternally(reason = "focus_lost")

        /** Frequent → rare mode transition: return to per-cue pause discipline. */
        fun pauseForModeChange() = pauseCurrentTrackExternally(reason = "mode_change_to_rare")

        /**
         * Both callers are reactive to something outside the ordinary cue lifecycle (a focus loss, or
         * frequent mode ending) — an ordinary rare-mode cue's own end-of-cue pause lives inside
         * playCue() itself instead, since cadence is data playCue() already has, not something a
         * caller should have to separately remember to act on (#114).
         */
        private fun pauseCurrentTrackExternally(reason: String) {
            val session = activeSession ?: return
            pausedExternally = true
            // A held-open route is exactly what something needing exclusive focus (dictation) needs
            // us to stop being — the filler must not keep writing through a focus loss or a
            // frequent→rare transition. The next Frequent-cadence cue restarts it (see playCue()).
            stopSilenceFiller()
            runCatching { session.track.pause() }
            // A paused track is cold the same way a just-created one is — the route may not be
            // settled by the time real audio resumes (field-confirmed: clipped/dropped tones, not
            // just a theoretical risk).
            session.needsPreRoll = true
            outputLifecycle.trackPaused(reason)
        }

        /**
         * Keeps a frequent-mode track fed with silence between real cues so it never sits idle long
         * enough for AudioFlinger to evict it from its active list (#114 — see class doc for why the
         * two cheaper fixes tried before this one didn't work). Idempotent; cheap to call every cue.
         */
        private fun ensureSilenceFiller() {
            if (fillerJob?.isActive == true) return
            val scope = sessionScope ?: return
            fillerJob =
                scope.launch(Dispatchers.IO) {
                    while (isActive) {
                        val session = activeSession
                        if (session == null) {
                            delay(SILENCE_CHUNK_MS.toLong())
                            continue
                        }
                        toneMutex.withLock {
                            if (activeSession === session) {
                                runCatching { session.track.play() }
                                writeFully(session, silenceChunk)
                            }
                        }
                    }
                }
        }

        private fun stopSilenceFiller() {
            fillerJob?.cancel()
            fillerJob = null
        }

        suspend fun playDirectionalBeacon(
            pan: Float,
            pitchHz: Double,
        ) {
            val left = (1f - pan).coerceIn(0f, 1f)
            val right = (1f + pan).coerceIn(0f, 1f)
            playCue(
                "DirectionalBeacon",
                CueCadence.Rare,
                Tone(pitchHz, durationMs = 100, leftVol = left, rightVol = right),
            )
        }

        suspend fun playAlignmentPing(
            pan: Float,
            pitchHz: Double,
        ) {
            val left = (1f - pan).coerceIn(0f, 1f)
            val right = (1f + pan).coerceIn(0f, 1f)
            playCue(
                "AlignmentPing",
                CueCadence.Frequent,
                Tone(pitchHz, durationMs = 80, leftVol = left, rightVol = right),
            )
        }

        /** Two descending tones (660 → 440 Hz) centered in both ears — "wrong direction" earcon. */
        suspend fun playWrongVector() =
            playCue(
                "WrongVector",
                CueCadence.Rare,
                Tone(660.0, durationMs = 120, leftVol = 0.7f, rightVol = 0.7f),
                Tone(440.0, durationMs = 120, leftVol = 0.7f, rightVol = 0.7f),
            )

        private suspend fun playCue(
            cue: String,
            cadence: CueCadence,
            vararg tones: Tone,
        ) = withContext(Dispatchers.IO) {
            // Idempotent and cheap — also the mechanism that restarts the filler after it was
            // stopped for a focus loss or a frequent→rare transition (see pauseCurrentTrack).
            if (cadence == CueCadence.Frequent) ensureSilenceFiller()
            val rendered =
                tones.map { tone ->
                    generateStereoSine(tone.frequencyHz, tone.durationMs, tone.leftVol, tone.rightVol)
                }
            toneMutex.withLock {
                pausedExternally = false
                val session = ensureSession(cue) ?: return@withLock
                if (runCatching { session.track.play() }.isFailure) {
                    closeSession(session, reason = "track_start_failed")
                    outputLifecycle.unavailable(cue, "track_start_failed")
                    return@withLock
                }

                var outcome = "interrupted"
                try {
                    if (session.needsPreRoll) {
                        // Folds into the same timeout/flush/escalation handling below as an ordinary
                        // completion timeout — a track that can't prove it's reactivated within its
                        // own budget is no more trustworthy than one that failed to finish a cue.
                        if (!warmUpUntilActive(session)) {
                            outcome = "timeout"
                            return@withLock
                        }
                        session.needsPreRoll = false
                    }
                    for (samples in rendered) {
                        if (!writeFully(session, samples)) {
                            outcome = "write_failed"
                            return@withLock
                        }
                    }
                    val cueEndFrame = session.progress.samplesWritten / 2L
                    val deadlineElapsedMs = SystemClock.elapsedRealtime() + COMPLETION_TIMEOUT_MS
                    while (activeSession === session) {
                        val playedFrames = playbackFramePosition(session) ?: run { outcome = "read_failed"; return@withLock }
                        if (playedFrames >= cueEndFrame) {
                            outcome = "cue_complete"
                            session.consecutiveTimeouts = 0
                            return@withLock
                        }
                        if (SystemClock.elapsedRealtime() >= deadlineElapsedMs) {
                            outcome = "timeout"
                            return@withLock
                        }
                        delay(5L)
                    }
                } finally {
                    if (!currentCoroutineContext().isActive && outcome != "cue_complete") {
                        outcome = "cancelled"
                    }
                    outputLifecycle.cuePlayed(cue, cadence, outcome)
                    if (outcome == "timeout" && !pausedExternally) {
                        // A timed-out cue's audio is not necessarily gone — it may still be queued in
                        // the track's buffer, genuinely draining slower than COMPLETION_TIMEOUT_MS.
                        // Leaving it there (as the on-demand design safely could, since a timed-out
                        // track was always immediately torn down) let it pile up behind every
                        // subsequent cue's writes on a session-scoped track: several cues' worth of
                        // backlog would eventually drain in one audible burst once the device caught
                        // up, surfacing as several quick beeps in a row rather than the intended
                        // silence. flush() drops whatever's still queued so the next cue starts clean.
                        // It resets the track's own head-position counter to zero, so the session's
                        // cumulative bookkeeping must reset with it, and — same as any other pause —
                        // the next write into it needs to re-confirm reactivation, not assume it.
                        runCatching {
                            session.track.pause()
                            session.track.flush()
                        }
                        session.progress.samplesWritten = 0L
                        session.progress.playbackHeadWrapOffset = 0L
                        session.progress.lastPlaybackHeadRaw = 0L
                        session.needsPreRoll = true
                        session.consecutiveTimeouts++
                        if (session.consecutiveTimeouts >= CONSECUTIVE_TIMEOUT_ESCALATION_THRESHOLD) {
                            closeSession(session, reason = "reopen_after_timeout_escalation")
                        }
                    } else if (outcome == "write_failed" || outcome == "read_failed") {
                        closeSession(session, reason = "reopen_after_error")
                    }
                    // Cadence, not the caller, decides whether a cue pauses when it's done — a caller
                    // that plays a cue directly (bypassing AudioCuePlayer.dispatch()) can't forget a
                    // separate follow-up call that doesn't exist (#114: exactly this shape of bug bit
                    // the debug screen's audio test). Skipped when the session already got closed above
                    // (nothing left to pause) or when an external pause/mode-change already raced this
                    // cue (pausedExternally) — don't fight a transition that already happened correctly.
                    if (cadence == CueCadence.Rare && !pausedExternally && activeSession === session) {
                        runCatching { session.track.pause() }
                        session.needsPreRoll = true
                        outputLifecycle.trackPaused("cue_end")
                    }
                }
            }
        }

        /**
         * Writes silence and waits for `playbackHeadPosition` to actually move — proof AudioFlinger
         * has reactivated a paused or freshly-created track — instead of guessing a fixed lead-in
         * duration. Requires both that confirmation *and* [MIN_WARMUP_MS] of real elapsed time:
         * `write()` accepting data (and position ticking) is not proof real time has passed for
         * hardware that needs to physically settle, only that AudioFlinger has buffer space. Must be
         * called while [toneMutex] is held.
         */
        private fun warmUpUntilActive(session: AudioSession): Boolean {
            val baseline = playbackFramePosition(session) ?: return false
            val startElapsedMs = SystemClock.elapsedRealtime()
            val deadlineElapsedMs = startElapsedMs + WARMUP_TIMEOUT_MS
            while (activeSession === session) {
                if (!writeFully(session, silenceChunk)) return false
                val pos = playbackFramePosition(session) ?: return false
                val nowElapsedMs = SystemClock.elapsedRealtime()
                if (pos > baseline && nowElapsedMs - startElapsedMs >= MIN_WARMUP_MS) return true
                if (nowElapsedMs >= deadlineElapsedMs) return false
            }
            return false
        }

        /** Must be called while [toneMutex] is held. Reuses [activeSession] if one exists. */
        private fun ensureSession(cue: String): AudioSession? {
            activeSession?.let { return it }
            val track = createTrack()
            if (track == null) {
                outputLifecycle.unavailable(cue, "track_create_failed")
                return null
            }
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                outputLifecycle.unavailable(cue, "track_uninitialized")
                return null
            }
            val session =
                AudioSession(
                    track = track,
                    lease = outputLifecycle.trackOpened("session_open", WARMUP_TIMEOUT_MS),
                )
            activeSession = session
            return session
        }

        private fun createTrack(): AudioTrack? {
            val minBufferBytes =
                AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                )
            if (minBufferBytes <= 0) return null
            return runCatching {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(beaconAudioAttributes())
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(maxOf(minBufferBytes, silenceChunk.size * Float.SIZE_BYTES))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull()
        }

        /** Must be called while [toneMutex] is held. */
        private fun writeFully(
            session: AudioSession,
            samples: FloatArray,
        ): Boolean {
            var offset = 0
            while (offset < samples.size && activeSession === session) {
                val count =
                    runCatching {
                        session.track.write(
                            samples,
                            offset,
                            samples.size - offset,
                            AudioTrack.WRITE_BLOCKING,
                        )
                    }.getOrElse { return false }
                if (count <= 0) return false
                offset += count
                session.progress.samplesWritten += count
            }
            return offset == samples.size
        }

        /** Expands AudioTrack's wrapping unsigned 32-bit counter into this session's cumulative frame count. */
        private fun playbackFramePosition(session: AudioSession): Long? {
            val progress = session.progress
            val raw =
                runCatching { session.track.playbackHeadPosition.toLong() and 0xffff_ffffL }
                    .getOrNull() ?: return null
            if (raw < progress.lastPlaybackHeadRaw) progress.playbackHeadWrapOffset += 1L shl 32
            progress.lastPlaybackHeadRaw = raw
            return progress.playbackHeadWrapOffset + raw
        }

        private fun closeSession(
            session: AudioSession,
            reason: String,
        ) {
            if (activeSession !== session) return
            activeSession = null
            runCatching { session.track.stop() }
            runCatching { session.track.release() }
            outputLifecycle.trackClosed(session.lease, reason)
        }

        /** One session's live [AudioTrack] plus its cumulative playback-position bookkeeping (#114). */
        private class AudioSession(
            val track: AudioTrack,
            val lease: AudioOutputLease,
        ) {
            val progress = PlaybackProgress()

            // Volatile: set from pauseCurrentTrack(), which can run on the AudioManager
            // focus-listener thread, and read/cleared from playCue()'s Dispatchers.IO coroutine.
            @Volatile
            var needsPreRoll = true
            var consecutiveTimeouts = 0
        }

        private class PlaybackProgress(
            var samplesWritten: Long = 0L,
            var playbackHeadWrapOffset: Long = 0L,
            var lastPlaybackHeadRaw: Long = 0L,
        )

        private data class Tone(
            val frequencyHz: Double,
            val durationMs: Int,
            val leftVol: Float,
            val rightVol: Float,
        )

        /** Generates a stereo-interleaved float PCM sine wave with a click-free envelope. */
        private fun generateStereoSine(
            frequencyHz: Double,
            durationMs: Int,
            leftVol: Float,
            rightVol: Float,
        ): FloatArray {
            val numFrames = SAMPLE_RATE * durationMs / 1000
            val samples = FloatArray(numFrames * 2)
            val fadeFrames = minOf(numFrames / 10, SAMPLE_RATE / 100)
            for (i in 0 until numFrames) {
                val raw = (sin(2.0 * PI * frequencyHz * i / SAMPLE_RATE) * AMPLITUDE).toFloat()
                val envelope =
                    when {
                        i < fadeFrames -> i.toFloat() / fadeFrames
                        i >= numFrames - fadeFrames -> (numFrames - i).toFloat() / fadeFrames
                        else -> 1f
                    }
                samples[i * 2] = raw * envelope * leftVol
                samples[i * 2 + 1] = raw * envelope * rightVol
            }
            return samples
        }
    }
