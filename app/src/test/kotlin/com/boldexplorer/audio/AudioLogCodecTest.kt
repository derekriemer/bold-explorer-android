package com.boldexplorer.audio

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Serialization for the audio/telemetry log.
 *
 * The behaviour that matters here is **round-tripping `extra`**. Before v2 the writer flattened
 * `extra` into top-level keys and the reader simply did not look for them, so every diagnostic field
 * survived on disk but vanished from the Debug screen after a restart — the file said more than the
 * UI did. That is tolerable for an announcement log and disqualifying for a log whose entire purpose
 * is replaying match decisions against retuned constants.
 */
class AudioLogCodecTest {
    private fun entry(
        kind: AudioLogEntry.Kind = AudioLogEntry.Kind.TTS_ANNOUNCEMENT,
        extra: Map<String, Any?> = emptyMap(),
    ) = AudioLogEntry(
        timestampMs = 1_700_000_000_000,
        kind = kind,
        trigger = "trigger",
        inputs = "inputs",
        outputs = "outputs",
        played = "played",
        note = "note",
        extra = extra,
    )

    @Test
    fun roundTrip_preservesTheCoreFields() {
        val original = entry()

        val parsed = assertNotNull(AudioLogCodec.parse(AudioLogCodec.format(original)))

        assertEquals(original.timestampMs, parsed.timestampMs, "timestamp")
        assertEquals(original.kind, parsed.kind, "kind")
        assertEquals(original.trigger, parsed.trigger, "trigger")
        assertEquals(original.inputs, parsed.inputs, "inputs")
        assertEquals(original.outputs, parsed.outputs, "outputs")
        assertEquals(original.played, parsed.played, "played")
        assertEquals(original.note, parsed.note, "note")
    }

    @Test
    fun roundTrip_preservesExtraFields() {
        val original = entry(extra = mapOf("lat" to 40.1, "alongTrackM" to 123.5, "state" to "Matched"))

        val parsed = assertNotNull(AudioLogCodec.parse(AudioLogCodec.format(original)))

        assertEquals(40.1, parsed.extra["lat"] as Double, 1e-9, "lat survives read-back")
        assertEquals(123.5, parsed.extra["alongTrackM"] as Double, 1e-9, "alongTrackM survives read-back")
        assertEquals("Matched", parsed.extra["state"], "state survives read-back")
    }

    @Test
    fun format_stampsTheSchemaVersion() {
        val json = JSONObject(AudioLogCodec.format(entry()))

        assertEquals(2, json.getInt("v"), "v2 is the current schema")
    }

    @Test
    fun format_nestsExtraUnderOneKey() {
        val json = JSONObject(AudioLogCodec.format(entry(extra = mapOf("lat" to 40.1))))

        // Nested rather than flattened, so a diagnostic field can never collide with a core one.
        assertTrue(json.has("extra"), "extra is its own object")
        assertEquals(40.1, json.getJSONObject("extra").getDouble("lat"), 1e-9, "and holds the field")
        assertTrue(!json.has("lat"), "and does not also leak to the top level")
    }

    @Test
    fun parse_readsV1LinesAndRecoversTheirFlattenedExtras() {
        // A line written by the old format: no "v", extras flattened alongside the core fields.
        val v1 =
            """
            {"ts":1700000000000,"kind":"TTS_ANNOUNCEMENT","trigger":"t","inputs":"i",
            "outputs":"o","played":"p","note":"n","distToTarget_m":42.5}
            """.trimIndent().replace("\n", "")

        val parsed = assertNotNull(AudioLogCodec.parse(v1), "old logs must still open")

        assertEquals("t", parsed.trigger, "core fields read as before")
        assertEquals(42.5, parsed.extra["distToTarget_m"] as Double, 1e-9, "and the stray field is recovered")
    }

    @Test
    fun parse_ofGarbage_yieldsNullRatherThanThrowing() {
        assertNull(AudioLogCodec.parse("not json at all"), "a corrupt line must not lose the whole file")
        assertNull(AudioLogCodec.parse(""), "nor must a blank one")
    }

    @Test
    fun parse_ofAnUnknownKind_yieldsNull() {
        val line = AudioLogCodec.format(entry()).replace("TTS_ANNOUNCEMENT", "SOMETHING_FROM_THE_FUTURE")

        assertNull(AudioLogCodec.parse(line), "an unreadable kind is skipped, not crashed on")
    }

    @Test
    fun parse_normalisesEveryNumberToDouble() {
        // Android's org.json yields Double here and the JVM reference implementation yields
        // BigDecimal. Pinning the parsed type keeps an off-device replay tool from having to
        // special-case whichever implementation it happens to be running against.
        val line = AudioLogCodec.format(entry(extra = mapOf("decimal" to 1.5, "whole" to 7)))

        val extra = assertNotNull(AudioLogCodec.parse(line)).extra

        assertTrue(extra["decimal"] is Double, "decimal came back as ${extra["decimal"]?.let { it::class.simpleName }}")
        assertTrue(extra["whole"] is Double, "whole came back as ${extra["whole"]?.let { it::class.simpleName }}")
    }

    @Test
    fun format_omitsNullExtras() {
        val json = JSONObject(AudioLogCodec.format(entry(extra = mapOf("present" to 1.0, "absent" to null))))

        val extra = json.getJSONObject("extra")
        assertTrue(extra.has("present"), "real values are written")
        assertTrue(!extra.has("absent"), "nulls are omitted rather than written as JSON null")
    }

    @Test
    fun trailMatchIsALoggableKind() {
        val parsed = assertNotNull(AudioLogCodec.parse(AudioLogCodec.format(entry(kind = AudioLogEntry.Kind.TRAIL_MATCH))))

        assertEquals(AudioLogEntry.Kind.TRAIL_MATCH, parsed.kind, "shadow matching has its own kind")
    }
}
