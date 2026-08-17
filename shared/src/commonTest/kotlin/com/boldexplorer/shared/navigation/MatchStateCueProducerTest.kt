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
    fun recoveryIsAnnouncedOnceAndOnlyAfterALoss() {
        val p = MatchStateCueProducer()
        repeat(3) { p.onFix(MatchState.Lost) }
        assertTrue(p.isLost)

        assertEquals(MatchStateCue.Reacquired, p.onFix(MatchState.Matched))
        assertNull(p.onFix(MatchState.Matched), "still matched is not news")
        assertFalse(p.isLost)
    }

    @Test
    fun aFollowThatStartsMatchedSaysNothing() {
        assertNull(MatchStateCueProducer().onFix(MatchState.Matched))
    }
}
