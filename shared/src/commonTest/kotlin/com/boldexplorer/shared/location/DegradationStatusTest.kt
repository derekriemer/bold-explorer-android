package com.boldexplorer.shared.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the live-region contract for the GPS degradation readout.
 *
 * The bug these exist for shipped and was caught in the field, not here: the countdown was given
 * `liveRegion = Polite`, so TalkBack re-read the whole status once a second for thirty minutes. The
 * property that was violated is testable without a device — an announcement string must be a
 * function of *state*, never of the clock — so it is tested here rather than left to a screen
 * reader to notice again.
 */
class DegradationStatusTest {
    private val armedAt = 1_000_000L
    private val windowMs = 30 * 60 * 1000L

    private fun armed() =
        DegradationConfig(
            lateralBiasM = 25.0,
            biasBearingDeg = 90.0,
            expiresAtMs = armedAt + windowMs,
        )

    /** Every whole second of a 30-minute window, which is exactly the interval that misbehaved. */
    private fun everySecond(config: DegradationConfig) =
        (0L until windowMs / 1000L).map { config.statusAt(armedAt + it * 1000L) }

    @Test
    fun theAnnouncementDoesNotChangeWhileTheCountdownTicks() {
        // The regression test proper. Before the fix this produced 1800 distinct strings, one per
        // second, each of which TalkBack spoke in full.
        val announcements = everySecond(armed()).map { it.announcement }.distinct()
        assertEquals(
            1,
            announcements.size,
            "an armed window must produce one announcement, not one per tick; got $announcements",
        )
    }

    @Test
    fun theDetailDoesTick() {
        // The other half of the contract: the countdown really is live information, which is why it
        // belongs in text that is read on focus rather than announced. A test that only pinned the
        // announcement would also pass if the countdown had been deleted outright.
        val details = everySecond(armed()).map { it.detail }.distinct()
        assertTrue(details.size > 1, "the visible countdown should still advance")
    }

    @Test
    fun expiryChangesTheAnnouncement() {
        // Expiry is the one transition the user did not initiate, so it is the one that has to be
        // announced. Arming and disarming are button presses; they announce themselves.
        val config = armed()
        val whileActive = config.statusAt(armedAt + windowMs - 1)
        val afterExpiry = config.statusAt(armedAt + windowMs)
        assertTrue(
            whileActive.announcement != afterExpiry.announcement,
            "expiry must change the announcement so the live region fires",
        )
    }

    @Test
    fun remainingTimeIsSpokenCoarsely() {
        val config = armed()
        // "1737 seconds" is long to say and no one converts it in their head.
        assertTrue(
            config.statusAt(armedAt + 60_000L).detail.contains("29 minutes"),
            "got ${config.statusAt(armedAt + 60_000L).detail}",
        )
        // Rounding up keeps it from reading "0 minutes" while still degrading.
        assertTrue(
            config.statusAt(armedAt + windowMs - 61_000L).detail.contains("2 minutes"),
            "got ${config.statusAt(armedAt + windowMs - 61_000L).detail}",
        )
        assertTrue(
            config.statusAt(armedAt + windowMs - 30_000L).detail.contains("30 seconds"),
            "got ${config.statusAt(armedAt + windowMs - 30_000L).detail}",
        )
    }

    @Test
    fun aDisarmedConfigReadsAsOff() {
        val status = DegradationConfig().statusAt(armedAt)
        assertTrue(status.detail.startsWith("Off"))
        assertTrue(status.announcement.contains("off"))
    }
}
