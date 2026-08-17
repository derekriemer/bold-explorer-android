package com.boldexplorer.ui.trails

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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.model.TrailNamedPoint
import com.boldexplorer.shared.model.Waypoint
import com.boldexplorer.ui.common.CollectionDropdown
import com.boldexplorer.ui.common.CreateItemDialog
import com.boldexplorer.ui.common.MultiSelectItemDialog
import com.boldexplorer.ui.common.SingleFieldDialog
import com.boldexplorer.ui.common.TentativeCheckboxRow
import com.boldexplorer.ui.common.ToastMessage
import com.boldexplorer.ui.common.useToast

/** Distance, in metres, within which a trail endpoint is treated as "here" for follow-from-end. */
private const val END_PROXIMITY_M = 10.0

/**
 * What to call a trail end when navigating to it.
 *
 * A recorded trail ends on a track point, and those are named for the clock — "Track 14:32:05" —
 * which tells a blind user nothing about where they are being sent. Name such an end by the trail it
 * ends. A hand-built route ends on a real waypoint whose name the user chose, so that name is kept.
 */
private fun endLabel(
    waypoint: Waypoint,
    which: String,
    trailName: String,
) = if (waypoint.kind == Waypoint.KIND_WAYPOINT) waypoint.name else "the $which of $trailName"

