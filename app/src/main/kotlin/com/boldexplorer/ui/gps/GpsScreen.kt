package com.boldexplorer.ui.gps

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.navigation.BearingComputer
import com.boldexplorer.shared.navigation.CollectionExplorerState
import com.boldexplorer.shared.navigation.CollectionPoint
import com.boldexplorer.shared.navigation.CollectionTargeting
import com.boldexplorer.shared.navigation.DirectionDescriptor
import com.boldexplorer.shared.navigation.FollowOption
import com.boldexplorer.shared.navigation.NavMode
import com.boldexplorer.shared.navigation.TrailEndAction
import com.boldexplorer.shared.navigation.TrailRecordingState
import com.boldexplorer.shared.settings.AppSettings
import com.boldexplorer.ui.common.CollectionDropdown
import com.boldexplorer.ui.common.CreateItemDialog

@Composable
fun GpsRoute(
    paddingValues: PaddingValues,
    viewModel: GpsViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingCreate by viewModel.pendingCreate.collectAsStateWithLifecycle()
    var showCreateCollection by remember { mutableStateOf(false) }

    if (showCreateCollection) {
        CreateItemDialog(
            title = "New Collection",
            confirmLabel = "Create",
            hasTentative = false,
            onConfirm = { name, _, _ ->
                viewModel.onAction(GpsAction.CreateCollection(name))
                showCreateCollection = false
            },
            onDismiss = { showCreateCollection = false },
        )
    }

    when (val pending = pendingCreate) {
        is PendingCreate.Waypoint -> {
            CreateItemDialog(
                title = "Name waypoint",
                confirmLabel = "Mark",
                launchSttOnOpen = pending.launchStt,
                onConfirm = { name, _, tentative -> viewModel.onWaypointNamed(name, tentative) },
                onDismiss = { viewModel.cancelPendingCreate() },
            )
        }

        is PendingCreate.Trail -> {
            CreateItemDialog(
                title = "Name trail",
                confirmLabel = "Create",
                launchSttOnOpen = pending.launchStt,
                onConfirm = { name, _, tentative -> viewModel.onTrailNamed(name, tentative) },
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
        onCreateCollection = { showCreateCollection = true },
    )
}

@Composable
fun GpsScreen(
    paddingValues: PaddingValues,
    state: GpsUiState,
    onAction: (GpsAction) -> Unit,
    onCreateCollection: () -> Unit,
) {
    var showAlignmentDialog by remember { mutableStateOf(false) }

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
                        // a11y: the visible label is just "+"; state also varies by disabled reason.
                        contentDescription =
                            when {
                                !canMark -> "Mark waypoint — select a collection first"
                                state.location == null -> "Mark waypoint — no GPS fix yet"
                                else -> "Mark waypoint at current location"
                            }
                        // Speak-to-name is an entry-point action: it opens the naming dialog straight into
                        // voice input (no custom actions on the dialog itself — it is a plain form).
                        if (canMark) {
                            customActions =
                                listOf(
                                    CustomAccessibilityAction("Speak new waypoint name") {
                                        onAction(GpsAction.MarkWaypointWithSpeech)
                                        true
                                    },
                                )
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
            TelemetryCard(
                state = state,
                onAction = onAction,
                onOpenAlignment = {
                    if (!state.alignmentActive) onAction(GpsAction.StartAlignment)
                    showAlignmentDialog = true
                },
            )

            // ── Announcement display (visual only — live region is in NavGraph) ──────
            Text(
                text = state.announcement,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )

            // ── Pinned collection selector ──────────────────────────────────────────
            CollectionDropdown(
                collections = state.collections,
                selectedId = state.selectedCollectionId,
                onSelect = { id -> if (id != null) onAction(GpsAction.SelectCollection(id)) },
                onCreateNew = onCreateCollection,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            )

            val active = state.collectionExplorerState as? CollectionExplorerState.Active

            // ── Collection controls (explore mode + add) ────────────────────────────
            if (active != null) {
                CollectionControls(active = active, onAction = onAction)
            }

            // ── Scrolling target list (the only scrollable region) ──────────────────
            val nearbyTrails = (state.navMode as? NavMode.NearTrail)?.trails ?: emptyList()
            CollectionTargetList(
                active = active,
                selectedCollectionId = state.selectedCollectionId,
                settings = state.settings,
                location = state.location,
                nearbyTrails = nearbyTrails,
                selectedTrailId = state.selectedTrailId,
                onAction = onAction,
                modifier = Modifier.weight(1f),
            )

            // ── Pinned contextual trail actions ─────────────────────────────────────
            ContextualTrailActions(
                navMode = state.navMode,
                selectedCollectionId = state.selectedCollectionId,
                selectedTrailId = state.selectedTrailId,
                recordingState = state.recordingState,
                onAction = onAction,
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

    // ── Alignment modal ───────────────────────────────────────────────────────────
    if (showAlignmentDialog) {
        AlignmentDialog(
            state = state,
            onAction = onAction,
            onDismiss = { showAlignmentDialog = false },
        )
    }
}

@Composable
private fun TelemetryCard(
    state: GpsUiState,
    onAction: (GpsAction) -> Unit,
    onOpenAlignment: () -> Unit,
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
            // The heading row is the entry point to the alignment modal (Open alignment custom action).
            TelemetryRow(
                label = "Heading",
                value = "$headingText ($headingDegText) · ${state.compassModeLabel}",
                customActions =
                    listOf(
                        CustomAccessibilityAction("Open alignment") {
                            onOpenAlignment()
                            true
                        },
                    ),
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
            TelemetryRow(label = bearingLabel, value = directionText)

            val distText =
                state.distanceM?.let {
                    BearingComputer.formatDistance(it, state.settings.units)
                } ?: "—"
            TelemetryRow(label = "Distance to target", value = distText)

            val accText =
                state.accuracyM?.let {
                    BearingComputer.formatDistance(it, state.settings.units)
                } ?: "—"
            TelemetryRow(label = "GPS Accuracy", value = accText)
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .weight(1f)
                    .toggleable(
                        value = active.exploreMode,
                        onValueChange = { onAction(GpsAction.SetCollectionExploreMode(it)) },
                        role = Role.Switch,
                    ),
        ) {
            Text("Auto-advance", modifier = Modifier.weight(1f))
            Switch(checked = active.exploreMode, onCheckedChange = null)
        }
        if (active.exploreMode && active.visitedIds.isNotEmpty()) {
            TextButton(
                onClick = { onAction(GpsAction.ClearCollectionVisited) },
            ) { Text("Reset visited (${active.visitedIds.size})") }
        }
    }
}

@Composable
private fun CollectionTargetList(
    active: CollectionExplorerState.Active?,
    selectedCollectionId: Long?,
    settings: AppSettings,
    location: LocationSample?,
    nearbyTrails: List<Trail>,
    selectedTrailId: Long?,
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
                // should this be in the view really? and we could just use flat earth coords for this, because we will be close enough to target that we can just use #_of_meters_per_degree as a shortcut.
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
                            // a11y: plain clickable Row has no native selected-state semantics, and
                            // "selected" is otherwise only shown by color, which isn't accessible.
                            contentDescription = "Nearest, automatic${if (autoSelected) ", selected" else ""}"
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
        items(nearbyTrails, key = { "nearby:${it.id}" }) { trail ->
            val isSelected = trail.id == selectedTrailId
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAction(GpsAction.SelectTrail(trail.id)) }
                        .padding(vertical = 12.dp)
                        .semantics {
                            // a11y: "nearby"/"selected" aren't shown by color alone; Row has no
                            // native selected-state semantics.
                            contentDescription = "${trail.name}, nearby${if (isSelected) ", selected" else ""}"
                        },
            ) {
                Text(
                    trail.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color =
                        if (isSelected) {
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
                            // a11y: "current target"/"visited" aren't shown by color alone; Row has
                            // no native selected-state semantics.
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
    navMode: NavMode,
    selectedCollectionId: Long?,
    selectedTrailId: Long?,
    recordingState: TrailRecordingState,
    onAction: (GpsAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        when (navMode) {
            NavMode.NoCollection, NavMode.NoTarget -> {
                RecordNewTrailButton(selectedCollectionId = selectedCollectionId, onAction = onAction)
            }

            is NavMode.CollectionTarget -> {
                SkipTargetButton(onAction)
                RecordNewTrailButton(selectedCollectionId = selectedCollectionId, onAction = onAction)
            }

            is NavMode.AtTrailEnd -> {
                val end = navMode.trailEnd
                val trailSelected = selectedTrailId == end.trail.id
                SkipTargetButton(onAction)
                if (TrailEndAction.Follow in navMode.actions && trailSelected) {
                    FollowAffordance(
                        trailName = end.trail.name,
                        trailId = end.trail.id,
                        options = navMode.followOptions,
                        onAction = onAction,
                    )
                }
                if (TrailEndAction.Extend in navMode.actions && trailSelected) {
                    Button(
                        onClick = { onAction(GpsAction.ExtendTrailFromCollectionEnd(end)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Extend ${end.trail.name}") }
                }
                RecordNewTrailButton(selectedCollectionId = selectedCollectionId, onAction = onAction)
            }

            is NavMode.NearTrail -> {
                val selected = navMode.trails.firstOrNull { it.id == selectedTrailId }
                if (selected != null) {
                    FollowAffordance(
                        trailName = selected.name,
                        trailId = selected.id,
                        options = navMode.options,
                        onAction = onAction,
                    )
                }
                RecordNewTrailButton(selectedCollectionId = selectedCollectionId, onAction = onAction)
            }

            is NavMode.FollowingTrail -> {
                Button(
                    onClick = { onAction(GpsAction.StopFollowTrail) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop Navigation") }
            }

            is NavMode.RecordingTrail -> {
                val recordStatusText = "Auto-recording: ${navMode.pointCount} points captured"
                Text(
                    recordStatusText,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { onAction(GpsAction.StopAutoRecord) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop Auto-Record") }
            }
        }

        if (recordingState is TrailRecordingState.Selected) {
            Button(
                onClick = { onAction(GpsAction.StartAutoRecord) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start Auto-Record") }
        }
    }
}

/**
 * The single "Follow {trail}" affordance. With one [options] entry it follows directly; with two it
 * opens a direction chooser so the user — who may not know the trail — picks a direction explicitly,
 * rather than the app guessing (which risks misdirecting a blind user when direction is ambiguous).
 */
@Composable
private fun FollowAffordance(
    trailName: String,
    trailId: Long,
    options: List<FollowOption>,
    onAction: (GpsAction) -> Unit,
) {
    if (options.isEmpty()) return

    if (options.size == 1) {
        val reversed = options.single().reversed
        Button(
            onClick = { onAction(GpsAction.FollowTrail(trailId, reversed)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Follow $trailName") }
        return
    }

    var showChooser by remember { mutableStateOf(false) }
    Button(
        onClick = { showChooser = true },
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    // a11y: this button opens a direction-choice dialog rather than following
                    // immediately (unlike the single-option case above); the visible label alone
                    // doesn't convey that a choice is needed.
                    contentDescription = "Follow $trailName, choose a direction"
                },
    ) { Text("Follow $trailName") }

    if (showChooser) {
        AlertDialog(
            onDismissRequest = { showChooser = false },
            title = { Text("Follow $trailName") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    options.forEach { option ->
                        val label = option.descriptor.label()
                        Button(
                            onClick = {
                                showChooser = false
                                onAction(GpsAction.FollowTrail(trailId, option.reversed))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChooser = false }) { Text("Cancel") }
            },
        )
    }
}

/** Human-readable label for a follow direction. Only Forward/Backward are produced today. */
private fun DirectionDescriptor.label(): String =
    when (this) {
        DirectionDescriptor.Forward -> "Forward"
        DirectionDescriptor.Backward -> "Backward"
        is DirectionDescriptor.Cardinal -> text
        is DirectionDescriptor.Loop -> if (clockwise) "Clockwise" else "Counter-clockwise"
    }

@Composable
private fun SkipTargetButton(onAction: (GpsAction) -> Unit) {
    TextButton(
        onClick = { onAction(GpsAction.SkipCollectionTarget) },
        // a11y: "Not this" alone doesn't convey what tapping does.
        modifier = Modifier.semantics { contentDescription = "Skip current target and pick something else" },
    ) { Text("Not this") }
}

@Composable
private fun RecordNewTrailButton(
    selectedCollectionId: Long?,
    onAction: (GpsAction) -> Unit,
) {
    Button(
        onClick = { onAction(GpsAction.RecordNewTrail) },
        enabled = selectedCollectionId != null,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    // a11y: visible "Record New Trail" doesn't convey the disabled reason or which
                    // collection it applies to.
                    contentDescription =
                        if (selectedCollectionId == null) {
                            "Record new trail — select a collection first"
                        } else {
                            "Record a new trail in the selected collection"
                        }
                    if (selectedCollectionId != null) {
                        customActions =
                            listOf(
                                CustomAccessibilityAction("Speak new trail name") {
                                    onAction(GpsAction.RecordNewTrailWithSpeech)
                                    true
                                },
                            )
                    }
                },
    ) { Text("Record New Trail") }
}

@Composable
private fun TelemetryRow(
    label: String,
    value: String,
    customActions: List<CustomAccessibilityAction> = emptyList(),
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .semantics(mergeDescendants = true) {
                    if (customActions.isNotEmpty()) this.customActions = customActions
                },
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
