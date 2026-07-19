package com.boldexplorer.shared.output

import com.boldexplorer.shared.settings.AppSettings

/** Whether speech / the live region are allowed for this event, given current settings. */
data class OutputDecision(
    val speechAllowed: Boolean,
    val liveRegionAllowed: Boolean,
)

/**
 * Pure, synchronous policy evaluation — deliberately not `suspend`. [OutputRouter] evaluates this
 * once per event, serially, inside its single collection loop, reading an already-cached
 * [AppSettings] value rather than a per-event `suspend fun load()` call. That ordering matters:
 * independently `suspend`-loading settings per emitted event risked reordering announcements
 * relative to their true emission order (see issue #11's adversarial review).
 */
interface OutputPolicy {
    fun evaluate(
        event: OutputEvent,
        settings: AppSettings,
    ): OutputDecision
}

/**
 * Encodes today's `spokenGuidanceEnabled` setting plus absolute silence mode (#14).
 *
 * Absolute silence suppresses both TTS and the live region for AUTOMATIC and
 * INTERACTION_CONFIRMATION events, but deliberately exempts USER_REQUESTED events (e.g. "Read
 * alignment now") — an explicit in-the-moment request still gets a response even while silence
 * mode is on; only unsolicited/automatic output is suppressed. Silence mode never auto-restores —
 * this function has no notion of time, so there's nothing here that could re-enable it.
 *
 * Does not route earcons (`AudioCueScheduler`/`AudioCuePlayer` don't consult this policy yet —
 * see issue #22's stopgap note in `AudioCuePlayer.dispatch()`).
 */
class DefaultOutputPolicy : OutputPolicy {
    override fun evaluate(
        event: OutputEvent,
        settings: AppSettings,
    ): OutputDecision {
        val silenced = settings.absoluteSilenceEnabled && event.origin != OutputOrigin.USER_REQUESTED
        return OutputDecision(
            speechAllowed = !silenced && settings.spokenGuidanceEnabled,
            liveRegionAllowed = !silenced,
        )
    }
}
