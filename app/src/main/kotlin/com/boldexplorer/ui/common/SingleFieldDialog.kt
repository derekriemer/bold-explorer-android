package com.boldexplorer.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Single-field rename/edit dialog with a "Speak" mic affordance, shared across item types. */
@Composable
fun SingleFieldDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val stt =
        rememberSttLauncher(prompt = title) {
            value = it
            error = null
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(label) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    SpeakButton(controller = stt)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (value.isBlank()) {
                    error = "Required"
                } else {
                    onConfirm(value.trim())
                }
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
