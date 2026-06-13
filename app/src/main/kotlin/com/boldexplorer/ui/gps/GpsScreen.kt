package com.boldexplorer.ui.gps

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.Collection
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.navigation.BearingComputer
import com.boldexplorer.shared.navigation.CollectionExplorerState
import com.boldexplorer.shared.navigation.CollectionPoint
import com.boldexplorer.shared.navigation.CollectionTargeting
import com.boldexplorer.shared.navigation.TrailFollowerState
import com.boldexplorer.shared.settings.AppSettings
import com.boldexplorer.ui.common.CreateItemDialog

@Composable
fun GpsRoute(
    paddingValues: PaddingValues,
    viewModel: GpsViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingCreate by viewModel.pendingCreate.collectAsStateWithLifecycle()

    when (pendingCreate) {
        is PendingCreate.Waypoint -> {
            CreateItemDialog(
                title = "Name waypoint",
                confirmLabel = "Mark",
                onConfirm = { name, tentative -> viewModel.onWaypointNamed(name, tentative) },
                onDismiss = { viewModel.cancelPendingCreate() },
            )
        }

        PendingCreate.Trail -> {
            CreateItemDialog(
                title = "Name trail",
                confirmLabel = "Create",
                onConfirm = { name, tentative -> viewModel.onTrailNamed(name, tentative) },
                onDismiss = { viewModel.cancelPendingCreate() },
            )
        }

        null -> {
            Unit
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            if (
                grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                viewModel.onAction(GpsAction.StartNavigation)
            }
        }

    GpsScreen(
        paddingValues = paddingValues,
        state = state,
        onAction = { action ->
            if (action == GpsAction.StartNavigation && !viewModel.hasForegroundLocationPermission()) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            } else {
                viewModel.onAction(action)
            }
        },
    )
}

