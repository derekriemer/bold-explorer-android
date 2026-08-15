package com.boldexplorer.audio

import com.boldexplorer.BuildConfig
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.navigation.MatchEvidence
import com.boldexplorer.shared.navigation.TrailMatch

/**
 * Builds the shadow-mode [AudioLogEntry.Kind.TRAIL_MATCH] record for one GPS fix.
 *
 * ## Why the raw fix is logged too
 *
 * The decision fields alone (state, window, chosen, rejected, disposition) let a walk be *audited*:
 * you can ask what the matcher did and whether it looks right. They do not let it be *replayed*,
 * because re-running the matcher needs its inputs. Without `lat`/`lon`/`acc`/`speed`/`course`,
 * changing `RECKONING_HORIZON_S` from 90 to 60 could only be evaluated by walking the trail again —
 * once per constant, in whatever weather. With them, one walk becomes a corpus that every candidate
 * set of constants can be swept against offline.
 *
 * That is the whole reason S4 ships in shadow mode: its constants are deliberately untuned, and the
 * ADR forbids anything depending on them until field evidence exists.
 *
 * ## Volume
 *
 * One entry per fix, so roughly 3600 lines/hour at 1 Hz. Measured at **620 bytes per line**, which
 * is **2.1 MB per walking hour**, appended on [kotlinx.coroutines.Dispatchers.IO]. Pinned by a test
 * rather than estimated, since the ADR asks for this to be verified before shipping. Fine for a
 * field walk that opts in; not something to leave on by default.
 */
fun trailMatchLogEntry(
    sample: LocationSample,
    match: TrailMatch,
    evidence: MatchEvidence,
): AudioLogEntry =
    AudioLogEntry(
        timestampMs = sample.timestamp,
        kind = AudioLogEntry.Kind.TRAIL_MATCH,
        trigger = "gps_fix",
        inputs = "acc=${sample.accuracy?.oneDp() ?: "?"}m spd=${sample.speed?.oneDp() ?: "?"}m/s",
        outputs = "${match.state} along=${match.confirmedAlongM?.oneDp() ?: "?"}m",
        // Shadow mode drives no audio. An empty `played` is the honest record of that, and it also
        // keeps these entries from being mistaken for something the user actually heard.
        played = "",
        note = match.disposition,
        extra =
            buildMap {
                // ── Raw fix: everything needed to replay this walk offline ──
                //
                // Debug and beta builds only. This is the one log record that carries the user's
                // position on *every* fix, so in a release build it would write a complete track of
                // wherever they walked — at 1 Hz, with the switch defaulting to on. Release already
                // strips coordinates everywhere else (`AudioCuePlayer`, and the build-type table in
                // AGENTS.md); this record was writing them anyway. Offline replay is a debug
                // activity, so losing it in release costs nothing.
                if (BuildConfig.SHOW_DEBUG_FEATURES) {
                    put("lat", sample.lat)
                    put("lon", sample.lon)
                    put("acc_m", sample.accuracy)
                    put("speed_mps", sample.speed)
                    put("course_deg", sample.heading)
                    put("provider", sample.provider)
                }

                // ── The decision ──
                put("state", match.state.name)
                put("scanKind", match.scanKind.name)
                put("disposition", match.disposition)
                put("confirmedAlong_m", match.confirmedAlongM)
                put("predictedAlong_m", match.predictedAlongM)
                put("predictionError_m", match.predictionErrorM)
                put("unmatched", match.unmatchedCount)
                put("uncertain_s", match.uncertainSec)
                put("travelled_m", match.travelledM)
                put("budget_m", match.budgetM)
                put("windowLo_m", match.windowM?.start)
                put("windowHi_m", match.windowM?.endInclusive)

                // ── Chosen and best-rejected, always as a pair ──
                // A decision recorded without its runner-up cannot be second-guessed on replay,
                // and the promote-or-drop question for course evidence is answerable only from
                // the split between the two.
                put("along_m", match.chosen?.alongTrackM)
                put("cross_m", match.chosen?.crossTrackM)
                put("segment", match.chosen?.segmentIndex)
                put("rejAlong_m", match.bestRejected?.alongTrackM)
                put("rejCross_m", match.bestRejected?.crossTrackM)

                // ── Course/vector evidence: logged, never constraining ──
                put("courseValid", evidence.courseValid)
                put("tangent_deg", evidence.tangentDeg)
                put("courseAgreement_deg", evidence.courseAgreementDeg)
                put("rejTangent_deg", evidence.rejectedTangentDeg)
                put("rejCourseAgreement_deg", evidence.rejectedCourseAgreementDeg)
                put("xtRate_mps", evidence.crossTrackRateMps)
            },
    )

/**
 * Reconstructs the fix from a parsed [AudioLogEntry.Kind.TRAIL_MATCH] record.
 *
 * The inverse of the raw-fix half of [trailMatchLogEntry], and the reason it exists: this is what a
 * replay harness calls to feed a recorded walk back through a retuned matcher. Returns `null` for
 * an entry that is not a trail match or is missing a position.
 */
fun AudioLogEntry.toReplayFix(): LocationSample? {
    if (kind != AudioLogEntry.Kind.TRAIL_MATCH) return null
    val lat = extra["lat"] as? Double ?: return null
    val lon = extra["lon"] as? Double ?: return null
    return LocationSample(
        lat = lat,
        lon = lon,
        accuracy = extra["acc_m"] as? Double,
        heading = extra["course_deg"] as? Double,
        speed = extra["speed_mps"] as? Double,
        timestamp = timestampMs,
        provider = extra["provider"] as? String ?: "unknown",
    )
}

private fun Double.oneDp(): String {
    val scaled = kotlin.math.round(this * 10.0) / 10.0
    return scaled.toString()
}
