package com.boldexplorer.location

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.boldexplorer.shared.location.LocationProvider
import com.boldexplorer.shared.model.LocationSample
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.locationPrefs: DataStore<Preferences>
    by preferencesDataStore(name = "location_provider_prefs")

private val USE_GNSS_KEY = booleanPreferencesKey("use_gnss")

/**
 * Routes the app's single [LocationProvider] subscription to either
 * [FusedLocationProviderImpl] ( better cold-start, Wi-Fi/cell fusion) or
 * [GnssLocationProviderImpl] (default; raw GNSS chip; better outdoor accuracy).
 *
 * The choice persists across restarts via DataStore. Toggle via [setUseGnss].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LocationProviderRouter
    @Inject
    constructor(
        val fused: FusedLocationProviderImpl,
        val gnss: GnssLocationProviderImpl,
        private val degradation: LocationDegradationController,
        @ApplicationContext private val context: Context,
    ) : LocationProvider {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val useGnss: StateFlow<Boolean> =
            context.locationPrefs.data
                .map { prefs -> prefs[USE_GNSS_KEY] ?: true }
                .stateIn(scope, SharingStarted.Eagerly, false)

        override val locationFlow: SharedFlow<LocationSample> =
            useGnss
                .flatMapLatest { gnssActive ->
                    if (gnssActive) gnss.locationFlow else fused.locationFlow
                }
                // Debug-only GPS degradation, applied after the accuracy gate so degraded fixes
                // still reach navigation instead of being dropped by the gate they exercise.
                // Identity in release builds.
                .map { degradation.apply(it) }
                .shareIn(scope, SharingStarted.WhileSubscribed(5_000L), replay = 1)

        /** Raw-fix telemetry (issue #23) from whichever provider is currently active. */
        val lastRawFix: StateFlow<RawFixEvent?> =
            useGnss
                .flatMapLatest { gnssActive ->
                    if (gnssActive) gnss.lastRawFix else fused.lastRawFix
                }.stateIn(scope, SharingStarted.Eagerly, null)

        fun setBackgroundMode(enabled: Boolean) {
            fused.setBackgroundMode(enabled)
            gnss.setBackgroundMode(enabled)
        }

        fun setUseGnss(enabled: Boolean) {
            scope.launch {
                context.locationPrefs.edit { prefs -> prefs[USE_GNSS_KEY] = enabled }
            }
        }
    }
