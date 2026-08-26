package com.boldexplorer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
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

    /** Transient-may-duck focus was granted for the cue's audible lifetime (or already held). */
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

/** A focus-change callback fired asynchronously while a lease is held. See #108. */
internal enum class AudioFocusChange {
    /** `AUDIOFOCUS_LOSS` — another app now holds focus indefinitely. */
    Lost,

    /** `AUDIOFOCUS_LOSS_TRANSIENT` — another app holds focus briefly (e.g. dictation/exclusive). */
    LostTransient,

    /** `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` — no reaction needed; the platform ducks our volume. */
    LostTransientCanDuck,

    /** `AUDIOFOCUS_GAIN` — focus is ours again after a transient loss. */
    Gained,
}

internal interface AudioFocusBackend {
    fun requestTransientMayDuck(onFocusChange: (AudioFocusChange) -> Unit): AudioFocusRequestResult

    fun abandonTransientMayDuck()
}

/** Android's focus API behind a small seam so focus lifetime can be unit-tested on the JVM. */
private class AndroidAudioFocusBackend(
    context: Context,
) : AudioFocusBackend {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var activeRequest: AudioFocusRequest? = null

    override fun requestTransientMayDuck(onFocusChange: (AudioFocusChange) -> Unit): AudioFocusRequestResult {
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(beaconAudioAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> onFocusChange(AudioFocusChange.Lost)
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onFocusChange(AudioFocusChange.LostTransient)
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                            onFocusChange(AudioFocusChange.LostTransientCanDuck)
                        AudioManager.AUDIOFOCUS_GAIN -> onFocusChange(AudioFocusChange.Gained)
                        // DELAYED-gain isn't requested (setAcceptsDelayedFocusGain(false)); no other
                        // value is defined by the platform for this listener.
                        else -> Unit
                    }
                }.build()
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
 * Owns the audio focus lease and reacts to Android taking it away or giving it back (#108).
 *
 * Focus is cue-scoped by default: [requestForCue] requests immediately before a cue plays and
 * [releaseAfterCue] abandons it right after, mirroring [android.media.AudioTrack]'s own default
 * per-cue lifetime in [AudioEngine]. The one exception is while [setFrequentMode] has been told a
 * frequent-cadence cue source (e.g. alignment) is active: then the lease is requested once and held
 * across the whole run of cues instead of per-cue, because reopening a transient focus grant on the
 * same ~2s cadence that reopens a Bluetooth-routed [android.media.AudioTrack] is the mechanism #114
 * targets. Holding a lease longer only stays safe because of the reactive [onFocusChange] handling
 * below — see the design note on #114/#108 for the full rationale.
 *
 * When ducking is disabled ([requestForCue]'s `duckAudioEnabled = false`), this class never touches
 * Android's focus API at all, in either mode.
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

    private val stateLock = Any()
    private var holding = false
    private var frequentMode = false
    private var heldSinceMs = 0L

    /** Set by [AudioEngine] via [AudioCuePlayer] so a loss/mode-change can silence output immediately. */
    @Volatile
    var onFocusLostOrModeChange: ((reason: String) -> Unit)? = null

    /** Whether a frequent-cadence cue source is currently active — observable for tests. */
    internal val frequentModeActive: Boolean
        get() = synchronized(stateLock) { frequentMode }

    /**
     * Enter or leave frequent mode (#114/#108 — see class doc). Entering does nothing eagerly; the
     * next [requestForCue] still requests fresh, same as rare mode. Leaving releases whatever's held
     * right now and tells the track to pause, returning to per-cue discipline.
     */
    fun setFrequentMode(frequent: Boolean) {
        val shouldRelease =
            synchronized(stateLock) {
                if (frequentMode == frequent) return
                frequentMode = frequent
                !frequent && holding
            }
        if (shouldRelease) {
            releaseHeldLease(reason = "mode_change_to_rare")
            onFocusLostOrModeChange?.invoke("mode_change_to_rare")
        }
    }

    /** Plays one cue with either transient ducking or transparent mixing. */
    internal fun requestForCue(
        duckAudioEnabled: Boolean,
        cue: String,
    ): CueAudioMode {
        if (!duckAudioEnabled) return CueAudioMode.MIX

        val alreadyHolding = synchronized(stateLock) { holding }
        if (alreadyHolding) return CueAudioMode.DUCK

        val startedAt = nowMs()
        val result =
            runCatching { backend.requestTransientMayDuck(::handleFocusChange) }
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
        if (result != AudioFocusRequestResult.GRANTED) return CueAudioMode.MIX_FOCUS_DENIED

        synchronized(stateLock) {
            holding = true
            heldSinceMs = startedAt
        }
        return CueAudioMode.DUCK
    }

    /** No-op while frequent mode holds the lease across cues, or while ducking is disabled. */
    internal fun releaseAfterCue() {
        val shouldRelease = synchronized(stateLock) { holding && !frequentMode }
        if (shouldRelease) releaseHeldLease(reason = "cue_end")
    }

    /** Releases whatever's held when navigation stops and the [AudioEngine] session is torn down. */
    fun close() {
        synchronized(stateLock) { frequentMode = false }
        releaseHeldLease(reason = "session_stop")
        onFocusLostOrModeChange = null
    }

    private fun handleFocusChange(change: AudioFocusChange) {
        when (change) {
            AudioFocusChange.Lost -> {
                val wasHolding = synchronized(stateLock) { holding.also { holding = false } }
                if (!wasHolding) return
                // Permanent loss carries no platform guarantee of an automatic future GAIN callback
                // (that contract is specific to LOSS_TRANSIENT, below) — abandon cleanly now so a
                // later requestForCue() isn't silently overwriting a still-registered stale request.
                runCatching { backend.abandonTransientMayDuck() }
                logFocusEvent(cue = "onFocusChange", outputs = "result=LOST, permanent=true")
                onFocusLostOrModeChange?.invoke("focus_lost")
            }

            AudioFocusChange.LostTransient -> {
                val wasHolding = synchronized(stateLock) { holding.also { holding = false } }
                if (!wasHolding) return
                // Deliberately no abandon() here: staying registered is what makes Android's
                // automatic AUDIOFOCUS_GAIN callback (once the transient interrupter releases) land
                // on this same listener — see the class doc and #108.
                logFocusEvent(cue = "onFocusChange", outputs = "result=LOST, permanent=false")
                onFocusLostOrModeChange?.invoke("focus_lost")
            }

            AudioFocusChange.LostTransientCanDuck -> {
                logFocusEvent(cue = "onFocusChange", outputs = "result=DUCK_REQUESTED")
            }

            AudioFocusChange.Gained -> {
                synchronized(stateLock) { holding = true }
                logFocusEvent(cue = "onFocusChange", outputs = "result=REGAINED")
            }
        }
    }

    private fun releaseHeldLease(reason: String) {
        val wasHolding =
            synchronized(stateLock) {
                holding.also {
                    holding = false
                }
            }
        if (!wasHolding) return
        val result = runCatching { backend.abandonTransientMayDuck() }
        logAbandon(reason, result.isSuccess)
    }

    private fun logAbandon(
        reason: String,
        success: Boolean,
    ) {
        val heldMs = (nowMs() - heldSinceMs).coerceAtLeast(0L)
        logFocusEvent(
            cue = reason,
            outputs = "result=${if (success) "ABANDONED" else "ERROR"}, heldMs=$heldMs",
        )
    }

    private fun logFocusEvent(
        cue: String,
        outputs: String,
    ) {
        audioLog.append(
            AudioLogEntry(
                timestampMs = nowMs(),
                kind = AudioLogEntry.Kind.AUDIO_FOCUS,
                trigger = cue,
                inputs = focusInputs("abandon"),
                outputs = outputs,
                played = "",
            ),
        )
    }

    private fun focusInputs(action: String): String =
        "action=$action, gain=$FOCUS_GAIN_NAME, usage=$AUDIO_USAGE_NAME, contentType=$CONTENT_TYPE_NAME"
}
