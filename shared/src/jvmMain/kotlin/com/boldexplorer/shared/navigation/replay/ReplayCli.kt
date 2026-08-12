package com.boldexplorer.shared.navigation.replay

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.navigation.MatchState
import com.boldexplorer.shared.navigation.MatchTuning
import com.boldexplorer.shared.navigation.NavigationPolicy
import com.boldexplorer.shared.navigation.TrailPolyline
import com.boldexplorer.shared.navigation.TravelDirection
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Command-line front end for [TrailReplay].
 *
 * ```
 * ./gradlew :shared:runReplay --args="trail.gpx walk.jsonl [--reverse] [--sweep]"
 * ```
 *
 * The trail comes from the app's own GPX export and the walk from the exported audio log, so a
 * replay needs nothing that a field tester cannot produce from the device in two taps.
 *
 * JVM-only on purpose: this reads files and prints tables, neither of which belongs in code that
 * ships to a phone.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("usage: runReplay <trail.gpx> <audio_log.jsonl> [--reverse] [--sweep]")
        return
    }
    val gpx = File(args[0])
    val log = File(args[1])
    val direction = if (args.contains("--reverse")) TravelDirection.Reverse else TravelDirection.Forward

    val points = readGpxPoints(gpx)
    val samples = readLoggedFixes(log)
    println("trail  : ${gpx.name} — ${points.size} points")
    println("walk   : ${log.name} — ${samples.size} fixes")
    if (points.size < 2 || samples.isEmpty()) {
        println("nothing to replay")
        return
    }
    val polyline = TrailPolyline(points)
    println("length : ${"%.0f".format(polyline.totalLengthM)} m")
    println("dir    : $direction")
    println()

    if (args.contains("--sweep")) {
        sweep(polyline, samples, direction)
    } else {
        printReport(TrailReplay.run(polyline, samples, direction, MatchTuning.DEFAULT), "shipping defaults")
    }
}

/**
 * One-at-a-time variation of the constants the field walk implicated.
 *
 * Deliberately not a grid search. Thirteen interacting constants make a grid both enormous and
 * misleading — with no ground truth to score against, the winner of a grid is whichever combination
 * happened to suit one walk. Varying one at a time shows *sensitivity*, which is the thing a human
 * can actually reason about before changing a default.
 */
private fun sweep(
    polyline: TrailPolyline,
    samples: List<LocationSample>,
    direction: TravelDirection,
) {
    val base = MatchTuning.DEFAULT
    val variants =
        buildList {
            add("shipping defaults" to base)
            // Finding 2: at the 60 m cap the matcher reported Matched at 56 m cross-track while the
            // live follower announced off-trail at OFF_TRAIL_FAR_M = 60 m.
            listOf(30.0, 40.0, 50.0).forEach { add("gate cap ${it.toInt()} m" to base.copy(matchGateCapM = it)) }
            listOf(15.0, 20.0).forEach { add("gate base ${it.toInt()} m" to base.copy(matchGateBaseM = it)) }
            listOf(2.0, 2.5).forEach { add("gate accuracy ×$it" to base.copy(matchGateAccuracyFactor = it)) }
            // Recovery latency: the cooldown dominates it, the corroboration distance sets the floor.
            listOf(10.0, 15.0, 20.0).forEach { add("rescan cooldown ${it.toInt()} s" to base.copy(rescanCooldownS = it)) }
            listOf(15.0, 35.0).forEach { add("corroboration ${it.toInt()} m" to base.copy(corroborationM = it)) }
            listOf(45.0, 60.0, 120.0).forEach { add("horizon ${it.toInt()} s" to base.copy(reckoningHorizonS = it)) }
            listOf(60.0, 90.0, 180.0).forEach { add("horizon ${it.toInt()} m" to base.copy(reckoningHorizonM = it)) }
        }

    println(
        "%-22s %7s %8s %8s %7s %6s %7s".format(
            "tuning", "matched", "xt p50", "xt max", "pinned", "recov", "median s",
        ),
    )
    println("-".repeat(70))
    for ((label, tuning) in variants) {
        val r = TrailReplay.run(polyline, samples, direction, tuning)
        println(
            "%-22s %6.1f%% %7.1f %8.1f %7d %6d %7.1f".format(
                label,
                r.matchedFraction * 100,
                r.crossPercentile(50),
                r.maxCrossWhileMatchedM,
                r.vertexPinnedFixes,
                r.recoveries.size,
                r.medianRecoverySec,
            ),
        )
    }
    println()
    println("xt max above ${NavigationPolicy.OFF_TRAIL_FAR_M} m means the matcher claims Matched")
    println("where the app is telling the user they may be off trail.")
}

