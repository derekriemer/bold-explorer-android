package com.boldexplorer.gpx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxParserTest {
    private fun parse(xml: String) = GpxParser.parse(xml.trimIndent().byteInputStream())

    // ── Waypoints (<wpt>) ─────────────────────────────────────────────────────────

    @Test
    fun wpt_basicFields() {
        val result =
            parse(
                """
            <?xml version="1.0"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="47.123" lon="-122.456">
                <name>Summit</name>
                <ele>1234.5</ele>
                <desc>Nice view</desc>
              </wpt>
            </gpx>
        """,
            )
        assertEquals(1, result.waypoints.size)
        val w = result.waypoints[0]
        assertEquals("Summit", w.name)
        assertEquals(47.123, w.lat)
        assertEquals(-122.456, w.lon)
        assertEquals(1234.5, w.elevM)
        assertEquals("Nice view", w.description)
    }

    @Test
    fun wpt_missingName_usesFallback() {
        val result =
            parse(
                """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <wpt lat="1.0" lon="2.0"/>
            </gpx>
        """,
            )
        assertEquals("Waypoint", result.waypoints[0].name)
    }

    @Test
    fun wpt_missingElevAndDesc_areNull() {
        val result =
            parse(
                """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <wpt lat="1.0" lon="2.0"><name>X</name></wpt>
            </gpx>
        """,
            )
        assertNull(result.waypoints[0].elevM)
        assertNull(result.waypoints[0].description)
    }

    @Test
    fun wpt_invalidCoords_skipped() {
        val result =
            parse(
                """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <wpt lat="notanumber" lon="2.0"><name>Bad</name></wpt>
              <wpt lat="1.0" lon="2.0"><name>Good</name></wpt>
            </gpx>
        """,
            )
        assertEquals(1, result.waypoints.size)
        assertEquals("Good", result.waypoints[0].name)
    }

    @Test
    fun wpt_multipleWaypoints() {
        val result =
            parse(
                """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <wpt lat="1.0" lon="2.0"><name>A</name></wpt>
              <wpt lat="3.0" lon="4.0"><name>B</name></wpt>
              <wpt lat="5.0" lon="6.0"><name>C</name></wpt>
            </gpx>
        """,
            )
        assertEquals(3, result.waypoints.size)
        assertEquals(listOf("A", "B", "C"), result.waypoints.map { it.name })
    }

    // ── Track (<trk>) ─────────────────────────────────────────────────────────────

    @Test
    fun trk_basicFields() {
        val result =
            parse(
                """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <trk>
                <name>My Hike</name>
                <trkseg>
                  <trkpt lat="47.0" lon="-122.0"><ele>100.0</ele></trkpt>
                  <trkpt lat="47.1" lon="-122.1"><ele>110.0</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """,
            )
        assertEquals(1, result.trails.size)
        val t = result.trails[0]
        assertEquals("My Hike", t.name)
        assertEquals(false, t.isRoute)
        assertEquals(2, t.points.size)
        assertEquals(47.0, t.points[0].lat)
        assertEquals(100.0, t.points[0].elevM)
    }

    @Test
    fun trk_multipleSegmentsConcatenated() {
        val result =
            parse(
                """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <trk>
                <name>Segmented</name>
                <trkseg>
                  <trkpt lat="1.0" lon="1.0"/>
                  <trkpt lat="2.0" lon="2.0"/>
                </trkseg>
                <trkseg>
                  <trkpt lat="3.0" lon="3.0"/>
                </trkseg>
              </trk>
            </gpx>
        """,
            )
        assertEquals(3, result.trails[0].points.size)
    }

    @Test
    fun trk_missingName_usesFallback() {
        val result =
            parse(
                """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <trk><trkseg><trkpt lat="1.0" lon="1.0"/></trkseg></trk>
            </gpx>
        """,
            )
        assertEquals("Imported Trail", result.trails[0].name)
    }

    @Test
    fun trk_pointsWithoutName_getIndexedFallback() {
        val result =
            parse(
                """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <trk><name>T</name><trkseg>
                <trkpt lat="1.0" lon="1.0"/>
                <trkpt lat="2.0" lon="2.0"/>
              </trkseg></trk>
            </gpx>
        """,
            )
        assertEquals("Point 1", result.trails[0].points[0].name)
        assertEquals("Point 2", result.trails[0].points[1].name)
    }

    @Test
    fun trk_nameElementNotConfusedWithTrkptName() {
        // The trail's <name> should not pick up a <name> nested inside a <trkpt>.
        val result =
            parse(
                """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <trk>
                <name>Trail Name</name>
                <trkseg>
                  <trkpt lat="1.0" lon="1.0"><name>Point Name</name></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """,
            )
        assertEquals("Trail Name", result.trails[0].name)
        assertEquals("Point Name", result.trails[0].points[0].name)
    }

    @Test
    fun trk_emptyTrack_omitted() {
        val result =
            parse(
                """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <trk><name>Empty</name></trk>
            </gpx>
        """,
            )
        assertTrue(result.trails.isEmpty())
    }

    // ── Route (<rte>) ─────────────────────────────────────────────────────────────

    @Test
    fun rte_isMarkedAsRoute() {
        val result =
            parse(
                """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <rte>
                <name>Day Route</name>
                <rtept lat="1.0" lon="1.0"><name>Start</name></rtept>
                <rtept lat="2.0" lon="2.0"><name>End</name></rtept>
              </rte>
            </gpx>
        """,
            )
        assertEquals(1, result.trails.size)
        assertEquals(true, result.trails[0].isRoute)
        assertEquals("Day Route", result.trails[0].name)
        assertEquals(2, result.trails[0].points.size)
    }

    // ── Mixed content ─────────────────────────────────────────────────────────────

    @Test
    fun mixed_wptAndTrkBothParsed() {
        val result =
            parse(
                """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <wpt lat="1.0" lon="1.0"><name>Camp</name></wpt>
              <trk>
                <name>Approach</name>
                <trkseg>
                  <trkpt lat="2.0" lon="2.0"/>
                </trkseg>
              </trk>
            </gpx>
        """,
            )
        assertEquals(1, result.waypoints.size)
        assertEquals(1, result.trails.size)
    }

    @Test
    fun empty_gpx_returnsEmptyResult() {
        val result = parse("""<?xml version="1.0"?><gpx version="1.1"/>""")
        assertTrue(result.isEmpty)
    }

    // ── Summary ───────────────────────────────────────────────────────────────────

    @Test
    fun summary_pluralisation() {
        val result =
            parse(
                """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <wpt lat="1.0" lon="1.0"><name>A</name></wpt>
              <wpt lat="2.0" lon="2.0"><name>B</name></wpt>
              <trk><name>T</name><trkseg><trkpt lat="3.0" lon="3.0"/></trkseg></trk>
            </gpx>
        """,
            )
        assertEquals("2 waypoints, 1 trail", result.summary)
    }
}
