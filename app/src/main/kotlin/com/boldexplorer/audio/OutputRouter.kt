package com.boldexplorer.audio

import com.boldexplorer.shared.output.OutputCategory
import com.boldexplorer.shared.output.OutputEvent
import com.boldexplorer.shared.output.OutputManager
import com.boldexplorer.shared.output.OutputPolicy
import com.boldexplorer.shared.repository.SettingsRepository
import com.boldexplorer.shared.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** What actually happened for an event's speech presentation. Not "delivered" — a TTS engine
 * accepting an utterance isn't proof it was spoken audibly, so this only claims what the app can
 * actually know: whether TTS was queued at all. */
enum class OutputDisposition {
    QUEUED_FOR_TTS,

    /**
     * Posted to a Compose live region and *not* spoken by the app's own TTS.
     *
     * This is the normal path while the app is in the foreground — the screen reader announces the
     * live region, and speaking as well would talk over it. It is emphatically **not** silence, and
     * the log line says so; the string used to read "Suppressed", which misled analysis of a real
     * walk. What the app cannot know either way is whether a screen reader was running to speak it.
     */
    LIVE_REGION_ONLY,

    /**
     * The app's own TTS stayed quiet because the screen was up, and no live region carried it
     * either — the cue paths in [AudioCuePlayer], which have no live region of their own.
     *
     * Distinct from [LIVE_REGION_ONLY]: there, something did announce it.
     */
    NOT_SPOKEN_FOREGROUND,

    // Neither channel fired — absolute silence mode (#14) suppressed this event entirely.
    SILENCED,
}

/**
 * How this outcome reads in the audio event log.
 *
 * One mapping, because there used to be two. `AudioCuePlayer` classified the same three outcomes
 * with its own `when` chain and its own wording, so the log spelled one state two ways — and this
 * vocabulary is not decoration: a `played` string that read as silence when the user had in fact
 * heard the announcement sent ADR 0001 to a wrong conclusion about a real walk. A single mapping
 * means the next rewording cannot land in one place and miss the other.
 */
fun OutputDisposition.playedLabel(text: String): String =
    when (this) {
        OutputDisposition.QUEUED_FOR_TTS -> "Spoke: '$text'"
        OutputDisposition.LIVE_REGION_ONLY -> "Live region: '$text'"
        OutputDisposition.NOT_SPOKEN_FOREGROUND -> "Not spoken, app in foreground: '$text'"
        OutputDisposition.SILENCED -> "Suppressed (silence mode): '$text'"
    }

/** Live-region text plus a monotonic sequence, so two consecutive identical announcements (e.g.
 * repeated "12 o'clock, 20 meters") still produce a state change TalkBack will re-announce — a
 * bare `StateFlow<String>` would conflate them into a no-op write. */
data class LiveRegionAnnouncement(
    val sequence: Long,
    val text: String,
)

/** User-facing "what did we last emit" surface — distinct from [AudioEventLog], which stays the
 * full-payload debug JSONL log. Unconsumed by UI for now; seam for a future last-output display. */
data class LastOutput(
    val text: String,
    val category: OutputCategory,
    val disposition: OutputDisposition,
)

/**
 * Sole consumer of [OutputManager]'s events (see issue #11). Fans each event out to the live
 * region, TTS, the debug audio log, and the [lastOutput] surface. Owns its own process-lifetime
 * scope, same pattern as [AudioEventLog].
 */
@Singleton
class OutputRouter
    @Inject
    constructor(
        private val outputManager: OutputManager,
        private val policy: OutputPolicy,
        settingsRepo: SettingsRepository,
        private val ttsEngine: TtsEngine,
        private val appForegroundState: AppForegroundState,
        private val audioEventLog: AudioEventLog,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val settings: StateFlow<AppSettings> =
            settingsRepo.observeSettings().stateIn(scope, SharingStarted.Eagerly, AppSettings())

        private var sequence = 0L

        private val _liveRegion = MutableStateFlow(LiveRegionAnnouncement(0L, ""))
        val liveRegion: StateFlow<LiveRegionAnnouncement> = _liveRegion.asStateFlow()

        private val _lastOutput = MutableStateFlow<LastOutput?>(null)
        val lastOutput: StateFlow<LastOutput?> = _lastOutput.asStateFlow()

        /** Idempotent via [OutputManager.startConsuming] — safe to call more than once. */
        fun start() {
            outputManager.startConsuming(scope, ::handle)
        }

        private suspend fun handle(event: OutputEvent) {
            val text = event.speech ?: return
            val decision = policy.evaluate(event, settings.value)

            // History continues being recorded even when silenced (#14) — this log append below
            // is unconditional regardless of disposition.
            var disposition = OutputDisposition.SILENCED
            if (decision.liveRegionAllowed) {
                sequence++
                _liveRegion.value = LiveRegionAnnouncement(sequence, text)
                disposition = OutputDisposition.LIVE_REGION_ONLY
            }
            if (decision.speechAllowed && !appForegroundState.isInForeground.value) {
                runCatching { ttsEngine.speak(text) }
                    .onSuccess { disposition = OutputDisposition.QUEUED_FOR_TTS }
            }
            _lastOutput.value = LastOutput(text, event.category, disposition)

            runCatching {
                audioEventLog.append(
                    AudioLogEntry(
                        timestampMs = System.currentTimeMillis(),
                        kind = AudioLogEntry.Kind.TTS_ANNOUNCEMENT,
                        trigger = event.kind.name,
                        inputs = "text=\"$text\"",
                        outputs = "",
                        played = disposition.playedLabel(text),
                        // "ttsDelivered" preserved as an explicit queryable field for existing log
                        // tooling — same name the pre-#11 announce() helper used.
                        extra = event.context + mapOf("ttsDelivered" to (disposition == OutputDisposition.QUEUED_FOR_TTS)),
                    ),
                )
            }
        }
    }