@Composable
fun TrailsScreen(
    paddingValues: PaddingValues,
    viewModel: TrailsViewModel = hiltViewModel(),
) {
    val trails by viewModel.trails.collectAsStateWithLifecycle()
    val namedWaypoints by viewModel.namedWaypoints.collectAsStateWithLifecycle()
    val trailEnds by viewModel.trailEnds.collectAsStateWithLifecycle()
    val trackPointCounts by viewModel.trackPointCounts.collectAsStateWithLifecycle()
    val trackPoints by viewModel.trackPoints.collectAsStateWithLifecycle()
    val allWaypoints by viewModel.allWaypoints.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val selectedCollectionId by viewModel.selectedCollectionId.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()

    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var trackExpandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showCreateCollection by remember { mutableStateOf(false) }
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
                onClick = { showAddDialog = true },
                // a11y: "Add" alone doesn't say what's being added.
                modifier = Modifier.semantics { contentDescription = "Add trail" },
            ) { Text("Add") }
        }

        ToastMessage(toastMessage)

        CollectionDropdown(
            collections = collections,
            selectedId = selectedCollectionId,
            onSelect = { viewModel.setCollectionFilter(it) },
            onCreateNew = { showCreateCollection = true },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setQuery(it) },
            label = { Text("Search trails") },
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (trails.isEmpty()) {
            Text(
                when {
                    selectedCollectionId == null -> "Select a collection to view trails"
                    query.isNotBlank() -> "No trails match your search"
                    else -> "No trails in this collection yet"
                },
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn {
                for (trail in trails) {
                    val expanded = trail.id == expandedId
                    val trackExpanded = trail.id == trackExpandedId
                    val wps = namedWaypoints[trail.id] ?: emptyList()
                    // Only vertices can be reordered, so the bound "move down" is checked against
                    // is the number of them, not the length of the list the user sees.
                    val vertexCount = wps.count { !it.isAnnotation }
                    val trackCount = trackPointCounts[trail.id] ?: 0L
                    val tps = trackPoints[trail.id] ?: emptyList()

                    item(key = "trail-${trail.id}") {
                        TrailItem(
                            trail = trail,
                            namedWaypoints = wps,
                            ends = trailEnds[trail.id],
                            trackPointCount = trackCount,
                            expanded = expanded,
                            trackExpanded = trackExpanded,
                            currentLocation = currentLocation,
                            onToggle = {
                                if (expanded) {
                                    expandedId = null
                                    trackExpandedId = null
                                } else {
                                    expandedId = trail.id
                                    trackExpandedId = null
                                    viewModel.loadNamedWaypoints(trail.id)
                                }
                            },
                            onToggleTrackPoints = {
                                if (trackExpanded) {
                                    trackExpandedId = null
                                } else {
                                    trackExpandedId = trail.id
                                    viewModel.loadTrackPoints(trail.id)
                                }
                            },
                            onRename = { renameTarget = trail },
                            onDelete = { deleteTarget = trail },
                            onExport = { viewModel.exportTrail(trail.id) },
                            onAddWaypoint = { addWpToTrail = trail.id },
                            onAttachExisting = { attachWpToTrail = trail.id },
                            onDetach = { wpId -> viewModel.detachWaypoint(trail.id, wpId) },
                            onMoveUp = { idx, wpId -> viewModel.moveUp(trail.id, wpId, idx) },
                            onMoveDown = { idx, wpId -> viewModel.moveDown(trail.id, wpId, idx, vertexCount) },
                            onFollow = { reversed -> viewModel.followTrail(trail.id, reversed) },
                            onRecord = { viewModel.recordTrail(trail.id) },
                            onNavigateToEnd = { isStart, label -> viewModel.navigateToTrailEnd(trail.id, isStart, label) },
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
                                            // a11y: names which trail this live announcement is for.
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
                                            // a11y: adds the list position, which the visible name alone doesn't.
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
            onConfirm = { name, desc, tentative ->
                viewModel.create(name, desc, tentative)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    if (showCreateCollection) {
        CreateItemDialog(
            title = "New Collection",
            confirmLabel = "Create",
            hasTentative = false,
            onConfirm = { name, _, _ ->
                viewModel.createCollection(name)
                showCreateCollection = false
            },
            onDismiss = { showCreateCollection = false },
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

    // Attach existing waypoint(s) to trail
    attachWpToTrail?.let { trailId ->
        val existing = namedWaypoints[trailId]?.map { it.waypoint.id }?.toSet() ?: emptySet()
        val candidates = allWaypoints.filter { it.id !in existing }
        MultiSelectItemDialog(
            title = "Attach Existing Waypoints",
            entries = candidates.map { it.id to it.name },
            onConfirm = { ids ->
                viewModel.attachExisting(trailId, ids)
                attachWpToTrail = null
            },
            onDismiss = { attachWpToTrail = null },
            emptyMessage = "No unattached waypoints available",
        )
    }
}

@Composable
private fun TrailItem(
    trail: Trail,
    namedWaypoints: List<TrailNamedPoint>,
    ends: TrailEnds?,
    trackPointCount: Long,
    expanded: Boolean,
    trackExpanded: Boolean,
    currentLocation: LatLng?,
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
    onFollow: (reversed: Boolean) -> Unit,
    onRecord: () -> Unit,
    onNavigateToEnd: (isStart: Boolean, label: String) -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 4.dp else 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Collapsed = a single focus stop: navigation, rename, and delete all live as TalkBack
            // custom actions (and as visible buttons once expanded). Within END_PROXIMITY_M of an
            // endpoint we offer "Follow from this end"; otherwise we offer explicit start/end navigation.
            //
            // The ends are the trail's geometry, never the named-waypoint list. A recorded trail's
            // geometry is track points, so it has no named vertices at all and the list is entirely
            // annotations — things *beside* the path. Reading them as ends would offer "Follow from
            // this end" while standing next to a bench halfway along and pick the travel direction
            // from it, which is the one guess this app must never make on a blind walker's behalf;
            // reading only the list's vertices would instead leave a recorded trail with no Follow
            // at all. Both are answered by asking the geometry, which every trail has.
            val vertices = namedWaypoints.filterNot { it.isAnnotation }.map { it.waypoint }
            val start = ends?.start
            val end = ends?.end
            val distStart = currentLocation?.let { loc -> start?.let { haversineDistanceMeters(loc, LatLng(it.lat, it.lon)) } }
            val distEnd = currentLocation?.let { loc -> end?.let { haversineDistanceMeters(loc, LatLng(it.lat, it.lon)) } }
            val nearStart = distStart != null && distStart <= END_PROXIMITY_M
            val nearEnd = distEnd != null && distEnd <= END_PROXIMITY_M
            val headerActions =
                buildList {
                    if (start != null) {
                        add(
                            CustomAccessibilityAction("Follow") {
                                onFollow(false)
                                true
                            },
                        )
                        if (start.id != end?.id) {
                            add(
                                CustomAccessibilityAction("Follow in reverse") {
                                    onFollow(true)
                                    true
                                },
                            )
                        }
                        // Within range of an end → resume following from the nearest end; the nearer end
                        // wins when both are in range. Otherwise expose explicit point-navigation to either end.
                        if (nearStart || nearEnd) {
                            val reversed = nearEnd && (!nearStart || (distEnd!! < distStart!!))
                            add(
                                CustomAccessibilityAction("Follow from this end") {
                                    onFollow(reversed)
                                    true
                                },
                            )
                        } else {
                            add(
                                CustomAccessibilityAction("Navigate to start") {
                                    onNavigateToEnd(true, endLabel(start, "start", trail.name))
                                    true
                                },
                            )
                            if (end != null && end.id != start.id) {
                                add(
                                    CustomAccessibilityAction("Navigate to end") {
                                        onNavigateToEnd(false, endLabel(end, "end", trail.name))
                                        true
                                    },
                                )
                            }
                        }
                    }
                    add(
                        CustomAccessibilityAction("Start recording") {
                            onRecord()
                            true
                        },
                    )
                    add(
                        CustomAccessibilityAction("Rename") {
                            onRename()
                            true
                        },
                    )
                    add(
                        CustomAccessibilityAction("Delete") {
                            onDelete()
                            true
                        },
                    )
                }
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
                            // a11y: adds waypoint/track-point counts, which aren't visible until expanded.
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

                // ── Trail-level actions (visible counterparts to the header custom actions).
                // Single-expand means at most one card's buttons are ever on screen, so the visible
                // text alone is unambiguous — no per-name disambiguator needed (see Collections/Waypoints).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (start != null) {
                        TextButton(onClick = { onFollow(false) }) { Text("Follow") }
                        if (start.id != end?.id) {
                            TextButton(onClick = { onFollow(true) }) { Text("Reverse") }
                        }
                    }
                    TextButton(onClick = onRecord) { Text("Record") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRename) { Text("Rename") }
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
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
                        // a11y: "Add" alone doesn't say what's being added.
                        modifier = Modifier.semantics { contentDescription = "Add new waypoint" },
                    ) { Text("Add") }
                    TextButton(onClick = onAttachExisting) { Text("Attach Existing") }
                    TextButton(onClick = onExport) { Text("Export GPX") }
                }

                if (namedWaypoints.isEmpty()) {
                    Text(
                        "No waypoints",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    namedWaypoints.forEachIndexed { idx, point ->
                        val wp = point.waypoint
                        // Name-only row = a single focus stop. Reorder/detach are TalkBack custom
                        // actions, gated so the first/last items omit the impossible move.
                        //
                        // Reorder is a geometry operation and an annotation has no geometry to
                        // reorder: `setPosition` finds no vertex row and returns having done
                        // nothing, so offering the action would report a success that never
                        // happened — worse than not offering it, because there is no other way to
                        // tell. Its position along the trail is derived from where it actually is;
                        // to move it, move the waypoint. Detach still applies to both kinds.
                        val vertexIdx = if (point.isAnnotation) -1 else vertices.indexOfFirst { it.id == wp.id }
                        val wpActions =
                            buildList {
                                if (vertexIdx > 0) {
                                    add(
                                        CustomAccessibilityAction("Move up") {
                                            onMoveUp(vertexIdx, wp.id)
                                            true
                                        },
                                    )
                                }
                                if (vertexIdx >= 0 && vertexIdx < vertices.size - 1) {
                                    add(
                                        CustomAccessibilityAction("Move down") {
                                            onMoveDown(vertexIdx, wp.id)
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
                                        customActions = wpActions
                                    },
                        )
                    }
                }

                // ── Track points section ──────────────────────────────────────
                if (trackPointCount > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    TextButton(onClick = onToggleTrackPoints) {
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

@Composable
private fun NameDescDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (name: String, description: String?, tentative: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var tentative by remember { mutableStateOf(false) }
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
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TentativeCheckboxRow(
                    tentative = tentative,
                    onTentativeChange = { tentative = it },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) {
                    error = "Name required"
                } else {
                    onConfirm(name.trim(), desc.trim().ifBlank { null }, tentative)
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

