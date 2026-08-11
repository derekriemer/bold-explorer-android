package com.boldexplorer.audio

import com.boldexplorer.shared.navigation.TrailMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A human-readable snapshot of what the shadow matcher last decided. */
data class ShadowMatchSnapshot(
    val state: String,
    val alongM: Double?,
    val crossM: Double?,
    val travelledM: Double,
    val disposition: String,
    val atMs: Long,
)

/**
 * On/off switch and debug readout for the shadow matcher.
 *
 * ## Why there is a switch at all
 *
 * `TRAIL_MATCH` is the first log kind that writes on *every* fix — every other kind is event-driven
 * (an announcement, a beacon, a rejected fix), so the log has never had a steady-state rate before.
 * At 620 bytes and 1 Hz that is 2.1 MB per walking hour, which is fine for a walk you opted into and
 * wrong to impose on every trail follow forever. Since the Debug screen ships in every build rather
 * than being a debug-only variant, "it is only in debug builds" is not the protection it sounds like.
 *
 * Defaults to **on**, deliberately. The failure this is guarding is asymmetric: leaving it on costs
 * a couple of megabytes, while walking a trail with it silently off costs the entire walk, and there
 * is no way to notice mid-walk that nothing is being recorded.
 *
 * ## Why the readout is throttled
 *
 * The snapshot updates at most every [UI_INTERVAL_MS], not per fix. This is a screen the user reads
 * with TalkBack; a value churning at 1 Hz is unreadable, and the log — not the screen — is the
 * record that matters. The logging itself is *not* throttled: that would put holes in the replay
 * corpus this whole step exists to produce.
 */
@Singleton
class ShadowMatchMonitor
    @Inject
    constructor() {
        private val _enabled = MutableStateFlow(true)

        /** Whether the shadow matcher runs at all. Gates the matching as well as the logging. */
        val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

        private val _snapshot = MutableStateFlow<ShadowMatchSnapshot?>(null)
        val snapshot: StateFlow<ShadowMatchSnapshot?> = _snapshot.asStateFlow()

        private var lastUiUpdateMs: Long = 0

        fun setEnabled(enabled: Boolean) {
            _enabled.value = enabled
            if (!enabled) _snapshot.value = null
        }

        /** Records [match] for the readout, at most once per [UI_INTERVAL_MS]. */
        fun record(match: TrailMatch) {
            val now = System.currentTimeMillis()
            if (now - lastUiUpdateMs < UI_INTERVAL_MS) return
            lastUiUpdateMs = now
            _snapshot.value =
                ShadowMatchSnapshot(
                    state = match.state.name,
                    alongM = match.confirmedAlongM,
                    crossM = match.chosen?.crossTrackM,
                    travelledM = match.travelledM,
                    disposition = match.disposition,
                    atMs = now,
                )
        }

        /** Clears the readout when a follow ends, so a stale position cannot be misread as live. */
        fun clear() {
            _snapshot.value = null
            lastUiUpdateMs = 0
        }

        companion object {
            /** Minimum gap between readout updates. Readable with TalkBack; 1 Hz is not. */
            const val UI_INTERVAL_MS = 5_000L
        }
    }
