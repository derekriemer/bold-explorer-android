package com.boldexplorer.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * "Mark as tentative" checkbox + label, merged into a single toggleable accessible node so
 * TalkBack announces the label and native checked state together instead of landing on a bare
 * unlabeled Checkbox.
 */
@Composable
fun TentativeCheckboxRow(
    tentative: Boolean,
    onTentativeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier.toggleable(
                value = tentative,
                onValueChange = onTentativeChange,
                role = Role.Checkbox,
            ),
    ) {
        Checkbox(checked = tentative, onCheckedChange = null)
        Text("Mark as tentative", modifier = Modifier.padding(start = 4.dp))
    }
}
