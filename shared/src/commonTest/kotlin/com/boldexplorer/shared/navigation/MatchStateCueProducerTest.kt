package com.boldexplorer.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchStateCueProducerTest {
    @Test
    fun aLossIsAnnouncedOnlyOnceItIsSustained() {
        // A Matched -> Uncertain -> Matched flap must not chatter.
        val p = MatchStateCueProducer()
        p.onFix(MatchState.Matched)

        assertNull(p.onFix(MatchState.Uncertain), "one bad fix is not a loss")
        assertNull(p.onFix(MatchState.Uncertain))
        assertEquals(MatchStateCue.Lost, p.onFix(MatchState.Uncertain), "three sustains it")
        assertTrue(p.isLost)
    }

    @Test
    fun aFlapNeverAnnouncesAnything() {
        val p = MatchStateCueProducer()
        repeat(5) {
            p.onFix(MatchState.Matched)
            assertNull(p.onFix(MatchState.Uncertain))
        }
        assertFalse(p.isLost)
    }

    @Test
    fun recoveryIsAnnouncedOnceAndOnlyAfterASustainedReacquisition() {
        // #82: reacquisition needs the same 3-fix sustain as loss, not a single fix.
        val p = MatchStateCueProducer()
        repeat(3) { p.onFix(MatchState.Lost) }
        assertTrue(p.isLost)

        assertNull(p.onFix(MatchState.Matched), "one good fix is not a reacquisition")
        assertTrue(p.isLost, "still considered lost until the sustain is met")
        assertNull(p.onFix(MatchState.Matched))
        assertEquals(MatchStateCue.Reacquired, p.onFix(MatchState.Matched), "three sustains it")
        assertNull(p.onFix(MatchState.Matched), "still matched is not news")
        assertFalse(p.isLost)
    }

    @Test
    fun aReacquisitionFlapNeverAnnouncesAnything() {
        // The exact #82 field case: a marginal Matched fix right at the gate, followed by a drop
        // back out, must not have announced "back on the trail" for that single fix.
        val p = MatchStateCueProducer()
        repeat(3) { p.onFix(MatchState.Lost) }

        repeat(5) {
            assertNull(p.onFix(MatchState.Matched))
            assertNull(p.onFix(MatchState.Uncertain))
        }
        assertTrue(p.isLost, "no sustained streak of 3 ever completed")
    }

    @Test
    fun aFollowThatStartsMatchedSaysNothing() {
        assertNull(MatchStateCueProducer().onFix(MatchState.Matched))
    }
}
