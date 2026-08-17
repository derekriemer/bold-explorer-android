package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units

/**
 * What the follow should emit on this fix by way of progress.
 *
 * A value, not an effect. The producer decides; `GpsViewModel` speaks and beeps. That split is what
 * lets these decisions be JVM-tested and inherited by iOS, and it is the seam the next producer —
 * S8's "300 feet until a right turn" — is added beside rather than inside.
 */
data class ProgressCue(
    val earcon: Boolean,
    val speech: String?,
)

/**
 * The periodic "you are still following, and this much is left" cue (ADR 0001, S6).
 *
 * Replaces "Checkpoint 12 of 40", which reported an index into a vertex array at whatever rate the
 * trail happened to be recorded at. Remaining distance answers the question the walker actually has,
 * and answers it the same way whatever the recording density.
 */
class ProgressCueProducer {
    private var lastEarconAtMs = Long.MIN_VALUE
    private var lastSpeechAtMs = Long.MIN_VALUE

    fun onFix(
        nowMs: Long,
        polyline: TrailPolyline,
        alongTrackM: Double,
        remainingM: Double,
        units: Units,
        lastSpokeAtMs: Long,
    ): ProgressCue {
        val earcon = elapsedSinceMs(nowMs, lastEarconAtMs) >= NavigationPolicy.PROGRESS_EARCON_INTERVAL_MS
        if (earcon) lastEarconAtMs = nowMs

        // The beep carries presence and never collides with speech, so it is deliberately not
        // subject to either suppression below.
        val due = elapsedSinceMs(nowMs, lastSpeechAtMs) >= NavigationPolicy.PROGRESS_SPEECH_INTERVAL_MS
        val yielding = elapsedSinceMs(nowMs, lastSpokeAtMs) < NavigationPolicy.PROGRESS_YIELD_MS
        val straight = FollowCuePolicy.isStraightAhead(polyline, alongTrackM)
        val speech =
            if (due && !yielding && straight) {
                lastSpeechAtMs = nowMs
                "${formatSpokenDistance(remainingM, units)} to go"
            } else {
                null
            }

        return ProgressCue(earcon = earcon, speech = speech)
    }

    /** Forget the cadence — a new follow starts its own rhythm rather than inheriting one. */
    fun reset() {
        lastEarconAtMs = Long.MIN_VALUE
        lastSpeechAtMs = Long.MIN_VALUE
    }
}

/**
 * Milliseconds elapsed since [atMs], treating the "never yet" sentinel `Long.MIN_VALUE` as
 * arbitrarily long ago rather than subtracting it directly.
 *
 * `nowMs - Long.MIN_VALUE` overflows for any non-negative `nowMs`: `Long.MIN_VALUE` is two's
 * complement's one self-negating value, so the subtraction wraps back around to a huge *negative*
 * number instead of the huge positive one "infinitely long ago" needs. Left unguarded, that silently
 * flips every "nothing has happened yet" case into "this just happened", which is backwards for both
 * callers here — it would suppress the very first earcon/speech and simultaneously force `yielding`
 * true forever.
 */
private fun elapsedSinceMs(
    nowMs: Long,
    atMs: Long,
): Long = if (atMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - atMs
