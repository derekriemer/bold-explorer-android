package com.boldexplorer.shared.navigation

import com.boldexplorer.shared.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The gate between a GPS fix and a trail's geometry (#69).
 *
 * The bug these exist for was found in review of the first version, which measured a fix's implied
 * speed against the last *recorded* point. A recorder writes a vertex only every 10 m and writes
 * none at all while the user stands still, so that elapsed time grows without bound and the speed
 * measured against it shrinks towards zero — the guard evaporating exactly when a stationary user
 * with a poor sky view is most likely to get a wild fix.
 */
class TrackPointGateTest {
    private fun latFor(m: Double) = m / 111_194.9

    private fun north(m: Double) = LatLng(latFor(m), 0.0)

    private fun gate() = TrackPointGate(minSpacingM = 10.0)

    @Test
    fun jitterAtPoorAccuracyIsKept() {
        // Swept from the 2026-08-12 corpus: the two fastest honest steps in 2105 consecutive-fix
        // pairs were 34.0 m and 30.2 m in one second, both at 25 m reported accuracy. A flat 30 m/s
        // rule — the first version of this check — refused them. A fix that says it might be 25 m
        // out has already told you it can invent a jump like that.
        val g = gate()
        g.start(north(0.0), timestampMs = 0L, accuracyM = 25.0)

        assertIs<TrackPointDecision.Record>(
            g.consider(north(34.0), timestampMs = 1_000L, accuracyM = 25.0),
            "refused a real fix from the corpus",
        )
    }

    @Test
    fun theSameJumpAtGoodAccuracyIsRefused() {
        // The inverse, and the reason a flat speed bar had it backwards: 60 m in a second while the
        // phone claims ±3 m is not noise it admits to. Budget there is the travel term, 50 m.
        val g = gate()
        g.start(north(0.0), timestampMs = 0L, accuracyM = 3.0)

        val decision = g.consider(north(60.0), timestampMs = 1_000L, accuracyM = 3.0)

        val impossible = assertIs<TrackPointDecision.Impossible>(decision)
        assertTrue(impossible.budgetM in 49.0..51.0, "budget was ${impossible.budgetM}")
    }

    @Test
    fun standingStillDoesNotErodeTheGuard() {
        // The review's case. Stand still for 40 s — every fix is believable and none is recorded,
        // because none is 10 m from the last vertex — then a 1 km teleport arrives. Measured from
        // the last recorded point that is 25 m/s and passes; measured from the last fix we believed,
        // one second earlier, it is 1000 m/s.
        val g = gate()
        g.start(north(0.0), timestampMs = 0L, accuracyM = 5.0)
        for (t in 1..40) {
            assertIs<TrackPointDecision.TooClose>(
                g.consider(north(0.3), timestampMs = t * 1_000L, accuracyM = 5.0),
                "a stationary fix should be believable but not recorded",
            )
        }

        val decision = g.consider(north(1000.0), timestampMs = 41_000L, accuracyM = 5.0)

        val impossible = assertIs<TrackPointDecision.Impossible>(decision, "a 1 km hop in one second was recorded")
        assertTrue(impossible.impliedSpeedMps > 900.0, "measured against the wrong anchor: ${impossible.impliedSpeedMps}")
        assertTrue(impossible.jumpM > impossible.budgetM, "a rejection has to exceed its own budget")
    }

    @Test
    fun aRejectedFixIsNotEvidenceOfAnything() {
        // After refusing a teleport, the next real fix must be judged from the last believed
        // position — not from the teleport. Otherwise the return leg reads as a second impossible
        // jump, and worse, a teleport that was somehow admitted would make the walk back look wild.
        val g = gate()
        g.start(north(0.0), timestampMs = 0L, accuracyM = 5.0)
        g.consider(north(12.0), timestampMs = 10_000L, accuracyM = 5.0)

        assertIs<TrackPointDecision.Impossible>(g.consider(north(5000.0), timestampMs = 11_000L, accuracyM = 5.0))

        // Walking on from where we actually were.
        assertIs<TrackPointDecision.Record>(
            g.consider(north(24.0), timestampMs = 20_000L, accuracyM = 5.0),
            "the walk could not continue after one bad fix",
        )
    }

    @Test
    fun anOrdinaryWalkRecordsAtTheSpacing() {
        val g = gate()
        g.start(north(0.0), timestampMs = 0L, accuracyM = 5.0)

        // 1.4 m/s, a fix a second: a vertex roughly every seven seconds, nothing refused.
        val decisions = (1..21).map { t -> g.consider(north(t * 1.4), timestampMs = t * 1_000L, accuracyM = 5.0) }

        assertTrue(decisions.none { it is TrackPointDecision.Impossible }, "ordinary walking was refused")
        assertEquals(2, decisions.count { it is TrackPointDecision.Record }, "expected a vertex every 10 m")
    }

    @Test
    fun aDropoutAtWalkingPaceStillRecords() {
        // No fixes for five minutes, then one 300 m further on: 1 m/s. That is real walking that
        // happened to go unobserved, and refusing it would delete the walk to protect the trail.
        val g = gate()
        g.start(north(0.0), timestampMs = 0L, accuracyM = 5.0)

        assertIs<TrackPointDecision.Record>(g.consider(north(300.0), timestampMs = 300_000L, accuracyM = 5.0))
    }

    @Test
    fun theFirstFixOfASessionIsNotJudged() {
        // Nothing to judge it against. A cold start records where the user is.
        val g = gate()
        g.start()

        assertIs<TrackPointDecision.Record>(g.consider(north(1000.0), timestampMs = 1_000L, accuracyM = 5.0))
    }

    @Test
    fun aStaleSeedIsPermissiveRatherThanWrong() {
        // Recording can begin from a cached fix minutes old. Seeding with its real timestamp makes
        // the first judgement lenient, which is the right bias when the reference is not trustworthy
        // — as against seeding with "now", which would call an ordinary first step a teleport.
        val g = gate()
        g.start(north(0.0), timestampMs = 0L, accuracyM = 5.0)

        assertIs<TrackPointDecision.Record>(g.consider(north(500.0), timestampMs = 600_000L, accuracyM = 5.0))
    }

    @Test
    fun stopForgetsTheSession() {
        val g = gate()
        g.start(north(0.0), timestampMs = 0L, accuracyM = 5.0)
        g.consider(north(20.0), timestampMs = 10_000L, accuracyM = 5.0)
        g.stop()
        g.start()

        // A new session cannot be judged against the previous one's last position.
        assertIs<TrackPointDecision.Record>(g.consider(north(9000.0), timestampMs = 11_000L, accuracyM = 5.0))
    }
}
