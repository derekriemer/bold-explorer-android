package com.boldexplorer.shared.gpx

import com.boldexplorer.shared.model.Waypoint
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class GpxExporterTest {

    private fun waypoint(
        id: Long = 1,
        name: String = "WP",
        lat: Double = 1.0,
        lon: Double = 2.0,
        elevM: Double? = null,
        description: String? = null,
        createdAt: Long = 0L,
        kind: String = Waypoint.KIND_WAYPOINT,
    ) = Waypoint(id, name, lat, lon, elevM, description, createdAt, kind)

    // ── XML escaping ──────────────────────────────────────────────────────────────

    @Test
    fun exportWaypoints_escapesXmlText() {
        val gpx = GpxExporter.exportWaypoints(
            listOf(waypoint(name = "A & B <C>", description = "\"quoted\""))
        )
        assertContains(gpx, "A &amp; B &lt;C&gt;")
        assertContains(gpx, "&quot;quoted&quot;")
        assertFalse(gpx.contains("A & B <C>"))
    }

    // ── <time> presence ───────────────────────────────────────────────────────────

    @Test
    fun exportWaypoints_includesTimeElement() {
        // createdAt = 0 → 1970-01-01T00:00:00Z
        val gpx = GpxExporter.exportWaypoints(listOf(waypoint(createdAt = 0L)))
        assertContains(gpx, "<time>1970-01-01T00:00:00Z</time>")
    }

    @Test
    fun exportTrail_trkptIncludesTime() {
        val gpx = GpxExporter.exportTrail("T", listOf(waypoint(createdAt = 0L)))
        assertContains(gpx, "<time>1970-01-01T00:00:00Z</time>")
    }

    @Test
    fun exportCollection_trkptIncludesTime() {
        val pt = waypoint(kind = Waypoint.KIND_TRACK_POINT, createdAt = 0L)
        val gpx = GpxExporter.exportCollection("C", emptyList(), listOf(GpxTrail("T", listOf(pt))))
        assertContains(gpx, "<time>1970-01-01T00:00:00Z</time>")
    }

    // ── Track-point name suppression ──────────────────────────────────────────────

    @Test
    fun exportTrail_trackPoint_nameOmittedFromTrkpt() {
        val pt = waypoint(name = "Track 12:34:56", kind = Waypoint.KIND_TRACK_POINT)
        val gpx = GpxExporter.exportTrail("T", listOf(pt))
        // name should not appear inside <trkpt>
        val trkptBlock = gpx.substringAfter("<trkpt").substringBefore("</trkpt>")
        assertFalse(trkptBlock.contains("<name>"))
    }

    @Test
    fun exportTrail_namedWaypoint_nameAppearsInTrkpt() {
        val pt = waypoint(name = "Summit", kind = Waypoint.KIND_WAYPOINT)
        val gpx = GpxExporter.exportTrail("T", listOf(pt))
        val trkptBlock = gpx.substringAfter("<trkpt").substringBefore("</trkpt>")
        assertContains(trkptBlock, "<name>Summit</name>")
    }

    @Test
    fun exportTrail_trackPointsNotDuplicatedAsWpt() {
        val trackPt = waypoint(id = 1, name = "Track 12:00:00", kind = Waypoint.KIND_TRACK_POINT)
        val namedPt = waypoint(id = 2, name = "Junction", kind = Waypoint.KIND_WAYPOINT)
        val gpx = GpxExporter.exportTrail("T", listOf(trackPt, namedPt))
        // Only the named waypoint should appear as a standalone <wpt>
        assertFalse(gpx.substringBefore("<trk>").contains("Track 12:00:00"))
        assertContains(gpx.substringBefore("<trk>"), "Junction")
    }

    // ── Collection structure ──────────────────────────────────────────────────────

    @Test
    fun exportCollection_includesMetadataWaypointsAndTracks() {
        val wpt = waypoint(id = 1, name = "Camp", lat = 1.0, lon = 2.0)
        val trkPt = waypoint(id = 2, name = "Point 1", lat = 3.0, lon = 4.0, kind = Waypoint.KIND_TRACK_POINT)

        val gpx = GpxExporter.exportCollection("My Trip", listOf(wpt), listOf(GpxTrail("Approach", listOf(trkPt))))

        assertContains(gpx, "<name>My Trip</name>")
        assertContains(gpx, """<wpt lat="1.0" lon="2.0">""")
        assertContains(gpx, "<trk>")
        assertContains(gpx, """<trkpt lat="3.0" lon="4.0">""")
    }
}
