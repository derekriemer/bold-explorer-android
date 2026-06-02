package com.boldexplorer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.boldexplorer.shared.audio.AudioCueEvent
import com.boldexplorer.shared.audio.AudioCueScheduler
import com.boldexplorer.shared.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges [AudioCueScheduler] (pure scheduling) to Android playback.
 *
 * - [AudioCueEvent.DirectionalBeacon] / [AudioCueEvent.AccuracyBeacon] / [AudioCueEvent.AlignmentPing]
 *   → [AudioEngine] streaming tone
 * - [AudioCueEvent.WaypointApproach] / [AudioCueEvent.TrailComplete] → [TtsEngine]
 *
 * Audio focus is requested per-tone only when [AppSettings.duckAudioEnabled] is true,
 * so music is not held ducked between beacons. When duck is off, tones mix transparently.
 *
 * [AudioEngine.start] keeps the Bluetooth A2DP stream alive via a silence keepalive loop,
 * preventing BT headphone dropout on the first frame of each tone.
 */
@Singleton
class AudioCuePlayer @Inject constructor(
    private val audioEngine: AudioEngine,
    private val ttsEngine: TtsEngine,
    private val scheduler: AudioCueScheduler,
    private val settingsRepo: SettingsRepository,
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

        audioEngine.start()
        val schedulerJob = scheduler.start(scope, accuracyM, relativeDeg, alignmentActive, audioCuesEnabled)
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
        audioEngine.stop()
        abandonAudioFocus()
    }

    private fun dispatch(event: AudioCueEvent) {
        val duck = runBlocking { settingsRepo.load().duckAudioEnabled }
        if (duck) requestAudioFocus()
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
        // Abandon focus immediately after tone dispatch so music unducks per-beep.
        // TTS manages its own focus internally; we abandon ours regardless.
        if (duck) abandonAudioFocus()
    }

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(focusAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { /* duck handled by system volume */ }
            .build()
        audioManager.requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
