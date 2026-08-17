package com.boldexplorer.audio

import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

// 1024 frames ≈ 23 ms of silence per keepalive write — small enough to yield
// quickly when a tone write needs the mutex, large enough to avoid spin overhead.
private const val SILENCE_FRAMES = 1024

/**
 * Single long-lived streaming AudioTrack with a silence keepalive loop.
 *
 * The keepalive continuously writes zero PCM so the Bluetooth A2DP stream never
 * goes idle. Tone writes acquire a mutex, replacing the silence mid-stream.
 * No per-tone track creation/teardown means no BT power-save dropout.
 *
 * Call [start] when navigation begins, [stop] when it ends.
 */
@Singleton
class AudioEngine
    @Inject
    constructor() {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val toneMutex = Mutex()

        @Volatile
        private var track: AudioTrack? = null
        private var keepaliveJob: Job? = null
        private var samplesWritten = 0L
        private var playbackHeadWrapOffset = 0L
        private var lastPlaybackHeadRaw = 0L

        private val audioAttributes = beaconAudioAttributes()

        private val audioFormat =
            AudioFormat
                .Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

        // Stereo silence chunk reused across all keepalive writes.
        private val silenceChunk = FloatArray(SILENCE_FRAMES * 2)

        fun start() {
            if (keepaliveJob?.isActive == true) return

            val minBuf =
                AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                )
            // 4× min buffer gives headroom so keepalive writes never starve the track.
            val bufferBytes = maxOf(minBuf * 4, SILENCE_FRAMES * 2 * 4)

            val newTrack =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            newTrack.play()
            track = newTrack
            samplesWritten = 0L
            playbackHeadWrapOffset = 0L
            lastPlaybackHeadRaw = 0L

            keepaliveJob =
                scope.launch {
                    while (isActive) {
                        // Yield to tone writes via the mutex; write silence otherwise.
                        toneMutex.withLock {
                            writeFully(newTrack, silenceChunk)
                        }
                    }
                }
        }

        fun stop() {
            keepaliveJob?.cancel()
            keepaliveJob = null
            val oldTrack = track
            track = null
            oldTrack?.stop()
            oldTrack?.release()
        }

        suspend fun playDirectionalBeacon(
            pan: Float,
            pitchHz: Double,
        ) {
            val left = (1f - pan).coerceIn(0f, 1f)
            val right = (1f + pan).coerceIn(0f, 1f)
            playCue(Tone(pitchHz, durationMs = 100, leftVol = left, rightVol = right))
        }

        suspend fun playAccuracyBeacon(accuracyM: Double) {
            val freq = mapAccuracyToFrequency(accuracyM)
            playCue(Tone(freq, durationMs = 100, leftVol = 0.7f, rightVol = 0.7f))
        }

        suspend fun playAlignmentPing(
            pan: Float,
            pitchHz: Double,
        ) {
            val left = (1f - pan).coerceIn(0f, 1f)
            val right = (1f + pan).coerceIn(0f, 1f)
            playCue(Tone(pitchHz, durationMs = 80, leftVol = left, rightVol = right))
        }

        /** Two descending tones (660 → 440 Hz) centered in both ears — "wrong direction" earcon. */
        suspend fun playWrongVector() =
            playCue(
                Tone(660.0, durationMs = 120, leftVol = 0.7f, rightVol = 0.7f),
                Tone(440.0, durationMs = 120, leftVol = 0.7f, rightVol = 0.7f),
            )

        // 0 m (perfect fix) → 880 Hz; 30 m (degraded) → 220 Hz; clamped outside that range.
        private fun mapAccuracyToFrequency(accuracyM: Double): Double {
            val clamped = accuracyM.coerceIn(0.0, 30.0)
            return 880.0 - (880.0 - 220.0) * (clamped / 30.0)
        }

        /**
         * Queues one complete earcon and returns only after its final audible frame is rendered.
         *
         * A blocking [AudioTrack.write] only proves that PCM reached the track's buffer; it may
         * return before the device renders that PCM. Tracking written frames against
         * [AudioTrack.getPlaybackHeadPosition] gives [AudioCuePlayer] a real completion boundary
         * for its transient audio-focus lease. One silence chunk is queued after the cue so the
         * keepalive has time to resume without creating a Bluetooth underrun.
         */
        private suspend fun playCue(vararg tones: Tone) =
            withContext(Dispatchers.IO) {
                val currentTrack = track ?: return@withContext
                val rendered =
                    tones.map { tone ->
                        generateStereoSine(tone.frequencyHz, tone.durationMs, tone.leftVol, tone.rightVol)
                    }
                toneMutex.withLock {
                    if (track !== currentTrack) return@withLock
                    for (samples in rendered) {
                        if (!writeFully(currentTrack, samples)) return@withLock
                    }
                    val cueEndFrame = samplesWritten / 2L
                    if (!writeFully(currentTrack, silenceChunk)) return@withLock
                    while (track === currentTrack) {
                        val playedFrames = playbackFramePosition(currentTrack) ?: break
                        if (playedFrames >= cueEndFrame) break
                        delay(5L)
                    }
                }
            }

        /** Must be called while [toneMutex] is held. */
        private fun writeFully(
            currentTrack: AudioTrack,
            samples: FloatArray,
        ): Boolean {
            var offset = 0
            while (offset < samples.size && track === currentTrack) {
                val count =
                    runCatching {
                        currentTrack.write(
                            samples,
                            offset,
                            samples.size - offset,
                            AudioTrack.WRITE_BLOCKING,
                        )
                    }.getOrElse { return false }
                if (count <= 0) return false
                offset += count
                samplesWritten += count
            }
            return offset == samples.size
        }

        /** Expands AudioTrack's wrapping unsigned 32-bit counter into this track's frame count. */
        private fun playbackFramePosition(currentTrack: AudioTrack): Long? {
            val raw =
                runCatching { currentTrack.playbackHeadPosition.toLong() and 0xffff_ffffL }
                    .getOrNull() ?: return null
            if (raw < lastPlaybackHeadRaw) playbackHeadWrapOffset += 1L shl 32
            lastPlaybackHeadRaw = raw
            return playbackHeadWrapOffset + raw
        }

        private data class Tone(
            val frequencyHz: Double,
            val durationMs: Int,
            val leftVol: Float,
            val rightVol: Float,
        )

        /**
         * Generates a stereo-interleaved float PCM sine wave with linear fade-in/out
         * at each end (up to 10 ms) to avoid audible clicks.
         */
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
