package com.boldexplorer.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MultiSelectItemDialog(
    title: String,
    entries: List<Pair<Long, String>>,
    onConfirm: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
    emptyMessage: String,
) {
    var selected by remember { mutableStateOf(emptySet<Long>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (entries.isEmpty()) {
                Text(emptyMessage)
            } else {
                Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    entries.forEach { (id, name) ->
                        val checked = id in selected
                        LabeledCheckboxRow(
                            label = name,
                            checked = checked,
                            onCheckedChange = { on -> selected = if (on) selected + id else selected - id },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected) },
                enabled = selected.isNotEmpty(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
