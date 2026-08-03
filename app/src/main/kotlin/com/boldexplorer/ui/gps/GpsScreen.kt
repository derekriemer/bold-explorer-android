package com.boldexplorer.ui.gps

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.boldexplorer.audio.OutputDisposition
import com.boldexplorer.shared.geo.LatLng
import com.boldexplorer.shared.geo.haversineDistanceMeters
import com.boldexplorer.shared.model.LocationSample
import com.boldexplorer.shared.model.Trail
import com.boldexplorer.shared.navigation.BearingComputer
import com.boldexplorer.shared.navigation.CollectionExplorerState
import com.boldexplorer.shared.navigation.CollectionPoint
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
        // Pinned layout: telemetry, HUD controls, collection selector, and the waypoint
        // quick-access rows are all fixed-height and stay put. Only the contextual actions bar
        // (bottom) is genuinely variable height — 0 to several buttons depending on state — so
        // it alone gets weight(1f) + its own scroll, rather than the fixed-height sections above
        // it fighting over a shrinking allocation as it grows (see the layout-overlap bug this
        // replaced: a Column doesn't clip overflowing content, so a shrinking weighted region
        // visually bled into whatever rendered after it).
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

            // ── HUD controls row (#15) — primary entry point for silence mode (#14) and
            // audio navigation start/stop (relocated here from the screen bottom during the
            // pre-release layout pass). Horizontally scrollable so more controls (e.g. #20's
            // shake gesture is an alternate trigger for the same silence setting, not a
            // replacement for this row) can be added without restructuring. ──
            HudControlsRow(state = state, onAction = onAction)

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

            // ── Waypoint quick-access row + controls (#17) ───────────────────────────
            val nearbyTrails = (state.navMode as? NavMode.NearTrail)?.trails ?: emptyList()
            CollectionTargetList(
                active = active,
                selectedCollectionId = state.selectedCollectionId,
                settings = state.settings,
                location = state.location,
                nearbyTrails = nearbyTrails,
                selectedTrailId = state.selectedTrailId,
                onAction = onAction,
            )

            // ── Contextual trail actions — the one variable-height section, so it alone
            // gets weight(1f) + scroll (see comment on the outer Column above). ───────────
            ContextualTrailActions(
                navMode = state.navMode,
                active = active,
                selectedTrailId = state.selectedTrailId,
                recordingState = state.recordingState,
                onAction = onAction,
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
            )

            Spacer(Modifier.height(8.dp))
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

/**
 * Primary HUD controls (#15): silence mode (#14) and audio navigation start/stop, relocated here
 * from the screen bottom during the pre-release layout pass so the screen's one truly permanent
 * "primary actions" row lives right under telemetry. Horizontally scrollable — same pattern as
 * the waypoint controls row (#17) — so more controls can be added without restructuring.
 */
@Composable
private fun HudControlsRow(
    state: GpsUiState,
    onAction: (GpsAction) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.toggleable(
                    value = state.settings.absoluteSilenceEnabled,
                    onValueChange = { onAction(GpsAction.SetAbsoluteSilence(it)) },
                    role = Role.Switch,
                ),
        ) {
            Text("Silence Mode")
            Switch(checked = state.settings.absoluteSilenceEnabled, onCheckedChange = null)
        }
        Button(
            onClick = {
                if (state.navigationActive) {
                    onAction(GpsAction.StopNavigation)
                } else {
                    onAction(GpsAction.StartNavigation)
                }
            },
        ) {
            Text(if (state.navigationActive) "Stop Audio Navigation" else "Start Audio Navigation")
        }
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
            // #23: bearingDeg/distanceM freeze on the last accepted GPS fix when new fixes stop
            // arriving; hedge instead of silently repeating a frozen number as if it were live.
            val staleSuffix = if (state.locationStale) " (GPS signal weak, may be outdated)" else ""
            TelemetryRow(label = bearingLabel, value = if (directionText != "—") directionText + staleSuffix else directionText)

            val distText =
                state.distanceM?.let {
                    BearingComputer.formatDistance(it, state.settings.units) + staleSuffix
                } ?: "—"
            TelemetryRow(label = "Distance to target", value = distText)

            val accText =
                state.accuracyM?.let {
                    BearingComputer.formatDistance(it, state.settings.units)
                } ?: "—"
            TelemetryRow(label = "GPS Accuracy", value = accText)

            // #18: plain telemetry, not a live region — reading this must never auto-announce,
            // or it would defeat the point of silence mode. Navigable/readable on demand only.
            val lastOutputText =
                state.lastOutput?.let {
                    if (it.disposition == OutputDisposition.SILENCED) "${it.text} (silenced)" else it.text
                } ?: "—"
            TelemetryRow(label = "Last announcement", value = lastOutputText)
        }
    }
}

