package com.boldexplorer.gpx

import com.boldexplorer.shared.model.Waypoint
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class GpxExporterTest {

    @Test
    fun exportWaypoints_escapesXmlText() {
        val gpx = GpxExporter.exportWaypoints(
            listOf(
                Waypoint(
                    id = 1,
                    name = "A & B <C>",
                    lat = 1.0,
                    lon = 2.0,
                    elevM = 3.0,
                    description = "\"quoted\"",
                    createdAt = 0,
                )
            )
        )

        assertContains(gpx, "A &amp; B &lt;C&gt;")
        assertContains(gpx, "&quot;quoted&quot;")
        assertFalse(gpx.contains("A & B <C>"))
    }

    @Test
    fun exportCollection_includesWaypointsAndTrailTracks() {
        val waypoint = Waypoint(1, "Standalone", 1.0, 2.0, null, null, 0)
        val trailPoint = Waypoint(2, "Trail point", 3.0, 4.0, null, null, 0)

        val gpx = GpxExporter.exportCollection(
            collectionName = "Collection",
            waypoints = listOf(waypoint),
            trails = listOf(GpxTrail("Trail", listOf(trailPoint))),
        )

        assertContains(gpx, "<name>Collection</name>")
        assertContains(gpx, """<wpt lat="1.0" lon="2.0">""")
        assertContains(gpx, "<trk>")
        assertContains(gpx, """<trkpt lat="3.0" lon="4.0">""")
    }
}
