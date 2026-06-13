package com.boldexplorer.ui.common

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Reusable speech-to-text affordance shared by every naming/edit dialog.
 *
 * [available] is false when no recognizer is installed; callers hide their mic button in that case.
 * The system [RecognizerIntent.ACTION_RECOGNIZE_SPEECH] flow handles its own microphone permission,
 * so the app does not request RECORD_AUDIO itself.
 */
class SttController(
    val available: Boolean,
    val launch: () -> Unit,
)

@Composable
fun rememberSttLauncher(
    prompt: String,
    onResult: (String) -> Unit,
): SttController {
    val context = LocalContext.current
    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(onResult)
            }
        }
    return SttController(available) {
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            }
        launcher.launch(intent)
    }
}

/** Visible "Speak" button; renders nothing when no recognizer is available. */
@Composable
fun SpeakButton(
    controller: SttController,
    modifier: Modifier = Modifier,
) {
    if (!controller.available) return
    TextButton(
        onClick = controller.launch,
        modifier = modifier.semantics { contentDescription = "Speak name" },
    ) { Text("Speak") }
}
