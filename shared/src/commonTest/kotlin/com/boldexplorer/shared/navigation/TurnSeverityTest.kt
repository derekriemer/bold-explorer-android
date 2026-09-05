package com.boldexplorer.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class TurnSeverityTest {
    @Test
    fun straightAheadIsUnderTheAngleThreshold() {
        assertEquals(TurnSeverity.STRAIGHT, TurnSeverity.of(0.0))
        assertEquals(TurnSeverity.STRAIGHT, TurnSeverity.of(10.0))
        assertEquals(TurnSeverity.STRAIGHT, TurnSeverity.of(-10.0))
    }

    @Test
    fun aFiftyEightDegreeTurnIsPlainRightNotSlight() {
        // Field-confirmed 2026-09-02: "that isn't slight right, that's right" -- the old
        // BearingComputer.toRelative bucketing (tuned for steering deviation, not turn severity)
        // called a 58 degree bend "slight right" because its own boundary sits at 60. Valhalla-style
        // boundaries (#123) put 58 degrees plainly in "right" (44-135).
        assertEquals(TurnSeverity.RIGHT, TurnSeverity.of(58.0))
        assertEquals(TurnSeverity.LEFT, TurnSeverity.of(-58.0))
    }

    @Test
    fun bucketsMatchValhallasBoundaries() {
        assertEquals(TurnSeverity.SLIGHT_RIGHT, TurnSeverity.of(30.0))
        assertEquals(TurnSeverity.RIGHT, TurnSeverity.of(90.0))
        assertEquals(TurnSeverity.SHARP_RIGHT, TurnSeverity.of(150.0))
        assertEquals(TurnSeverity.SLIGHT_LEFT, TurnSeverity.of(-30.0))
        assertEquals(TurnSeverity.LEFT, TurnSeverity.of(-90.0))
        assertEquals(TurnSeverity.SHARP_LEFT, TurnSeverity.of(-150.0))
    }

    @Test
    fun labelsAreLowercasePlainEnglish() {
        assertEquals("straight ahead", TurnSeverity.STRAIGHT.label())
        assertEquals("slight right", TurnSeverity.SLIGHT_RIGHT.label())
        assertEquals("right", TurnSeverity.RIGHT.label())
        assertEquals("sharp right", TurnSeverity.SHARP_RIGHT.label())
        assertEquals("slight left", TurnSeverity.SLIGHT_LEFT.label())
        assertEquals("left", TurnSeverity.LEFT.label())
        assertEquals("sharp left", TurnSeverity.SHARP_LEFT.label())
    }
}
