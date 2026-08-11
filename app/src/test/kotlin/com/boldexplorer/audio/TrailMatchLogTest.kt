package com.boldexplorer.audio

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.navigation.MatchEvidenceRecorder
import com.boldexplorer.shared.navigation.ProgressTracker
import com.boldexplorer.shared.navigation.TrailPolyline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shadow trail-match record, and the property that justifies its size: **replayability**.
 *
 * Decision fields alone would let a walk be audited but not re-run. Since S4's constants are
 * deliberately untuned, a log that cannot be replayed would mean one field walk per constant
 * change. These tests pin the round trip that makes one walk answer every future tuning question.
 */
class TrailMatchLogTest {
    private fun northTrail(lengthM: Double = 400.0): TrailPolyline {
        val north = 40.0 + lengthM / 111_194.9
        return TrailPolyline(listOf(LatLng(40.0, -105.0), LatLng(north, -105.0)))
    }

    private fun fix(
        lat: Double = 40.0009,
        lon: Double = -105.0,
        timestamp: Long = 1_700_000_000_000,
    ) = LocationSample(
        lat = lat,
        lon = lon,
        accuracy = 4.5,
        heading = 12.5,
        speed = 1.4,
        timestamp = timestamp,
        provider = "gps",
    )

    private fun loggedEntry(sample: LocationSample = fix()): AudioLogEntry {
        val poly = northTrail()
        val tracker = ProgressTracker(poly)
        val recorder = MatchEvidenceRecorder(poly)
        val match = tracker.onFix(sample)
        return trailMatchLogEntry(sample, match, recorder.observe(sample, match))
    }

    @Test
    fun aLoggedFix_survivesAFullWriteReadCycle() {
        val original = fix()

        val parsed = assertNotNull(AudioLogCodec.parse(AudioLogCodec.format(loggedEntry(original))))
        val replayed = assertNotNull(parsed.toReplayFix(), "a trail-match entry must yield its fix back")

        // This is the requirement in one assertion: what went to disk comes back well enough to
        // drive a retuned matcher.
        assertEquals(original.lat, replayed.lat, 1e-9, "lat")
        assertEquals(original.lon, replayed.lon, 1e-9, "lon")
        assertEquals(original.accuracy!!, replayed.accuracy!!, 1e-9, "accuracy")
        assertEquals(original.speed!!, replayed.speed!!, 1e-9, "speed")
        assertEquals(original.heading!!, replayed.heading!!, 1e-9, "course")
        assertEquals(original.timestamp, replayed.timestamp, "timestamp")
        assertEquals(original.provider, replayed.provider, "provider")
    }

    @Test
    fun aWholeWalk_replaysThroughAFreshTracker() {
        // Record a short walk, write it, read it back, and re-run it through a new tracker. If the
        // replayed run does not track, the log is not a corpus and the field walk buys one guess.
        val poly = northTrail()
        val tracker = ProgressTracker(poly)
        val recorder = MatchEvidenceRecorder(poly)

        val lines =
            (0..30).map { i ->
                val sample = fix(lat = 40.0 + (100.0 + i * 1.4) / 111_194.9, timestamp = 1_700_000_000_000 + i * 1000L)
                val match = tracker.onFix(sample)
                AudioLogCodec.format(trailMatchLogEntry(sample, match, recorder.observe(sample, match)))
            }

        val replayTracker = ProgressTracker(northTrail())
        var last: com.boldexplorer.shared.navigation.TrailMatch? = null
        for (line in lines) {
            val replayFix = assertNotNull(AudioLogCodec.parse(line)?.toReplayFix())
            last = replayTracker.onFix(replayFix)
        }

        val finalMatch = assertNotNull(last)
        assertEquals(
            tracker.match!!.confirmedAlongM!!,
            assertNotNull(finalMatch.confirmedAlongM),
            0.5,
            "a replayed walk must reach the same place as the live one",
        )
    }

    @Test
    fun theRecord_carriesBothTheChosenAndTheRejectedCandidate() {
        val extra = loggedEntry().extra

        // Present as keys even when there is no rival, so a replay tool can rely on the schema
        // rather than probing for it.
        assertTrue(extra.containsKey("along_m"), "chosen along")
        assertTrue(extra.containsKey("rejAlong_m"), "rejected along")
        assertTrue(extra.containsKey("courseAgreement_deg"), "chosen course agreement")
        assertTrue(extra.containsKey("rejCourseAgreement_deg"), "rejected course agreement")
    }

    @Test
    fun theRecord_playsNothing() {
        // Shadow mode drives no audio; an entry claiming otherwise would corrupt any analysis of
        // what the user actually heard.
        assertEquals("", loggedEntry().played, "shadow matching is silent")
    }

    @Test
    fun theRecord_staysWithinABudgetedLineSize() {
        // The ADR asks for the append rate to be verified rather than assumed before shipping.
        // One entry per fix at 1 Hz is ~3600 lines/hour, so line size sets the hourly volume.
        val bytes = AudioLogCodec.format(loggedEntry()).length

        val perHourMb = bytes * 3600.0 / 1_048_576.0
        assertTrue(
            bytes < 1024,
            "a trail-match line is $bytes bytes (${"%.1f".format(perHourMb)} MB/walking-hour); " +
                "over 1 KB the field-log volume needs a deliberate decision, not a silent one",
        )
    }

    @Test
    fun aNonMatchEntry_yieldsNoReplayFix() {
        val other =
            AudioLogEntry(
                timestampMs = 1,
                kind = AudioLogEntry.Kind.TTS_ANNOUNCEMENT,
                trigger = "t",
                inputs = "i",
                outputs = "o",
                played = "p",
            )

        assertNull(other.toReplayFix(), "only trail-match entries carry a fix")
    }
}
