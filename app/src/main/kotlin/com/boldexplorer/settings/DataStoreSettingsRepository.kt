package com.boldexplorer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.boldexplorer.shared.repository.SettingsRepository
import com.boldexplorer.shared.settings.AppSettings
import com.boldexplorer.shared.settings.AudioCuesPrefSpec
import com.boldexplorer.shared.settings.BearingDisplayMode
import com.boldexplorer.shared.settings.BearingDisplayPrefSpec
import com.boldexplorer.shared.settings.CompassMode
import com.boldexplorer.shared.settings.CompassPrefSpec
import com.boldexplorer.shared.settings.Units
import com.boldexplorer.shared.settings.UnitsPrefSpec
import com.boldexplorer.shared.settings.migrateStoredValue
import com.boldexplorer.shared.settings.serializeVersioned
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "bold_explorer_settings")

private val UNITS_KEY = stringPreferencesKey(UnitsPrefSpec.key)
private val COMPASS_KEY = stringPreferencesKey(CompassPrefSpec.key)
private val BEARING_KEY = stringPreferencesKey(BearingDisplayPrefSpec.key)
private val AUDIO_KEY = stringPreferencesKey(AudioCuesPrefSpec.key)

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    override fun observeSettings(): Flow<AppSettings> =
        context.settingsDataStore.data.map { prefs -> prefsToSettings(prefs) }

    override suspend fun load(): AppSettings =
        prefsToSettings(context.settingsDataStore.data.first())

    private fun prefsToSettings(prefs: Preferences): AppSettings {
        val units = migrateStoredValue(UnitsPrefSpec, prefs[UNITS_KEY])
        val compass = migrateStoredValue(CompassPrefSpec, prefs[COMPASS_KEY])
        val bearing = migrateStoredValue(BearingDisplayPrefSpec, prefs[BEARING_KEY])
        val audio = migrateStoredValue(AudioCuesPrefSpec, prefs[AUDIO_KEY])
        return AppSettings(
            units = if (units == "metric") Units.METRIC else Units.IMPERIAL,
            compassMode = if (compass == "true") CompassMode.TRUE else CompassMode.MAGNETIC,
            bearingDisplayMode = when (bearing) {
                "clock" -> BearingDisplayMode.CLOCK
                "true" -> BearingDisplayMode.TRUE_NORTH
                else -> BearingDisplayMode.RELATIVE
            },
            audioCuesEnabled = audio,
        )
    }

    override suspend fun save(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[UNITS_KEY] = serializeVersioned(
                UnitsPrefSpec.currentVersion,
                if (settings.units == Units.METRIC) "metric" else "imperial",
            )
            prefs[COMPASS_KEY] = serializeVersioned(
                CompassPrefSpec.currentVersion,
                if (settings.compassMode == CompassMode.TRUE) "true" else "magnetic",
            )
            prefs[BEARING_KEY] = serializeVersioned(
                BearingDisplayPrefSpec.currentVersion,
                when (settings.bearingDisplayMode) {
                    BearingDisplayMode.CLOCK -> "clock"
                    BearingDisplayMode.TRUE_NORTH -> "true"
                    else -> "relative"
                },
            )
            prefs[AUDIO_KEY] = serializeVersioned(
                AudioCuesPrefSpec.currentVersion,
                settings.audioCuesEnabled,
            )
        }
    }
}
