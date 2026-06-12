package com.boldexplorer.shared.gpx

import com.boldexplorer.shared.model.Waypoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class GpxTrail(
    val name: String,
    val waypoints: List<Waypoint>,
)

object GpxExporter {
    fun exportWaypoints(waypoints: List<Waypoint>): String =
        buildString {
            appendGpxHeader()
            for (wpt in waypoints) appendWpt(wpt)
            appendLine("</gpx>")
        }

    fun exportTrail(
        trailName: String,
        waypoints: List<Waypoint>,
    ): String =
        buildString {
            appendGpxHeader()
            for (wpt in waypoints.filter { it.kind == Waypoint.KIND_WAYPOINT }) appendWpt(wpt)
            appendTrk(trailName, waypoints)
            appendLine("</gpx>")
        }

    fun exportCollection(
        collectionName: String,
        waypoints: List<Waypoint>,
        trails: List<GpxTrail>,
    ): String =
        buildString {
            appendGpxHeader()
            appendLine("  <metadata>")
            appendLine("    <name>${escapeXml(collectionName)}</name>")
            appendLine("  </metadata>")
            for (wpt in waypoints) appendWpt(wpt)
            for (trail in trails) appendTrk(trail.name, trail.waypoints)
            appendLine("</gpx>")
        }

    private fun StringBuilder.appendGpxHeader() {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<gpx version="1.1" creator="Bold Explorer" """ +
                """xmlns="http://www.topografix.com/GPX/1/1">""",
        )
    }

    private fun StringBuilder.appendWpt(wpt: Waypoint) {
        appendLine("""  <wpt lat="${wpt.lat}" lon="${wpt.lon}">""")
        wpt.elevM?.let { appendLine("    <ele>$it</ele>") }
        appendLine("    <time>${formatTime(wpt.createdAt)}</time>")
        appendLine("    <name>${escapeXml(wpt.name)}</name>")
        wpt.description?.let { appendLine("    <desc>${escapeXml(it)}</desc>") }
        appendLine("  </wpt>")
    }

    private fun StringBuilder.appendTrk(
        trailName: String,
        waypoints: List<Waypoint>,
    ) {
        appendLine("  <trk>")
        appendLine("    <name>${escapeXml(trailName)}</name>")
        appendLine("    <trkseg>")
        for (wpt in waypoints) {
            appendLine("""      <trkpt lat="${wpt.lat}" lon="${wpt.lon}">""")
            wpt.elevM?.let { appendLine("        <ele>$it</ele>") }
            appendLine("        <time>${formatTime(wpt.createdAt)}</time>")
            // Named waypoints embedded in a trail get a name; raw track points don't.
            if (wpt.kind == Waypoint.KIND_WAYPOINT) {
                appendLine("        <name>${escapeXml(wpt.name)}</name>")
            }
            appendLine("      </trkpt>")
        }
        appendLine("    </trkseg>")
        appendLine("  </trk>")
    }

    // Produces e.g. "2025-06-03T14:32:00Z". SimpleDateFormat is available on all
    // current targets (JVM + androidTarget). If a non-JVM target is added later,
    // replace with expect/actual or kotlinx-datetime.
    private fun formatTime(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }

    private fun escapeXml(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
