package com.boldexplorer.ui.collections

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boldexplorer.ui.common.CollectionGroupedMultiSelectDialog
import com.boldexplorer.ui.common.CreateItemDialog
import com.boldexplorer.ui.common.SingleFieldDialog
import com.boldexplorer.ui.common.ToastMessage
import com.boldexplorer.ui.common.useToast
import com.boldexplorer.shared.model.Collection as ExplorerCollection

@Composable
fun CollectionsScreen(
    paddingValues: PaddingValues,
    viewModel: CollectionsViewModel = hiltViewModel(),
) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val contents by viewModel.contents.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { viewModel.importGpx(it, it.lastPathSegment?.removeSuffix(".gpx") ?: "Imported Collection") }
        }

    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ExplorerCollection?>(null) }
    var deleteTarget by remember { mutableStateOf<ExplorerCollection?>(null) }
    var addWpToCollection by remember { mutableStateOf<Long?>(null) }
    var addTrailToCollection by remember { mutableStateOf<Long?>(null) }
    var importTargetCollection by remember { mutableStateOf<Long?>(null) }
    val importIntoLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val collId = importTargetCollection
            importTargetCollection = null
            if (uri != null && collId != null) viewModel.importGpxIntoCollection(collId, uri)
        }
    val toastMessage =
        useToast(toast, viewModel::clearToast)
            ?: useToast(exportStatus, viewModel::clearExportStatus, durationMs = 3000L)

    Column(modifier = Modifier.padding(paddingValues)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Collections", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { importLauncher.launch("*/*") },
                // a11y: names the file format and outcome, which "Import" alone doesn't.
                modifier = Modifier.semantics { contentDescription = "Import GPX file as collection" },
            ) { Text("Import") }
            TextButton(
                onClick = { showAddDialog = true },
                // a11y: "Add" alone doesn't say what's being added.
                modifier = Modifier.semantics { contentDescription = "Add collection" },
            ) { Text("Add") }
        }

        ToastMessage(toastMessage)

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setQuery(it) },
            label = { Text("Search collections") },
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
        )

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
                        onRename = { renameTarget = coll },
                        onDelete = { deleteTarget = coll },
                        onExport = { viewModel.exportCollection(coll.id) },
                        onAddWaypoints = { addWpToCollection = coll.id },
                        onAddTrails = { addTrailToCollection = coll.id },
                        onImport = {
                            importTargetCollection = coll.id
                            importIntoLauncher.launch("*/*")
                        },
                        onRemoveWaypoint = { wpId -> viewModel.removeWaypoint(coll.id, wpId) },
                        onRemoveTrail = { trailId -> viewModel.removeTrail(coll.id, trailId) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        CreateItemDialog(
            title = "New Collection",
            confirmLabel = "Create",
            hasDescription = true,
            hasTentative = false,
            onConfirm = { name, desc, _ ->
                viewModel.create(name, desc)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    renameTarget?.let { coll ->
        SingleFieldDialog(
            title = "Rename Collection",
            confirmLabel = "Rename",
            initial = coll.name,
            label = "Name",
            onConfirm = { name ->
                viewModel.rename(coll.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { coll ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Collection?") },
            text = { Text("Delete \"${coll.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(coll.id)
                        deleteTarget = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    addWpToCollection?.let { collId ->
        val existing = contents[collId]?.waypoints?.map { it.id }?.toSet() ?: emptySet()
        val groups = collections.filter { it.id != collId }.map { it.id to it.name }
        CollectionGroupedMultiSelectDialog(
            title = "Add Waypoints",
            itemNoun = "waypoint",
            groups = groups,
            itemsForGroup = { gid ->
                contents[gid]?.waypoints?.filter { it.id !in existing }?.map { it.id to it.name }
            },
            onExpandGroup = { gid -> viewModel.loadContents(gid) },
            onConfirm = { ids ->
                viewModel.addWaypoints(collId, ids)
                addWpToCollection = null
            },
            onDismiss = { addWpToCollection = null },
            emptyMessage = "No waypoints available",
        )
    }

    addTrailToCollection?.let { collId ->
        val existing = contents[collId]?.trails?.map { it.id }?.toSet() ?: emptySet()
        val groups = collections.filter { it.id != collId }.map { it.id to it.name }
        CollectionGroupedMultiSelectDialog(
            title = "Add Trails",
            itemNoun = "trail",
            groups = groups,
            itemsForGroup = { gid ->
                contents[gid]?.trails?.filter { it.id !in existing }?.map { it.id to it.name }
            },
            onExpandGroup = { gid -> viewModel.loadContents(gid) },
            onConfirm = { ids ->
                viewModel.addTrails(collId, ids)
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
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onAddWaypoints: () -> Unit,
    onAddTrails: () -> Unit,
    onImport: () -> Unit,
    onRemoveWaypoint: (Long) -> Unit,
    onRemoveTrail: (Long) -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 4.dp else 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val wpCount = contents?.waypoints?.size ?: 0
            val trailCount = contents?.trails?.size ?: 0
            val wpLabel = "$wpCount waypoint" + if (wpCount == 1) "" else "s"
            val trailLabel = "$trailCount trail" + if (trailCount == 1) "" else "s"
            val headerActions =
                listOf(
                    CustomAccessibilityAction("Rename") {
                        onRename()
                        true
                    },
                    CustomAccessibilityAction("Delete") {
                        onDelete()
                        true
                    },
                    CustomAccessibilityAction("Export GPX") {
                        onExport()
                        true
                    },
                    CustomAccessibilityAction("Add Waypoints") {
                        onAddWaypoints()
                        true
                    },
                    CustomAccessibilityAction("Add Trails") {
                        onAddTrails()
                        true
                    },
                    CustomAccessibilityAction("Import GPX") {
                        onImport()
                        true
                    },
                )

            // Clickable header row — mergeDescendants scoped only to this row.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggle)
                        .semantics(mergeDescendants = true) {
                            // a11y: adds waypoint/trail counts, which aren't visible until expanded.
                            // Expand state via stateDescription so TalkBack localises it; not baked into text.
                            contentDescription = "${collection.name} collection, $wpLabel, $trailLabel"
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                            customActions = headerActions
                        },
            ) {
                Text(collection.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // ── Collection-level actions (visible counterparts to the header custom actions).
                // Single-expand means at most one set of these is ever on screen, so — unlike Trails'
                // multi-expand cards — the visible text alone is unambiguous; no per-name disambiguator.
                Row {
                    TextButton(onClick = onRename) { Text("Rename") }
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onExport) { Text("Export GPX") }
                }

                Row {
                    TextButton(onClick = onAddWaypoints) { Text("Add Waypoints") }
                    TextButton(onClick = onAddTrails) { Text("Add Trails") }
                    TextButton(
                        onClick = onImport,
                        // a11y: names the file format, which "Import" alone doesn't.
                        modifier = Modifier.semantics { contentDescription = "Import GPX file into this collection" },
                    ) { Text("Import") }
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
                                // a11y: names the waypoint — every row in this list has a "Remove" button.
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
                                // a11y: names the trail — every row in this list has a "Remove" button.
                                modifier = Modifier.semantics { contentDescription = "Remove trail ${trail.name}" },
                            ) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}
