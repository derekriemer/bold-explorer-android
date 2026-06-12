package com.boldexplorer.audio

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether the app UI is currently in the foreground (at least one activity started).
 * Used to decide whether TTS should speak navigation announcements; when the app is visible
 * the TalkBack live region handles readout instead.
 */
@Singleton
class AppForegroundState
    @Inject
    constructor() {
        private val _isInForeground = MutableStateFlow(false)
        val isInForeground: StateFlow<Boolean> = _isInForeground.asStateFlow()

        init {
            Handler(Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.addObserver(
                    object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            _isInForeground.value = true
                        }

                        override fun onStop(owner: LifecycleOwner) {
                            _isInForeground.value = false
                        }
                    },
                )
            }
        }
    }
