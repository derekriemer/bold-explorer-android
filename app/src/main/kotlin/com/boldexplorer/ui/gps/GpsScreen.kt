package com.boldexplorer.ui.gps

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.boldexplorer.shared.navigation.BearingComputer
import com.boldexplorer.shared.navigation.TrailFollowerState
import com.boldexplorer.shared.settings.Units

@Composable
fun GpsScreen(
    paddingValues: PaddingValues,
    viewModel: GpsViewModel = hiltViewModel(),
) {
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val location by viewModel.location.collectAsStateWithLifecycle()
    val headingDeg by viewModel.headingDeg.collectAsStateWithLifecycle()
    val accuracyM by viewModel.accuracyM.collectAsStateWithLifecycle()
    val waypoints by viewModel.waypoints.collectAsStateWithLifecycle()
    val trails by viewModel.trails.collectAsStateWithLifecycle()
    val selectedWaypointId by viewModel.selectedWaypointId.collectAsStateWithLifecycle()
    val selectedTrailId by viewModel.selectedTrailId.collectAsStateWithLifecycle()
    val trailWaypoints by viewModel.trailWaypoints.collectAsStateWithLifecycle()
    val trailFollowState by viewModel.trailFollowState.collectAsStateWithLifecycle()
    val targetName by viewModel.targetName.collectAsStateWithLifecycle()
    val bearingDeg by viewModel.bearingDeg.collectAsStateWithLifecycle()
    val distanceM by viewModel.distanceM.collectAsStateWithLifecycle()
    val relativeDeg by viewModel.relativeDeg.collectAsStateWithLifecycle()
    val alignmentActive by viewModel.alignmentActive.collectAsStateWithLifecycle()
    val alignmentBearingDeg by viewModel.alignmentBearingDeg.collectAsStateWithLifecycle()
    val alignmentRelativeDeg by viewModel.alignmentRelativeDeg.collectAsStateWithLifecycle()
    val announcement by viewModel.announcement.collectAsStateWithLifecycle()
    val navigationActive by viewModel.navigationActive.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled; user can retry navigation */ }

    var showAlignmentDialog by remember { mutableStateOf(false) }
    var alignmentInput by remember { mutableStateOf("") }

    Scaffold(
        modifier = Modifier.padding(paddingValues),
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (location != null) viewModel.markWaypoint()
                },
                modifier = Modifier.semantics {
                    contentDescription = if (location != null) "Mark waypoint at current location" else "Mark waypoint — no GPS fix yet"
                },
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "GPS",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // ── Scope tabs ────────────────────────────────────────────────────────
            val scopeLabels = listOf("Waypoint", "Trail", "Collection")
            val scopeValues = GpsScope.values()
            TabRow(selectedTabIndex = scopeValues.indexOf(scope)) {
                scopeValues.forEachIndexed { index, gpScope ->
                    Tab(
                        selected = scope == gpScope,
                        onClick = { viewModel.setScope(gpScope) },
                        text = { Text(scopeLabels[index]) },
                        modifier = Modifier.semantics {
                            contentDescription = "${scopeLabels[index]} scope tab${if (scope == gpScope) ", selected" else ""}"
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Scope panel ───────────────────────────────────────────────────────
            when (scope) {
                GpsScope.WAYPOINT -> WaypointScopePanel(
                    waypoints = waypoints,
                    selectedId = selectedWaypointId,
                    onSelect = { viewModel.selectWaypoint(it) },
                )
                GpsScope.TRAIL -> TrailScopePanel(
                    trails = trails,
                    selectedId = selectedTrailId,
                    trailWaypoints = trailWaypoints,
                    followState = trailFollowState,
                    onSelectTrail = { viewModel.selectTrail(it) },
                    onStartFollow = { viewModel.startFollowTrail() },
                    onStopFollow = { viewModel.stopFollowTrail() },
                )
                GpsScope.COLLECTION -> Text(
                    "Collections navigation coming in a future update.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── TalkBack live region ──────────────────────────────────────────────
            Text(
                text = announcement,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = announcement
                    },
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(4.dp))

            // ── Telemetry card ────────────────────────────────────────────────────
            Card(modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val headingText = headingDeg?.let { BearingComputer.toCardinal(it) } ?: "—"
                    val headingDegText = headingDeg?.let { "${"%.0f".format(it)}°" } ?: "—"
                    TelemetryRow(
                        label = "Heading",
                        value = "$headingText ($headingDegText)",
                        contentDesc = "Heading: $headingText $headingDegText",
                    )

                    val bearingText = bearingDeg?.let {
                        "${BearingComputer.toCardinal(it)} (${"%.0f".format(it)}°)"
                    } ?: "—"
                    val bearingLabel = targetName?.let { "Bearing to $it" } ?: "Bearing"
                    TelemetryRow(label = bearingLabel, value = bearingText, contentDesc = "$bearingLabel: $bearingText")

                    val clockText = relativeDeg?.let { BearingComputer.toClock(it) } ?: "—"
                    TelemetryRow(label = "Direction", value = clockText, contentDesc = "Direction to target: $clockText")

                    val distText = distanceM?.let {
                        BearingComputer.formatDistance(it, Units.IMPERIAL)
                    } ?: "—"
                    TelemetryRow(label = "Distance", value = distText, contentDesc = "Distance to target: $distText")

                    val accText = accuracyM?.let { "${"%.0f".format(it)} m" } ?: "—"
                    TelemetryRow(label = "GPS Accuracy", value = accText, contentDesc = "GPS accuracy: $accText")

                    location?.let { loc ->
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
                if (!alignmentActive) {
                    TextButton(
                        onClick = { viewModel.startAlignment() },
                        modifier = Modifier.semantics { contentDescription = "Start bearing alignment guidance" },
                    ) { Text("Set Bearing") }
                    bearingDeg?.let {
                        TextButton(
                            onClick = { viewModel.setAlignmentToBearing(); viewModel.startAlignment() },
                            modifier = Modifier.semantics { contentDescription = "Align to waypoint bearing" },
                        ) { Text("Align to Target") }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val targetBearingText = "%.0f".format(alignmentBearingDeg ?: 0.0)
                        val deltaText = alignmentRelativeDeg?.let { delta ->
                            val absDeg = kotlin.math.abs(delta).toInt()
                            when {
                                absDeg <= 5 -> "Aligned"
                                delta > 0 -> "$absDeg° right of target"
                                else -> "$absDeg° left of target"
                            }
                        } ?: "Waiting for compass…"
                        Text(
                            "Target: $targetBearingText° — $deltaText",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row {
                            TextButton(
                                onClick = { viewModel.resetAlignmentToCurrent() },
                                modifier = Modifier.semantics { contentDescription = "Reset alignment to current compass heading" },
                            ) { Text("Reset") }
                            TextButton(
                                onClick = { showAlignmentDialog = true },
                                modifier = Modifier.semantics { contentDescription = "Edit alignment bearing" },
                            ) { Text("Edit") }
                            TextButton(
                                onClick = { viewModel.stopAlignment() },
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
                    if (!viewModel.hasForegroundLocationPermission()) {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                            )
                        )
                    } else if (navigationActive) {
                        viewModel.stopNavigation()
                    } else {
                        viewModel.startNavigation()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .semantics {
                        contentDescription = if (navigationActive)
                            "Stop audio navigation"
                        else
                            "Start audio navigation"
                    },
            ) {
                Text(if (navigationActive) "Stop Audio Navigation" else "Start Audio Navigation")
            }

            Spacer(Modifier.height(80.dp)) // FAB clearance
        }
    }

    // Alignment bearing edit dialog
    if (showAlignmentDialog) {
        AlertDialog(
            onDismissRequest = { showAlignmentDialog = false },
            title = { Text("Set Alignment Bearing") },
            text = {
                OutlinedTextField(
                    value = alignmentInput,
                    onValueChange = { alignmentInput = it },
                    label = { Text("Bearing (0–360°)") },
                    singleLine = true,
                    modifier = Modifier.semantics { contentDescription = "Bearing in degrees, 0 to 360" },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    alignmentInput.toDoubleOrNull()?.let { viewModel.setAlignmentBearing(it) }
                    showAlignmentDialog = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showAlignmentDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TelemetryRow(label: String, value: String, contentDesc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = contentDesc },
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
    onSelect: (Long) -> Unit,
) {
    val selectedName = waypoints.find { it.id == selectedId }?.name ?: "Select waypoint"
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Waypoint") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
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
                        onClick = { onSelect(wp.id); expanded = false },
                        modifier = Modifier.semantics { contentDescription = "Select waypoint ${wp.name}" },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrailScopePanel(
    trails: List<com.boldexplorer.shared.model.Trail>,
    selectedId: Long?,
    trailWaypoints: List<com.boldexplorer.shared.model.Waypoint>,
    followState: TrailFollowerState,
    onSelectTrail: (Long) -> Unit,
    onStartFollow: () -> Unit,
    onStopFollow: () -> Unit,
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
                modifier = Modifier
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
                            onClick = { onSelectTrail(trail.id); expanded = false },
                            modifier = Modifier.semantics { contentDescription = "Select trail ${trail.name}" },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        val statusText = when (followState) {
            is TrailFollowerState.Idle -> "Not following"
            is TrailFollowerState.Active -> {
                val total = followState.waypoints.size
                val current = followState.currentIndex + 1
                val name = followState.waypoints.getOrNull(followState.currentIndex)?.name ?: ""
                "Waypoint $current of $total: $name"
            }
            is TrailFollowerState.Complete -> "Trail complete"
        }
        Text(
            statusText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Trail status: $statusText"
            },
        )

        Spacer(Modifier.height(4.dp))

        if (followState is TrailFollowerState.Active) {
            Button(
                onClick = onStopFollow,
                enabled = selectedId != null && trailWaypoints.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Stop trail navigation" },
            ) { Text("Stop Trail Navigation") }
        } else {
            Button(
                onClick = onStartFollow,
                enabled = selectedId != null && trailWaypoints.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = if (selectedId == null) "Select a trail first"
                        else "Start trail navigation"
                    },
            ) { Text("Start Trail Navigation") }
        }
    }
}
