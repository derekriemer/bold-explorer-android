package com.boldexplorer.location

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug-only diagnostic for the #23 freeze investigation: a haptic heartbeat that keeps working
 * while the GPS screen is on screen, so the accept/discard signal doesn't require alt-tabbing to
 * the Debug screen to read (which was itself suspected of masking the bug by forcing a
 * recomposition). Every [TICK_MS], buzzes once if at least one raw fix was accepted since the last
 * tick, or three quick buzzes if at least one was discarded — discard wins if both happened, since
 * that's the signal worth noticing. Silent if no raw fix arrived at all in the interval.
 *
 * App-scoped (not tied to the Debug screen's ViewModel lifecycle) so it keeps running across screen
 * navigation — toggled from the Debug screen, felt on whichever screen is actually open.
 */
@Singleton
class AccuracyHapticMonitor
    @Inject
    constructor(
        private val locationRouter: LocationProviderRouter,
        @ApplicationContext private val context: Context,
    ) {
        private val vibrator = context.getSystemService(Vibrator::class.java)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val _enabled = MutableStateFlow(false)
        val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

        @Volatile private var sawAccepted = false

        @Volatile private var sawDiscarded = false

        init {
            scope.launch {
                locationRouter.lastRawFix.collect { fix ->
                    if (fix == null) return@collect
                    if (fix.accepted) sawAccepted = true else sawDiscarded = true
                }
            }
            scope.launch {
                while (true) {
                    delay(TICK_MS)
                    val discarded = sawDiscarded
                    val accepted = sawAccepted
                    sawDiscarded = false
                    sawAccepted = false
                    if (!_enabled.value) continue
                    when {
                        discarded -> vibrator?.vibrate(DISCARDED_PATTERN)
                        accepted -> vibrator?.vibrate(ACCEPTED_PATTERN)
                    }
                }
            }
        }

        fun setEnabled(value: Boolean) {
            _enabled.value = value
        }

        companion object {
            private const val TICK_MS = 5_000L
            private val ACCEPTED_PATTERN = VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE)

            // 3 quick pulses: off, on, off, on, off, on.
            private val DISCARDED_PATTERN =
                VibrationEffect.createWaveform(longArrayOf(0L, 50L, 60L, 50L, 60L, 50L), -1)
        }
    }
