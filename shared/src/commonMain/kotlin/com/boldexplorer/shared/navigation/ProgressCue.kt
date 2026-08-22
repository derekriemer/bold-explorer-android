package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units
import kotlin.math.roundToInt

/**
 * What the follow should emit on this fix by way of progress.
 *
 * A value, not an effect. The producer decides; `GpsViewModel` speaks and beeps. That split is what
 * lets these decisions be JVM-tested and inherited by iOS, and it is the seam the next producer —
 * S8's "300 feet until a right turn" — is added beside rather than inside.
 *
 * @param disposition why, in `bail:`/`speak:` form (#85) — the audio log's `played` field for this
 *   producer, same shape as `TrailGuidanceCoordinator`'s detector dispositions. Silence used to be
 *   indistinguishable between four different causes; a field walk had to eliminate them by hand.
 */
data class ProgressCue(
    val speech: String?,
    val disposition: String,
)

/**
 * The periodic "this much is left" speech cue (ADR 0001, S6; earcon retired, Amendment 3).
 *
 * Replaces "Checkpoint 12 of 40", which reported an index into a vertex array at whatever rate the
 * trail happened to be recorded at. Remaining distance answers the question the walker actually has,
 * and answers it the same way whatever the recording density.
 */
class ProgressCueProducer {
    private var lastSpeechAtMs = Long.MIN_VALUE

    /**
     * @param alongTrackM null before the match has ever confirmed a position (e.g. a follow just
     *   started, still walking to the trailhead) — speech is suppressed until it is non-null.
     * @param remainingM null under the same condition as [alongTrackM]; always non-null together
     *   with it in practice, both kept nullable independently so a caller cannot pass one without
     *   the other by construction.
     * @param matchLost the **immediate** match state for this fix — `true` whenever the matcher's
     *   `MatchState` is not `Matched` right now. `confirmedAlongM`/`remainingM` freeze the instant the
     *   match stops being `Matched`, so the very first bad fix already makes the number stale — speech
     *   has to gate on that immediately rather than waiting for a sustained loss.
     * @param travelled [CompletionEvidence.travelled] for this session — whether confirmed along-track
     *   travel has cleared [NavigationPolicy.completionTravelGuardM]. A "0 feet to go" reading is only
     *   trustworthy on the same evidence that would let the trail actually complete (#91): a follow
     *   that armed near the far end of a loop can read near-zero remaining on its very first fix,
     *   before the walker has gone anywhere, and that must stay silent rather than announcing arrival.
     *   Non-zero readings are unaffected — the guard only concerns the "nothing left" claim.
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
        travelled: Boolean,
    ): ProgressCue {
        // The decision, then its description — not the description parsed back into a decision (see
        // TrailGuidanceCoordinator.offTrailDisposition for why: a spoken cue must never depend on the
        // spelling of the log string that describes it). Each branch below produces both together.
        val yieldElapsedMs = elapsedSinceMs(nowMs, lastSpokeAtMs)
        val (speech, disposition) =
            when {
                elapsedSinceMs(nowMs, lastSpeechAtMs) < NavigationPolicy.PROGRESS_SPEECH_INTERVAL_MS ->
                    null to "bail:not_due"
                yieldElapsedMs < NavigationPolicy.PROGRESS_YIELD_MS ->
                    null to "bail:yield_${yieldElapsedMs}ms"
                matchLost -> null to "bail:match_lost"
                alongTrackM == null || remainingM == null -> null to "bail:unconfirmed"
                else -> {
                    val sagittaM = FollowCuePolicy.sagittaAhead(polyline, alongTrackM, direction)
                    when {
                        sagittaM > NavigationPolicy.STRAIGHT_SAGITTA_M ->
                            null to
                                "bail:not_straight_sagitta_${sagittaM.roundToInt()}m_over_" +
                                "${NavigationPolicy.STRAIGHT_SAGITTA_M.roundToInt()}m"

                        !travelled && roundsToZero(remainingM, units) ->
                            null to "bail:untrusted_zero_remaining_${remainingM.roundToInt()}m"

                        else -> {
                            lastSpeechAtMs = nowMs
                            "${formatSpokenDistance(remainingM, units)} to go" to
                                "speak:remaining_${remainingM.roundToInt()}m"
                        }
                    }
                }
            }

        return ProgressCue(speech = speech, disposition = disposition)
    }

    /** Forget the cadence — a new follow starts its own rhythm rather than inheriting one. */
    fun reset() {
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
