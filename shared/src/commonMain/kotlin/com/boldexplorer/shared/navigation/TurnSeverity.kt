package com.boldexplorer.shared.navigation

import kotlin.math.abs

/**
 * How sharply a piece of trail geometry turns, independent of any caller's phrasing.
 *
 * Deliberately separate from [BearingComputer.toRelative]'s buckets: that one answers "how far off
 * is the walker's current heading from the target bearing right now" — real-time steering
 * correction, where every few degrees matters near zero. This one answers "how sharp is this one
 * specific trail-geometry turn" — a discrete classification of the trail itself, made once per
 * turn rather than every fix. [BendCue] mislabelling turns by reusing the steering buckets (#123,
 * field-confirmed 2026-09-02: a 58° turn read out as "slight right") is why this exists as its own
 * type instead of another `toRelative` call site.
 *
 * Boundaries follow Valhalla's production turn classifier (`src/baldr/turn.cc`): an ~11° "not
 * straight" cutoff, which also matches [NavigationPolicy.TURN_ANGLE_THRESHOLD_DEG] — the same
 * angle [BendDetector] already gates candidate turns on, so a [Bend] this classifies is never
 * [STRAIGHT] in practice.
 */
enum class TurnSeverity {
    STRAIGHT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    ;

    companion object {
        /** [turnDeg]: positive = right, negative = left, [com.boldexplorer.shared.geo.deltaAngle]'s convention. */
        fun of(turnDeg: Double): TurnSeverity =
            when {
                abs(turnDeg) < 11.0 -> STRAIGHT
                turnDeg in 11.0..44.0 -> SLIGHT_RIGHT
                turnDeg in 44.0..135.0 -> RIGHT
                turnDeg in 135.0..180.0 -> SHARP_RIGHT
                turnDeg in -44.0..-11.0 -> SLIGHT_LEFT
                turnDeg in -135.0..-44.0 -> LEFT
                else -> SHARP_LEFT
            }
    }
}

/**
 * The one place this app's wording for a [TurnSeverity] lives (#126) — every caller that speaks or
 * displays a turn's severity should go through this, so swapping the wording later means changing
 * it here once rather than hunting down each call site.
 */
fun TurnSeverity.label(): String =
    when (this) {
        TurnSeverity.STRAIGHT -> "straight ahead"
        TurnSeverity.SLIGHT_RIGHT -> "slight right"
        TurnSeverity.RIGHT -> "right"
        TurnSeverity.SHARP_RIGHT -> "sharp right"
        TurnSeverity.SLIGHT_LEFT -> "slight left"
        TurnSeverity.LEFT -> "left"
        TurnSeverity.SHARP_LEFT -> "sharp left"
    }
