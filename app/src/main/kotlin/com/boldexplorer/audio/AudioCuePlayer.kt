package com.boldexplorer.audio

import com.boldexplorer.BuildConfig
import com.boldexplorer.shared.audio.AudioCueEvent
import com.boldexplorer.shared.audio.AudioCueScheduler
import com.boldexplorer.shared.location.isLocationStale
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.navigation.SmoothedHeading
import com.boldexplorer.shared.navigation.TrailGuidanceState
import com.boldexplorer.shared.repository.SettingsRepository
import com.boldexplorer.shared.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges [AudioCueScheduler] (pure scheduling) to Android playback.
 *
 * - [AudioCueEvent.DirectionalBeacon] / [AudioCueEvent.AlignmentPing]
 *   → [AudioEngine] streaming tone
 * - [AudioCueEvent.TrailComplete] → [TtsEngine]
 *
 * [AppSettings.duckAudioEnabled] selects between two explicit modes: transient-may-duck focus for
 * exactly the audible cue, or transparent mixing with other media. A denied focus request falls
 * back to mixing so an accessibility cue is never lost solely because focus was unavailable.
 *
 * [AudioEngine] holds one session-scoped [android.media.AudioTrack] across the whole session
 * (#114/#108) rather than reopening one per cue; [AudioFocusController] mirrors that with a
 * cue-scoped focus lease by default, widening to span a run of cues only while a frequent-cadence
 * cue source (`frequentCuesActive`) is active. See the design note on #114/#108 for the full
 * rationale — this replaced a strictly per-cue-scoped track/lease pair.
 *
 * Every dispatched event is appended to [AudioEventLog] for post-session debugging.
 */
@Singleton
class AudioCuePlayer
    @Inject
    constructor(
        private val audioEngine: AudioEngine,
        private val audioFocusController: AudioFocusController,
        private val ttsEngine: TtsEngine,
        private val scheduler: AudioCueScheduler,
        private val settingsRepo: SettingsRepository,
        private val appForegroundState: AppForegroundState,
        private val audioEventLog: AudioEventLog,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var playerJob: Job? = null

        // Guards against dispatching a cue against the seed AppSettings() (absoluteSilenceEnabled =
        // false) before DataStore's first async emission lands — a sticky silence preference must
        // never be audibly violated by a race at process start.
        private val firstSettingsLoaded = CompletableDeferred<Unit>()
        private val settings: StateFlow<AppSettings> =
            settingsRepo.observeSettings()
                .onEach { firstSettingsLoaded.complete(Unit) }
                .stateIn(scope, SharingStarted.Eagerly, AppSettings())

        // Stored from start() so dispatch() can snapshot current values for logging.
        private var accuracyMFlow: StateFlow<Double?>? = null
        private var relativeDegFlow: StateFlow<Double?>? = null
        private var locationFlow: StateFlow<LocationSample?>? = null
        private var trailGuidanceFlow: StateFlow<TrailGuidanceState?>? = null
        private var smoothedHeadingFlow: StateFlow<SmoothedHeading?>? = null

        fun start(
            accuracyM: StateFlow<Double?>,
            relativeDeg: StateFlow<Double?>,
            frequentCuesActive: StateFlow<Boolean>,
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

            audioEngine.open()
            audioFocusController.onFocusLostOrModeChange = { reason ->
                if (reason == "focus_lost") audioEngine.pauseForFocusLoss() else audioEngine.pauseForModeChange()
            }
            // Edge-triggered mode toggle (#114/#108) — not a per-cue re-check. StateFlow only emits on
            // an actual value change, so this fires exactly on frequent-mode entry/exit.
            frequentCuesActive.onEach { audioFocusController.setFrequentMode(it) }.launchIn(scope)

            val schedulerJob = scheduler.start(scope, relativeDeg, frequentCuesActive, beaconCuesEnabled)
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
            audioFocusController.close()
        }

        private suspend fun dispatch(event: AudioCueEvent) {
            firstSettingsLoaded.await()
            val currentSettings = settings.value
            val duck = currentSettings.duckAudioEnabled

            // STOPGAP for #14 (absolute silence mode): earcons don't route through
            // OutputManager/OutputPolicy yet — that unification is tracked in #22. Until then,
            // this is a direct settings check here (same shape as the existing foreground check
            // below), so "no automatic audio output whatsoever" is actually true. History still
            // gets recorded below regardless of `silenced` — only the play calls are gated.
            // Replace with real OutputPolicy routing once #22 lands.
            val silenced = currentSettings.absoluteSilenceEnabled

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
                    val shouldAttempt = !silenced && !stale
                    val audioMode =
                        if (shouldAttempt) {
                            audioFocusController.requestForCue(duck, "DirectionalBeacon")
                        } else {
                            CueAudioMode.SUPPRESSED
                        }
                    if (shouldAttempt) {
                        audioEngine.playDirectionalBeacon(event.pan, event.pitchHz)
                        audioFocusController.releaseAfterCue()
                        audioEngine.pauseAfterCue()
                    }
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
                                    cueInputs(
                                        buildString {
                                            if (relDeg != null) append("relativeDeg=${"%.1f".format(relDeg)}°")
                                            if (accM != null) append(", accuracy=${"%.1f".format(accM)}m")
                                            if (courseIsSmoothed) append(", smoothed=true")
                                        }.trimStart(',', ' '),
                                        duck,
                                        audioMode,
                                    ),
                                outputs = "pan=${"%.3f".format(event.pan)}, pitchHz=${"%.0f".format(event.pitchHz)} Hz",
                                played =
                                    when {
                                        stale -> {
                                            val ageMs = loc?.let { nowMs - it.timestamp }
                                            "Suppressed (stale GPS fix${ageMs?.let { ", ${it}ms old" } ?: ""}): " +
                                                "tone @ ${"%.0f".format(event.pitchHz)} Hz"
                                        }

                                        silenced -> {
                                            "Suppressed (silence mode): tone @ ${"%.0f".format(event.pitchHz)} Hz"
                                        }

                                        else -> {
                                            "Tone @ ${"%.0f".format(event.pitchHz)} Hz"
                                        }
                                    },
                                extra = extra,
                            ),
                        )
                    }
                }

                is AudioCueEvent.AlignmentPing -> {
                    val shouldAttempt = !silenced
                    val audioMode =
                        if (shouldAttempt) {
                            audioFocusController.requestForCue(duck, "AlignmentPing")
                        } else {
                            CueAudioMode.SUPPRESSED
                        }
                    if (shouldAttempt) {
                        audioEngine.playAlignmentPing(event.pan, event.pitchHz)
                        audioFocusController.releaseAfterCue()
                        audioEngine.pauseAfterCue()
                    }
                    scope.launch {
                        audioEventLog.append(
                            AudioLogEntry(
                                timestampMs = nowMs,
                                kind = AudioLogEntry.Kind.ALIGNMENT_PING,
                                trigger = "Alignment ping",
                                inputs =
                                    cueInputs(
                                        relDeg?.let { "relativeDeg=${"%.1f".format(it)}°" } ?: "",
                                        duck,
                                        audioMode,
                                    ),
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
                    val shouldAttempt = !silenced
                    val audioMode =
                        if (shouldAttempt) {
                            audioFocusController.requestForCue(duck, "WrongVector")
                        } else {
                            CueAudioMode.SUPPRESSED
                        }
                    if (shouldAttempt) {
                        audioEngine.playWrongVector()
                        audioFocusController.releaseAfterCue()
                        audioEngine.pauseAfterCue()
                    }
                    scope.launch {
                        audioEventLog.append(
                            AudioLogEntry(
                                timestampMs = nowMs,
                                kind = AudioLogEntry.Kind.DIRECTIONAL_BEACON,
                                trigger = "WrongVector",
                                inputs =
                                    cueInputs(
                                        relDeg?.let { "relativeDeg=${"%.1f".format(it)}°" } ?: "",
                                        duck,
                                        audioMode,
                                    ),
                                outputs = "660 Hz → 440 Hz descending",
                                played = if (silenced) "Suppressed (silence mode): wrong-vector earcon" else "Wrong-vector earcon",
                            ),
                        )
                    }
                }
            }
        }
    }

private fun cueInputs(
    inputs: String,
    duckAudioEnabled: Boolean,
    audioMode: CueAudioMode,
): String =
    listOf(
        inputs.takeIf(String::isNotEmpty),
        "duckAudioEnabled=$duckAudioEnabled",
        "audioMode=${audioMode.logValue}",
    ).filterNotNull().joinToString(", ")

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
