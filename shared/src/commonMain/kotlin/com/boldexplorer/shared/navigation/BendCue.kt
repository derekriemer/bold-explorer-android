package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the follow should emit on this fix by way of the next turn (ADR 0001, S8).
 *
 * A value, not an effect — same split as [ProgressCue], added beside it as that type's own doc
 * anticipated.
 */
data class BendCue(
    val speech: String?,
    val disposition: String,
)

/**
 * How far into announcing one particular turn [BendCueProducer] has gotten (#124).
 *
 * Stages are cumulative and only ever advance forward for a given anchor — [APPROACH] first
 * (possibly throttled), then [CLOSE], then [AT_TURN] — never re-fired once reached, and reset
 * entirely (back to nothing tracked) only by [BendCueProducer]'s self-correction, never by
 * regressing to an earlier stage.
 */
enum class BendStage { APPROACH, CLOSE, AT_TURN }

/**
 * The "distance and direction to the next turn" speech cue (ADR 0001, S8).
 *
 * [BendDetector] is stateless — it always answers "what is the next turn from here" fresh. This
 * producer owns the one piece of state that has to persist across fixes: how much of that turn's
 * staged announcement ([BendStage]) has already been given, so a hairpin doesn't re-announce
 * itself on every fix while the scan window straddles it, but a single distant approach cue also
 * isn't the *only* thing ever said about it (#124: field-confirmed 2026-09-02/03, a correct and
 * correctly-worded approach cue given ~70m out still read as "missed" because nothing confirmed
 * the turn as the walker actually reached it).
 *
 * That state is deliberately *not* a permanent "announced anchors" ledger. An anchor's progress
 * only counts as current while it is still ahead of the walker's confirmed position, in their
 * direction of travel — the mark clears itself entirely the moment the confirmed position shows
 * the anchor has fallen behind. This matters for the same reason [alongTrackM] itself is
 * trustworthy: if an early fix's position was wrong (a bad acquisition, a dead-reckoned drift) and
 * the matcher's reacquisition ladder later corrects it, or if the walker genuinely backtracks past
 * an announced turn and re-approaches it, nothing here can be left holding a stale commitment from
 * before the correction — every fix re-derives "ahead or behind" from the current [alongTrackM],
 * never from what a past fix believed.
 */
class BendCueProducer(
    private val tuning: BendTuning = BendTuning.DEFAULT,
) {
    private data class Progress(val anchorM: Double, val stage: BendStage)

    private var progress: Progress? = null
    private var lastSpeechAtMs: Long? = null

    /**
     * @param nowMs the fix's timestamp, for [BendTuning.speechIntervalMs]'s throttle — defence in
     *   depth alongside the anchor dedup below, not a substitute for it, and only gating a
     *   newly-found anchor's [BendStage.APPROACH] cue (see [NavigationPolicy.TURN_SPEECH_INTERVAL_MS]
     *   for why [BendStage.CLOSE]/[BendStage.AT_TURN] are exempt). Found in the field
     *   (2026-09-02): an unstable anchor (fixed in [BendDetector]) kept reporting a slightly
     *   different "next" turn on consecutive fixes, defeating the dedup and re-announcing every
     *   few seconds for minutes on a stretch with two turns close together. A time floor bounds
     *   the damage the same way even if an anchor is ever unstable again.
     * @param alongTrackM the confirmed along-track position (`TrailMatch.confirmedAlongM`), never
     *   `TrailFollower.currentIndex` — see the class doc for why that distinction is load-bearing.
     *   Null before the match has ever confirmed a position; speech is suppressed until it is not.
     */
    fun onFix(
        nowMs: Long,
        polyline: TrailPolyline,
        alongTrackM: Double?,
        direction: TravelDirection,
        units: Units,
    ): BendCue {
        if (alongTrackM == null) return BendCue(null, "bail:unconfirmed")

        progress?.let { p ->
            val stillAhead =
                when (direction) {
                    TravelDirection.Forward -> alongTrackM <= p.anchorM + tuning.anchorToleranceM
                    TravelDirection.Reverse -> alongTrackM >= p.anchorM - tuning.anchorToleranceM
                }
            if (!stillAhead) progress = null
        }

        val bend =
            BendDetector.findNextBend(polyline, alongTrackM, direction, tuning)
                ?: return BendCue(null, "bail:no_bend_ahead")

        val current = progress
        val isTrackedAnchor =
            current != null && abs(current.anchorM - bend.anchorAlongTrackM) <= tuning.anchorToleranceM

        val targetStage =
            when {
                bend.distanceAheadM <= tuning.atAnchorM -> BendStage.AT_TURN
                bend.distanceAheadM <= tuning.closeRangeM -> BendStage.CLOSE
                else -> BendStage.APPROACH
            }
        val currentStage = if (isTrackedAnchor) current!!.stage else null

        if (currentStage != null && targetStage.ordinal <= currentStage.ordinal) {
            return BendCue(null, "bail:already_announced")
        }

        // A brand-new anchor's very first cue is throttled against whatever last spoke, cross-anchor
        // defence in depth; a stage advance on an anchor already being tracked is never throttled --
        // it is the deliberate, tightly-spaced follow-up the whole design exists to give, not a rival
        // interruption.
        if (!isTrackedAnchor) {
            val sinceLastSpeechMs = lastSpeechAtMs?.let { nowMs - it }
            if (sinceLastSpeechMs != null && sinceLastSpeechMs < tuning.speechIntervalMs) {
                return BendCue(null, "bail:throttled_${sinceLastSpeechMs}ms")
            }
        }

        progress = Progress(bend.anchorAlongTrackM, targetStage)
        lastSpeechAtMs = nowMs
        val dirLabel = TurnSeverity.of(bend.turnDeg).label()
        val speech =
            when (targetStage) {
                BendStage.APPROACH -> "${formatSpokenDistance(bend.distanceAheadM, units)} until a $dirLabel turn"
                BendStage.CLOSE -> "Turn coming up, $dirLabel"
                BendStage.AT_TURN -> "Turn $dirLabel"
            }
        val stageTag = targetStage.name.lowercase()
        return BendCue(
            speech = speech,
            disposition = "speak:${stageTag}_${bend.distanceAheadM.roundToInt()}m_${bend.turnDeg.roundToInt()}deg",
        )
    }

    /** Forget the throttle and the tracked anchor's progress — a new follow starts clean. */
    fun reset() {
        progress = null
        lastSpeechAtMs = null
    }
}
