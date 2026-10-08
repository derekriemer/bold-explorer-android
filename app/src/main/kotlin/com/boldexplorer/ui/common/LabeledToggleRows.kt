package com.boldexplorer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The actionable row owns the label; toggleable supplies native role, state and click action. */
@Composable
fun LabeledSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier =
            modifier
                // A scrolling HUD can offer infinite width; bound it before using a weighted label.
                .width(IntrinsicSize.Max)
                .heightIn(min = 48.dp)
                .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
                .semantics {
                    // a11y: visual child semantics are cleared; the native switch node owns its label.
                    contentDescription = if (supportingText == null) label else "$label. $supportingText"
                },
    ) {
        // Visual children are represented by the row's current label and native toggle state.
        Column(modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {}) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            supportingText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** A single focus stop for the item name, native checked state and selection action. */
@Composable
fun LabeledCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Checkbox)
                .semantics {
                    // a11y: visual child semantics are cleared; the native checkbox node owns its label.
                    contentDescription = label
                },
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
        Text(label, modifier = Modifier.padding(start = 8.dp).clearAndSetSemantics {})
    }
}
