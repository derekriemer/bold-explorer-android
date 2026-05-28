package com.boldexplorer.ui.collections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boldexplorer.shared.model.Collection as ExplorerCollection
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import kotlinx.coroutines.delay

@Composable
fun CollectionsScreen(
    paddingValues: PaddingValues,
    viewModel: CollectionsViewModel = hiltViewModel(),
) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val contents by viewModel.contents.collectAsStateWithLifecycle()
    val allWaypoints by viewModel.allWaypoints.collectAsStateWithLifecycle()
    val allTrails by viewModel.allTrails.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ExplorerCollection?>(null) }
    var addWpToCollection by remember { mutableStateOf<Long?>(null) }
    var addTrailToCollection by remember { mutableStateOf<Long?>(null) }
    var toastMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toast) {
        if (toast != null) {
            toastMessage = toast
            viewModel.clearToast()
            delay(2000)
            toastMessage = null
        }
    }

    Column(modifier = Modifier.padding(paddingValues)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Collections", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.semantics { contentDescription = "Add collection" },
            ) { Text("Add") }
        }

        toastMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (collections.isEmpty()) {
            Text(
                "No collections yet",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn {
                items(collections, key = { it.id }) { coll ->
                    val collContents = contents[coll.id]
                    val expanded = expandedId == coll.id

                    CollectionItem(
                        collection = coll,
                        contents = collContents,
                        expanded = expanded,
                        onToggle = {
                            if (!expanded) {
                                expandedId = coll.id
                                viewModel.loadContents(coll.id)
                            } else {
                                expandedId = null
                            }
                        },
                        onDelete = { deleteTarget = coll },
                        onAddWaypoints = { addWpToCollection = coll.id },
                        onAddTrails = { addTrailToCollection = coll.id },
                        onRemoveWaypoint = { wpId -> viewModel.removeWaypoint(coll.id, wpId) },
                        onRemoveTrail = { trailId -> viewModel.removeTrail(coll.id, trailId) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddCollectionDialog(
            onConfirm = { name, desc ->
                viewModel.create(name, desc)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    deleteTarget?.let { coll ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Collection?") },
            text = { Text("Delete \"${coll.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.delete(coll.id); deleteTarget = null },
                    modifier = Modifier.semantics { contentDescription = "Confirm delete collection ${coll.name}" },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    addWpToCollection?.let { collId ->
        val existing = contents[collId]?.waypoints?.map { it.id }?.toSet() ?: emptySet()
        val candidates = allWaypoints.filter { it.id !in existing }
        SelectItemDialog(
            title = "Add Waypoints",
            items = candidates.map { it.id to it.name },
            onSelect = { id ->
                viewModel.addWaypoint(collId, id)
                addWpToCollection = null
            },
            onDismiss = { addWpToCollection = null },
            emptyMessage = "No waypoints available",
        )
    }

    addTrailToCollection?.let { collId ->
        val existing = contents[collId]?.trails?.map { it.id }?.toSet() ?: emptySet()
        val candidates = allTrails.filter { it.id !in existing }
        SelectItemDialog(
            title = "Add Trails",
            items = candidates.map { it.id to it.name },
            onSelect = { id ->
                viewModel.addTrail(collId, id)
                addTrailToCollection = null
            },
            onDismiss = { addTrailToCollection = null },
            emptyMessage = "No trails available",
        )
    }
}

@Composable
private fun CollectionItem(
    collection: ExplorerCollection,
    contents: CollectionContents?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onAddWaypoints: () -> Unit,
    onAddTrails: () -> Unit,
    onRemoveWaypoint: (Long) -> Unit,
    onRemoveTrail: (Long) -> Unit,
) {
    Card(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = "${collection.name} collection, ${if (expanded) "expanded" else "collapsed"}" },
        elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 4.dp else 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(collection.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics { contentDescription = "Delete collection ${collection.name}" },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Row {
                    TextButton(
                        onClick = onAddWaypoints,
                        modifier = Modifier.semantics { contentDescription = "Add waypoints to ${collection.name}" },
                    ) { Text("Add Waypoints") }
                    TextButton(
                        onClick = onAddTrails,
                        modifier = Modifier.semantics { contentDescription = "Add trails to ${collection.name}" },
                    ) { Text("Add Trails") }
                }

                Text("Waypoints", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                val wps = contents?.waypoints ?: emptyList()
                if (wps.isEmpty()) {
                    Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    wps.forEach { wp ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(wp.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(
                                onClick = { onRemoveWaypoint(wp.id) },
                                modifier = Modifier.semantics { contentDescription = "Remove waypoint ${wp.name}" },
                            ) { Text("Remove") }
                        }
                    }
                }

                Text("Trails", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                val trails = contents?.trails ?: emptyList()
                if (trails.isEmpty()) {
                    Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    trails.forEach { trail ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(trail.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(
                                onClick = { onRemoveTrail(trail.id) },
                                modifier = Modifier.semantics { contentDescription = "Remove trail ${trail.name}" },
                            ) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddCollectionDialog(
    onConfirm: (name: String, description: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Collection") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Collection name" },
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Collection description, optional" },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) error = "Name required"
                else onConfirm(name.trim(), desc.trim().ifBlank { null })
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SelectItemDialog(
    title: String,
    items: List<Pair<Long, String>>,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
    emptyMessage: String,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (items.isEmpty()) {
                Text(emptyMessage)
            } else {
                Column {
                    items.forEach { (id, name) ->
                        TextButton(
                            onClick = { onSelect(id) },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Select $name" },
                        ) { Text(name) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
