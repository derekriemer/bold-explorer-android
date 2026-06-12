package com.boldexplorer.gpx

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

data class GpxParseResult(
    val waypoints: List<ParsedWaypoint>,
    val trails: List<ParsedTrail>,
    /** Value of <metadata><name>, if present. */
    val collectionName: String? = null,
) {
    val isEmpty get() = waypoints.isEmpty() && trails.isEmpty()
    val summary: String get() {
        val parts =
            buildList {
                if (waypoints.isNotEmpty()) add("${waypoints.size} waypoint${if (waypoints.size == 1) "" else "s"}")
                if (trails.isNotEmpty()) add("${trails.size} trail${if (trails.size == 1) "" else "s"}")
            }
        return if (parts.isEmpty()) "Nothing to import" else parts.joinToString(", ")
    }
}

data class ParsedWaypoint(
    val name: String,
    val lat: Double,
    val lon: Double,
    val elevM: Double?,
    val description: String?,
)

data class ParsedTrail(
    val name: String,
    val points: List<ParsedWaypoint>,
    /** true = from <rte> (named route points); false = from <trk> (raw track points) */
    val isRoute: Boolean,
)

object GpxParser {
    fun parse(stream: InputStream): GpxParseResult {
        val root =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(stream)
                .documentElement

        val collectionName =
            root
                .getElementsByTagName("metadata")
                .elements()
                .firstOrNull()
                ?.directChildText("name")

        val waypoints =
            root
                .getElementsByTagName("wpt")
                .elements()
                .mapNotNull { it.toWaypoint(fallbackName = "Waypoint") }

        val trails =
            buildList {
                root.getElementsByTagName("trk").elements().forEach { trk ->
                    val name = trk.directChildText("name") ?: "Imported Trail"
                    // Concatenate all <trkseg> segments into one ordered list.
                    val points =
                        trk
                            .getElementsByTagName("trkpt")
                            .elements()
                            .mapIndexed { i, pt -> pt.toWaypoint(fallbackName = "Point ${i + 1}") }
                            .filterNotNull()
                    if (points.isNotEmpty()) add(ParsedTrail(name, points, isRoute = false))
                }
                root.getElementsByTagName("rte").elements().forEach { rte ->
                    val name = rte.directChildText("name") ?: "Imported Route"
                    val points =
                        rte
                            .getElementsByTagName("rtept")
                            .elements()
                            .mapIndexed { i, pt -> pt.toWaypoint(fallbackName = "Point ${i + 1}") }
                            .filterNotNull()
                    if (points.isNotEmpty()) add(ParsedTrail(name, points, isRoute = true))
                }
            }

        return GpxParseResult(waypoints, trails, collectionName)
    }

    private fun Element.toWaypoint(fallbackName: String): ParsedWaypoint? {
        val lat = getAttribute("lat").toDoubleOrNull() ?: return null
        val lon = getAttribute("lon").toDoubleOrNull() ?: return null
        return ParsedWaypoint(
            name = directChildText("name") ?: fallbackName,
            lat = lat,
            lon = lon,
            elevM = directChildText("ele")?.toDoubleOrNull(),
            description = directChildText("desc"),
        )
    }

    /**
     * Text content of the first direct child element matching [tag] (namespace-prefix-agnostic).
     * Uses a direct child scan rather than getElementsByTagName to avoid picking up
     * nested elements with the same tag (e.g. <name> inside a <trkpt> within a <trk>).
     */
    private fun Element.directChildText(tag: String): String? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.localTag == tag) {
                return node.textContent.trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** Strip any namespace prefix so we match regardless of whether the file uses one. */
    private val Node.localTag: String
        get() = (localName ?: nodeName).substringAfterLast(':')

    private fun NodeList.elements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }
}
