package com.boldexplorer.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsMigrationTest {
    @Test
    fun units_nullReturnsDefault() {
        val result = migrateStoredValue(UnitsPrefSpec, null)
        assertEquals("imperial", result)
    }

    @Test
    fun units_legacyPlainString_metric() {
        val result = migrateStoredValue(UnitsPrefSpec, "\"metric\"")
        assertEquals("metric", result)
    }

    @Test
    fun units_versionedWrapper_imperial() {
        val result = migrateStoredValue(UnitsPrefSpec, """{"v":1,"value":"imperial"}""")
        assertEquals("imperial", result)
    }

    @Test
    fun units_invalidValue_returnsDefault() {
        val result = migrateStoredValue(UnitsPrefSpec, """{"v":1,"value":"cubits"}""")
        assertEquals("imperial", result)
    }

    @Test
    fun compass_legacyTrue_mapsToTrueString() {
        // v0 boolean true → "true"
        val result = migrateStoredValue(CompassPrefSpec, "true")
        assertEquals("true", result)
    }

    @Test
    fun compass_legacyFalse_mapToMagnetic() {
        val result = migrateStoredValue(CompassPrefSpec, "false")
        assertEquals("magnetic", result)
    }

    @Test
    fun audioCues_legacyTrueString_isTrue() {
        val result = migrateStoredValue(AudioCuesPrefSpec, "true")
        assertEquals(true, result)
    }

    @Test
    fun audioCues_legacyFalseString_isFalse() {
        val result = migrateStoredValue(AudioCuesPrefSpec, "false")
        assertEquals(false, result)
    }

    @Test
    fun audioCues_versionedFalse() {
        val result = migrateStoredValue(AudioCuesPrefSpec, """{"v":1,"value":false}""")
        assertEquals(false, result)
    }

    @Test
    fun spokenGuidance_legacyFalseString_isFalse() {
        val result = migrateStoredValue(SpokenGuidancePrefSpec, "false")
        assertEquals(false, result)
    }

    @Test
    fun beaconCues_legacyFalseString_isFalse() {
        val result = migrateStoredValue(BeaconCuesPrefSpec, "false")
        assertEquals(false, result)
    }

    @Test
    fun duckAudio_versionedTrue_survivesPersistenceRoundTrip() {
        val stored = serializeVersioned(DuckAudioPrefSpec.currentVersion, true)

        assertEquals(true, migrateStoredValue(DuckAudioPrefSpec, stored))
    }

    @Test
    fun absoluteSilence_nullReturnsDefaultFalse() {
        val result = migrateStoredValue(AbsoluteSilencePrefSpec, null)
        assertEquals(false, result)
    }

    @Test
    fun absoluteSilence_versionedWrapper_true() {
        val result = migrateStoredValue(AbsoluteSilencePrefSpec, """{"v":1,"value":true}""")
        assertEquals(true, result)
    }

    @Test
    fun bearingDisplay_legacyClock() {
        val result = migrateStoredValue(BearingDisplayPrefSpec, "\"clock\"")
        assertEquals("clock", result)
    }

    @Test
    fun serialize_roundTrip() {
        val serialized = serializeVersioned(1, "metric")
        val result = migrateStoredValue(UnitsPrefSpec, serialized)
        assertEquals("metric", result)
    }
}
