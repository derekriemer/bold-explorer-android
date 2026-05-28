package com.boldexplorer.ui.settings

import androidx.lifecycle.ViewModel
import com.boldexplorer.shared.settings.AppSettings
import com.boldexplorer.shared.settings.BearingDisplayMode
import com.boldexplorer.shared.settings.CompassMode
import com.boldexplorer.shared.settings.Units
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

// In-memory settings for Phase 5. Phase 6 wires this to DataStore.
@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun setUnits(units: Units) {
        _settings.value = _settings.value.copy(units = units)
    }

    fun setBearingMode(mode: BearingDisplayMode) {
        _settings.value = _settings.value.copy(bearingDisplayMode = mode)
    }

    fun setAudioCues(enabled: Boolean) {
        _settings.value = _settings.value.copy(audioCuesEnabled = enabled)
    }

    fun setCompassMode(mode: CompassMode) {
        _settings.value = _settings.value.copy(compassMode = mode)
    }
}
