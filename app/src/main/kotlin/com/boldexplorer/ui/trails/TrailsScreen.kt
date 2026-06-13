package com.boldexplorer.ui.trails

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boldexplorer.shared.model.Collection
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.ui.common.ToastMessage
import com.boldexplorer.ui.common.useToast

@Composable
fun TrailsScreen(
    paddingValues: PaddingValues,
    viewModel: TrailsViewModel = hiltViewModel(),
) {
    val trails by viewModel.trails.collectAsStateWithLifecycle()
    val namedWaypoints by viewModel.namedWaypoints.collectAsStateWithLifecycle()
    val trackPointCounts by viewModel.trackPointCounts.collectAsStateWithLifecycle()
    val trackPoints by viewModel.trackPoints.collectAsStateWithLifecycle()
    val expandedIds by viewModel.expandedTrailIds.collectAsStateWithLifecycle()
    val trackExpandedIds by viewModel.trackExpandedTrailIds.collectAsStateWithLifecycle()
    val allWaypoints by viewModel.allWaypoints.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { viewModel.importGpx(it) }
        }

    var showAddDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Trail?>(null) }
    var deleteTarget by remember { mutableStateOf<Trail?>(null) }
    var addWpToTrail by remember { mutableStateOf<Long?>(null) }
    var attachWpToTrail by remember { mutableStateOf<Long?>(null) }
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
            Text("Trails", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { importLauncher.launch("*/*") },
                modifier = Modifier.semantics { contentDescription = "Import GPX file" },
            ) { Text("Import") }
            TextButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.semantics { contentDescription = "Add trail" },
            ) { Text("Add") }
        }

        ToastMessage(toastMessage)

        if (trails.isEmpty()) {
            Text(
                "No trails yet",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn {
                for (trail in trails) {
                    val expanded = trail.id in expandedIds
                    val trackExpanded = trail.id in trackExpandedIds
                    val wps = namedWaypoints[trail.id] ?: emptyList()
                    val trackCount = trackPointCounts[trail.id] ?: 0L
                    val tps = trackPoints[trail.id] ?: emptyList()

                    item(key = "trail-${trail.id}") {
                        TrailItem(
                            trail = trail,
                            namedWaypoints = wps,
                            trackPointCount = trackCount,
                            expanded = expanded,
                            trackExpanded = trackExpanded,
                            onToggle = { viewModel.toggleExpand(trail.id) },
                            onToggleTrackPoints = { viewModel.toggleTrackExpand(trail.id) },
                            onRename = { renameTarget = trail },
                            onDelete = { deleteTarget = trail },
                            onExport = { viewModel.exportTrail(trail.id) },
                            onAddWaypoint = { addWpToTrail = trail.id },
                            onAttachExisting = { attachWpToTrail = trail.id },
                            onDetach = { wpId -> viewModel.detachWaypoint(trail.id, wpId) },
                            onMoveUp = { idx, wpId -> viewModel.moveUp(trail.id, wpId, idx) },
                            onMoveDown = { idx, wpId -> viewModel.moveDown(trail.id, wpId, idx, wps.size) },
                        )
                    }

                    if (expanded && trackExpanded && tps.isNotEmpty()) {
                        item(key = "trail-${trail.id}-tpheader") {
                            Text(
                                "$trackCount track points:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier =
                                    Modifier
                                        .padding(horizontal = 24.dp, vertical = 2.dp)
                                        .semantics {
                                            liveRegion = LiveRegionMode.Polite
                                            contentDescription = "$trackCount track points for ${trail.name}"
                                        },
                            )
                        }
                        tps.forEachIndexed { idx, tp ->
                            item(key = "tp-${trail.id}-${tp.id}") {
                                Text(
                                    tp.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier =
                                        Modifier
                                            .padding(start = 32.dp, top = 1.dp, end = 16.dp, bottom = 1.dp)
                                            .semantics { contentDescription = "Track point ${idx + 1}: ${tp.name}" },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add trail dialog
    if (showAddDialog) {
        NameDescDialog(
            title = "New Trail",
            confirmLabel = "Create",
            collections = collections,
            onConfirm = { collectionId, name, desc, tentative ->
                viewModel.create(collectionId, name, desc, tentative)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    // Rename dialog
    renameTarget?.let { trail ->
        SingleFieldDialog(
            title = "Rename Trail",
            confirmLabel = "Save",
            initial = trail.name,
            label = "Name",
            onConfirm = { name ->
                viewModel.rename(trail.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    // Delete confirm
    deleteTarget?.let { trail ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Trail?") },
            text = { Text("Delete \"${trail.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(trail.id)
                        deleteTarget = null
                    },
                    modifier = Modifier.semantics { contentDescription = "Confirm delete trail ${trail.name}" },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    // Add new waypoint to trail
    addWpToTrail?.let { trailId ->
        AddWaypointToTrailDialog(
            onConfirm = { name, lat, lon, elev ->
                viewModel.addWaypointToTrail(trailId, name, lat, lon, elev)
                addWpToTrail = null
            },
            onDismiss = { addWpToTrail = null },
        )
    }

    // Attach existing waypoint to trail
    attachWpToTrail?.let { trailId ->
        val existing = namedWaypoints[trailId]?.map { it.id }?.toSet() ?: emptySet()
        val candidates = allWaypoints.filter { it.id !in existing }
        AttachExistingDialog(
            candidates = candidates,
            onConfirm = { wpId ->
                viewModel.attachExisting(trailId, wpId)
                attachWpToTrail = null
            },
            onDismiss = { attachWpToTrail = null },
        )
    }
}

@Composable
private fun TrailItem(
    trail: Trail,
    namedWaypoints: List<Waypoint>,
    trackPointCount: Long,
    expanded: Boolean,
    trackExpanded: Boolean,
    onToggle: () -> Unit,
    onToggleTrackPoints: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onAddWaypoint: () -> Unit,
    onAttachExisting: () -> Unit,
    onDetach: (waypointId: Long) -> Unit,
    onMoveUp: (index: Int, waypointId: Long) -> Unit,
    onMoveDown: (index: Int, waypointId: Long) -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 4.dp else 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Collapsed = a single focus stop: no visible header buttons. Rename/Delete live as
            // TalkBack custom actions (and as visible buttons once expanded).
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
                )
            val wpCount = namedWaypoints.size
            val wpLabel = "$wpCount waypoint" + if (wpCount == 1) "" else "s"
            val tpLabel = if (trackPointCount > 0) ", $trackPointCount track points" else ""
            // Clickable header row — mergeDescendants scoped only to this row.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggle)
                        .semantics(mergeDescendants = true) {
                            // Expand state via stateDescription so TalkBack localises it; not baked into text.
                            contentDescription = "${trail.name} trail, $wpLabel$tpLabel"
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                            customActions = headerActions
                        },
            ) {
                Text(trail.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // ── Trail-level actions (visible counterparts to the header custom actions) ──
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onRename,
                        modifier = Modifier.semantics { contentDescription = "Rename trail ${trail.name}" },
                    ) { Text("Rename") }
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.semantics { contentDescription = "Delete trail ${trail.name}" },
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }

                // ── Named waypoints section ───────────────────────────────────
                Text(
                    "Waypoints",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onAddWaypoint,
                        modifier = Modifier.semantics { contentDescription = "Add new waypoint to trail ${trail.name}" },
                    ) { Text("Add") }
                    TextButton(
                        onClick = onAttachExisting,
                        modifier = Modifier.semantics { contentDescription = "Attach existing waypoint to trail ${trail.name}" },
                    ) { Text("Attach Existing") }
                    TextButton(
                        onClick = onExport,
                        modifier = Modifier.semantics { contentDescription = "Export trail ${trail.name} as GPX" },
                    ) { Text("Export GPX") }
                }

                if (namedWaypoints.isEmpty()) {
                    Text(
                        "No waypoints",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    namedWaypoints.forEachIndexed { idx, wp ->
                        // Name-only row = a single focus stop. Reorder/detach are TalkBack custom
                        // actions, gated so the first/last items omit the impossible move.
                        val wpActions =
                            buildList {
                                if (idx > 0) {
                                    add(
                                        CustomAccessibilityAction("Move up") {
                                            onMoveUp(idx, wp.id)
                                            true
                                        },
                                    )
                                }
                                if (idx < namedWaypoints.size - 1) {
                                    add(
                                        CustomAccessibilityAction("Move down") {
                                            onMoveDown(idx, wp.id)
                                            true
                                        },
                                    )
                                }
                                add(
                                    CustomAccessibilityAction("Detach from trail") {
                                        onDetach(wp.id)
                                        true
                                    },
                                )
                            }
                        Text(
                            "${idx + 1}. ${wp.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .semantics {
                                        contentDescription = "${idx + 1}. ${wp.name}"
                                        customActions = wpActions
                                    },
                        )
                    }
                }

                // ── Track points section ──────────────────────────────────────
                if (trackPointCount > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    TextButton(
                        onClick = onToggleTrackPoints,
                        modifier =
                            Modifier.semantics {
                                contentDescription =
                                    if (trackExpanded) {
                                        "Hide $trackPointCount track points for ${trail.name}"
                                    } else {
                                        "Show $trackPointCount track points for ${trail.name}"
                                    }
                            },
                    ) {
                        Text(
                            if (trackExpanded) {
                                "Hide $trackPointCount track points"
                            } else {
                                "$trackPointCount track points — tap to show"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameDescDialog(
    title: String,
    confirmLabel: String,
    collections: List<Collection>,
    onConfirm: (collectionId: Long, name: String, description: String?, tentative: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var tentative by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedCollectionId by remember(collections) { mutableStateOf(collections.firstOrNull()?.id) }
    var expanded by remember { mutableStateOf(false) }
    val selectedName = collections.firstOrNull { it.id == selectedCollectionId }?.name ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (collections.isEmpty()) {
                    Text("Create a collection first", color = MaterialTheme.colorScheme.error)
                } else {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Collection") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier =
                                Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            collections.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.name) },
                                    onClick = {
                                        selectedCollectionId = c.id
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = tentative, onCheckedChange = { tentative = it })
                    Text(
                        "Mark as tentative",
                        modifier =
                            Modifier.clearAndSetSemantics {
                                contentDescription =
                                    if (tentative) {
                                        "Mark as tentative, checked"
                                    } else {
                                        "Mark as tentative, not checked"
                                    }
                            },
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val collectionId = selectedCollectionId
                if (name.isBlank()) {
                    error = "Name required"
                } else if (collectionId == null) {
                    error = "Select a collection"
                } else {
                    onConfirm(collectionId, name.trim(), desc.trim().ifBlank { null }, tentative)
                }
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SingleFieldDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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

@Composable
private fun AddWaypointToTrailDialog(
    onConfirm: (name: String, lat: Double, lon: Double, elevM: Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var elev by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Waypoint") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = {
                    name = it
                }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = lat,
                    onValueChange = {
                        lat = it
                    },
                    label = {
                        Text("Latitude")
                    },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = lon,
                    onValueChange = {
                        lon = it
                    },
                    label = {
                        Text("Longitude")
                    },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = elev,
                    onValueChange = {
                        elev = it
                    },
                    label = {
                        Text("Elevation m (optional)")
                    },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val latD = lat.toDoubleOrNull()
                val lonD = lon.toDoubleOrNull()
                when {
                    name.isBlank() -> error = "Name required"
                    latD == null || latD !in -90.0..90.0 -> error = "Latitude must be -90 to 90"
                    lonD == null || lonD !in -180.0..180.0 -> error = "Longitude must be -180 to 180"
                    else -> onConfirm(name.trim(), latD, lonD, elev.toDoubleOrNull())
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AttachExistingDialog(
    candidates: List<Waypoint>,
    onConfirm: (waypointId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    if (candidates.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Attach Waypoint") },
            text = { Text("No unattached waypoints available.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach Existing Waypoint") },
        text = {
            Column {
                candidates.forEach { wp ->
                    TextButton(
                        onClick = { onConfirm(wp.id) },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Attach ${wp.name}" },
                    ) { Text(wp.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
