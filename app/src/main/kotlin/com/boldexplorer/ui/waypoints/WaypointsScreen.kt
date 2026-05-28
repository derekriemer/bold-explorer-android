package com.boldexplorer.ui.waypoints

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
fun WaypointsScreen(
    paddingValues: PaddingValues,
    viewModel: WaypointsViewModel = hiltViewModel(),
) {
    val waypoints by viewModel.waypoints.collectAsStateWithLifecycle()
    val trails by viewModel.trails.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Waypoint?>(null) }
    var deleteTarget by remember { mutableStateOf<Waypoint?>(null) }
    var attachTarget by remember { mutableStateOf<Waypoint?>(null) }
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
        // Header row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Waypoints", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.semantics { contentDescription = "Add waypoint" },
            ) { Text("Add") }
        }

        // Toast
        toastMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // Search
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setQuery(it) },
            label = { Text("Search waypoints") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = "Search waypoints" },
        )

        Spacer(Modifier.height(8.dp))

        if (waypoints.isEmpty()) {
            Text(
                "No waypoints yet",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn {
                items(waypoints, key = { it.id }) { wp ->
                    WaypointItem(
                        waypoint = wp,
                        expanded = expandedId == wp.id,
                        onToggle = { expandedId = if (expandedId == wp.id) null else wp.id },
                        onEdit = { editTarget = wp },
                        onDelete = { deleteTarget = wp },
                        onAttach = { attachTarget = wp },
                    )
                }
            }
        }
    }

    // Add dialog
    if (showAddDialog) {
        WaypointEditDialog(
            title = "Add Waypoint",
            confirmLabel = "Add",
            initial = null,
            onConfirm = { name, lat, lon, elev ->
                viewModel.create(name, lat, lon, elev)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    // Edit dialog
    editTarget?.let { wp ->
        WaypointEditDialog(
            title = "Edit Waypoint",
            confirmLabel = "Save",
            initial = wp,
            onConfirm = { name, lat, lon, elev ->
                viewModel.update(wp.id, name, lat, lon, elev)
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }

    // Delete confirm dialog
    deleteTarget?.let { wp ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Waypoint?") },
            text = { Text("Delete \"${wp.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.delete(wp.id); deleteTarget = null },
                    modifier = Modifier.semantics { contentDescription = "Confirm delete waypoint ${wp.name}" },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    // Attach to trail dialog
    attachTarget?.let { wp ->
        AttachToTrailDialog(
            waypointName = wp.name,
            trails = trails,
            onConfirm = { trailId ->
                viewModel.attach(wp.id, trailId)
                attachTarget = null
            },
            onDismiss = { attachTarget = null },
        )
    }
}

@Composable
private fun WaypointItem(
    waypoint: Waypoint,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAttach: () -> Unit,
) {
    Card(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = "${waypoint.name}, ${if (expanded) "expanded" else "collapsed"}" },
        elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 4.dp else 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(waypoint.name, style = MaterialTheme.typography.titleMedium)

            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text("Lat: ${"%.5f".format(waypoint.lat)}")
                Text("Lon: ${"%.5f".format(waypoint.lon)}")
                waypoint.elevM?.let { Text("Elevation: ${it.toInt()} m") }
                waypoint.description?.let { Text("Note: $it") }

                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onEdit,
                        modifier = Modifier.semantics { contentDescription = "Edit waypoint ${waypoint.name}" },
                    ) { Text("Edit") }
                    TextButton(
                        onClick = onAttach,
                        modifier = Modifier.semantics { contentDescription = "Attach ${waypoint.name} to trail" },
                    ) { Text("Attach to trail") }
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.semantics { contentDescription = "Delete waypoint ${waypoint.name}" },
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun WaypointEditDialog(
    title: String,
    confirmLabel: String,
    initial: Waypoint?,
    onConfirm: (name: String, lat: Double, lon: Double, elevM: Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var lat by remember { mutableStateOf(initial?.lat?.toString() ?: "") }
    var lon by remember { mutableStateOf(initial?.lon?.toString() ?: "") }
    var elev by remember { mutableStateOf(initial?.elevM?.toString() ?: "") }
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
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Waypoint name" },
                )
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it },
                    label = { Text("Latitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Latitude" },
                )
                OutlinedTextField(
                    value = lon,
                    onValueChange = { lon = it },
                    label = { Text("Longitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Longitude" },
                )
                OutlinedTextField(
                    value = elev,
                    onValueChange = { elev = it },
                    label = { Text("Elevation m (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Elevation in meters, optional" },
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
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AttachToTrailDialog(
    waypointName: String,
    trails: List<Trail>,
    onConfirm: (trailId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    if (trails.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Attach to Trail") },
            text = { Text("No trails exist yet. Create a trail first.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach $waypointName to Trail") },
        text = {
            Column {
                trails.forEach { trail ->
                    TextButton(
                        onClick = { onConfirm(trail.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Attach to trail ${trail.name}" },
                    ) { Text(trail.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
