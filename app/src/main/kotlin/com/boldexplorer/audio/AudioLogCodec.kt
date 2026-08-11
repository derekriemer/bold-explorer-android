package com.boldexplorer.audio

import org.json.JSONObject

/**
 * JSONL serialization for [AudioLogEntry].
 *
 * Split out of `AudioEventLog` so it can be unit-tested without an Android [android.content.Context]
 * — the round-tripping of [AudioLogEntry.extra] is the part worth pinning, and it was silently
 * broken before v2.
 *
 * ## Schema versions
 *
 * - **v1** (unstamped): core fields at the top level, with `extra` *flattened* alongside them. The
 *   writer emitted those fields but the reader never looked for them, so a restart quietly dropped
 *   every diagnostic value: the file on disk said more than the Debug screen did.
 * - **v2**: stamped with `"v": 2` and `extra` nested under its own key. Nesting also removes the
 *   latent collision where a diagnostic field named `note` or `kind` would overwrite a core one.
 *
 * v1 lines still parse, and their flattened extras are recovered by treating every unrecognised
 * top-level key as an extra — which fixes the old logs too, rather than only new ones.
 */
object AudioLogCodec {
    /** Current schema version, written into every line as `v`. */
    const val SCHEMA_VERSION = 2

    private const val KEY_VERSION = "v"
    private const val KEY_EXTRA = "extra"

    /** Top-level keys that belong to the entry itself rather than to [AudioLogEntry.extra]. */
    private val CORE_KEYS =
        setOf(KEY_VERSION, KEY_EXTRA, "ts", "kind", "trigger", "inputs", "outputs", "played", "note")

    fun format(entry: AudioLogEntry): String =
        JSONObject()
            .apply {
                put(KEY_VERSION, SCHEMA_VERSION)
                put("ts", entry.timestampMs)
                put("kind", entry.kind.name)
                put("trigger", entry.trigger)
                put("inputs", entry.inputs)
                put("outputs", entry.outputs)
                put("played", entry.played)
                if (entry.note.isNotEmpty()) put("note", entry.note)
                val extras = entry.extra.filterValues { it != null }
                if (extras.isNotEmpty()) {
                    put(KEY_EXTRA, JSONObject().apply { extras.forEach { (k, v) -> put(k, v) } })
                }
            }.toString()

    /**
     * Parses one line, or returns `null` if it cannot be read.
     *
     * A corrupt or future-versioned line is skipped rather than thrown on: losing one line of a
     * field log is a nuisance, losing the whole file is the end of a walk's worth of data.
     */
    fun parse(line: String): AudioLogEntry? {
        if (line.isBlank()) return null
        return runCatching {
            val json = JSONObject(line)
            AudioLogEntry(
                timestampMs = json.getLong("ts"),
                kind = AudioLogEntry.Kind.valueOf(json.getString("kind")),
                trigger = json.getString("trigger"),
                inputs = json.getString("inputs"),
                outputs = json.getString("outputs"),
                played = json.getString("played"),
                note = json.optString("note", ""),
                extra = extrasFrom(json),
            )
        }.getOrNull()
    }

    private fun extrasFrom(json: JSONObject): Map<String, Any?> {
        json.optJSONObject(KEY_EXTRA)?.let { nested ->
            return nested.keys().asSequence().associateWith { normalize(nested.get(it)) }
        }
        // v1: anything that is not a core key was an extra that the old reader threw away.
        return json
            .keys()
            .asSequence()
            .filter { it !in CORE_KEYS }
            .associateWith { normalize(json.get(it)) }
    }

    /**
     * Collapses every JSON number to [Double].
     *
     * Android's bundled `org.json` decodes `1.5` as a [Double]; the reference implementation used by
     * JVM unit tests decodes it as a [java.math.BigDecimal], and whole numbers arrive as [Int] or
     * [Long] in both. Leaving that through would make the parsed type depend on where the parsing
     * ran, which a replay tool reading these logs off-device would then have to special-case.
     *
     * Every numeric field in this log is a physical measurement — metres, seconds, degrees, m/s —
     * so [Double] loses nothing that matters. Entry timestamps are a core field and are read as
     * [Long] directly, so they never take this path.
     */
    private fun normalize(value: Any?): Any? = if (value is Number) value.toDouble() else value
}
