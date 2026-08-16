package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters

/** What a recorder should do with a fix. */
sealed interface TrackPointDecision {
    /** Keep it: far enough from the last kept point to be new geometry. */
    data class Record(
        val fromLastRecordedM: Double,
    ) : TrackPointDecision

    /** Believable, but too close to the last recorded point to be worth a vertex of its own. */
    data object TooClose : TrackPointDecision

    /**
     * No walk could have reached here from the last fix we believed.
     *
     * Carries its own evidence so the rejection can be logged and argued with afterwards rather
     * than inferred from a hole in the data.
     */
    data class Impossible(
        val jumpM: Double,
        val elapsedMs: Long,
        val budgetM: Double,
    ) : TrackPointDecision {
        val impliedSpeedMps: Double get() = if (elapsedMs > 0L) jumpM / (elapsedMs / 1000.0) else 0.0
    }
}

/**
 * The gate between a GPS fix and a trail's geometry.
 *
 * It answers two questions that look like one and are not, which is the whole reason this exists as
 * an object rather than a pair of fields in the recorder:
 *
 * 1. **Is this fix believable?** Judged against the last fix we believed — the previous *plausible*
 *    one, not the last one we kept. Every believable fix updates that reference, including the ones
 *    that are then thrown away for being too close to the last vertex.
 * 2. **Is it far enough to be worth keeping?** Judged against the last point actually recorded.
 *
 * Sharing one anchor between them is a silent hole. A recorder only writes a vertex every
 * [minSpacingM], and while the user stands still it writes none at all, so the elapsed time since
 * the last *recorded* point grows without bound — and any speed measured against it shrinks towards
 * zero. A 1 km teleport looks slower than walking after 34 s of standing still; a 100 m one needs
 * only 3.4 s, which is less than the gap between vertices at an ordinary walking pace. The guard
 * would then be strongest exactly when it is least needed and absent when a stationary user is most
 * likely to get a wild fix.
 *
 * A rejected fix updates **neither** anchor. It is not evidence of anything: not of where the user
 * is, and not of when we last knew. So the next fix is judged from the last position we believed,
 * which is what stops one teleport from dragging the trail out after it and then admitting the
 * return leg as an ordinary step.
 *
 * @param minSpacingM how far the user must move from the last recorded point before another vertex
 *   is worth writing.
 */
class TrackPointGate(
    private val minSpacingM: Double,
) {
    private var believed: LatLng? = null
    private var believedAtMs: Long? = null
    private var believedAccuracyM: Double? = null
    private var lastRecorded: LatLng? = null

    /**
     * Begin a recording session, optionally seeded with the fix already in hand.
     *
     * Seeding with its real [timestampMs] rather than "now" is what makes a stale cached fix
     * self-correcting: a long elapsed time makes the first judgement permissive, which is the right
     * bias when the reference itself is not trustworthy.
     */
    fun start(
        from: LatLng? = null,
        timestampMs: Long? = null,
        accuracyM: Double? = null,
    ) {
        believed = from
        believedAtMs = timestampMs
        believedAccuracyM = accuracyM
        lastRecorded = from
    }

    fun stop() {
        believed = null
        believedAtMs = null
        believedAccuracyM = null
        lastRecorded = null
    }

    /** Decide what to do with a fix, and fold it into whichever anchors it has earned. */
    fun consider(
        point: LatLng,
        timestampMs: Long,
        accuracyM: Double? = null,
    ): TrackPointDecision {
        val from = believed
        val fromAtMs = believedAtMs
        if (from != null && fromAtMs != null) {
            val jumpM = haversineDistanceMeters(from, point)
            val elapsedMs = timestampMs - fromAtMs
            val budgetM = NavigationPolicy.wildJumpBudgetM(elapsedMs, accuracyM, believedAccuracyM)
            // Out-of-order or same-instant fixes carry no travel budget and so cannot be judged on
            // one; the accuracy term is all that remains, and a non-positive elapsed time makes even
            // that meaningless. A check with no information must not reject.
            if (elapsedMs > 0L && jumpM > budgetM) {
                return TrackPointDecision.Impossible(jumpM = jumpM, elapsedMs = elapsedMs, budgetM = budgetM)
            }
        }

        // Believable — so it is the reference for the next fix whether or not it becomes geometry.
        believed = point
        believedAtMs = timestampMs
        believedAccuracyM = accuracyM

        val recorded = lastRecorded
        val stepM = recorded?.let { haversineDistanceMeters(it, point) }
        if (stepM != null && stepM < minSpacingM) return TrackPointDecision.TooClose

        lastRecorded = point
        return TrackPointDecision.Record(fromLastRecordedM = stepM ?: 0.0)
    }
}
