package com.boldexplorer.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A marked section that has been opened and not yet closed. */
data class OpenSpan(
    val label: String,
    val startedAtMs: Long,
)

/**
 * Marked sections: a log entry for an *interval* rather than an instant.
 *
 * ## Why this exists
 *
 * `IMPORTANT!` files a point marker, and on the 2026-08-12 walk the tester improvised intervals out
 * of pairs of them — `stop start` then `stop end`, `bad gps start`. That bracket turned out to be
 * the single most useful structure in the file for reading behaviour, and it worked only because
 * the words happened to be unambiguous.
 *
 * Brackets also dissolve the timing problem that a point marker has. A point marker is ambiguous
 * about whether it describes what just happened or what is about to: the tester sometimes presses
 * the button to say "I am going to do this now". Encoding that in wording ("starting" versus
 * "start") would put a one-consonant distinction between a correct log and a confidently wrong one,
 * decided while walking, with cold hands, through a screen reader. Inside a bracket the question
 * does not arise — the bracket *is* the timing.
 *
 * ## Lives in a singleton, not the ViewModel
 *
 * A span outlives the screen. The tester opens one, walks for four minutes with the phone in a
 * pocket, and closes it — during which the Debug screen may well have been disposed. Holding this
 * in `DebugViewModel` would silently drop the open span, and an interval that loses its closing
 * bracket is worse than no interval at all.
 */
@Singleton
class MarkerSpanRecorder
    @Inject
    constructor(
        private val audioEventLog: AudioLogSink,
    ) {
        private val _openSpan = MutableStateFlow<OpenSpan?>(null)
        val openSpan: StateFlow<OpenSpan?> = _openSpan.asStateFlow()

        private var nextSpanId: Int = 1
        private var currentSpanId: Int = 0

        /**
         * Opens a marked section called [label].
         *
         * Opening one while another is open closes the first. Nesting would be more expressive and
         * is not worth it: there is one tester, walking, and a stack they cannot see is a stack they
         * will lose track of. Silently discarding the outer span instead would lose data.
         */
        fun start(
            label: String,
            nowMs: Long = System.currentTimeMillis(),
        ) {
            if (_openSpan.value != null) end(nowMs)
            currentSpanId = nextSpanId++
            _openSpan.value = OpenSpan(label = label, startedAtMs = nowMs)
            audioEventLog.append(
                AudioLogEntry(
                    timestampMs = nowMs,
                    kind = AudioLogEntry.Kind.USER_MARKER,
                    trigger = TRIGGER_START,
                    inputs = label,
                    outputs = "span=$currentSpanId",
                    played = "",
                    note = label,
                ),
            )
        }

        /** Closes the open section, if any. Idempotent, so a double press cannot log a stray end. */
        fun end(nowMs: Long = System.currentTimeMillis()) {
            val open = _openSpan.value ?: return
            _openSpan.value = null
            val durationMs = (nowMs - open.startedAtMs).coerceAtLeast(0L)
            audioEventLog.append(
                AudioLogEntry(
                    timestampMs = nowMs,
                    kind = AudioLogEntry.Kind.USER_MARKER,
                    trigger = TRIGGER_END,
                    inputs = open.label,
                    outputs = "span=$currentSpanId duration_ms=$durationMs",
                    played = "",
                    note = open.label,
                ),
            )
        }

        companion object {
            const val TRIGGER_START = "SpanStart"
            const val TRIGGER_END = "SpanEnd"
        }
    }

/**
 * What the screen shows and announces for [open].
 *
 * Split for the same reason [com.boldexplorer.shared.location.DegradationStatus] is: `detail` may
 * carry an elapsed count and tick, `announcement` may not. This one is a function of the open span
 * alone and has no clock in it at all, so a live region carrying it fires exactly on open and close.
 */
fun spanAnnouncement(open: OpenSpan?): String =
    if (open == null) "No marked section open." else "Marked section open: ${open.label}."

/** Button label. Says what pressing it will *do*, which is what a button is for. */
fun spanButtonLabel(open: OpenSpan?): String = if (open == null) "Start marked section" else "End: ${open.label}"

/**
 * Elapsed time, spoken.
 *
 * Coarse on purpose — the exact seconds are in the log, and this is read aloud while walking.
 */
fun spanElapsedText(elapsedMs: Long): String {
    val totalS = (elapsedMs / 1000L).coerceAtLeast(0L)
    if (totalS < 60L) return "$totalS seconds"
    val minutes = totalS / 60L
    val seconds = totalS % 60L
    val minutePart = if (minutes == 1L) "1 minute" else "$minutes minutes"
    return if (seconds == 0L) minutePart else "$minutePart $seconds seconds"
}
