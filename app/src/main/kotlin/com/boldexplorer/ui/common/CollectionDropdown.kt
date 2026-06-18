package com.boldexplorer.ui.common

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import com.boldexplorer.shared.model.Collection as ExplorerCollection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDropdown(
    collections: List<ExplorerCollection>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onCreateNew: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label =
        if (selectedId == null) {
            "Select collection"
        } else {
            collections.firstOrNull { it.id == selectedId }?.name ?: "Select collection"
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Collection") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (collections.isEmpty() && onCreateNew == null) {
                DropdownMenuItem(
                    text = { Text("No collections yet") },
                    onClick = { expanded = false },
                    enabled = false,
                )
            }
            collections.forEach { col ->
                DropdownMenuItem(
                    text = { Text(col.name) },
                    onClick = {
                        onSelect(col.id)
                        expanded = false
                    },
                )
            }
            if (onCreateNew != null) {
                DropdownMenuItem(
                    text = { Text("Create new collection…") },
                    onClick = {
                        expanded = false
                        onCreateNew()
                    },
                )
            }
        }
    }
}
