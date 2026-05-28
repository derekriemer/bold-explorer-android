package com.boldexplorer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.sin

private const val SAMPLE_RATE = 44100
private const val AMPLITUDE = 0.6f

/**
 * Generates and plays short PCM sine tones via AudioTrack (MODE_STATIC).
 *
 * Two tone types:
 * - Accuracy beacon: frequency maps GPS accuracy → pitch (0 m=880 Hz, 30 m=220 Hz).
 * - Alignment ping: 80 ms stereo-panned tone; 440 Hz off-bearing, 660 Hz aligned.
 *
 * Each call launches a fire-and-forget coroutine on [Dispatchers.IO]; tones that
 * arrive faster than their duration (rare given GPS + scheduler gating) run concurrently.
 */
@Singleton
class AudioEngine @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun playAccuracyBeacon(accuracyM: Double) {
        val freq = mapAccuracyToFrequency(accuracyM)
        scope.launch { playTone(freq, durationMs = 100, leftVol = 0.7f, rightVol = 0.7f) }
    }

    fun playAlignmentPing(pan: Int, aligned: Boolean) {
        val freq = if (aligned) 660.0 else 440.0
        val (left, right) = when (pan) {
            -1 -> 1f to 0f   // bearing is to the left
             1 -> 0f to 1f   // bearing is to the right
            else -> 1f to 1f // on-bearing (center)
        }
        scope.launch { playTone(freq, durationMs = 80, leftVol = left, rightVol = right) }
    }

    // 0 m (perfect fix) → 880 Hz; 30 m (degraded) → 220 Hz; clamped outside that range.
    private fun mapAccuracyToFrequency(accuracyM: Double): Double {
        val clamped = accuracyM.coerceIn(0.0, 30.0)
        return 880.0 - (880.0 - 220.0) * (clamped / 30.0)
    }

    private suspend fun playTone(frequencyHz: Double, durationMs: Int, leftVol: Float, rightVol: Float) {
        val numFrames = SAMPLE_RATE * durationMs / 1000
        val samples = generateStereoSine(frequencyHz, numFrames, leftVol, rightVol)

        // For MODE_STATIC, buffer must hold all samples. Respect getMinBufferSize just in case.
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferBytes = maxOf(samples.size * 4, minBuf)

        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        track.play()
        delay(durationMs.toLong() + 20L)  // wait for playback to complete
        track.stop()
        track.release()
    }

    /**
     * Generates a stereo-interleaved float PCM sine wave with linear fade-in/out
     * at each end (up to 10 ms) to avoid audible clicks.
     */
    private fun generateStereoSine(
        frequencyHz: Double,
        numFrames: Int,
        leftVol: Float,
        rightVol: Float,
    ): FloatArray {
        val samples = FloatArray(numFrames * 2)
        val fadeFrames = minOf(numFrames / 10, SAMPLE_RATE / 100)  // 10% or 10 ms, whichever smaller
        for (i in 0 until numFrames) {
            val raw = (sin(2.0 * PI * frequencyHz * i / SAMPLE_RATE) * AMPLITUDE).toFloat()
            val envelope = when {
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
