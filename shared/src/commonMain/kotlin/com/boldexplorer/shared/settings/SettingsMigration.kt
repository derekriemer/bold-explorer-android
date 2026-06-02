package com.boldexplorer.shared.settings

// Port of the PrefSpec / getOrInitWithMigrate pattern from src/stores/usePrefs.ts.
// The DataStore read/write is Android-side; this class contains only the pure migration logic.

data class PrefSpec<T>(
    val key: String,
    val currentVersion: Int,
    val default: T,
    val validate: (Any?) -> Boolean,
    // migrations[v] transforms a value at version v to version v+1
    val migrations: Map<Int, (Any?) -> T> = emptyMap(),
)

// Given a raw stored string (may be null/legacy/versioned JSON), applies the migration
// chain and returns a valid value at currentVersion. Pure function — no I/O.
fun <T> migrateStoredValue(spec: PrefSpec<T>, raw: String?): T {
    if (raw == null) return spec.default

    // Parse: detect versioned wrapper {v, value} vs legacy plain value
    val parsed = parseVersioned(raw)
    var version = parsed.first
    var value: Any? = parsed.second

    // Walk migrations stepwise (same logic as usePrefs.ts while loop)
    while (version < spec.currentVersion) {
        val migrator = spec.migrations[version]
        if (migrator == null) {
            // No migration path — repair to default
            return spec.default
        }
        value = try {
            migrator(value)
        } catch (_: Throwable) {
            return spec.default
        }
        version += 1
    }

    @Suppress("UNCHECKED_CAST")
    return if (spec.validate(value)) value as T else spec.default
}

// Serialize a value for storage as {v, value} JSON.
fun <T> serializeVersioned(version: Int, value: T): String =
    """{"v":$version,"value":${encodeValue(value)}}"""

private fun parseVersioned(raw: String): Pair<Int, Any?> {
    // Try to detect {v: N, value: ...} pattern with a simple string check
    val trimmed = raw.trim()
    if (trimmed.startsWith("{")) {
        val vMatch = Regex(""""v"\s*:\s*(\d+)""").find(trimmed)
        val valMatch = Regex(""""value"\s*:\s*(.+?)\s*$""").find(trimmed)
        if (vMatch != null && valMatch != null) {
            val v = vMatch.groupValues[1].toIntOrNull() ?: 0
            val rawVal = valMatch.groupValues[1].trimEnd('}', ' ')
            return v to decodeValue(rawVal)
        }
    }
    // Legacy plain value — treat as v0
    return 0 to decodeValue(trimmed)
}

private fun decodeValue(raw: String): Any? = when {
    raw == "null" -> null
    raw == "true" -> true
    raw == "false" -> false
    raw.startsWith("\"") && raw.endsWith("\"") -> raw.drop(1).dropLast(1)
    raw.toDoubleOrNull() != null -> raw.toDouble()
    else -> raw
}

private fun encodeValue(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"$value\""
    is Boolean -> value.toString()
    is Number -> value.toString()
    else -> "\"$value\""
}

// ---------- Canonical PrefSpecs (mirrors usePrefs.ts specs) ----------

val UnitsPrefSpec = PrefSpec(
    key = "units",
    currentVersion = 1,
    default = "imperial",
    validate = { it is String && it in setOf("metric", "imperial") },
    migrations = mapOf(
        0 to { old -> if (old is String && old in setOf("metric", "imperial")) old else "imperial" },
    ),
)

val CompassPrefSpec = PrefSpec(
    key = "compass_mode",
    currentVersion = 1,
    default = "magnetic",
    validate = { it is String && it in setOf("magnetic", "true") },
    migrations = mapOf(
        0 to { old ->
            when {
                old is String && old in setOf("magnetic", "true") -> old
                old == true -> "true"
                old == false -> "magnetic"
                else -> "magnetic"
            }
        },
    ),
)

val BearingDisplayPrefSpec = PrefSpec(
    key = "bearing_display_mode",
    currentVersion = 1,
    default = "relative",
    validate = { it is String && it in setOf("relative", "clock", "true") },
    migrations = mapOf(
        0 to { old -> if (old is String && old in setOf("relative", "clock", "true")) old else "relative" },
    ),
)

val AudioCuesPrefSpec = PrefSpec(
    key = "audio_cues",
    currentVersion = 1,
    default = true,
    validate = { it is Boolean },
    migrations = mapOf(
        0 to { old ->
            when (old) {
                "true", true -> true
                "false", false -> false
                else -> true
            }
        },
    ),
)

val SpokenGuidancePrefSpec = PrefSpec(
    key = "spoken_guidance",
    currentVersion = 1,
    default = true,
    validate = { it is Boolean },
    migrations = AudioCuesPrefSpec.migrations,
)

val BeaconCuesPrefSpec = PrefSpec(
    key = "beacon_cues",
    currentVersion = 1,
    default = true,
    validate = { it is Boolean },
    migrations = AudioCuesPrefSpec.migrations,
)

val DuckAudioPrefSpec = PrefSpec(
    key = "duck_audio",
    currentVersion = 1,
    default = false,
    validate = { it is Boolean },
    migrations = mapOf(
        0 to { old ->
            when (old) {
                "true", true -> true
                "false", false -> false
                else -> false
            }
        },
    ),
)
