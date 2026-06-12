package com.boldexplorer.ui.waypoints

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.shared.navigation.BearingComputer
import com.boldexplorer.ui.common.MultiSelectItemDialog
import com.boldexplorer.ui.common.ToastMessage
import com.boldexplorer.ui.common.useToast
import com.boldexplorer.shared.model.Collection as ExplorerCollection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointsScreen(
    paddingValues: PaddingValues,
    viewModel: WaypointsViewModel = hiltViewModel(),
) {
    val waypoints by viewModel.waypoints.collectAsStateWithLifecycle()
    val trails by viewModel.trails.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val waypointCollectionIds by viewModel.waypointCollectionIds.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val radiusFilterM by viewModel.radiusFilterM.collectAsStateWithLifecycle()
    val collectionFilter by viewModel.collectionFilter.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Waypoint?>(null) }
    var deleteTarget by remember { mutableStateOf<Waypoint?>(null) }
    var attachTarget by remember { mutableStateOf<Waypoint?>(null) }
    var collectionTarget by remember { mutableStateOf<Waypoint?>(null) }
    val toastMessage = useToast(toast, viewModel::clearToast)

    LaunchedEffect(collectionTarget) {
        collectionTarget?.let { viewModel.loadWaypointCollections(it.id) }
    }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { viewModel.importGpx(it) }
        }

    Column(modifier = Modifier.padding(paddingValues)) {
        // ── Header ────────────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Waypoints", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { importLauncher.launch("*/*") },
                modifier = Modifier.semantics { contentDescription = "Import GPX file" },
            ) { Text("Import") }
            TextButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.semantics { contentDescription = "Add waypoint" },
            ) { Text("Add") }
        }

        ToastMessage(toastMessage)

        // ── Search ────────────────────────────────────────────────────────────────
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setQuery(it) },
            label = { Text("Search waypoints") },
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(8.dp))

        // ── Sort ──────────────────────────────────────────────────────────────────
        SingleChoiceSegmentedButtonRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
        ) {
            SegmentedButton(
                selected = sortMode == WaypointSortMode.DISTANCE,
                onClick = { viewModel.setSortMode(WaypointSortMode.DISTANCE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                modifier = Modifier.semantics { contentDescription = "Sort by distance" },
            ) { Text("Distance") }
            SegmentedButton(
                selected = sortMode == WaypointSortMode.ALPHA,
                onClick = { viewModel.setSortMode(WaypointSortMode.ALPHA) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier = Modifier.semantics { contentDescription = "Sort A to Z" },
            ) { Text("A–Z") }
        }

        Spacer(Modifier.height(8.dp))

        // ── Radius + Collection filters ───────────────────────────────────────────
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadiusDropdown(
                selected = radiusFilterM,
                onSelect = { viewModel.setRadiusFilter(it) },
                modifier = Modifier.weight(1f),
            )
            CollectionDropdown(
                collections = collections,
                selectedId = collectionFilter,
                onSelect = { viewModel.setCollectionFilter(it) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── List ──────────────────────────────────────────────────────────────────
        if (waypoints.isEmpty()) {
            Text(
                if (query.isNotBlank() || radiusFilterM != null || collectionFilter != null) {
                    "No waypoints match your filters"
                } else {
                    "No waypoints yet"
                },
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn {
                items(waypoints, key = { it.waypoint.id }) { item ->
                    WaypointItem(
                        item = item,
                        units = settings.units,
                        expanded = expandedId == item.waypoint.id,
                        onToggle = { expandedId = if (expandedId == item.waypoint.id) null else item.waypoint.id },
                        onEdit = { editTarget = item.waypoint },
                        onDelete = { deleteTarget = item.waypoint },
                        onAttach = { attachTarget = item.waypoint },
                        onAddToCollection = { collectionTarget = item.waypoint },
                    )
                }
            }
        }
    }

    // ── Add dialog ────────────────────────────────────────────────────────────────
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

    // ── Edit dialog ───────────────────────────────────────────────────────────────
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

    // ── Delete confirm ────────────────────────────────────────────────────────────
    deleteTarget?.let { wp ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Waypoint?") },
            text = { Text("Delete \"${wp.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(wp.id)
                        deleteTarget = null
                    },
                    modifier = Modifier.semantics { contentDescription = "Confirm delete waypoint ${wp.name}" },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    // ── Attach to trail ───────────────────────────────────────────────────────────
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

    // ── Add to collection ─────────────────────────────────────────────────────────
    collectionTarget?.let { wp ->
        val candidates = collections.filterNot { it.id in waypointCollectionIds }
        MultiSelectItemDialog(
            title = "Add \"${wp.name}\" to Collections",
            entries = candidates.map { it.id to it.name },
            onConfirm = { ids ->
                viewModel.addToCollections(wp.id, ids)
                collectionTarget = null
            },
            onDismiss = { collectionTarget = null },
            emptyMessage = "This waypoint is already in every collection.",
        )
    }
}

// ── Filter controls ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadiusDropdown(
    selected: Double?,
    onSelect: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = RADIUS_OPTIONS.firstOrNull { it.second == selected }?.first ?: "Any distance"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Radius") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .semantics { contentDescription = "Radius filter: $label" },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RADIUS_OPTIONS.forEach { (name, value) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                    modifier = Modifier.semantics { contentDescription = name },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionDropdown(
    collections: List<ExplorerCollection>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label =
        if (selectedId == null) {
            "All collections"
        } else {
            collections.firstOrNull { it.id == selectedId }?.name ?: "All collections"
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
                    .fillMaxWidth()
                    .semantics { contentDescription = "Collection filter: $label" },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All collections") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
                modifier = Modifier.semantics { contentDescription = "All collections" },
            )
            collections.forEach { col ->
                DropdownMenuItem(
                    text = { Text(col.name) },
                    onClick = {
                        onSelect(col.id)
                        expanded = false
                    },
                    modifier = Modifier.semantics { contentDescription = col.name },
                )
            }
        }
    }
}

// ── Waypoint list item ────────────────────────────────────────────────────────────

@Composable
private fun WaypointItem(
    item: WaypointListItem,
    units: com.boldexplorer.shared.settings.Units,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAttach: () -> Unit,
    onAddToCollection: () -> Unit,
) {
    val waypoint = item.waypoint
    Card(
        onClick = onToggle,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .semantics {
                    val dist = item.distanceM?.let { ", ${BearingComputer.formatDistance(it, units)}" } ?: ""
                    contentDescription = "${waypoint.name}$dist, ${if (expanded) "expanded" else "collapsed"}"
                },
        elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 4.dp else 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(waypoint.name, style = MaterialTheme.typography.titleMedium)
            item.distanceM?.let {
                Text(
                    BearingComputer.formatDistance(it, units),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                        onClick = onAddToCollection,
                        modifier = Modifier.semantics { contentDescription = "Add ${waypoint.name} to collection" },
                    ) { Text("Add to collection") }
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.semantics { contentDescription = "Delete waypoint ${waypoint.name}" },
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

// ── Dialogs ───────────────────────────────────────────────────────────────────────

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
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it },
                    label = { Text("Latitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = lon,
                    onValueChange = { lon = it },
                    label = { Text("Longitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = elev,
                    onValueChange = { elev = it },
                    label = { Text("Elevation m (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                        modifier =
                            Modifier
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
