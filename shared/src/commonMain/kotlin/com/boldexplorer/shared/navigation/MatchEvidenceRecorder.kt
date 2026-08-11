package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.deltaAngle
import com.boldexplorer.shared.model.LocationSample

/**
 * Course and divergence evidence for one matched fix. Recorded, never acted upon.
 *
 * @property speedMps GPS speed over ground, as reported.
 * @property courseDeg GPS course over ground, as reported.
 * @property courseValid whether [courseDeg] is trustworthy at this speed. Recorded explicitly
 *   because course is noise at a standstill, and an analysis that cannot tell jitter from evidence
 *   will read the jitter as evidence.
 * @property tangentDeg chord bearing of the trail at the chosen candidate.
 * @property courseAgreementDeg `deltaAngle(course, tangent)` for the chosen candidate,
 *   right-positive per the repo's angle convention.
 * @property rejectedTangentDeg chord bearing at the best rejected candidate.
 * @property rejectedCourseAgreementDeg agreement for the best rejected candidate. Together with
 *   [courseAgreementDeg] this is the load-bearing pair: a large split means course would have
 *   discriminated, a small split means it would have abstained, and that distribution is the entire
 *   basis for later promoting course from logged evidence to a load-bearing signal.
 * @property crossTrackRateMps signed rate of change of cross-track offset. Divergence evidence that
 *   needs no target and is density-independent — unlike bearing-to-target, whose staleness caused
 *   the original off-trail defect.
 */
data class MatchEvidence(
    val speedMps: Double?,
    val courseDeg: Double?,
    val courseValid: Boolean,
    val tangentDeg: Double?,
    val courseAgreementDeg: Double?,
    val rejectedTangentDeg: Double?,
    val rejectedCourseAgreementDeg: Double?,
    val crossTrackRateMps: Double?,
)

/**
 * Computes [MatchEvidence] beside a [ProgressTracker], without influencing it.
 *
 * This is a separate object rather than fields on the tracker so that the boundary is visible in
 * the type system: course is deliberately excluded from the matching window, because on a
 * switchback the parallel same-direction arms agree on course, so course endorses precisely the
 * confusion most needing rejection. Anything that could quietly start constraining the search has
 * to live outside the thing that does the searching.
 *
 * During `Unconfirmed` the calculus inverts — there the question is whether sustained evidence
 * supports a candidate over its rival, and course is close to decisive across the whole retrace
 * class. Promoting it from logged to load-bearing is an explicit post-field-walk decision, which is
 * what this recorder exists to inform.
 *
 * Stateful only in that a rate needs two samples.
 */
class MatchEvidenceRecorder(
    private val polyline: TrailPolyline,
) {
    private var previousCrossTrackM: Double? = null
    private var previousTimestampMs: Long? = null

    /** Records evidence for [match], which must be the result of this fix. */
    fun observe(
        sample: LocationSample,
        match: TrailMatch,
    ): MatchEvidence {
        val courseDeg = sample.heading
        val speedMps = sample.speed
        val courseValid = courseDeg != null && (speedMps ?: 0.0) >= TrailGuidance.MIN_TRUSTED_SPEED_MPS

        val tangentDeg = tangentAt(match.chosen)
        val rejectedTangentDeg = tangentAt(match.bestRejected)

        return MatchEvidence(
            speedMps = speedMps,
            courseDeg = courseDeg,
            courseValid = courseValid,
            tangentDeg = tangentDeg,
            courseAgreementDeg = agreement(courseDeg, tangentDeg),
            rejectedTangentDeg = rejectedTangentDeg,
            rejectedCourseAgreementDeg = agreement(courseDeg, rejectedTangentDeg),
            crossTrackRateMps = rateFor(match.chosen?.crossTrackM, sample.timestamp),
        )
    }

    private fun tangentAt(position: TrailPosition?): Double? =
        position?.let { polyline.chordBearingAt(it.alongTrackM, NavigationPolicy.COURSE_BASELINE_M) }

    private fun agreement(
        courseDeg: Double?,
        tangentDeg: Double?,
    ): Double? = if (courseDeg != null && tangentDeg != null) deltaAngle(courseDeg, tangentDeg) else null

    /**
     * Signed cross-track rate, or `null` when there is no comparable previous sample.
     *
     * A fix that produced no candidate clears the baseline rather than being skipped: measuring a
     * rate across a gap of unknown length would manufacture a divergence that never happened.
     */
    private fun rateFor(
        crossTrackM: Double?,
        timestampMs: Long,
    ): Double? {
        val previousCross = previousCrossTrackM
        val previousMs = previousTimestampMs
        previousCrossTrackM = crossTrackM
        previousTimestampMs = if (crossTrackM == null) null else timestampMs

        if (crossTrackM == null || previousCross == null || previousMs == null) return null
        val elapsedSec = (timestampMs - previousMs) / 1000.0
        if (elapsedSec <= 0.0) return null
        return (crossTrackM - previousCross) / elapsedSec
    }
}
