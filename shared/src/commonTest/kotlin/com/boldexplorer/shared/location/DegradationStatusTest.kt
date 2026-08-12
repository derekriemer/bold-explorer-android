package com.boldexplorer.shared.location

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The GPS degradation readout is display-only: nothing about it is announced.
 *
 * It got there via a bug worth not repeating. The countdown was first given `liveRegion = Polite`,
 * so TalkBack re-read the whole status once a second for thirty minutes. The first fix stabilised
 * the announced *string* and made no difference, because the Debug screen reads a 2 Hz clock in its
 * own body: the entire screen recomposes twice a second, every semantics node in it is re-emitted,
 * and TalkBack announces on node update rather than on string change. The live region was then
 * removed outright — reported accuracy already shows when degradation is armed.
 *
 * What survives here is the display text, which may tick freely precisely because nothing speaks it.
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

    @Test
    fun theDetailTicks() {
        val details = (0L until windowMs / 1000L).map { armed().detailAt(armedAt + it * 1000L) }.distinct()
        assertTrue(details.size > 1, "the countdown should advance; it is read on focus, never announced")
    }

    @Test
    fun remainingTimeIsSpokenCoarsely() {
        val config = armed()
        // "1737 seconds" is long to say and no one converts it in their head.
        assertTrue(
            config.detailAt(armedAt + 60_000L).contains("29 minutes"),
            "got ${config.detailAt(armedAt + 60_000L)}",
        )
        // Rounding up keeps it from reading "0 minutes" while still degrading.
        assertTrue(
            config.detailAt(armedAt + windowMs - 61_000L).contains("2 minutes"),
            "got ${config.detailAt(armedAt + windowMs - 61_000L)}",
        )
        assertTrue(
            config.detailAt(armedAt + windowMs - 30_000L).contains("30 seconds"),
            "got ${config.detailAt(armedAt + windowMs - 30_000L)}",
        )
    }

    @Test
    fun anExpiredConfigReadsAsOff() {
        assertTrue(armed().detailAt(armedAt + windowMs).startsWith("Off"))
        assertTrue(DegradationConfig().detailAt(armedAt).startsWith("Off"))
    }
}
