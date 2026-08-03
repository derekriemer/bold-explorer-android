package com.boldexplorer.location

import com.boldexplorer.shared.location.LocationProvider
import com.boldexplorer.shared.model.LocationSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FOSS-flavor [LocationProviderRouter]: wraps only [GnssLocationProviderImpl] — this flavor has
 * no Google Play Services dependency compiled in at all (no FusedLocationProviderImpl class
 * exists in this source set; compare src/google/kotlin/.../LocationProviderRouter.kt).
 *
 * Same public API as the google-flavor router, so DebugViewModel, DebugScreen,
 * LocationForegroundService, and di/LocationModule need zero changes: [useGnss] always reports
 * true, [setUseGnss] is a no-op (there is nothing else to switch to).
 */
@Singleton
class LocationProviderRouter
    @Inject
    constructor(
        val gnss: GnssLocationProviderImpl,
    ) : LocationProvider {
        override val locationFlow: SharedFlow<LocationSample> = gnss.locationFlow

        val useGnss: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

        /** Raw-fix telemetry (issue #23) — always from the GNSS provider in this flavor. */
        val lastRawFix: StateFlow<RawFixEvent?> = gnss.lastRawFix

        fun setBackgroundMode(enabled: Boolean) {
            gnss.setBackgroundMode(enabled)
        }

        fun setUseGnss(enabled: Boolean) {
            // No-op: FOSS builds have no Fused provider to switch to.
        }
    }
