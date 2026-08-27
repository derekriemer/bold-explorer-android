package com.boldexplorer.audio

import com.boldexplorer.shared.audio.CueCadence
import javax.inject.Inject
import javax.inject.Singleton

private const val AUDIO_USAGE_NAME = "USAGE_ASSISTANCE_ACCESSIBILITY"
private const val CONTENT_TYPE_NAME = "CONTENT_TYPE_SONIFICATION"

/** A bounded lease for one session-scoped [android.media.AudioTrack] (#114/#108). */
internal data class AudioOutputLease(
    val startedAtMs: Long,
)

/**
 * Records every audio-output transition for field diagnosis (#53, #114, #108).
 *
 * Three separate concepts, logged separately, because "is the track healthy" and "did this cue's
 * audio land" became different questions once [AudioEngine] moved to a session-scoped track: a
 * session covers the whole [AudioCuePlayer] session; a track may be opened, paused, and reopened
 * several times within one session; a cue is played (or not) independent of whether the track
 * happened to be reopened for it.
 */
@Singleton
class CueOutputLifecycle internal constructor(
    private val audioLog: AudioLogSink,
    private val nowMs: () -> Long,
) {
    @Inject
    constructor(
        audioLog: AudioLogSink,
    ) : this(audioLog, System::currentTimeMillis)

    internal fun sessionStarted() {
        audioLog.append(
            AudioLogEntry(
                timestampMs = nowMs(),
                kind = AudioLogEntry.Kind.AUDIO_OUTPUT,
                trigger = "session",
                inputs = "action=session_start",
                outputs = "state=SESSION_STARTED",
                played = "",
            ),
        )
    }

    internal fun sessionEnded(reason: String) {
        audioLog.append(
            AudioLogEntry(
                timestampMs = nowMs(),
                kind = AudioLogEntry.Kind.AUDIO_OUTPUT,
                trigger = "session",
                inputs = "action=session_end",
                outputs = "state=SESSION_ENDED, reason=$reason",
                played = "",
            ),
        )
    }

    internal fun trackOpened(
        reason: String,
        warmupBudgetMs: Long,
    ): AudioOutputLease {
        val startedAt = nowMs()
        audioLog.append(
            AudioLogEntry(
                timestampMs = startedAt,
                kind = AudioLogEntry.Kind.AUDIO_OUTPUT,
                trigger = reason,
                inputs = outputInputs(warmupBudgetMs),
                outputs = "state=TRACK_OPENED",
                played = "",
            ),
        )
        return AudioOutputLease(startedAt)
    }

    internal fun trackClosed(
        lease: AudioOutputLease,
        reason: String,
    ) {
        val stoppedAt = nowMs()
        audioLog.append(
            AudioLogEntry(
                timestampMs = stoppedAt,
                kind = AudioLogEntry.Kind.AUDIO_OUTPUT,
                trigger = reason,
                inputs = "action=track_close",
                outputs = "state=TRACK_CLOSED, activeMs=${(stoppedAt - lease.startedAtMs).coerceAtLeast(0L)}",
                played = "",
            ),
        )
    }

    internal fun trackPaused(reason: String) {
        audioLog.append(
            AudioLogEntry(
                timestampMs = nowMs(),
                kind = AudioLogEntry.Kind.AUDIO_OUTPUT,
                trigger = reason,
                inputs = "action=track_pause",
                outputs = "state=TRACK_PAUSED",
                played = "",
            ),
        )
    }

    internal fun cuePlayed(
        cue: String,
        cadence: CueCadence,
        outcome: String,
    ) {
        audioLog.append(
            AudioLogEntry(
                timestampMs = nowMs(),
                kind = AudioLogEntry.Kind.AUDIO_OUTPUT,
                trigger = cue,
                inputs = "action=cue_play, cadence=$cadence",
                outputs = "state=CUE_OUTCOME, outcome=$outcome",
                played = "",
            ),
        )
    }

    internal fun unavailable(
        cue: String,
        reason: String,
    ) {
        audioLog.append(
            AudioLogEntry(
                timestampMs = nowMs(),
                kind = AudioLogEntry.Kind.AUDIO_OUTPUT,
                trigger = cue,
                inputs = "action=track_open_attempt",
                outputs = "state=UNAVAILABLE, reason=$reason",
                played = "",
            ),
        )
    }

    private fun outputInputs(warmupBudgetMs: Long): String =
        "action=track_open, usage=$AUDIO_USAGE_NAME, contentType=$CONTENT_TYPE_NAME, warmupBudgetMs=$warmupBudgetMs"
}
