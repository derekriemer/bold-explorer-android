package com.boldexplorer.audio

import com.boldexplorer.shared.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns background spoken guidance. UI live regions handle foreground readout;
 * this only speaks when the app is backgrounded and spoken guidance is enabled.
 */
@Singleton
class SpokenGuidancePlayer
    @Inject
    constructor(
        private val ttsEngine: TtsEngine,
        private val settingsRepo: SettingsRepository,
        private val appForegroundState: AppForegroundState,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Returns true if TTS will actually play (app is backgrounded and spoken guidance is enabled). */
        fun speak(text: String): Boolean {
            val willSpeak = !appForegroundState.isInForeground.value
            scope.launch {
                if (!willSpeak) return@launch
                if (!settingsRepo.load().spokenGuidanceEnabled) return@launch
                ttsEngine.speak(text)
            }
            return willSpeak
        }
    }
