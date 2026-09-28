package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the follow should emit on this fix by way of the next turn (ADR 0001, S8).
 *
 * A value, not an effect — same split as [ProgressCue], added beside it as that type's own doc
 * anticipated.
 *
 * @property bend the next turn as of this fix, whenever one exists — set on every return from
 *   [BendCueProducer.onFix], not only when [speech] is non-null. Speech is gated by cadence
 *   (yield/throttle/dedup); a display consumer wanting "what is the next turn right now" needs the
 *   fact independent of whether this particular fix was allowed to speak it, and independent of
 *   running its own separate `BendDetector.findNextBend` scan to get it (#104 review finding, PR
 *   #144 — `GpsViewModel` was doing exactly that, once per fix, unconditionally, duplicating the
 *   scan [BendCueProducer] already does internally for the tracked-anchor case).
 */
data class BendCue(
    val speech: String?,
    val disposition: String,
    val bend: Bend?,
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
    /**
     * @property turnDeg carried alongside the anchor so a tracked anchor's remaining stages can be
     *   evaluated directly from this remembered position (see [onFix]) — never by asking
     *   [BendDetector] to re-find the same anchor, which only ever returns vertices strictly ahead
     *   of the current position and would silently drop this anchor, and its still-owed stages, the
     *   moment a fix lands even slightly past it (review finding, PR #134).
     */
    private data class Progress(val anchorM: Double, val turnDeg: Double, val stage: BendStage)

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
     * @param lastSpokeAtMs when *anything* (any cue, any producer) last actually spoke, shared
     *   across the whole follow — not [lastSpeechAtMs], which is this producer's own, bend-specific
     *   cadence. Yielding here mirrors [ProgressCue.onFix]'s identical parameter (review finding,
     *   PR #134): without it, a bend cue evaluated the same fix as a more important alert (an
     *   off-trail warning, a match-state change) had no way to know something else had just spoken,
     *   and could queue right behind it instead of waiting its turn.
     */
    fun onFix(
        nowMs: Long,
        polyline: TrailPolyline,
        alongTrackM: Double?,
        direction: TravelDirection,
        units: Units,
        lastSpokeAtMs: Long,
    ): BendCue {
        if (alongTrackM == null) return BendCue(null, "bail:unconfirmed", null)

        progress?.let { p ->
            val stillAhead =
                when (direction) {
                    TravelDirection.Forward -> alongTrackM <= p.anchorM + tuning.anchorToleranceM
                    TravelDirection.Reverse -> alongTrackM >= p.anchorM - tuning.anchorToleranceM
                }
            if (!stillAhead) progress = null
        }

        // Resolved once and carried on every return via BendCue.bend, whether or not this fix ends
        // up speaking -- a display consumer needs "what is the next turn right now" independent of
        // speech cadence, and must not run its own separate findNextBend scan to get it (see
        // BendCue.bend's doc). Deliberately computed before the yield check below: yielding governs
        // speech only, and the fact itself must not go stale on a fix that yielded to something else.
        val bend = resolveBend(alongTrackM, polyline, direction)

        // Checked after self-correction (bookkeeping, not speech) but before anything that could
        // produce a cue -- yielding must suppress every stage equally, not just a brand-new anchor.
        val yieldElapsedMs = elapsedSinceMs(nowMs, lastSpokeAtMs)
        if (yieldElapsedMs < NavigationPolicy.PROGRESS_YIELD_MS) {
            return BendCue(null, "bail:yield_${yieldElapsedMs}ms", bend)
        }

        // An anchor already being tracked is evaluated directly from its own remembered position --
        // never through BendDetector, which only returns vertices strictly ahead of alongTrackM and
        // would drop this one (and its still-owed CLOSE/AT_TURN stages) the instant a fix lands past
        // it, before stillAhead's tolerance would have cleared it (review finding, PR #134).
        val tracked = progress
        if (tracked != null && tracked.stage != BendStage.AT_TURN) {
            val distanceAheadM = abs(alongTrackM - tracked.anchorM)
            val targetStage = stageFor(distanceAheadM)
            if (targetStage.ordinal <= tracked.stage.ordinal) {
                return BendCue(null, "bail:already_announced", bend)
            }
            return speak(tracked.anchorM, tracked.turnDeg, targetStage, distanceAheadM, units, nowMs, bend)
        }

        if (bend == null) return BendCue(null, "bail:no_bend_ahead", null)

        val isTrackedAnchor =
            tracked != null && abs(tracked.anchorM - bend.anchorAlongTrackM) <= tuning.anchorToleranceM
        if (isTrackedAnchor) {
            // Only reachable once tracked.stage == AT_TURN (the branch above already handles every
            // other case) -- fully announced, and still ahead by index even though nothing more is
            // owed for it.
            return BendCue(null, "bail:already_announced", bend)
        }

        // A brand-new anchor's very first cue is throttled against whatever last spoke, cross-anchor
        // defence in depth; a stage advance on an anchor already being tracked is never throttled --
        // it is the deliberate, tightly-spaced follow-up the whole design exists to give, not a rival
        // interruption.
        val sinceLastSpeechMs = lastSpeechAtMs?.let { nowMs - it }
        if (sinceLastSpeechMs != null && sinceLastSpeechMs < tuning.speechIntervalMs) {
            return BendCue(null, "bail:throttled_${sinceLastSpeechMs}ms", bend)
        }

        val targetStage = stageFor(bend.distanceAheadM)
        return speak(bend.anchorAlongTrackM, bend.turnDeg, targetStage, bend.distanceAheadM, units, nowMs, bend)
    }

    /**
     * The next turn as of [alongTrackM], resolved the cheap way whenever possible: a tracked
     * anchor not yet [BendStage.AT_TURN] is reconstructed directly from its own remembered
     * position (O(1)), never by asking [BendDetector] to re-scan for the same anchor it would only
     * find again at scan cost. Falls through to a real scan once there is nothing tracked, or once
     * the tracked anchor has been fully announced (`AT_TURN`) and the search must move on to
     * whatever comes after it.
     */
    private fun resolveBend(
        alongTrackM: Double,
        polyline: TrailPolyline,
        direction: TravelDirection,
    ): Bend? {
        val tracked = progress
        if (tracked != null && tracked.stage != BendStage.AT_TURN) {
            return Bend(tracked.anchorM, abs(alongTrackM - tracked.anchorM), tracked.turnDeg)
        }
        return BendDetector.findNextBend(polyline, alongTrackM, direction, tuning)
    }

    private fun stageFor(distanceAheadM: Double): BendStage =
        when {
            distanceAheadM <= tuning.atAnchorM -> BendStage.AT_TURN
            distanceAheadM <= tuning.closeRangeM -> BendStage.CLOSE
            else -> BendStage.APPROACH
        }

    private fun speak(
        anchorM: Double,
        turnDeg: Double,
        stage: BendStage,
        distanceAheadM: Double,
        units: Units,
        nowMs: Long,
        bend: Bend?,
    ): BendCue {
        progress = Progress(anchorM, turnDeg, stage)
        lastSpeechAtMs = nowMs
        val dirLabel = TurnSeverity.of(turnDeg).label()
        val speech =
            when (stage) {
                BendStage.APPROACH -> "${formatSpokenDistance(distanceAheadM, units)} until a $dirLabel turn"
                BendStage.CLOSE -> "Turn coming up, $dirLabel"
                BendStage.AT_TURN -> "Turn $dirLabel"
            }
        return BendCue(
            speech = speech,
            disposition = "speak:${stage.name.lowercase()}_${distanceAheadM.roundToInt()}m_${turnDeg.roundToInt()}deg",
            bend = bend,
        )
    }

    /** Forget the throttle and the tracked anchor's progress — a new follow starts clean. */
    fun reset() {
        progress = null
        lastSpeechAtMs = null
    }
}

/**
 * Milliseconds elapsed since [atMs], treating the "never yet" sentinel `Long.MIN_VALUE` as
 * arbitrarily long ago rather than subtracting it directly (`nowMs - Long.MIN_VALUE` overflows).
 * Same idiom as `ProgressCue.kt`'s private copy of this exact function — file-local rather than
 * shared because each is small enough that the duplication costs less than the coupling would; see
 * that copy's own doc for the fuller overflow explanation and a third occurrence of the same
 * hazard, `TrailGuidanceCoordinator.lastOrdinaryGuidanceAtMs`.
 */
private fun elapsedSinceMs(
    nowMs: Long,
    atMs: Long,
): Long = if (atMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - atMs
