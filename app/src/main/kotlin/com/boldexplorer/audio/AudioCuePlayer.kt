package com.boldexplorer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.boldexplorer.BuildConfig
import com.boldexplorer.shared.audio.AudioCueEvent
import com.boldexplorer.shared.audio.AudioCueScheduler
import com.boldexplorer.shared.location.isLocationStale
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.navigation.SmoothedHeading
import com.boldexplorer.shared.navigation.TrailGuidanceState
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
 *
 * Every dispatched event is appended to [AudioEventLog] for post-session debugging.
 */
@Singleton
class AudioCuePlayer
    @Inject
    constructor(
        private val audioEngine: AudioEngine,
        private val ttsEngine: TtsEngine,
        private val scheduler: AudioCueScheduler,
        private val settingsRepo: SettingsRepository,
        private val appForegroundState: AppForegroundState,
        private val audioEventLog: AudioEventLog,
        @ApplicationContext private val context: Context,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var playerJob: Job? = null
        private var focusRequest: AudioFocusRequest? = null

        // Stored from start() so dispatch() can snapshot current values for logging.
        private var accuracyMFlow: StateFlow<Double?>? = null
        private var relativeDegFlow: StateFlow<Double?>? = null
        private var locationFlow: StateFlow<LocationSample?>? = null
        private var trailGuidanceFlow: StateFlow<TrailGuidanceState?>? = null
        private var smoothedHeadingFlow: StateFlow<SmoothedHeading?>? = null

        private val audioManager = context.getSystemService(AudioManager::class.java)
        private val focusAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

        fun start(
            accuracyM: StateFlow<Double?>,
            relativeDeg: StateFlow<Double?>,
            alignmentActive: StateFlow<Boolean>,
            beaconCuesEnabled: StateFlow<Boolean>,
            location: StateFlow<LocationSample?>,
            trailGuidance: StateFlow<TrailGuidanceState?>,
            smoothedHeading: StateFlow<SmoothedHeading?>,
        ) {
            if (playerJob != null) return

            accuracyMFlow = accuracyM
            relativeDegFlow = relativeDeg
            locationFlow = location
            trailGuidanceFlow = trailGuidance
            smoothedHeadingFlow = smoothedHeading

            audioEngine.start()
            val schedulerJob = scheduler.start(scope, accuracyM, relativeDeg, alignmentActive, beaconCuesEnabled)
            playerJob =
                scheduler.events
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
            accuracyMFlow = null
            relativeDegFlow = null
            locationFlow = null
            trailGuidanceFlow = null
            smoothedHeadingFlow = null
            audioEngine.stop()
            abandonAudioFocus()
        }

        private fun dispatch(event: AudioCueEvent) {
            val settings = runBlocking { settingsRepo.load() }
            val duck = settings.duckAudioEnabled
            if (duck) requestAudioFocus()

            // STOPGAP for #14 (absolute silence mode): earcons don't route through
            // OutputManager/OutputPolicy yet — that unification is tracked in #22. Until then,
            // this is a direct settings check here (same shape as the existing foreground check
            // below), so "no automatic audio output whatsoever" is actually true. History still
            // gets recorded below regardless of `silenced` — only the play calls are gated.
            // Replace with real OutputPolicy routing once #22 lands.
            val silenced = settings.absoluteSilenceEnabled

            val nowMs = System.currentTimeMillis()
            val relDeg = relativeDegFlow?.value
            val accM = accuracyMFlow?.value
            val loc = locationFlow?.value
            val guidance = trailGuidanceFlow?.value
            val smoothed = smoothedHeadingFlow?.value

            when (event) {
                is AudioCueEvent.DirectionalBeacon -> {
                    // Issue #23: the beacon is driven by relativeDeg, which keeps changing with
                    // heading even when the underlying GPS fix (and thus the absolute bearing to
                    // target) is stale — so a live-sounding beacon over a frozen number gives a
                    // false impression of live tracking. Go silent instead of playing a
                    // confidently-wrong tone.
                    val stale = isLocationStale(nowMs, loc?.timestamp)
                    if (!silenced && !stale) audioEngine.playDirectionalBeacon(event.pan, event.pitchHz)
                    scope.launch {
                        val courseIsSmoothed = guidance?.courseIsSmoothed ?: false
                        val extra =
                            buildMap<String, Any?> {
                                if (BuildConfig.SHOW_DEBUG_FEATURES) {
                                    loc?.let {
                                        put("userLat", it.lat)
                                        put("userLng", it.lon)
                                        it.altitude?.let { v -> put("userElev_m", v) }
                                        it.heading?.let { v -> put("userHeading", v) }
                                        it.speed?.let { v -> put("userSpeed_ms", v) }
                                        it.accuracy?.let { v -> put("userAccuracy_m", v) }
                                    }
                                    put("courseIsSmoothed", courseIsSmoothed)
                                    smoothed?.let {
                                        put("smoothedHeadingDeg", "%.1f".format(it.deg).toDouble())
                                        put("smoothedConfidence", "%.2f".format(it.confidence).toDouble())
                                        put("smoothedSampleCount", it.sampleCount)
                                        put("smoothedAgeMs", nowMs - it.newestTimestampMs)
                                    }
                                }
                                guidance?.let {
                                    put("targetIndex", it.targetIndex)
                                    put("distToTarget_m", it.distanceToTargetM)
                                }
                            }
                        audioEventLog.append(
                            AudioLogEntry(
                                timestampMs = nowMs,
                                kind = AudioLogEntry.Kind.DIRECTIONAL_BEACON,
                                trigger = "5s timer",
                                inputs =
                                    buildString {
                                        if (relDeg != null) append("relativeDeg=${"%.1f".format(relDeg)}°")
                                        if (accM != null) append(", accuracy=${"%.1f".format(accM)}m")
                                        if (courseIsSmoothed) append(", smoothed=true")
                                    }.trimStart(',', ' '),
                                outputs = "pan=${"%.3f".format(event.pan)}, pitchHz=${"%.0f".format(event.pitchHz)} Hz",
                                played =
                                    when {
                                        stale -> {
                                            val ageMs = loc?.let { nowMs - it.timestamp }
                                            "Suppressed (stale GPS fix${ageMs?.let { ", ${it}ms old" } ?: ""}): " +
                                                "tone @ ${"%.0f".format(event.pitchHz)} Hz"
                                        }
                                        silenced -> "Suppressed (silence mode): tone @ ${"%.0f".format(event.pitchHz)} Hz"
                                        else -> "Tone @ ${"%.0f".format(event.pitchHz)} Hz"
                                    },
                                extra = extra,
                            ),
                        )
                    }
                }

                is AudioCueEvent.AccuracyBeacon -> {
                    if (!silenced) audioEngine.playAccuracyBeacon(event.accuracyM)
                    val mappedHz = 880.0 - (880.0 - 220.0) * (event.accuracyM.coerceIn(0.0, 30.0) / 30.0)
                    scope.launch {
                        audioEventLog.append(
                            AudioLogEntry(
                                timestampMs = nowMs,
                                kind = AudioLogEntry.Kind.ACCURACY_BEACON,
                                trigger = "GPS update",
                                inputs = "accuracy=${"%.1f".format(event.accuracyM)}m",
                                outputs = "pitchHz=${"%.0f".format(mappedHz)} Hz",
                                played =
                                    if (silenced) {
                                        "Suppressed (silence mode): accuracy tone @ ${"%.0f".format(mappedHz)} Hz"
                                    } else {
                                        "Accuracy tone @ ${"%.0f".format(mappedHz)} Hz"
                                    },
                            ),
                        )
                    }
                }

                is AudioCueEvent.AlignmentPing -> {
                    if (!silenced) audioEngine.playAlignmentPing(event.pan, event.pitchHz)
                    scope.launch {
                        audioEventLog.append(
                            AudioLogEntry(
                                timestampMs = nowMs,
                                kind = AudioLogEntry.Kind.ALIGNMENT_PING,
                                trigger = "Alignment ping",
                                inputs = relDeg?.let { "relativeDeg=${"%.1f".format(it)}°" } ?: "",
                                outputs = "pan=${"%.3f".format(event.pan)}, pitchHz=${"%.0f".format(event.pitchHz)} Hz",
                                played =
                                    if (silenced) {
                                        "Suppressed (silence mode): alignment ping @ ${"%.0f".format(event.pitchHz)} Hz"
                                    } else {
                                        "Alignment ping @ ${"%.0f".format(event.pitchHz)} Hz"
                                    },
                            ),
                        )
                    }
                }

                is AudioCueEvent.WaypointApproach -> {
                    val spoke = !silenced && !appForegroundState.isInForeground.value
                    if (spoke) {
                        scope.launch { ttsEngine.speak("Next waypoint: ${event.waypointName}") }
                    }
                    scope.launch {
                        audioEventLog.append(
                            AudioLogEntry(
                                timestampMs = nowMs,
                                kind = AudioLogEntry.Kind.WAYPOINT_APPROACH,
                                trigger = "Waypoint reached",
                                inputs = "waypointName=\"${event.waypointName}\"",
                                outputs = "",
                                played = dispositionOf(spoke, silenced).playedLabel("Next waypoint: ${event.waypointName}"),
                            ),
                        )
                    }
                }

                is AudioCueEvent.TrailComplete -> {
                    val spoke = !silenced && !appForegroundState.isInForeground.value
                    if (spoke) {
                        scope.launch { ttsEngine.speak("Trail complete") }
                    }
                    scope.launch {
                        audioEventLog.append(
                            AudioLogEntry(
                                timestampMs = nowMs,
                                kind = AudioLogEntry.Kind.TRAIL_COMPLETE,
                                trigger = "Trail complete",
                                inputs = "",
                                outputs = "",
                                played = dispositionOf(spoke, silenced).playedLabel("Trail complete"),
                            ),
                        )
                    }
                }

                is AudioCueEvent.WrongVector -> {
                    if (!silenced) audioEngine.playWrongVector()
                    scope.launch {
                        audioEventLog.append(
                            AudioLogEntry(
                                timestampMs = nowMs,
                                kind = AudioLogEntry.Kind.DIRECTIONAL_BEACON,
                                trigger = "WrongVector",
                                inputs = relDeg?.let { "relativeDeg=${"%.1f".format(it)}°" } ?: "",
                                outputs = "660 Hz → 440 Hz descending",
                                played = if (silenced) "Suppressed (silence mode): wrong-vector earcon" else "Wrong-vector earcon",
                            ),
                        )
                    }
                }
            }

            // Abandon focus immediately after tone dispatch so music unducks per-beep.
            // TTS manages its own focus internally; we abandon ours regardless.
            if (duck) abandonAudioFocus()
        }

        private fun requestAudioFocus() {
            val request =
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
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

/**
 * Which channel carried a cue that [AudioCuePlayer] speaks directly.
 *
 * These paths have no live region of their own, so a foregrounded app means nothing announced it —
 * [OutputDisposition.NOT_SPOKEN_FOREGROUND] rather than [OutputDisposition.LIVE_REGION_ONLY]. The
 * distinction is the whole reason this maps to the shared enum instead of writing its own labels.
 */
private fun dispositionOf(
    spoke: Boolean,
    silenced: Boolean,
): OutputDisposition =
    when {
        spoke -> OutputDisposition.QUEUED_FOR_TTS
        silenced -> OutputDisposition.SILENCED
        else -> OutputDisposition.NOT_SPOKEN_FOREGROUND
    }
