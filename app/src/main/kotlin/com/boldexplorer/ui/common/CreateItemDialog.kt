package com.boldexplorer.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/**
 * Single naming dialog used by every "create" entry point (waypoint, trail, collection).
 *
 * It is a plain focused form, NOT a TalkBack action target: the optional speech-to-text affordance
 * is a visible "Speak" button (hidden when [SpeechRecognizer.isRecognitionAvailable] is false), not
 * a custom accessibility action. Custom actions for "speak the name" belong on the entry point (the
 * FAB / list item that opens this dialog) — see [launchSttOnOpen].
 *
 * @param launchSttOnOpen when true, fires the speech recognizer immediately on first composition so a
 *   "Speak new name" entry-point action lands the user directly in voice input with the result
 *   pre-filled.
 */
@Composable
fun CreateItemDialog(
    title: String,
    confirmLabel: String = "Create",
    initialName: String = "",
    launchSttOnOpen: Boolean = false,
    onConfirm: (name: String, tentative: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var tentative by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val focusRequester = remember { FocusRequester() }

    val stt =
        rememberSttLauncher(prompt = title) { spoken ->
            name = spoken
            error = null
        }

    LaunchedEffect(Unit) {
        if (launchSttOnOpen && stt.available) {
            stt.launch()
        } else {
            focusRequester.requestFocus()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            error = null
                        },
                        label = { Text("Name") },
                        singleLine = true,
                        isError = error != null,
                        modifier =
                            Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                    )
                    SpeakButton(controller = stt, modifier = Modifier.padding(start = 4.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = tentative, onCheckedChange = { tentative = it })
                    Text(
                        "Mark as tentative",
                        modifier =
                            Modifier
                                .padding(start = 4.dp)
                                .clearAndSetSemantics {
                                    contentDescription =
                                        if (tentative) {
                                            "Mark as tentative, checked"
                                        } else {
                                            "Mark as tentative, not checked"
                                        }
                                },
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) {
                    error = "Name required"
                } else {
                    onConfirm(trimmed, tentative)
                }
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
