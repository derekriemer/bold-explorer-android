package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.settings.Units
import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenDistanceTest {
    @Test
    fun metricSpeaksWholeWordsNotAbbreviations() {
        // "km" is a coin toss on how a TTS engine renders it; imperial already speaks "miles".
        assertEquals("500 meters", formatSpokenDistance(500.0, Units.METRIC))
        assertEquals("1.3 kilometers", formatSpokenDistance(1300.0, Units.METRIC))
    }

    @Test
    fun imperialSwitchesToMilesAtAMileNotAtAThousandFeet() {
        // The defect: the old threshold was 1000 feet while the divisor was 5280, so everything
        // from 1000 ft to a mile spoke as a fraction — "0.2 miles" where "1000 feet" was meant.
        assertEquals("1000 feet", formatSpokenDistance(304.8, Units.IMPERIAL))
        assertEquals("5000 feet", formatSpokenDistance(1524.0, Units.IMPERIAL))
        assertEquals("1.2 miles", formatSpokenDistance(2000.0, Units.IMPERIAL))
    }

    @Test
    fun zeroAndTinyDistancesStillRender() {
        assertEquals("0 meters", formatSpokenDistance(0.0, Units.METRIC))
        assertEquals("0 feet", formatSpokenDistance(0.0, Units.IMPERIAL))
    }
}
