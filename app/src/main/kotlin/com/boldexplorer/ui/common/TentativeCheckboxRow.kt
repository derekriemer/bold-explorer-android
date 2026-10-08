package com.boldexplorer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * "Mark as tentative" checkbox + label on a single toggleable accessible node so
 * TalkBack announces the label and native checked state together instead of landing on a bare
 * unlabeled Checkbox.
 */
@Composable
fun TentativeCheckboxRow(
    tentative: Boolean,
    onTentativeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LabeledCheckboxRow(
        label = "Mark as tentative",
        checked = tentative,
        onCheckedChange = onTentativeChange,
        modifier = modifier,
    )
}
