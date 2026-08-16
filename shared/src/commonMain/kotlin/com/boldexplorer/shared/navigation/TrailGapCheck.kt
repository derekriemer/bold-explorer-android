package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import kotlin.math.max

/** Why a jump between consecutive points looks like a data error rather than walking. */
enum class GapReason {
    /** The later point was recorded *earlier*. Two sessions spliced together. */
    BackwardsTime,

    /** Covering that distance in that time is not travel of any kind — a single wild fix. */
    ImpossibleSpeed,

    /**
     * Far beyond the trail's own spacing, but at a speed a walk could produce.
     *
     * The weakest of the three, and genuinely ambiguous: a point appended from elsewhere long
     * afterwards looks exactly like a GPS dropout that lasted a while. `dos` — the case #69 was
     * written for — is this kind: 1031 m over 78 minutes is 0.22 m/s, an ordinary walking pace.
     */
    ImplausibleDistance,
}

/**
 * An implausibly long jump between two consecutive points of a recorded trail.
 *
 * @property afterIndex the gap lies between this point and the next one.
 * @property gapM how far apart they are.
 * @property medianSpacingM the trail's own typical spacing, which is what makes the gap suspect.
 * @property reason which signal fired; see [GapReason].
 * @property impliedSpeedMps distance over elapsed time, or `null` when the points carry no times.
 *   Reported rather than judged, because whether it is plausible depends on how the trail was made.
 */
data class TrailGap(
    val afterIndex: Int,
    val gapM: Double,
    val medianSpacingM: Double,
    val reason: GapReason,
    val impliedSpeedMps: Double? = null,
)

/**
 * Finds jumps in a trail's geometry that no walk could have produced (#69).
 *
 * A trail is a walked path, so consecutive points are bounded by walking. A gap far beyond the
 * trail's own typical spacing is not a segment, it is a data error — and one that is silent and
 * confidently wrong. The case this exists for: a point appended to trail 12 an hour after recording
 * sat 1031 m from its predecessor, against a median spacing of ~11 m. The app then believed the
 * trail was 2523 m rather than 1492 m, that it ended 50 m from its own start rather than 1060 m
 * away, announced a "nearby" trail end to someone 2.4 km of walking from it, and would have routed
 * a blind user down a kilometre-long straight line across unmapped ground. Nothing flagged it; it
 * was caught because the owner knew how far he had walked.
 *
 * **Detection only.** Whether a stray point was a mistake or a deliberate extension is not knowable
 * from the data, so repair is a question for the human — see #69's scope.
 */
object TrailGapCheck {
    /**
     * Absolute floor, so a densely recorded trail does not cry wolf.
     *
     * At ~2 m spacing a routine GPS dropout leaves a 50–100 m gap that is perfectly real walking.
     * Without this floor the median multiple alone would flag those constantly, and a check that
     * fires on normal recordings is one nobody reads.
     */
    const val ABSOLUTE_FLOOR_M = 200.0

    /**
     * Multiple of the trail's median spacing a gap must also exceed.
     *
     * Sparse curated routes legitimately have long legs, so the bar has to scale with how the trail
     * was recorded rather than being one number for every trail. Twenty is deliberately blunt: the
     * signal it exists to catch is 1031 m against 11 m, and a threshold tuned finely enough to
     * argue about would be tuned on a single example.
     */
    const val MEDIAN_MULTIPLE = 20.0

    /**
     * Faster than any travel that could have laid down a trail, in m/s.
     *
     * 30 m/s is 108 km/h — beyond walking, cycling or ordinary driving, so exceeding it means the
     * fix moved rather than the person. Deliberately far above anything legitimate: the case this
     * catches ran at 287 m/s. Only consulted for gaps already too long to be walking, because the
     * timestamps are row-insert times and a batch of them shares one.
     */
    const val MAX_PLAUSIBLE_SPEED_MPS = 30.0

    /**
     * Every suspect gap in [points], in order. Empty for a trail that was simply walked.
     *
     * Fewer than three points cannot establish a typical spacing, so nothing is claimed about them.
     *
     * @param recordedAtMs when each point was recorded, parallel to [points]. Optional because the
     *   geometry check stands without it — but with it, two sharper signals become available that
     *   distance alone cannot express: a point recorded *before* its predecessor, and one that
     *   would have had to move faster than any vehicle.
     */
    fun findGaps(
        points: List<LatLng>,
        recordedAtMs: List<Long>? = null,
    ): List<TrailGap> {
        if (points.size < 3) return emptyList()
        require(recordedAtMs == null || recordedAtMs.size == points.size) {
            "recordedAtMs must line up with points: ${recordedAtMs?.size} vs ${points.size}"
        }
        val spacings = points.zipWithNext { a, b -> haversineDistanceMeters(a, b) }
        val median = spacings.sorted()[spacings.size / 2]
        if (median <= 0.0) return emptyList()
        val threshold = max(ABSOLUTE_FLOOR_M, MEDIAN_MULTIPLE * median)

        return spacings.mapIndexedNotNull { i, gap ->
            // Distance decides *whether* a gap is suspect; time only decides *what kind*.
            //
            // Time cannot stand alone here because `created_at` is when the row was written, not
            // when the fix was taken. Points inserted in a batch — an import, or any bulk write —
            // share a timestamp, so a perfectly ordinary 1 m step between them reads as hundreds of
            // metres per second. Run as an independent signal it flagged eighteen consecutive
            // "impossible" hops of 1-11 m on one trail, which is the check crying wolf on exactly
            // the data it is supposed to reassure you about.
            if (gap <= threshold) return@mapIndexedNotNull null
            val elapsedSec = recordedAtMs?.let { (it[i + 1] - it[i]) / 1000.0 }
            val speed = if (elapsedSec != null && elapsedSec > 0.0) gap / elapsedSec else null
            val reason =
                when {
                    elapsedSec != null && elapsedSec < 0.0 -> GapReason.BackwardsTime
                    speed != null && speed > MAX_PLAUSIBLE_SPEED_MPS -> GapReason.ImpossibleSpeed
                    else -> GapReason.ImplausibleDistance
                }
            TrailGap(
                afterIndex = i,
                gapM = gap,
                medianSpacingM = median,
                reason = reason,
                impliedSpeedMps = speed,
            )
        }
    }
}
