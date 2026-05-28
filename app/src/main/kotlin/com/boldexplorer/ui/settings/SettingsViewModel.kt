package com.boldexplorer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boldexplorer.shared.repository.SettingsRepository
import com.boldexplorer.shared.settings.AppSettings
import com.boldexplorer.shared.settings.BearingDisplayMode
import com.boldexplorer.shared.settings.CompassMode
import com.boldexplorer.shared.settings.Units
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        viewModelScope.launch {
            _settings.value = settingsRepo.load()
        }
    }

    fun setUnits(units: Units) = update { it.copy(units = units) }
    fun setBearingMode(mode: BearingDisplayMode) = update { it.copy(bearingDisplayMode = mode) }
    fun setAudioCues(enabled: Boolean) = update { it.copy(audioCuesEnabled = enabled) }
    fun setCompassMode(mode: CompassMode) = update { it.copy(compassMode = mode) }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            _settings.value = transform(_settings.value)
            settingsRepo.save(_settings.value)
        }
    }
}
