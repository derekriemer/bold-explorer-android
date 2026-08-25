package com.boldexplorer.audio

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
// cue. Device testing determines whether 60 ms is sufficient for a given route; it never leaves a
// stream active between cues.
private const val PRE_ROLL_MS = 60
private const val PRE_ROLL_FRAMES = SAMPLE_RATE * PRE_ROLL_MS / 1000

// Safety net for #109: on at least one device/route, AlignmentPing's playbackHeadPosition never
// reached its cue's end frame — the completion-wait loop below spun until navigation stopped,
// holding toneMutex (and, transitively, AudioFocusController's cueMutex) for up to 87s and
// silently blocking every other cue behind it. 2s is generous next to the longest cue (WrongVector,
// ~300ms including pre-roll) but short enough that a genuinely stuck track can never again hang the
// shared pipeline for more than a couple of seconds.
private const val COMPLETION_TIMEOUT_MS = 2_000L

/**
 * On-demand streaming earcon output.
 *
 * A navigation session no longer owns a continuously playing silent [AudioTrack]. Each cue opens
 * one track, writes a short silent pre-roll and the cue, waits until its final audible frame is
 * rendered, then releases the track. This keeps the audio HAL idle between cues for dictation and
 * avoids a session-long power draw. [stop] interrupts the one active cue, if any.
 */
@Singleton
class AudioEngine
    @Inject
    constructor(
        private val outputLifecycle: CueOutputLifecycle,
    ) {
        private val toneMutex = Mutex()

        @Volatile
        private var activePlayback: ActivePlayback? = null

        private val audioFormat =
            AudioFormat
                .Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

        private val preRollSamples = FloatArray(PRE_ROLL_FRAMES * 2)

        /** Stops and releases the currently audible cue; a stopped navigation session owns no stream. */
        fun stop() {
            activePlayback?.let { closePlayback(it, reason = "navigation_stop") }
        }

        suspend fun playDirectionalBeacon(
            pan: Float,
            pitchHz: Double,
        ) {
            val left = (1f - pan).coerceIn(0f, 1f)
            val right = (1f + pan).coerceIn(0f, 1f)
            playCue("DirectionalBeacon", Tone(pitchHz, durationMs = 100, leftVol = left, rightVol = right))
        }

        suspend fun playAlignmentPing(
            pan: Float,
            pitchHz: Double,
        ) {
            val left = (1f - pan).coerceIn(0f, 1f)
            val right = (1f + pan).coerceIn(0f, 1f)
            playCue("AlignmentPing", Tone(pitchHz, durationMs = 80, leftVol = left, rightVol = right))
        }

        /** Two descending tones (660 → 440 Hz) centered in both ears — "wrong direction" earcon. */
        suspend fun playWrongVector() =
            playCue(
                "WrongVector",
                Tone(660.0, durationMs = 120, leftVol = 0.7f, rightVol = 0.7f),
                Tone(440.0, durationMs = 120, leftVol = 0.7f, rightVol = 0.7f),
            )

        private suspend fun playCue(
            cue: String,
            vararg tones: Tone,
        ) = withContext(Dispatchers.IO) {
            val rendered =
                tones.map { tone ->
                    generateStereoSine(tone.frequencyHz, tone.durationMs, tone.leftVol, tone.rightVol)
                }
            toneMutex.withLock {
                val track = createTrack()
                if (track == null) {
                    outputLifecycle.unavailable(cue, "track_create_failed")
                    return@withLock
                }
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    runCatching { track.release() }
                    outputLifecycle.unavailable(cue, "track_uninitialized")
                    return@withLock
                }
                if (runCatching { track.play() }.isFailure) {
                    runCatching { track.release() }
                    outputLifecycle.unavailable(cue, "track_start_failed")
                    return@withLock
                }

                val playback = ActivePlayback(track, outputLifecycle.started(cue, PRE_ROLL_MS))
                activePlayback = playback
                val progress = PlaybackProgress()
                var stopReason = "interrupted"
                try {
                    if (!writeFully(playback, preRollSamples, progress)) return@withLock
                    for (samples in rendered) {
                        if (!writeFully(playback, samples, progress)) return@withLock
                    }
                    val cueEndFrame = progress.samplesWritten / 2L
                    val deadlineElapsedMs = SystemClock.elapsedRealtime() + COMPLETION_TIMEOUT_MS
                    while (activePlayback === playback) {
                        val playedFrames = playbackFramePosition(playback.track, progress) ?: return@withLock
                        if (playedFrames >= cueEndFrame) {
                            stopReason = "cue_complete"
                            return@withLock
                        }
                        if (SystemClock.elapsedRealtime() >= deadlineElapsedMs) {
                            stopReason = "timeout"
                            return@withLock
                        }
                        delay(5L)
                    }
                } finally {
                    if (!currentCoroutineContext().isActive && stopReason != "cue_complete") {
                        stopReason = "cancelled"
                    }
                    closePlayback(playback, stopReason)
                }
            }
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
            playback: ActivePlayback,
            samples: FloatArray,
            progress: PlaybackProgress,
        ): Boolean {
            var offset = 0
            while (offset < samples.size && activePlayback === playback) {
                val count =
                    runCatching {
                        playback.track.write(
                            samples,
                            offset,
                            samples.size - offset,
                            AudioTrack.WRITE_BLOCKING,
                        )
                    }.getOrElse { return false }
                if (count <= 0) return false
                offset += count
                progress.samplesWritten += count
            }
            return offset == samples.size
        }

        /** Expands AudioTrack's wrapping unsigned 32-bit counter into this cue's frame count. */
        private fun playbackFramePosition(
            track: AudioTrack,
            progress: PlaybackProgress,
        ): Long? {
            val raw =
                runCatching { track.playbackHeadPosition.toLong() and 0xffff_ffffL }
                    .getOrNull() ?: return null
            if (raw < progress.lastPlaybackHeadRaw) progress.playbackHeadWrapOffset += 1L shl 32
            progress.lastPlaybackHeadRaw = raw
            return progress.playbackHeadWrapOffset + raw
        }

        private fun closePlayback(
            playback: ActivePlayback,
            reason: String,
        ) {
            if (activePlayback !== playback) return
            activePlayback = null
            runCatching { playback.track.stop() }
            runCatching { playback.track.release() }
            outputLifecycle.stopped(playback.lease, reason)
        }

        private data class ActivePlayback(
            val track: AudioTrack,
            val lease: AudioOutputLease,
        )

        private data class PlaybackProgress(
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
