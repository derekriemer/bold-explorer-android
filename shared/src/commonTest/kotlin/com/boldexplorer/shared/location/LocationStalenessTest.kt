package com.boldexplorer.shared.location

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationStalenessTest {
    @Test
    fun noFixEver_isStale() {
        assertTrue(isLocationStale(nowMs = 10_000L, fixTimestampMs = null))
    }

    @Test
    fun recentFix_notStale() {
        assertFalse(isLocationStale(nowMs = 10_000L, fixTimestampMs = 9_000L, thresholdMs = 8_000L))
    }

    @Test
    fun oldFix_isStale() {
        assertTrue(isLocationStale(nowMs = 20_000L, fixTimestampMs = 9_000L, thresholdMs = 8_000L))
    }

    @Test
    fun exactlyAtThreshold_notYetStale() {
        assertFalse(isLocationStale(nowMs = 17_000L, fixTimestampMs = 9_000L, thresholdMs = 8_000L))
    }
}
