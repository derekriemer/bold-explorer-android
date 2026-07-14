package com.boldexplorer.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * Accordion of [groups] (other collections); expanding one lazily reveals checkboxes for that
 * group's own items via [itemsForGroup]. Selection is a single [Set] keyed by item id, so it
 * carries correctly across however many groups get expanded before confirming.
 */
@Composable
fun CollectionGroupedMultiSelectDialog(
    title: String,
    itemNoun: String,
    groups: List<Pair<Long, String>>,
    itemsForGroup: (Long) -> List<Pair<Long, String>>?,
    onExpandGroup: (Long) -> Unit,
    onConfirm: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
    emptyMessage: String,
) {
    var expandedGroupId by remember { mutableStateOf<Long?>(null) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (groups.isEmpty()) {
                Text(emptyMessage)
            } else {
                Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    groups.forEach { (groupId, groupName) ->
                        val groupExpanded = expandedGroupId == groupId
                        val items = if (groupExpanded) itemsForGroup(groupId) else null

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (groupExpanded) {
                                            expandedGroupId = null
                                        } else {
                                            expandedGroupId = groupId
                                            onExpandGroup(groupId)
                                        }
                                    }.semantics(mergeDescendants = true) {
                                        stateDescription = if (groupExpanded) "Expanded" else "Collapsed"
                                        if (items != null) {
                                            // a11y: adds the item count, which isn't visible until expanded.
                                            val n = items.size
                                            contentDescription = "$groupName collection, $n $itemNoun${if (n == 1) "" else "s"}"
                                        }
                                    },
                        ) {
                            Text(
                                groupName,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                            )
                        }

                        if (groupExpanded) {
                            when {
                                items == null -> Text(
                                    "Loading…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                                )
                                items.isEmpty() -> Text(
                                    emptyMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                                )
                                else -> {
                                    items.forEach { (id, name) ->
                                        val checked = id in selected
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 8.dp)
                                                    .toggleable(
                                                        value = checked,
                                                        onValueChange = { on ->
                                                            selected = if (on) selected + id else selected - id
                                                        },
                                                        role = Role.Checkbox,
                                                    ),
                                        ) {
                                            Checkbox(checked = checked, onCheckedChange = null)
                                            Text(name, modifier = Modifier.padding(start = 8.dp))
                                        }
                                    }
                                }
                            }
                        }
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
