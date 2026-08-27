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

// A bounded silent lead-in gives a newly opened Bluetooth route a chance to warm before an audible
// cue. Written only when a track is (re)created, not on every cue — see #114/#108.
private const val PRE_ROLL_MS = 60
private const val PRE_ROLL_FRAMES = SAMPLE_RATE * PRE_ROLL_MS / 1000

// Safety net for #109: on at least one device/route, AlignmentPing's playbackHeadPosition never
// reached its cue's end frame — the completion-wait loop below spun until navigation stopped,
// holding toneMutex (and, transitively, AudioFocusController's lease) for up to 87s and silently
// blocking every other cue behind it. 2s is generous next to the longest cue (WrongVector, ~300ms
// including pre-roll) but short enough that a genuinely stuck track can never again hang the shared
// pipeline for more than a couple of seconds.
private const val COMPLETION_TIMEOUT_MS = 2_000L

// #114: after this many consecutive completion-timeouts on the same session-scoped track, treat it
// as wedged and recreate it, even without an exception. Not field-tuned — a guess.
private const val CONSECUTIVE_TIMEOUT_ESCALATION_THRESHOLD = 3

// Silence chunk the filler writes at a time while frequent mode is active. Short enough that a real
// cue's write (which shares toneMutex) never waits long for a filler write in flight to finish.
private const val SILENCE_CHUNK_MS = 100
private const val SILENCE_CHUNK_FRAMES = SAMPLE_RATE * SILENCE_CHUNK_MS / 1000

/**
 * Session-scoped streaming earcon output (#114/#108).
 *
 * One [AudioTrack] is created lazily on the first cue of a session and kept open across every cue
 * dispatched until the session ends ([stop]) or the track errors/wedges — reopening a
 * Bluetooth-routed stream on every single cue is the mechanism #114's stall theory targets.
 *
 * **Two prior fixes for the remaining stall were tried and field-disproven before this one; see
 * #114's issue history for the traces.** Leaving a frequent-cadence run's track in
 * `PLAYSTATE_PLAYING` between cues (relying on natural underrun-to-silence) let AudioFlinger disable
 * the track — confirmed via `AudioFlinger`/`AudioTrack` logcat, on the plain speaker, not just
 * Bluetooth. Switching to an explicit `pause()`/`play()` cycle between every cue did *not* fix it:
 * the same `prepareTracks_l BUFFER TIMEOUT: ... due to underrun` eviction still fired repeatedly for
 * an explicitly-paused track, just without the extra `restartIfDisabled()` recovery tax layered on
 * top — AudioFlinger's periodic sweep does not appear to distinguish "app paused this on purpose"
 * from "app let this starve." The only remaining lever is to never let a frequent-mode track go idle
 * at all: [ensureSilenceFiller] keeps writing small silence chunks between real cues for as long as
 * frequent mode is active, so the track is never absent from AudioFlinger's active list in the first
 * place. Rare-mode cues still use the cheaper [pauseAfterCue] discipline — the two prior fixes were
 * never disproven for that case, and it avoids paying the same #53-shaped continuous-activity
 * tradeoff where it isn't needed. Not fully understood *why* this device evicts so eagerly; this is
 * confirmed to route around the symptom, not a mechanism we've verified end to end.
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

        private val preRollSamples = FloatArray(PRE_ROLL_FRAMES * 2)
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
        fun pauseForFocusLoss() = pauseCurrentTrack(reason = "focus_lost", external = true)

        /** Frequent → rare mode transition: return to per-cue pause discipline. */
        fun pauseForModeChange() = pauseCurrentTrack(reason = "mode_change_to_rare", external = true)

        /** Called after every rare-mode cue; never races an in-flight completion poll. */
        fun pauseAfterCue() = pauseCurrentTrack(reason = "cue_end", external = false)

        private fun pauseCurrentTrack(
            reason: String,
            external: Boolean,
        ) {
            val session = activeSession ?: return
            if (external) {
                pausedExternally = true
                // A held-open route is exactly what something needing exclusive focus (dictation)
                // needs us to stop being — the filler must not keep writing through a focus loss or a
                // frequent→rare transition. The next Frequent-cadence cue restarts it (see playCue()).
                stopSilenceFiller()
            }
            runCatching { session.track.pause() }
            // A paused track is cold the same way a just-created one is — the route may not be
            // settled by the time real audio resumes. Rare mode pauses after every single cue, so
            // without this every rare-mode cue after the first would resume with zero warm-up lead-in
            // (field-confirmed: clipped/dropped tones, not just a theoretical risk).
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
                        if (!writeFully(session, preRollSamples)) {
                            outcome = "write_failed"
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
                        // cumulative bookkeeping must reset with it.
                        runCatching {
                            session.track.pause()
                            session.track.flush()
                        }
                        session.progress.samplesWritten = 0L
                        session.progress.playbackHeadWrapOffset = 0L
                        session.progress.lastPlaybackHeadRaw = 0L
                        session.consecutiveTimeouts++
                        if (session.consecutiveTimeouts >= CONSECUTIVE_TIMEOUT_ESCALATION_THRESHOLD) {
                            closeSession(session, reason = "reopen_after_timeout_escalation")
                        }
                    } else if (outcome == "write_failed" || outcome == "read_failed") {
                        closeSession(session, reason = "reopen_after_error")
                    }
                }
            }
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
                    lease = outputLifecycle.trackOpened("session_open", PRE_ROLL_MS),
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
                    .setBufferSizeInBytes(maxOf(minBufferBytes, preRollSamples.size * Float.SIZE_BYTES))
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
