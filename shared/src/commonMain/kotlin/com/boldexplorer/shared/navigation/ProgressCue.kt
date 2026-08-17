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

    /**
     * @param alongTrackM null before the match has ever confirmed a position (e.g. a follow just
     *   started, still walking to the trailhead). The earcon does not need this — it is presence,
     *   not position — so it is the one thing here that still fires with it null.
     * @param remainingM null under the same condition as [alongTrackM]; always non-null together
     *   with it in practice, both kept nullable independently so a caller cannot pass one without
     *   the other by construction.
     * @param matchLost the **immediate** match state for this fix — `true` whenever the matcher's
     *   `MatchState` is not `Matched` right now, not the sustained "Lost the trail" state
     *   [MatchStateCueProducer] reports. This is a deliberately different threshold from the one the
     *   earcon's *character* uses at the call site: `confirmedAlongM`/`remainingM` freeze the instant
     *   the match stops being `Matched`, so the very first bad fix already makes the number stale —
     *   speech has to gate on that immediately. The earcon's lost/normal *sound*, by contrast, is
     *   decided by the caller from [MatchStateCueProducer.isLost] (sustained over
     *   `MATCH_LOST_SUSTAIN` fixes), because flapping the tone on every marginal fix would be noise
     *   rather than information. Two different questions; do not collapse them into one threshold.
     */
    fun onFix(
        nowMs: Long,
        polyline: TrailPolyline,
        alongTrackM: Double?,
        remainingM: Double?,
        direction: TravelDirection,
        units: Units,
        lastSpokeAtMs: Long,
        matchLost: Boolean,
    ): ProgressCue {
        // Presence, not position: the earcon fires on its cadence for the whole active follow,
        // whether or not the match has ever confirmed a position and whether or not it currently
        // has one. ADR 0001 is explicit that silence and an unchanged sound are both wrong here —
        // silence during, say, the 200 m walk to an unacquired trailhead reads to a blind user as
        // indistinguishable from the app having crashed.
        val earcon = elapsedSinceMs(nowMs, lastEarconAtMs) >= NavigationPolicy.PROGRESS_EARCON_INTERVAL_MS
        if (earcon) lastEarconAtMs = nowMs

        // The beep carries presence and never collides with speech, so it is deliberately not
        // subject to either suppression below.
        val due = elapsedSinceMs(nowMs, lastSpeechAtMs) >= NavigationPolicy.PROGRESS_SPEECH_INTERVAL_MS
        val yielding = elapsedSinceMs(nowMs, lastSpokeAtMs) < NavigationPolicy.PROGRESS_YIELD_MS
        val straight = alongTrackM != null && FollowCuePolicy.isStraightAhead(polyline, alongTrackM, direction)
        val speech =
            if (due && !yielding && !matchLost && alongTrackM != null && remainingM != null && straight) {
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
 *
 * See also `TrailGuidanceCoordinator.lastOrdinaryGuidanceAtMs`, which hit the identical hazard from a
 * `Long.MIN_VALUE` sentinel and was fixed with a nullable `Long?` instead of this guard — a different
 * idiom for the same overflow, found independently. Cross-referenced so the next person who finds one
 * of these two finds the other rather than re-deriving a third way.
 */
private fun elapsedSinceMs(
    nowMs: Long,
    atMs: Long,
): Long = if (atMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - atMs
