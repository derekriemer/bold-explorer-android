package com.boldexplorer.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioLogHistoryTest {
    private fun line(timestamp: Long) =
        AudioLogCodec.format(
            AudioLogEntry(timestamp, AudioLogEntry.Kind.USER_MARKER, "synthetic", "", "", ""),
        )

    @Test
    fun restoresTheRecentWindowFromALongLazyHistoryNewestFirst() {
        var consumed = 0
        val history =
            sequence {
                repeat(20_000) {
                    consumed++
                    yield(line(it.toLong()))
                }
            }

        val restored = AudioLogCodec.restoreRecent(history, maxEntries = 3)

        assertEquals(20_000, consumed)
        assertEquals(listOf(19_999L, 19_998L, 19_997L), restored.map { it.timestampMs })
    }

    @Test
    fun unreadableAndUnknownRowsDoNotEvictReadableHistory() {
        val history =
            sequenceOf(
                line(1),
                "not JSON",
                line(2),
                line(3).replace("USER_MARKER", "FUTURE_DIAGNOSTIC"),
                "",
                line(4),
                "truncated tail",
            )

        val restored = AudioLogCodec.restoreRecent(history, maxEntries = 2)

        assertEquals(listOf(4L, 2L), restored.map { it.timestampMs })
    }

    @Test
    fun emptyOrEntirelyUnreadableLogsRestoreAnEmptyWindow() {
        assertTrue(AudioLogCodec.restoreRecent(emptySequence(), maxEntries = 2).isEmpty())
        assertTrue(AudioLogCodec.restoreRecent(sequenceOf("", "bad"), maxEntries = 2).isEmpty())
    }
}
