package com.boldexplorer.gpx

import com.boldexplorer.shared.model.Waypoint

object GpxExporter {

    fun exportWaypoints(waypoints: List<Waypoint>): String = buildString {
        appendGpxHeader()
        for (wpt in waypoints) appendWpt(wpt)
        appendLine("</gpx>")
    }

    fun exportTrail(trailName: String, waypoints: List<Waypoint>): String = buildString {
        appendGpxHeader()
        for (wpt in waypoints) appendWpt(wpt)
        appendLine("  <trk>")
        appendLine("    <name>${escapeXml(trailName)}</name>")
        appendLine("    <trkseg>")
        for (wpt in waypoints) {
            appendLine("""      <trkpt lat="${wpt.lat}" lon="${wpt.lon}">""")
            appendLine("        <name>${escapeXml(wpt.name)}</name>")
            wpt.elevM?.let { appendLine("        <ele>$it</ele>") }
            appendLine("      </trkpt>")
        }
        appendLine("    </trkseg>")
        appendLine("  </trk>")
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
        appendLine("    <name>${escapeXml(wpt.name)}</name>")
        wpt.elevM?.let { appendLine("    <ele>$it</ele>") }
        wpt.description?.let { appendLine("    <desc>${escapeXml(it)}</desc>") }
        appendLine("  </wpt>")
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
