package com.boldexplorer.audio

import javax.inject.Inject
import javax.inject.Singleton

private const val AUDIO_USAGE_NAME = "USAGE_ASSISTANCE_ACCESSIBILITY"
private const val CONTENT_TYPE_NAME = "CONTENT_TYPE_SONIFICATION"

/** A bounded lease for one on-demand [android.media.AudioTrack] cue. */
internal data class AudioOutputLease(
    val cue: String,
    val startedAtMs: Long,
)

/** Records every active output-stream transition for field diagnosis of issue #53. */
@Singleton
class CueOutputLifecycle internal constructor(
    private val audioLog: AudioLogSink,
    private val nowMs: () -> Long,
) {
    @Inject
    constructor(
        audioLog: AudioLogSink,
    ) : this(audioLog, System::currentTimeMillis)

    internal fun started(
        cue: String,
        preRollMs: Int,
    ): AudioOutputLease {
        val startedAt = nowMs()
        audioLog.append(
            AudioLogEntry(
                timestampMs = startedAt,
                kind = AudioLogEntry.Kind.AUDIO_OUTPUT,
                trigger = cue,
                inputs = outputInputs(preRollMs),
                outputs = "state=STARTED",
                played = "",
            ),
        )
        return AudioOutputLease(cue, startedAt)
    }

    internal fun stopped(
        lease: AudioOutputLease,
        reason: String,
    ) {
        val stoppedAt = nowMs()
        audioLog.append(
            AudioLogEntry(
                timestampMs = stoppedAt,
                kind = AudioLogEntry.Kind.AUDIO_OUTPUT,
                trigger = lease.cue,
                inputs = "action=stop",
                outputs = "state=STOPPED, reason=$reason, activeMs=${(stoppedAt - lease.startedAtMs).coerceAtLeast(0L)}",
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
                inputs = "action=start",
                outputs = "state=UNAVAILABLE, reason=$reason",
                played = "",
            ),
        )
    }

    private fun outputInputs(preRollMs: Int): String =
        "action=start, stream=on_demand, usage=$AUDIO_USAGE_NAME, contentType=$CONTENT_TYPE_NAME, preRollMs=$preRollMs"
}
