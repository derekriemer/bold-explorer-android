package com.boldexplorer.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Label and changing value share one Text node, including when TalkBack reads them again. */
@Composable
fun LabeledValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    customActions: List<CustomAccessibilityAction> = emptyList(),
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val actionTarget =
        if (customActions.isEmpty()) {
            Modifier
        } else {
            Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
        }
    Text(
        text =
            buildAnnotatedString {
                withStyle(SpanStyle(color = labelColor)) { append("$label: ") }
                withStyle(SpanStyle(color = valueColor)) { append(value) }
            },
        style = MaterialTheme.typography.bodyMedium,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .semantics { if (customActions.isNotEmpty()) this.customActions = customActions }
                .then(actionTarget),
    )
}
