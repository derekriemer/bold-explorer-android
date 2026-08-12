package com.boldexplorer.shared.navigation.replay

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The readers are the part of the harness that fails silently. A metric that is wrong shows up as a
 * strange number a human questions; a reader that drops every line shows up as a confident report
 * about zero fixes. These pin both file formats against samples in the shape the app really writes.
 */
class ReplayCliTest {
    private fun tempFile(
        name: String,
        content: String,
    ): File = File.createTempFile(name, null).apply { writeText(content); deleteOnExit() }

    @Test
    fun readsTrackPointsFromAnExportedGpx() {
        val gpx =
            tempFile(
                "trail",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1" creator="Bold Explorer" xmlns="http://www.topografix.com/GPX/1/1">
                  <trk><name>test trail</name><trkseg>
                    <trkpt lat="40.0000" lon="-105.0000"><ele>1600</ele></trkpt>
                    <trkpt lat="40.0001" lon="-105.0001"></trkpt>
                    <trkpt lat="40.0002" lon="-105.0002"></trkpt>
                  </trkseg></trk>
                </gpx>
                """.trimIndent(),
            )
        val points = readGpxPoints(gpx)
        assertEquals(3, points.size)
        assertEquals(40.0000, points.first().lat, 1e-9)
        assertEquals(-105.0002, points.last().lon, 1e-9)
    }

    @Test
    fun prefersTrackPointsOverWaypoints() {
        // A trail export carries both: the track is the geometry, the waypoints are the named
        // checkpoints along it. Replaying the waypoints would silently use a far coarser polyline.
        val gpx =
            tempFile(
                "both",
                """
                <gpx version="1.1">
                  <wpt lat="1.0" lon="1.0"><name>checkpoint 1</name></wpt>
                  <wpt lat="2.0" lon="2.0"><name>checkpoint 2</name></wpt>
                  <trk><trkseg>
                    <trkpt lat="10.0" lon="10.0"></trkpt>
                    <trkpt lat="11.0" lon="11.0"></trkpt>
                    <trkpt lat="12.0" lon="12.0"></trkpt>
                  </trkseg></trk>
                </gpx>
                """.trimIndent(),
            )
        assertEquals(3, readGpxPoints(gpx).size, "the track has more detail than the waypoints")
        assertEquals(10.0, readGpxPoints(gpx).first().lat, 1e-9)
    }

    @Test
    fun readsRawFixesFromTheAudioLogAndIgnoresEverythingElse() {
        val log =
            tempFile(
                "walk",
                listOf(
                    // A TRAIL_MATCH line in the shape the app writes, on the synthetic origin the rest of
                    // the fixtures use — never a real position from a recorded walk.
                    """{"v":2,"ts":1786495669999,"kind":"TRAIL_MATCH","trigger":"gps_fix","inputs":"","outputs":"","played":"","extra":{"lat":40.00042,"lon":-105.00033,"acc_m":2.97,"speed_mps":0.57,"course_deg":327.2,"provider":"gnss","state":"Matched"}}""",
                    """{"v":2,"ts":1786495671000,"kind":"TRAIL_MATCH","trigger":"gps_fix","inputs":"","outputs":"","played":"","extra":{"lat":40.00045,"lon":-105.00036,"acc_m":3.1,"provider":"gnss"}}""",
                    // Other kinds share the file and must not become fixes.
                    """{"v":2,"ts":1786495672000,"kind":"USER_MARKER","trigger":"IMPORTANT button","inputs":"","outputs":"","played":"","note":"trail start"}""",
                    """{"v":2,"ts":1786495673000,"kind":"DETECTION_STATE","trigger":"GpsFixRejected","inputs":"accuracy=1.9m","outputs":"","played":"bail:accuracy_gate"}""",
                    "",
                    "{ this line is corrupt",
                ).joinToString("\n"),
            )
        val fixes = readLoggedFixes(log)
        assertEquals(2, fixes.size, "only TRAIL_MATCH lines carry a raw fix")
        assertEquals(40.00042, fixes[0].lat, 1e-9)
        assertEquals(2.97, fixes[0].accuracy!!, 1e-9)
        assertEquals(0.57, fixes[0].speed!!, 1e-9)
        assertEquals("gnss", fixes[0].provider)
        // Optional fields really are optional — a fix with no speed or course must still replay.
        assertNull(fixes[1].speed)
        assertNull(fixes[1].heading)
    }

    @Test
    fun fixesComeBackInTimeOrder() {
        // The ladder measures elapsed time between fixes, so an out-of-order file would produce a
        // confident report about a walk that never happened.
        val log =
            tempFile(
                "shuffled",
                listOf(3000L, 1000L, 2000L).joinToString("\n") { ts ->
                    """{"v":2,"ts":$ts,"kind":"TRAIL_MATCH","trigger":"gps_fix","inputs":"","outputs":"","played":"","extra":{"lat":39.0,"lon":-105.0,"provider":"gnss"}}"""
                },
            )
        assertEquals(listOf(1000L, 2000L, 3000L), readLoggedFixes(log).map { it.timestamp })
    }

    @Test
    fun aLogWithNoTrailMatchLinesReadsAsEmpty() {
        // Every log written before S4b looks like this. Reporting zero fixes is right; throwing, or
        // reporting a partial walk, is not.
        val log =
            tempFile(
                "old",
                """{"v":2,"ts":1,"kind":"TTS_ANNOUNCEMENT","trigger":"TRAIL_STARTED","inputs":"","outputs":"","played":""}""",
            )
        assertTrue(readLoggedFixes(log).isEmpty())
    }
}