private fun printReport(
    r: ReplayReport,
    label: String,
) {
    println("── $label ─────────────────────────────────")
    println("fixes            : ${r.fixes}")
    println("matched          : ${"%.1f".format(r.matchedFraction * 100)}%")
    MatchState.entries.forEach { s -> println("  ${s.name.padEnd(15)}: ${r.stateCounts[s] ?: 0}") }
    println("cross-track while Matched (m):")
    listOf(50, 90, 95, 99).forEach { p -> println("  p$p".padEnd(19) + ": ${"%.1f".format(r.crossPercentile(p))}") }
    println("  max              : ${"%.1f".format(r.maxCrossWhileMatchedM)}")
    println("vertex-pinned    : ${r.vertexPinnedFixes} fixes, longest run ${"%.0f".format(r.longestVertexPinM)} m walked")
    println("recoveries       : ${r.recoveries.size}, median ${"%.1f".format(r.medianRecoverySec)} s")
    println("flaps            : ${r.flaps.size} (dipped to Uncertain and back)")
    r.recoveries.forEach {
        println(
            "  lost ${it.latencySec.let { s -> "%.0f".format(s) }}s, " +
                "along jump ${it.alongJumpM?.let { j -> "%.0f".format(j) } ?: "?"} m",
        )
    }
    println("top dispositions :")
    r.dispositionCounts.entries.sortedByDescending { it.value }.take(12).forEach {
        println("  ${it.value.toString().padStart(5)}  ${it.key}")
    }
}

/** Reads `<trkpt>`/`<rtept>`/`<wpt>` in document order. */
internal fun readGpxPoints(file: File): List<LatLng> {
    val doc =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }.newDocumentBuilder().parse(file)
    // Track points first: a recorded trail exports as a track. Falling back to waypoints keeps a
    // hand-made or waypoint-only GPX usable rather than silently replaying nothing.
    for (tag in listOf("trkpt", "rtept", "wpt")) {
        val nodes = doc.getElementsByTagName(tag)
        if (nodes.length >= 2) {
            return (0 until nodes.length).map { i ->
                val e = nodes.item(i) as Element
                LatLng(e.getAttribute("lat").toDouble(), e.getAttribute("lon").toDouble())
            }
        }
    }
    return emptyList()
}

/**
 * Reads the raw fixes back out of an exported audio log.
 *
 * Mirrors the writer in the app's `TrailMatchLog` — these six key names are the contract between the
 * two, and the reason the raw fix is logged at all. A rename on the writing side that is not made
 * here shows up as "0 fixes" rather than as wrong numbers, which is the failure mode worth having.
 */
internal fun readLoggedFixes(file: File): List<LocationSample> =
    file.useLines { lines ->
        lines.mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching {
                val json = JSONObject(line)
                if (json.optString("kind") != "TRAIL_MATCH") return@runCatching null
                val extra = json.optJSONObject("extra") ?: return@runCatching null
                if (!extra.has("lat") || !extra.has("lon")) return@runCatching null
                LocationSample(
                    lat = extra.getDouble("lat"),
                    lon = extra.getDouble("lon"),
                    accuracy = extra.optDoubleOrNull("acc_m"),
                    heading = extra.optDoubleOrNull("course_deg"),
                    speed = extra.optDoubleOrNull("speed_mps"),
                    timestamp = json.getLong("ts"),
                    provider = extra.optString("provider", "unknown"),
                )
            }.getOrNull()
        }.toList()
    }.sortedBy { it.timestamp }

private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
