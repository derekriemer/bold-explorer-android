package com.boldexplorer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.shared.repository.SettingsRepository
import com.boldexplorer.shared.settings.AppSettings
import com.boldexplorer.shared.settings.BearingDisplayMode
import com.boldexplorer.shared.settings.CompassMode
import com.boldexplorer.shared.settings.Units
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepo.observeSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), AppSettings())

    fun setUnits(units: Units) = save { it.copy(units = units) }
    fun setBearingMode(mode: BearingDisplayMode) = save { it.copy(bearingDisplayMode = mode) }
    fun setAudioCues(enabled: Boolean) = save { it.copy(audioCuesEnabled = enabled) }
    fun setDuckAudio(enabled: Boolean) = save { it.copy(duckAudioEnabled = enabled) }
    fun setCompassMode(mode: CompassMode) = save { it.copy(compassMode = mode) }

    private fun save(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepo.save(transform(settings.value))
        }
    }
}
