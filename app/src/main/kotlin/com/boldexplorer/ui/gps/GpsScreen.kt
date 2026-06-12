package com.boldexplorer.ui.gps

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.navigation.BearingComputer
import com.boldexplorer.shared.navigation.CollectionExplorerState
import com.boldexplorer.shared.navigation.CollectionPoint
import com.boldexplorer.shared.navigation.CollectionTargeting
import com.boldexplorer.shared.navigation.TrailFollowerState
import com.boldexplorer.ui.common.MultiSelectItemDialog

@Composable
fun GpsRoute(
    paddingValues: PaddingValues,
    viewModel: GpsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
            FloatingActionButton(
                onClick = { onAction(GpsAction.MarkWaypoint) },
                containerColor =
                    if (state.location != null) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                modifier =
                    Modifier.semantics {
                        contentDescription =
                            if (state.location != null) {
                                "Mark waypoint at current location"
                            } else {
                                "Mark waypoint — no GPS fix yet"
                            }
                    },
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "GPS",
                style = MaterialTheme.typography.headlineSmall,
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics {
                            heading()
                        },
            )

            // ── Scope tabs ────────────────────────────────────────────────────────
            val scopeLabels = listOf("Waypoint", "Trail", "Collection")
            val scopeValues = GpsScope.entries
            TabRow(selectedTabIndex = scopeValues.indexOf(state.scope)) {
                scopeValues.forEachIndexed { index, gpScope ->
                    Tab(
                        selected = state.scope == gpScope,
                        onClick = { onAction(GpsAction.SetScope(gpScope)) },
                        text = { Text(scopeLabels[index]) },
                        modifier =
                            Modifier.semantics {
                                contentDescription = "${scopeLabels[index]} scope tab${if (state.scope == gpScope) ", selected" else ""}"
                            },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Scope panel ───────────────────────────────────────────────────────
            when (state.scope) {
                GpsScope.WAYPOINT -> {
                    WaypointScopePanel(
                        waypoints = state.waypoints,
                        selectedId = state.selectedWaypointId,
                        onAction = onAction,
                    )
                }

                GpsScope.TRAIL -> {
                    TrailScopePanel(
                        trails = state.trails,
                        selectedId = state.selectedTrailId,
                        trailWaypoints = state.trailWaypoints,
                        followState = state.trailFollowState,
                        autoRecording = state.autoRecording,
                        autoRecordCount = state.autoRecordCount,
                        onAction = onAction,
                    )
                }

                GpsScope.COLLECTION -> {
                    CollectionScopePanel(
                        collections = state.collections,
                        selectedId = state.selectedCollectionId,
                        explorerState = state.collectionExplorerState,
                        settings = state.settings,
                        allWaypoints = state.waypoints,
                        allTrails = state.trails,
                        collectionWaypoints = state.collectionWaypoints,
                        collectionTrails = state.collectionTrails,
                        location = state.location,
                        onAction = onAction,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── TalkBack live region ──────────────────────────────────────────────
            Text(
                text = state.announcement,
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = state.announcement
                        },
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(4.dp))

            // ── Telemetry card ────────────────────────────────────────────────────
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
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

                    state.location?.let { loc ->
                        TelemetryRow(
                            label = "Position",
                            value = "${"%.5f".format(loc.lat)}, ${"%.5f".format(loc.lon)}",
                            contentDesc = "Position: latitude ${"%.5f".format(loc.lat)}, longitude ${"%.5f".format(loc.lon)}",
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Alignment section ─────────────────────────────────────────────────
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
                                onClick = {
                                    alignmentInput = ""
                                    alignmentInputError = false
                                    showAlignmentDialog = true
                                },
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

            Spacer(Modifier.height(8.dp))

            // ── Navigation button ─────────────────────────────────────────────────
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
                        .padding(horizontal = 16.dp),
            ) {
                Text(if (state.navigationActive) "Stop Audio Navigation" else "Start Audio Navigation")
            }

            Spacer(Modifier.height(80.dp)) // FAB clearance
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaypointScopePanel(
    waypoints: List<com.boldexplorer.shared.model.Waypoint>,
    selectedId: Long?,
    onAction: (GpsAction) -> Unit,
) {
    val selectedName = waypoints.find { it.id == selectedId }?.name ?: "Select waypoint"
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
            label = { Text("Waypoint") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .semantics { contentDescription = "Selected waypoint: $selectedName" },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (waypoints.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No waypoints yet") },
                    onClick = { expanded = false },
                )
            } else {
                waypoints.forEach { wp ->
                    DropdownMenuItem(
                        text = { Text(wp.name) },
                        onClick = {
                            onAction(GpsAction.SelectWaypoint(wp.id))
                            expanded = false
                        },
                        modifier = Modifier.semantics { contentDescription = "Select waypoint ${wp.name}" },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionScopePanel(
    collections: List<com.boldexplorer.shared.model.Collection>,
    selectedId: Long?,
    explorerState: CollectionExplorerState,
    settings: com.boldexplorer.shared.settings.AppSettings,
    allWaypoints: List<com.boldexplorer.shared.model.Waypoint>,
    allTrails: List<com.boldexplorer.shared.model.Trail>,
    collectionWaypoints: List<com.boldexplorer.shared.model.Waypoint>,
    collectionTrails: List<com.boldexplorer.shared.model.Trail>,
    location: com.boldexplorer.shared.model.LocationSample?,
    onAction: (GpsAction) -> Unit,
) {
    val selectedName = collections.find { it.id == selectedId }?.name ?: "Select collection"
    var collectionExpanded by remember { mutableStateOf(false) }
    var pointExpanded by remember { mutableStateOf(false) }
    var showAddWaypoints by remember { mutableStateOf(false) }
    var showAddTrails by remember { mutableStateOf(false) }
    val active = explorerState as? CollectionExplorerState.Active

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

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        // ── Collection picker ─────────────────────────────────────────────────────
        ExposedDropdownMenuBox(
            expanded = collectionExpanded,
            onExpandedChange = { collectionExpanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = selectedName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Collection") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = collectionExpanded) },
                modifier =
                    Modifier
                        .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                        .semantics { contentDescription = "Selected collection: $selectedName" },
            )
            ExposedDropdownMenu(expanded = collectionExpanded, onDismissRequest = { collectionExpanded = false }) {
                if (collections.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No collections yet") },
                        onClick = { collectionExpanded = false },
                    )
                } else {
                    collections.forEach { collection ->
                        DropdownMenuItem(
                            text = { Text(collection.name) },
                            onClick = {
                                onAction(GpsAction.SelectCollection(collection.id))
                                collectionExpanded = false
                            },
                        )
                    }
                }
            }
        }

        if (active == null) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (selectedId == null) "No collection selected" else "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = if (selectedId == null) "No collection selected" else "Loading collection"
                    },
            )
            return@Column
        }

        Spacer(Modifier.height(8.dp))

        // ── Add waypoints / trails to this collection ─────────────────────────────
        Row {
            TextButton(
                onClick = { showAddWaypoints = true },
                modifier = Modifier.semantics { contentDescription = "Add waypoints to collection" },
            ) { Text("Add Waypoints") }
            TextButton(
                onClick = { showAddTrails = true },
                modifier = Modifier.semantics { contentDescription = "Add trails to collection" },
            ) { Text("Add Trails") }
        }

        // ── Point picker (dropdown, sorted nearest-first) ─────────────────────────
        if (active.points.isEmpty()) {
            Text(
                "Collection has no points",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { contentDescription = "Collection has no points" },
            )
        } else {
            val targetLabel =
                when (val targeting = active.targeting) {
                    is CollectionTargeting.Auto -> targeting.target?.let { pointLabel(it) } ?: "Nearest (auto)"
                    is CollectionTargeting.Manual -> pointLabel(targeting.target)
                    CollectionTargeting.Paused -> "No target"
                }
            val pointScrollState = rememberScrollState()
            LaunchedEffect(pointExpanded) {
                if (pointExpanded) pointScrollState.scrollTo(0)
            }
            ExposedDropdownMenuBox(
                expanded = pointExpanded,
                onExpandedChange = { pointExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = targetLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Target") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pointExpanded) },
                    modifier =
                        Modifier
                            .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                            .semantics { contentDescription = "Current target: $targetLabel" },
                )
                ExposedDropdownMenu(
                    expanded = pointExpanded,
                    onDismissRequest = { pointExpanded = false },
                    scrollState = pointScrollState,
                ) {
                    DropdownMenuItem(
                        text = { Text("Nearest (auto)") },
                        onClick = {
                            onAction(GpsAction.ClearCollectionTarget)
                            pointExpanded = false
                        },
                        modifier = Modifier.semantics { contentDescription = "Let system pick nearest point" },
                    )
                    active.points.forEach { point ->
                        val label = pointLabel(point)
                        val isVisited = point.id in active.visitedIds
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    color =
                                        if (isVisited) {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                )
                            },
                            onClick = {
                                onAction(GpsAction.SelectCollectionPoint(point))
                                pointExpanded = false
                            },
                            modifier =
                                Modifier.semantics {
                                    contentDescription = "$label${if (isVisited) ", visited" else ""}"
                                },
                        )
                    }
                }
            }

            if (active.target != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { onAction(GpsAction.SkipCollectionTarget) },
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Skip current target and pick something else"
                        },
                ) {
                    Text("Not this")
                }
            }

            // ── Follow button (trail endpoints only, when within range) ───────────
            val currentTarget = active.target
            if (currentTarget is CollectionPoint.TrailEnd && active.nearTrailEndM != null) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { onAction(GpsAction.FollowTrailFromCollectionEnd(currentTarget)) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Follow ${currentTarget.trail.name}" +
                                    if (currentTarget.isStart) "" else " in reverse"
                            },
                ) {
                    Text("Follow ${currentTarget.trail.name}")
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Explore mode toggle + visited reset ───────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Explore mode", modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = active.exploreMode,
                    onCheckedChange = { onAction(GpsAction.SetCollectionExploreMode(it)) },
                    modifier =
                        Modifier.semantics {
                            contentDescription = if (active.exploreMode) "Explore mode, on" else "Explore mode, off"
                        },
                )
            }
            if (active.exploreMode && active.visitedIds.isNotEmpty()) {
                TextButton(
                    onClick = { onAction(GpsAction.ClearCollectionVisited) },
                    modifier = Modifier.semantics { contentDescription = "Reset visited points" },
                ) { Text("Reset visited (${active.visitedIds.size})") }
            }
        }
    }

    if (showAddWaypoints) {
        val existingIds = collectionWaypoints.map { it.id }.toSet()
        val candidates =
            allWaypoints.filter { it.id !in existingIds }.let { wps ->
                val loc = location
                if (loc == null) {
                    wps
                } else {
                    wps.sortedBy { haversineDistanceMeters(LatLng(loc.lat, loc.lon), LatLng(it.lat, it.lon)) }
                }
            }
        MultiSelectItemDialog(
            title = "Add Waypoints",
            entries = candidates.map { it.id to it.name },
            onConfirm = { ids ->
                onAction(GpsAction.AddWaypointsToCollection(ids))
                showAddWaypoints = false
            },
            onDismiss = { showAddWaypoints = false },
            emptyMessage = "No waypoints available",
        )
    }

    if (showAddTrails) {
        val existingIds = collectionTrails.map { it.id }.toSet()
        val candidates = allTrails.filter { it.id !in existingIds }
        MultiSelectItemDialog(
            title = "Add Trails",
            entries = candidates.map { it.id to it.name },
            onConfirm = { ids ->
                onAction(GpsAction.AddTrailsToCollection(ids))
                showAddTrails = false
            },
            onDismiss = { showAddTrails = false },
            emptyMessage = "No trails available",
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrailScopePanel(
    trails: List<com.boldexplorer.shared.model.Trail>,
    selectedId: Long?,
    trailWaypoints: List<com.boldexplorer.shared.model.Waypoint>,
    followState: TrailFollowerState,
    autoRecording: Boolean,
    autoRecordCount: Int,
    onAction: (GpsAction) -> Unit,
) {
    val selectedName = trails.find { it.id == selectedId }?.name ?: "Select trail"
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = selectedName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Trail") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier =
                    Modifier
                        .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                        .semantics { contentDescription = "Selected trail: $selectedName" },
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (trails.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No trails yet") },
                        onClick = { expanded = false },
                    )
                } else {
                    trails.forEach { trail ->
                        DropdownMenuItem(
                            text = { Text(trail.name) },
                            onClick = {
                                onAction(GpsAction.SelectTrail(trail.id))
                                expanded = false
                            },
                            modifier = Modifier.semantics { contentDescription = "Select trail ${trail.name}" },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        val statusText =
            when (followState) {
                is TrailFollowerState.Idle -> {
                    "Not following"
                }

                is TrailFollowerState.Active -> {
                    val total = followState.waypoints.size
                    val current = followState.currentIndex + 1
                    val name = followState.waypoints.getOrNull(followState.currentIndex)?.name ?: ""
                    "Waypoint $current of $total: $name"
                }

                is TrailFollowerState.Complete -> {
                    "Trail complete"
                }
            }
        Text(
            statusText,
            style = MaterialTheme.typography.bodyMedium,
            modifier =
                Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "Trail status: $statusText"
                },
        )

        Spacer(Modifier.height(4.dp))

        // Record new trail button (always shown)
        Button(
            onClick = { onAction(GpsAction.RecordNewTrail) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Create a new trail with current timestamp" },
        ) { Text("Record New Trail") }

        Spacer(Modifier.height(4.dp))

        // Auto-record controls (only when a trail is selected)
        if (selectedId != null) {
            if (autoRecording) {
                val recordStatusText = "Auto-recording: $autoRecordCount points captured"
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
            } else {
                Button(
                    onClick = { onAction(GpsAction.StartAutoRecord) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Start auto-recording GPS track points every 10 meters" },
                ) { Text("Start Auto-Record") }
            }
        }

        Spacer(Modifier.height(4.dp))

        val hasTrail = selectedId != null && trailWaypoints.isNotEmpty()
        if (followState is TrailFollowerState.Active) {
            Button(
                onClick = { onAction(GpsAction.StopFollowTrail) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Stop trail navigation" },
            ) { Text("Stop Navigation") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onAction(GpsAction.StartFollowTrail) },
                    enabled = hasTrail,
                    modifier =
                        Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription =
                                    if (!hasTrail) {
                                        "Select a trail first"
                                    } else {
                                        "Follow trail forward from nearest point"
                                    }
                            },
                ) { Text("Follow") }
                Button(
                    onClick = { onAction(GpsAction.StartFollowTrailReversed) },
                    enabled = hasTrail,
                    modifier =
                        Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription =
                                    if (!hasTrail) {
                                        "Select a trail first"
                                    } else {
                                        "Follow trail in reverse from nearest point"
                                    }
                            },
                ) { Text("Reverse") }
            }
        }
    }
}