@Composable
fun GpsScreen(
    paddingValues: PaddingValues,
    state: GpsUiState,
    onAction: (GpsAction) -> Unit,
) {
    var showAlignmentDialog by remember { mutableStateOf(false) }
    var alignmentInput by remember { mutableStateOf("") }
    var alignmentInputError by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.padding(paddingValues),
        floatingActionButton = {
            val canMark = state.selectedCollectionId != null
            FloatingActionButton(
                onClick = { if (canMark) onAction(GpsAction.MarkWaypoint) },
                containerColor =
                    if (canMark && state.location != null) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                modifier =
                    Modifier.semantics {
                        contentDescription =
                            when {
                                !canMark -> "Mark waypoint — select a collection first"
                                state.location == null -> "Mark waypoint — no GPS fix yet"
                                else -> "Mark waypoint at current location"
                            }
                    },
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { innerPadding ->
        // Pinned layout: telemetry + collection selector stay fixed at the top, only the target
        // list scrolls, and the contextual actions + navigation button stay pinned at the bottom.
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
        ) {
            Text(
                "GPS",
                style = MaterialTheme.typography.headlineSmall,
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { heading() },
            )

            // ── Pinned telemetry (never scrolls off) ────────────────────────────────
            TelemetryCard(state = state, onAction = onAction)

            // ── Announcement display (visual only — live region is in NavGraph) ──────
            Text(
                text = state.announcement,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )

            // ── Pinned collection selector ──────────────────────────────────────────
            CollectionSelector(
                collections = state.collections,
                selectedId = state.selectedCollectionId,
                onAction = onAction,
            )

            val active = state.collectionExplorerState as? CollectionExplorerState.Active

            // ── Collection controls (explore mode + add) ────────────────────────────
            if (active != null) {
                CollectionControls(active = active, onAction = onAction)
            }

            // ── Scrolling target list (the only scrollable region) ──────────────────
            CollectionTargetList(
                active = active,
                selectedCollectionId = state.selectedCollectionId,
                settings = state.settings,
                location = state.location,
                onAction = onAction,
                modifier = Modifier.weight(1f),
            )

            // ── Pinned contextual trail actions ─────────────────────────────────────
            ContextualTrailActions(state = state, active = active, onAction = onAction)

            // ── Pinned alignment row ────────────────────────────────────────────────
            AlignmentRow(
                state = state,
                onAction = onAction,
                onEditBearing = {
                    alignmentInput = ""
                    alignmentInputError = false
                    showAlignmentDialog = true
                },
            )

            Spacer(Modifier.height(8.dp))

            // ── Pinned navigation button ────────────────────────────────────────────
            Button(
                onClick = {
                    if (state.navigationActive) {
                        onAction(GpsAction.StopNavigation)
                    } else {
                        onAction(GpsAction.StartNavigation)
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(if (state.navigationActive) "Stop Audio Navigation" else "Start Audio Navigation")
            }
        }
    }

    // ── Alignment bearing edit dialog ─────────────────────────────────────────────
    if (showAlignmentDialog) {
        AlertDialog(
            onDismissRequest = { showAlignmentDialog = false },
            title = { Text("Set Alignment Bearing") },
            text = {
                Column {
                    OutlinedTextField(
                        value = alignmentInput,
                        onValueChange = {
                            alignmentInput = it
                            alignmentInputError = false
                        },
                        label = { Text("Bearing (0–360°)") },
                        isError = alignmentInputError,
                        singleLine = true,
                        modifier = Modifier.semantics { contentDescription = "Bearing in degrees, 0 to 360" },
                    )
                    if (alignmentInputError) {
                        Text(
                            "Enter a number between 0 and 360",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val deg = alignmentInput.toDoubleOrNull()
                    if (deg == null || deg < 0 || deg > 360) {
                        alignmentInputError = true
                    } else {
                        onAction(GpsAction.SetAlignmentBearing(deg))
                        showAlignmentDialog = false
                    }
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showAlignmentDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TelemetryCard(
    state: GpsUiState,
    onAction: (GpsAction) -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Copy coordinates") {
                                onAction(GpsAction.CopyCoordinates)
                                true
                            },
                        )
                },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val headingText = state.headingDeg?.let { BearingComputer.toCardinal(it) } ?: "—"
            val headingDegText = state.headingDeg?.let { "${"%.0f".format(it)}°" } ?: "—"
            TelemetryRow(
                label = "Heading",
                value = "$headingText ($headingDegText) · ${state.compassModeLabel}",
                contentDesc = "Heading: $headingText $headingDegText, ${state.compassModeLabel}",
            )

            val bearingLabel = state.targetName?.let { "Bearing to $it" } ?: "Bearing"
            val directionText =
                when (state.settings.bearingDisplayMode) {
                    com.boldexplorer.shared.settings.BearingDisplayMode.RELATIVE -> {
                        state.relativeDeg?.let { BearingComputer.toRelative(it) } ?: "—"
                    }

                    com.boldexplorer.shared.settings.BearingDisplayMode.CLOCK -> {
                        state.relativeDeg?.let { BearingComputer.toClock(it) } ?: "—"
                    }

                    com.boldexplorer.shared.settings.BearingDisplayMode.TRUE_NORTH -> {
                        state.bearingDeg?.let { "${BearingComputer.toCardinal(it)} (${"%.0f".format(it)}°)" } ?: "—"
                    }
                }
            TelemetryRow(label = bearingLabel, value = directionText, contentDesc = "$bearingLabel: $directionText")

            val distText =
                state.distanceM?.let {
                    BearingComputer.formatDistance(it, state.settings.units)
                } ?: "—"
            TelemetryRow(label = "Distance", value = distText, contentDesc = "Distance to target: $distText")

            val accText =
                state.accuracyM?.let {
                    BearingComputer.formatDistance(it, state.settings.units)
                } ?: "—"
            TelemetryRow(label = "GPS Accuracy", value = accText, contentDesc = "GPS accuracy: $accText")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionSelector(
    collections: List<Collection>,
    selectedId: Long?,
    onAction: (GpsAction) -> Unit,
) {
    val selectedName = collections.find { it.id == selectedId }?.name ?: "Select collection"
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Collection") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .semantics { contentDescription = "Selected collection: $selectedName" },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (collections.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No collections yet") },
                    onClick = { expanded = false },
                )
            } else {
                collections.forEach { collection ->
                    DropdownMenuItem(
                        text = { Text(collection.name) },
                        onClick = {
                            onAction(GpsAction.SelectCollection(collection.id))
                            expanded = false
                        },
                        modifier = Modifier.semantics { contentDescription = "Select collection ${collection.name}" },
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionControls(
    active: CollectionExplorerState.Active,
    onAction: (GpsAction) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        Text("Explore mode", modifier = Modifier.weight(1f))
        if (active.exploreMode && active.visitedIds.isNotEmpty()) {
            TextButton(
                onClick = { onAction(GpsAction.ClearCollectionVisited) },
                modifier = Modifier.semantics { contentDescription = "Reset visited points" },
            ) { Text("Reset visited (${active.visitedIds.size})") }
        }
        Switch(
            checked = active.exploreMode,
            onCheckedChange = { onAction(GpsAction.SetCollectionExploreMode(it)) },
            modifier =
                Modifier.semantics {
                    contentDescription = if (active.exploreMode) "Explore mode, on" else "Explore mode, off"
                },
        )
    }
}

@Composable
private fun CollectionTargetList(
    active: CollectionExplorerState.Active?,
    selectedCollectionId: Long?,
    settings: AppSettings,
    location: LocationSample?,
    onAction: (GpsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun pointLabel(point: CollectionPoint): String {
        val baseName =
            when (point) {
                is CollectionPoint.Standalone -> point.waypoint.name
                is CollectionPoint.TrailEnd -> "${point.trail.name} (${if (point.isStart) "start" else "end"})"
            }
        val distStr =
            location?.let { loc ->
                val dist =
                    haversineDistanceMeters(
                        LatLng(loc.lat, loc.lon),
                        LatLng(point.waypoint.lat, point.waypoint.lon),
                    )
                BearingComputer.formatDistance(dist, settings.units)
            }
        return if (distStr != null) "$baseName — $distStr" else baseName
    }

    if (active == null) {
        Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                if (selectedCollectionId == null) "Select a collection to start navigating" else "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription =
                            if (selectedCollectionId == null) "No collection selected" else "Loading collection"
                    },
            )
        }
        return
    }

    if (active.points.isEmpty()) {
        Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "Collection has no points",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { contentDescription = "Collection has no points" },
            )
        }
        return
    }

    val currentTargetId =
        when (val targeting = active.targeting) {
            is CollectionTargeting.Auto -> targeting.target?.id
            is CollectionTargeting.Manual -> targeting.target.id
            CollectionTargeting.Paused -> null
        }

    LazyColumn(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        item {
            val autoSelected = active.targeting is CollectionTargeting.Auto
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAction(GpsAction.ClearCollectionTarget) }
                        .padding(vertical = 12.dp)
                        .semantics {
                            contentDescription =
                                "Nearest, automatic${if (autoSelected) ", selected" else ""}"
                        },
            ) {
                Text(
                    "Nearest (auto)",
                    style = MaterialTheme.typography.bodyLarge,
                    color =
                        if (autoSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
            }
        }
        items(active.points, key = { it.id }) { point ->
            val label = pointLabel(point)
            val isVisited = point.id in active.visitedIds
            val isTarget = point.id == currentTargetId
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAction(GpsAction.SelectCollectionPoint(point)) }
                        .padding(vertical = 12.dp)
                        .semantics {
                            contentDescription =
                                buildString {
                                    append(label)
                                    if (isTarget) append(", current target")
                                    if (isVisited) append(", visited")
                                }
                        },
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color =
                        when {
                            isTarget -> MaterialTheme.colorScheme.primary
                            isVisited -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                )
            }
        }
    }
}

@Composable
private fun ContextualTrailActions(
    state: GpsUiState,
    active: CollectionExplorerState.Active?,
    onAction: (GpsAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // Skip the current collection target.
        if (active?.target != null && state.trailFollowState !is TrailFollowerState.Active) {
            TextButton(
                onClick = { onAction(GpsAction.SkipCollectionTarget) },
                modifier = Modifier.semantics { contentDescription = "Skip current target and pick something else" },
            ) { Text("Not this") }
        }

        // Follow-from-end: only when the current target is a trail end within approach range.
        val target = active?.target
        if (target is CollectionPoint.TrailEnd &&
            active.nearTrailEndM != null &&
            state.trailFollowState !is TrailFollowerState.Active
        ) {
            Button(
                onClick = { onAction(GpsAction.FollowTrailFromCollectionEnd(target)) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription =
                                "Follow ${target.trail.name}" + if (target.isStart) "" else " in reverse"
                        },
            ) {
                Text("Follow ${target.trail.name}")
            }
        }

        // Trail follow / record controls, mutually exclusive by current state.
        when {
            state.trailFollowState is TrailFollowerState.Active -> {
                Button(
                    onClick = { onAction(GpsAction.StopFollowTrail) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Stop trail navigation" },
                ) { Text("Stop Navigation") }
            }

            state.autoRecording -> {
                val recordStatusText = "Auto-recording: ${state.autoRecordCount} points captured"
                Text(
                    recordStatusText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { contentDescription = recordStatusText },
                )
                Button(
                    onClick = { onAction(GpsAction.StopAutoRecord) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Stop auto-recording GPS track" },
                ) { Text("Stop Auto-Record") }
            }

            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onAction(GpsAction.RecordNewTrail) },
                        enabled = state.selectedCollectionId != null,
                        modifier =
                            Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription =
                                        if (state.selectedCollectionId == null) {
                                            "Record new trail — select a collection first"
                                        } else {
                                            "Record a new trail in the selected collection"
                                        }
                                },
                    ) { Text("Record New Trail") }
                    if (state.selectedTrailId != null) {
                        Button(
                            onClick = { onAction(GpsAction.StartAutoRecord) },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = "Start auto-recording GPS track points every 10 meters" },
                        ) { Text("Auto-Record") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlignmentRow(
    state: GpsUiState,
    onAction: (GpsAction) -> Unit,
    onEditBearing: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        if (!state.alignmentActive) {
            TextButton(
                onClick = { onAction(GpsAction.StartAlignment) },
                modifier = Modifier.semantics { contentDescription = "Start bearing alignment guidance" },
            ) { Text("Set Bearing") }
            if (state.bearingDeg != null) {
                TextButton(
                    onClick = { onAction(GpsAction.AlignToTarget) },
                    modifier = Modifier.semantics { contentDescription = "Align audio to waypoint bearing" },
                ) { Text("Align to Target") }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                val targetBearingText = "%.0f".format(state.alignmentBearingDeg ?: 0.0)
                val deltaText =
                    state.alignmentRelativeDeg?.let { delta ->
                        BearingComputer.toAlignmentRelative(delta)
                    } ?: "Waiting for compass…"
                Text(
                    "Target: $targetBearingText° — $deltaText",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row {
                    TextButton(
                        onClick = { onAction(GpsAction.ResetAlignment) },
                        modifier = Modifier.semantics { contentDescription = "Reset alignment to current compass heading" },
                    ) { Text("Reset") }
                    TextButton(
                        onClick = onEditBearing,
                        modifier = Modifier.semantics { contentDescription = "Edit alignment bearing" },
                    ) { Text("Edit") }
                    TextButton(
                        onClick = { onAction(GpsAction.StopAlignment) },
                        modifier = Modifier.semantics { contentDescription = "Stop alignment guidance" },
                    ) { Text("Stop") }
                }
            }
        }
    }
}

@Composable
private fun TelemetryRow(
    label: String,
    value: String,
    contentDesc: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clearAndSetSemantics { contentDescription = contentDesc },
    ) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
