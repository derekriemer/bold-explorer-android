package com.boldexplorer.location

import com.boldexplorer.BuildConfig
import com.boldexplorer.shared.location.DegradationConfig
import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the debug-only GPS degradation state and applies it to the live fix stream.
 *
 * Exists because switchback matching cannot be falsified under good GPS: with an accurate fix the
 * nearest trail arm *is* the correct arm, so the unwindowed scan in #62 picks correctly every time.
 * The failure mode only appears once positional error approaches arm separation, which is not a
 * condition you can wait for — and not one worth discovering by accident on a cliff edge.
 *
 * ## Safety
 *
 * Degradation is **self-limiting**. Every config carries an expiry and stops applying once it
 * passes, and the state is in-memory so it cannot survive a process restart. This is not
 * belt-and-braces: a forgotten toggle would make correct behaviour look like a bug, and a
 * screen-reader user has no glanceable indicator that it is still on. The Debug screen also emits a
 * periodic audible reminder while it is active — see [isDegrading].
 *
 * Hard-gated on [BuildConfig.SHOW_DEBUG_FEATURES]; in release builds [apply] is the identity
 * function and the config cannot be set.
 */
@Singleton
class LocationDegradationController
    @Inject
    constructor() {
        private val _config = MutableStateFlow(DegradationConfig())
        val config: StateFlow<DegradationConfig> = _config.asStateFlow()

        /**
         * Whether degradation is applying *right now*.
         *
         * Note this is a snapshot, not a flow: expiry is time-based, so a flow would need its own
         * ticker. The Debug screen polls it for the reminder cue, which it does anyway.
         */
        val isDegrading: Boolean
            get() = _config.value.isActiveAt(System.currentTimeMillis())

        /**
         * Arms degradation for [durationMs] from now.
         *
         * The duration is mandatory rather than optional — there is deliberately no way to enable
         * this indefinitely.
         */
        fun arm(
            lateralBiasM: Double = 0.0,
            biasBearingDeg: Double = 0.0,
            jitterSigmaM: Double = 0.0,
            accuracyOverrideM: Double? = null,
            durationMs: Long = DEFAULT_DURATION_MS,
        ) {
            if (!BuildConfig.SHOW_DEBUG_FEATURES) return
            _config.value =
                DegradationConfig(
                    lateralBiasM = lateralBiasM,
                    biasBearingDeg = biasBearingDeg,
                    jitterSigmaM = jitterSigmaM,
                    accuracyOverrideM = accuracyOverrideM,
                    expiresAtMs = System.currentTimeMillis() + durationMs,
                )
        }

        /** Disarms immediately. */
        fun disarm() {
            _config.value = DegradationConfig()
        }

        /** Applies the current config to [sample]. Identity when disarmed, expired, or in release. */
        fun apply(sample: LocationSample): LocationSample {
            if (!BuildConfig.SHOW_DEBUG_FEATURES) return sample
            return _config.value.apply(sample, System.currentTimeMillis(), ::nextGaussian)
        }

        /**
         * Box–Muller standard normal.
         *
         * Hand-rolled because the Kotlin common stdlib has no gaussian generator, and pulling in
         * java.util.Random here would tie a debug affordance to the JVM.
         */
        private fun nextGaussian(): Double {
            val u1 = Random.nextDouble().coerceAtLeast(1e-12)
            val u2 = Random.nextDouble()
            return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI_2 * u2)
        }

        companion object {
            /** Degradation expires after this by default; long enough for a walk, short enough to forget safely. */
            const val DEFAULT_DURATION_MS = 30 * 60 * 1000L

            private const val PI_2 = 3.141592653589793
        }
    }
