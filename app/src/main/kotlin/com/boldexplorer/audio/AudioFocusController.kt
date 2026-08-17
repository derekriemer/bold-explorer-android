package com.boldexplorer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val FOCUS_GAIN_NAME = "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"
private const val AUDIO_USAGE_NAME = "USAGE_ASSISTANCE_ACCESSIBILITY"
private const val CONTENT_TYPE_NAME = "CONTENT_TYPE_SONIFICATION"

/** The attributes shared by the focus request and the [android.media.AudioTrack]. */
internal fun beaconAudioAttributes(): AudioAttributes =
    AudioAttributes
        .Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

/** The mode that was actually applied to one audible cue. */
internal enum class CueAudioMode(
    val logValue: String,
) {
    /** No focus request: the cue mixes with other media at its current volume. */
    MIX("mix"),

    /** Transient-may-duck focus was granted for the cue's audible lifetime. */
    DUCK("duck"),

    /** Ducking was requested but denied; the accessibility cue still played by mixing. */
    MIX_FOCUS_DENIED("mix_focus_denied"),

    /** Policy prevented the cue from playing, so no focus request was made. */
    SUPPRESSED("suppressed"),
}

internal enum class AudioFocusRequestResult {
    GRANTED,
    DELAYED,
    FAILED,
}

internal interface AudioFocusBackend {
    fun requestTransientMayDuck(): AudioFocusRequestResult

    fun abandonTransientMayDuck()
}

/** Android's focus API behind a small seam so focus lifetime can be unit-tested on the JVM. */
private class AndroidAudioFocusBackend(
    context: Context,
) : AudioFocusBackend {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var activeRequest: AudioFocusRequest? = null

    override fun requestTransientMayDuck(): AudioFocusRequestResult {
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(beaconAudioAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* System-owned ducking needs no app callback. */ }
                .build()
        return when (audioManager.requestAudioFocus(request)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                activeRequest = request
                AudioFocusRequestResult.GRANTED
            }

            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                AudioFocusRequestResult.DELAYED
            }

            else -> {
                AudioFocusRequestResult.FAILED
            }
        }
    }

    override fun abandonTransientMayDuck() {
        try {
            activeRequest?.let(audioManager::abandonAudioFocusRequest)
        } finally {
            activeRequest = null
        }
    }
}

/**
 * Owns the per-cue transient ducking lease.
 *
 * Focus starts immediately before the suspendable playback operation and is released from its
 * `finally` block after [AudioEngine] reports that the cue's final PCM frame was rendered. When
 * ducking is disabled, or Android denies focus, playback remains an accessibility cue and mixes
 * with media instead of being dropped.
 */
@Singleton
class AudioFocusController internal constructor(
    private val backend: AudioFocusBackend,
    private val audioLog: AudioLogSink,
    private val nowMs: () -> Long,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        audioLog: AudioLogSink,
    ) : this(AndroidAudioFocusBackend(context), audioLog, System::currentTimeMillis)

    private val cueMutex = Mutex()
    private val stateLock = Any()
    private var nextLeaseId = 0L
    private var activeLease: FocusLease? = null

    /** Plays one cue with either transient ducking or transparent mixing. */
    internal suspend fun play(
        duckAudioEnabled: Boolean,
        cue: String,
        playback: suspend () -> Unit,
    ): CueAudioMode {
        if (!duckAudioEnabled) {
            playback()
            return CueAudioMode.MIX
        }

        return cueMutex.withLock {
            val lease = request(cue)
            try {
                playback()
                if (lease == null) CueAudioMode.MIX_FOCUS_DENIED else CueAudioMode.DUCK
            } finally {
                if (lease != null) release(lease, cue)
            }
        }
    }

    /** Releases a cue lease when navigation stops and its AudioTrack is torn down. */
    fun abandonForStop() {
        val lease = synchronized(stateLock) { activeLease?.also { activeLease = null } } ?: return
        val result = runCatching { backend.abandonTransientMayDuck() }
        logAbandon(
            cue = "Navigation stop (${lease.cue})",
            heldMs = (nowMs() - lease.startedAtMs).coerceAtLeast(0L),
            result = if (result.isSuccess) "ABANDONED" else "ERROR",
        )
    }

    private fun request(cue: String): FocusLease? =
        synchronized(stateLock) {
            val startedAt = nowMs()
            val result =
                runCatching { backend.requestTransientMayDuck() }
                    .getOrDefault(AudioFocusRequestResult.FAILED)
            audioLog.append(
                AudioLogEntry(
                    timestampMs = startedAt,
                    kind = AudioLogEntry.Kind.AUDIO_FOCUS,
                    trigger = cue,
                    inputs = focusInputs("request"),
                    outputs = "result=${result.name}",
                    played = "",
                ),
            )
            if (result != AudioFocusRequestResult.GRANTED) return@synchronized null

            FocusLease(++nextLeaseId, cue, startedAt).also { activeLease = it }
        }

    private fun release(
        lease: FocusLease,
        cue: String,
    ) {
        val ownsActiveLease =
            synchronized(stateLock) {
                if (activeLease?.id != lease.id) return@synchronized false
                activeLease = null
                true
            }
        if (!ownsActiveLease) return

        val result = runCatching { backend.abandonTransientMayDuck() }
        logAbandon(
            cue = cue,
            heldMs = (nowMs() - lease.startedAtMs).coerceAtLeast(0L),
            result = if (result.isSuccess) "ABANDONED" else "ERROR",
        )
    }

    private fun logAbandon(
        cue: String,
        heldMs: Long,
        result: String,
    ) {
        audioLog.append(
            AudioLogEntry(
                timestampMs = nowMs(),
                kind = AudioLogEntry.Kind.AUDIO_FOCUS,
                trigger = cue,
                inputs = focusInputs("abandon"),
                outputs = "result=$result, heldMs=$heldMs",
                played = "",
            ),
        )
    }

    private fun focusInputs(action: String): String =
        "action=$action, gain=$FOCUS_GAIN_NAME, usage=$AUDIO_USAGE_NAME, contentType=$CONTENT_TYPE_NAME"

    private data class FocusLease(
        val id: Long,
        val cue: String,
        val startedAtMs: Long,
    )
}
