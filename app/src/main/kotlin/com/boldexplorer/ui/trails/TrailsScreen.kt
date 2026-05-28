package com.boldexplorer.ui.trails

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import kotlinx.coroutines.delay

@Composable
fun TrailsScreen(
    paddingValues: PaddingValues,
    viewModel: TrailsViewModel = hiltViewModel(),
) {
    val trails by viewModel.trails.collectAsStateWithLifecycle()
    val trailWaypoints by viewModel.trailWaypoints.collectAsStateWithLifecycle()
    val allWaypoints by viewModel.allWaypoints.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Trail?>(null) }
    var deleteTarget by remember { mutableStateOf<Trail?>(null) }
    var addWpToTrail by remember { mutableStateOf<Long?>(null) }
    var attachWpToTrail by remember { mutableStateOf<Long?>(null) }
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
            Text("Trails", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.semantics { contentDescription = "Add trail" },
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

        if (trails.isEmpty()) {
            Text(
                "No trails yet",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn {
                items(trails, key = { it.id }) { trail ->
                    val wps = trailWaypoints[trail.id] ?: emptyList()
                    val expanded = expandedId == trail.id

                    TrailItem(
                        trail = trail,
                        waypoints = wps,
                        expanded = expanded,
                        onToggle = {
                            if (!expanded) {
                                expandedId = trail.id
                                viewModel.loadWaypoints(trail.id)
                            } else {
                                expandedId = null
                            }
                        },
                        onRename = { renameTarget = trail },
                        onDelete = { deleteTarget = trail },
                        onAddWaypoint = { addWpToTrail = trail.id },
                        onAttachExisting = { attachWpToTrail = trail.id },
                        onDetach = { wpId -> viewModel.detachWaypoint(trail.id, wpId) },
                        onMoveUp = { idx, wpId -> viewModel.moveUp(trail.id, wpId, idx) },
                        onMoveDown = { idx, wpId -> viewModel.moveDown(trail.id, wpId, idx, wps.size) },
                    )
                }
            }
        }
    }

    // Add trail dialog
    if (showAddDialog) {
        NameDescDialog(
            title = "New Trail",
            confirmLabel = "Create",
            onConfirm = { name, desc ->
                viewModel.create(name, desc)
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
            contentDesc = "Trail name",
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
                    onClick = { viewModel.delete(trail.id); deleteTarget = null },
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
        val existing = trailWaypoints[trailId]?.map { it.id }?.toSet() ?: emptySet()
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
    waypoints: List<Waypoint>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddWaypoint: () -> Unit,
    onAttachExisting: () -> Unit,
    onDetach: (waypointId: Long) -> Unit,
    onMoveUp: (index: Int, waypointId: Long) -> Unit,
    onMoveDown: (index: Int, waypointId: Long) -> Unit,
) {
    Card(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = "${trail.name} trail, ${if (expanded) "expanded" else "collapsed"}" },
        elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 4.dp else 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(trail.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onRename,
                    modifier = Modifier.semantics { contentDescription = "Rename trail ${trail.name}" },
                ) { Text("Rename") }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics { contentDescription = "Delete trail ${trail.name}" },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onAddWaypoint,
                        modifier = Modifier.semantics { contentDescription = "Add new waypoint to trail ${trail.name}" },
                    ) { Text("Add Waypoint") }
                    TextButton(
                        onClick = onAttachExisting,
                        modifier = Modifier.semantics { contentDescription = "Attach existing waypoint to trail ${trail.name}" },
                    ) { Text("Attach Existing") }
                }

                if (waypoints.isEmpty()) {
                    Text("No waypoints", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    waypoints.forEachIndexed { idx, wp ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            Text(
                                "${idx + 1}. ${wp.name}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(
                                onClick = { onMoveUp(idx, wp.id) },
                                enabled = idx > 0,
                                modifier = Modifier.semantics { contentDescription = "Move ${wp.name} up" },
                            ) { Text("Up") }
                            TextButton(
                                onClick = { onMoveDown(idx, wp.id) },
                                enabled = idx < waypoints.size - 1,
                                modifier = Modifier.semantics { contentDescription = "Move ${wp.name} down" },
                            ) { Text("Down") }
                            TextButton(
                                onClick = { onDetach(wp.id) },
                                modifier = Modifier.semantics { contentDescription = "Detach ${wp.name} from trail" },
                            ) { Text("Detach") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDescDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (name: String, description: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

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
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Trail name" },
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Trail description, optional" },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) error = "Name required"
                else onConfirm(name.trim(), desc.trim().ifBlank { null })
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
    contentDesc: String,
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
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = contentDesc },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (value.isBlank()) error = "Required"
                else onConfirm(value.trim())
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
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Waypoint name" })
                OutlinedTextField(value = lat, onValueChange = { lat = it }, label = { Text("Latitude") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Latitude" })
                OutlinedTextField(value = lon, onValueChange = { lon = it }, label = { Text("Longitude") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Longitude" })
                OutlinedTextField(value = elev, onValueChange = { elev = it }, label = { Text("Elevation m (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Elevation, optional" })
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