// Fixed count of nearest points shown live (reordering as the user walks) in the quick-access
// row (#17). Anything beyond this is only reachable via the frozen "show all" dialog — bounded
// (not the full reactive list) so a target chip can't shift out from under a tap mid-interaction.
private const val VISIBLE_POINT_COUNT = 4

private data class FrozenTargetRow(
    val point: CollectionPoint,
    val label: String,
    val isTarget: Boolean,
    val isVisited: Boolean,
)

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

    val currentTargetId = active.target?.id

    // Resets if the collection changes, so a stale snapshot from a different collection can't
    // linger open.
    var showAllPoints by rememberSaveable(selectedCollectionId) { mutableStateOf(false) }

    // Snapshot taken only at the instant the dialog opens (remember's key flips false -> true) —
    // it does NOT recompute while active.points keeps re-sorting live underneath it, so the list
    // and every distance label stay put while the user reads/scrolls/picks.
    val frozenAllPoints =
        remember(showAllPoints) {
            if (!showAllPoints) {
                emptyList()
            } else {
                active.points.map { point ->
                    FrozenTargetRow(
                        point = point,
                        label = pointLabel(point),
                        isTarget = point.id == currentTargetId,
                        isVisited = point.id in active.visitedIds,
                    )
                }
            }
        }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Row 1: horizontal quick-access chips (#17) — nearby trails first, then the
        // nearest VISIBLE_POINT_COUNT collection points, nearest first. TalkBack swipes through
        // them left-to-right like any other row of nodes.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            nearbyTrails.forEach { trail ->
                WaypointChip(
                    label = trail.name,
                    selected = trail.id == selectedTrailId,
                    onClick = { onAction(GpsAction.SelectTrail(trail.id)) },
                )
            }
            active.points.take(VISIBLE_POINT_COUNT).forEach { point ->
                WaypointChip(
                    label = pointLabel(point),
                    selected = point.id == currentTargetId,
                    isVisited = point.id in active.visitedIds,
                    onClick = { onAction(GpsAction.SelectCollectionPoint(point)) },
                )
            }
        }

        // ── Row 2: permanent list-level controls (pre-release layout pass) — Auto-advance |
        // Show All | Record New Trail. All three always occupy a slot; Show All/Record New Trail
        // are disabled (not hidden) when not applicable, so this row's contents never shift
        // position. Genuinely contextual actions (Clear target, Reset visited) moved to
        // ContextualTrailActions, since they only make sense given a current target/visited-set.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.toggleable(
                        value = active.autoAdvance,
                        onValueChange = { onAction(GpsAction.SetCollectionAutoAdvance(it)) },
                        role = Role.Switch,
                    ),
            ) {
                Text("Auto-advance")
                Switch(checked = active.autoAdvance, onCheckedChange = null)
            }
            TextButton(
                onClick = { showAllPoints = true },
                enabled = active.points.size > VISIBLE_POINT_COUNT,
            ) {
                Text("Show all ${active.points.size}")
            }
            RecordNewTrailButton(selectedCollectionId = selectedCollectionId, onAction = onAction)
        }
    }

    if (showAllPoints) {
        AlertDialog(
            onDismissRequest = { showAllPoints = false },
            title = { Text("All waypoints (${frozenAllPoints.size}), nearest first") },
            text = {
                // Not lazy: keeps the whole frozen snapshot visible to a screen reader without
                // extra windowing semantics. AlertDialog's text slot doesn't scroll on its own, so
                // this Column needs its own verticalScroll to reach rows past the dialog's max height.
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    frozenAllPoints.forEach { row ->
                        PointTargetRow(
                            label = row.label,
                            isTarget = row.isTarget,
                            isVisited = row.isVisited,
                            onClick = {
                                showAllPoints = false
                                onAction(GpsAction.SelectCollectionPoint(row.point))
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAllPoints = false }) { Text("Close") }
            },
        )
    }
}

