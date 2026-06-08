package com.boldexplorer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        private var track: AudioTrack? = null
        private var keepaliveJob: Job? = null

        private val audioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

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

            keepaliveJob =
                scope.launch {
                    while (isActive) {
                        // Yield to tone writes via the mutex; write silence otherwise.
                        toneMutex.withLock {
                            newTrack.write(silenceChunk, 0, silenceChunk.size, AudioTrack.WRITE_BLOCKING)
                        }
                    }
                }
        }

        fun stop() {
            keepaliveJob?.cancel()
            keepaliveJob = null
            track?.stop()
            track?.release()
            track = null
        }

        fun playDirectionalBeacon(
            pan: Float,
            pitchHz: Double,
        ) {
            val left = (1f - pan).coerceIn(0f, 1f)
            val right = (1f + pan).coerceIn(0f, 1f)
            scope.launch { playTone(pitchHz, durationMs = 100, leftVol = left, rightVol = right) }
        }

        fun playAccuracyBeacon(accuracyM: Double) {
            val freq = mapAccuracyToFrequency(accuracyM)
            scope.launch { playTone(freq, durationMs = 100, leftVol = 0.7f, rightVol = 0.7f) }
        }

        fun playAlignmentPing(
            pan: Float,
            pitchHz: Double,
        ) {
            val left = (1f - pan).coerceIn(0f, 1f)
            val right = (1f + pan).coerceIn(0f, 1f)
            scope.launch { playTone(pitchHz, durationMs = 80, leftVol = left, rightVol = right) }
        }

        /** Two descending tones (660 → 440 Hz) centered in both ears — "wrong direction" earcon. */
        fun playWrongVector() {
            scope.launch {
                playTone(660.0, durationMs = 120, leftVol = 0.7f, rightVol = 0.7f)
                playTone(440.0, durationMs = 120, leftVol = 0.7f, rightVol = 0.7f)
            }
        }

        // 0 m (perfect fix) → 880 Hz; 30 m (degraded) → 220 Hz; clamped outside that range.
        private fun mapAccuracyToFrequency(accuracyM: Double): Double {
            val clamped = accuracyM.coerceIn(0.0, 30.0)
            return 880.0 - (880.0 - 220.0) * (clamped / 30.0)
        }

        private suspend fun playTone(
            frequencyHz: Double,
            durationMs: Int,
            leftVol: Float,
            rightVol: Float,
        ) {
            val t = track ?: return
            val samples = generateStereoSine(frequencyHz, durationMs, leftVol, rightVol)
            toneMutex.withLock {
                t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            }
        }

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
