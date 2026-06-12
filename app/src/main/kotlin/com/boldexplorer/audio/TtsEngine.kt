package com.boldexplorer.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Android [TextToSpeech] with a coroutine-friendly API.
 *
 * Initialization is asynchronous; [speak] suspends until TTS is ready (or bails
 * silently if it failed). All utterances are queued with [TextToSpeech.QUEUE_ADD]
 * so navigation announcements never cut each other off.
 */
@Singleton
class TtsEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private var tts: TextToSpeech? = null
        private val ready = CompletableDeferred<Boolean>()

        init {
            tts =
                TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.getDefault()
                        ready.complete(true)
                    } else {
                        ready.complete(false)
                    }
                }
        }

        /**
         * Queues [text] for speech output.
         * Suspends until TTS engine is initialized; returns immediately if init failed.
         */
        suspend fun speak(text: String) {
            if (!ready.await()) return
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text)
        }

        fun shutdown() {
            tts?.shutdown()
            tts = null
        }
    }
