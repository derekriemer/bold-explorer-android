package com.boldexplorer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.boldexplorer.shared.audio.AudioCueEvent
import com.boldexplorer.shared.audio.AudioCueScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges [AudioCueScheduler] (pure scheduling) to Android playback.
 *
 * - [AudioCueEvent.AccuracyBeacon] → [AudioEngine] tone (frequency = GPS accuracy)
 * - [AudioCueEvent.AlignmentPing] → [AudioEngine] stereo-panned tone
 * - [AudioCueEvent.WaypointApproach] / [AudioCueEvent.TrailComplete] → [TtsEngine]
 *
 * Holds AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK so music apps duck rather than stop.
 * Call [start] when navigation begins and [stop] when it ends.
 */
@Singleton
class AudioCuePlayer @Inject constructor(
    private val audioEngine: AudioEngine,
    private val ttsEngine: TtsEngine,
    private val scheduler: AudioCueScheduler,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playerJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start(
        accuracyM: StateFlow<Double?>,
        relativeDeg: StateFlow<Double?>,
        alignmentActive: StateFlow<Boolean>,
        audioCuesEnabled: StateFlow<Boolean>,
    ) {
        if (playerJob != null) return

        val schedulerJob = scheduler.start(scope, accuracyM, relativeDeg, alignmentActive, audioCuesEnabled)
        requestAudioFocus()
        playerJob = scheduler.events
            .onEach { event -> dispatch(event) }
            .launchIn(scope)
            .also { job ->
                job.invokeOnCompletion {
                    schedulerJob?.cancel()
                }
            }
    }

    fun stop() {
        playerJob?.cancel()
        playerJob = null
        abandonAudioFocus()
    }

    private fun dispatch(event: AudioCueEvent) {
        when (event) {
            is AudioCueEvent.DirectionalBeacon ->
                audioEngine.playDirectionalBeacon(event.pan, event.pitchHz)
            is AudioCueEvent.AccuracyBeacon ->
                audioEngine.playAccuracyBeacon(event.accuracyM)
            is AudioCueEvent.AlignmentPing ->
                audioEngine.playAlignmentPing(event.pan, event.aligned)
            is AudioCueEvent.WaypointApproach ->
                scope.launch { ttsEngine.speak("Next waypoint: ${event.waypointName}") }
            is AudioCueEvent.TrailComplete ->
                scope.launch { ttsEngine.speak("Trail complete") }
        }
    }

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(focusAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { /* DUCK: audio continues at reduced volume */ }
            .build()
        audioManager.requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
