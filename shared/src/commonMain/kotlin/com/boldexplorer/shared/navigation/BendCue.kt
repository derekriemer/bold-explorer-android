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
 * The "distance and direction to the next turn" speech cue (ADR 0001, S8).
 *
 * [BendDetector] is stateless — it always answers "what is the next turn from here" fresh. This
 * producer owns the one piece of state that has to persist across fixes: which turn has already
 * been spoken, so a hairpin doesn't re-announce itself on every fix while the scan window
 * straddles it.
 *
 * That state is deliberately *not* a permanent "announced anchors" ledger. An anchor only counts
 * as already-spoken while it is still ahead of the walker's confirmed position, in their direction
 * of travel — the mark clears itself the moment the confirmed position shows the anchor has fallen
 * behind. This matters for the same reason [alongTrackM] itself is trustworthy: if an early fix's
 * position was wrong (a bad acquisition, a dead-reckoned drift) and the matcher's reacquisition
 * ladder later corrects it, or if the walker genuinely backtracks past an announced turn and
 * re-approaches it, nothing here can be left holding a stale commitment from before the
 * correction — every fix re-derives "ahead or behind" from the current [alongTrackM], never from
 * what a past fix believed.
 */
class BendCueProducer(
    private val tuning: BendTuning = BendTuning.DEFAULT,
) {
    private var announcedAnchorM: Double? = null
    private var lastSpeechAtMs: Long? = null

    /**
     * @param nowMs the fix's timestamp, for [BendTuning.speechIntervalMs]'s throttle — defence in
     *   depth alongside the anchor dedup below, not a substitute for it. Found in the field
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

        announcedAnchorM?.let { anchor ->
            val stillAhead =
                when (direction) {
                    TravelDirection.Forward -> alongTrackM <= anchor + tuning.anchorToleranceM
                    TravelDirection.Reverse -> alongTrackM >= anchor - tuning.anchorToleranceM
                }
            if (!stillAhead) announcedAnchorM = null
        }

        val bend =
            BendDetector.findNextBend(polyline, alongTrackM, direction, tuning)
                ?: return BendCue(null, "bail:no_bend_ahead")

        if (abs(bend.turnDeg) < tuning.angleThresholdDeg) {
            return BendCue(
                null,
                "bail:turn_${bend.turnDeg.roundToInt()}deg_under_${tuning.angleThresholdDeg.roundToInt()}deg",
            )
        }

        val alreadyAnnounced =
            announcedAnchorM?.let { abs(it - bend.anchorAlongTrackM) <= tuning.anchorToleranceM } ?: false
        if (alreadyAnnounced) return BendCue(null, "bail:already_announced")

        // Throttle checked last, right before actually speaking a genuinely new bend -- not marked
        // as announced here, so a throttled bend still gets its turn once the interval clears
        // rather than being silently dropped for the rest of the approach.
        val sinceLastSpeechMs = lastSpeechAtMs?.let { nowMs - it }
        if (sinceLastSpeechMs != null && sinceLastSpeechMs < tuning.speechIntervalMs) {
            return BendCue(null, "bail:throttled_${sinceLastSpeechMs}ms")
        }

        announcedAnchorM = bend.anchorAlongTrackM
        lastSpeechAtMs = nowMs
        val distLabel = formatSpokenDistance(bend.distanceAheadM, units)
        val dirLabel = BearingComputer.toRelative(bend.turnDeg)
        return BendCue(
            speech = "$distLabel until a $dirLabel turn",
            disposition = "speak:turn_${bend.distanceAheadM.roundToInt()}m_${bend.turnDeg.roundToInt()}deg",
        )
    }

    /** Forget the throttle and the announced anchor — a new follow starts clean. */
    fun reset() {
        announcedAnchorM = null
        lastSpeechAtMs = null
    }
}