/**
 * One chip in the horizontal quick-access row (#17). Uses [FilterChip]'s native `selected` state
 * for "current target" (TalkBack announces selected/not-selected for free, same reasoning as
 * `Modifier.toggleable`/`Switch` elsewhere in this file — see AGENTS.md's contentDescription
 * guidance). "Visited" isn't a native chip concept, so it's folded into the visible label text
 * instead, consistent with [PointTargetRow]'s "visible text carries the same state" convention.
 */
@Composable
private fun WaypointChip(
    label: String,
    selected: Boolean,
    isVisited: Boolean = false,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(if (isVisited) "$label — visited" else label) },
    )
}

@Composable
private fun PointTargetRow(
    label: String,
    isTarget: Boolean,
    isVisited: Boolean = false,
    // "current target" for an actual waypoint; "selected" for a mode choice like "Nearest (auto)"
    // that isn't itself a point on the map.
    targetSuffix: String = "current target",
    // Spoken-only context (e.g. "nearby") that doesn't need to be shown to sighted users because
    // it's not part of the row's visual state, unlike isTarget/isVisited.
    extraDescription: String? = null,
    onClick: () -> Unit,
) {
    // Visible text carries the same state, not just color/contentDescription — color alone isn't
    // colorblind-friendly, and contentDescription alone isn't visible to sighted users.
    val visibleLabel =
        buildString {
            append(label)
            if (isTarget) append(" — $targetSuffix")
            if (isVisited) append(" — visited")
        }
    val accessibleLabel =
        buildString {
            append(label)
            if (extraDescription != null) append(", $extraDescription")
            if (isTarget) append(", $targetSuffix")
            if (isVisited) append(", visited")
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp)
                .semantics {
                    // a11y: Row has no native selected-state semantics; without this, only the
                    // text node's own content would be announced.
                    contentDescription = accessibleLabel
                },
    ) {
        Text(
            visibleLabel,
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

/**
 * Genuinely contextual actions — things that only make sense given the current target/trail/
 * recording state. `Record New Trail` used to live here too, but it showed in 5 of 7 [navMode]
 * branches (i.e. it wasn't actually contextual); it moved to the permanent controls row in
 * [CollectionTargetList] during the pre-release layout pass. `Clear target`/`Reset visited` moved
 * the other direction, from that same permanent row down into this one, since both only make
 * sense given a specific current target/visited-set.
 */
@Composable
private fun ContextualTrailActions(
    navMode: NavMode,
    active: CollectionExplorerState.Active?,
    selectedTrailId: Long?,
    recordingState: TrailRecordingState,
    onAction: (GpsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        when (navMode) {
            NavMode.NoCollection, NavMode.NoTarget -> Unit

            is NavMode.CollectionTarget -> {
                SkipTargetButton(onAction)
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

        if (active?.target != null) {
            TextButton(onClick = { onAction(GpsAction.ClearCollectionTarget) }) { Text("Clear target") }
        }
        if (active?.autoAdvance == true && active.visitedIds.isNotEmpty()) {
            TextButton(
                onClick = { onAction(GpsAction.ClearCollectionVisited) },
            ) { Text("Reset visited (${active.visitedIds.size})") }
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
