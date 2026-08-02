package com.boldexplorer.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boldexplorer.audio.AudioLogEntry
import com.boldexplorer.location.RawFixEvent
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DebugScreen(
    paddingValues: PaddingValues,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val location by viewModel.location.collectAsStateWithLifecycle()
    val heading by viewModel.heading.collectAsStateWithLifecycle()
    val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()
    val accuracyBeaconEnabled by viewModel.accuracyBeaconEnabled.collectAsStateWithLifecycle()
    val useGnss by viewModel.useGnss.collectAsStateWithLifecycle()
    val logEntries by viewModel.logEntries.collectAsStateWithLifecycle()
    val logStatus by viewModel.logStatus.collectAsStateWithLifecycle()
    val showMarkerDialog by viewModel.showMarkerDialog.collectAsStateWithLifecycle()
    val lastRawFix by viewModel.lastRawFix.collectAsStateWithLifecycle()

    // Ticks so the "Ns ago" fix-age text (below) keeps advancing even when no new fix arrives —
    // that stalling *is* the signal for issue #23, so it must be visible live, not just on the
    // next recomposition triggered by other state. Plain text, not a live region: this would spam
    // TalkBack every tick if it auto-announced.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(500L)
        }
    }

    Column(
        modifier =
            Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
    ) {
        // Header row: title on left, IMPORTANT! on right
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Debug",
                style = MaterialTheme.typography.headlineSmall,
                modifier =
                    Modifier.semantics {
                        heading()
                    },
            )
            Button(
                onClick = { viewModel.onImportantPressed() },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
            ) {
                Text("IMPORTANT!")
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("GPS", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DebugRow("Latitude", location?.lat?.let { "%.6f".format(it) } ?: "—")
                DebugRow("Longitude", location?.lon?.let { "%.6f".format(it) } ?: "—")
                DebugRow("Altitude", location?.altitude?.let { "${"%.1f".format(it)} m" } ?: "—")
                DebugRow("Accuracy", location?.accuracy?.let { "${"%.1f".format(it)} m" } ?: "—")
                DebugRow("Speed", location?.speed?.let { "${"%.1f".format(it)} m/s" } ?: "—")
                DebugRow("Provider", location?.provider ?: "—")

                // Issue #23: shows every raw fix, accepted or accuracy-gate-dropped, so a
                // freezing distance/bearing reading can be diagnosed live in the field instead of
                // guessed at afterward from an exported log.
                RawFixRow(lastRawFix, nowMs)

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                // a11y: explains the tradeoff (sensor fusion vs. raw GNSS) that isn't
                                // conveyed by the visible labels alone.
                                contentDescription =
                                    "Raw GNSS provider ${if (useGnss) "on" else "off"}: bypasses sensor fusion for better outdoor accuracy"
                            },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Raw GNSS", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (useGnss) "GPS chip direct · better outdoors" else "Fused · better indoors / cold-start",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = useGnss,
                        onCheckedChange = { viewModel.setUseGnss(it) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Compass", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DebugRow("Magnetic", heading?.magnetic?.let { "${"%.1f".format(it)}°" } ?: "—")
                DebugRow("True North", heading?.trueNorth?.let { "${"%.1f".format(it)}°" } ?: "—")
            }
        }

        Spacer(Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Audio", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                // a11y: explains what the beacon sounds like and means, which the
                                // visible "Accuracy beacon" label + switch don't convey.
                                contentDescription =
                                    "Accuracy beacon ${if (accuracyBeaconEnabled) "on" else "off"}: beeps on every GPS update, pitch maps to fix quality"
                            },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Accuracy beacon", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = accuracyBeaconEnabled,
                        onCheckedChange = { viewModel.setAccuracyBeaconEnabled(it) },
                    )
                }
                Text(
                    "Current accuracy: ${location?.accuracy?.let {
                        "${"%.0f".format(
                            it,
                        )} m → ${"%.0f".format(880.0 - (880.0 - 220.0) * (it.coerceIn(0.0, 30.0) / 30.0))} Hz"
                    } ?: "no fix"}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Button(
                    onClick = { viewModel.testAccuracyBeacon() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Play Accuracy Beacon")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Export", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                exportStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                Button(
                    onClick = { viewModel.exportAllWaypoints() },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics {
                                // a11y: names the destination folder, which the visible label doesn't.
                                contentDescription = "Export all waypoints to Downloads folder as GPX file"
                            },
                ) {
                    Text("Export Waypoints GPX")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Audio log — minimal footprint, no entries shown on-screen
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Audio Log", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                logStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.newLog() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("New Log")
                    }
                    Button(
                        onClick = { viewModel.exportLog() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Export Log")
                    }
                }
            }
        }

        Spacer(Modifier.height(80.dp))
    }

    // IMPORTANT! marker dialog — shows last 5 log entries + note field
    if (showMarkerDialog) {
        MarkerNoteDialog(
            recentEntries = logEntries.take(5),
            onConfirm = { note -> viewModel.confirmMarker(note) },
            onDismiss = { viewModel.dismissMarker() },
        )
    }
}

@Composable
private fun MarkerNoteDialog(
    recentEntries: List<AudioLogEntry>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember { mutableStateOf("") }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark important moment") },
        text = {
            Column {
                if (recentEntries.isNotEmpty()) {
                    Text(
                        "Recent events:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    recentEntries.forEach { entry ->
                        val time = timeFmt.format(Date(entry.timestampMs))
                        val detail =
                            when {
                                entry.note.isNotBlank() -> entry.note
                                entry.played.isNotBlank() -> entry.played
                                else -> entry.inputs
                            }
                        Text(
                            "$time  ${entry.kind.name.replace('_', ' ')}  $detail",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("What happened? (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(note) }) { Text("Save marker") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Live "Last GPS fix: Ns ago" row for issue #23 — distinguishes an accepted fix from one the
 * accuracy gate dropped, and shows the current discard streak so a long run of drops (accuracy
 * gate rejecting fixes) reads differently from a long gap between raw fixes at all (OS/OEM
 * throttling not delivering fixes in the first place).
 */
@Composable
private fun RawFixRow(
    fix: RawFixEvent?,
    nowMs: Long,
) {
    if (fix == null) {
        DebugRow("Last GPS fix", "No fix yet")
        return
    }
    val ageSec = ((nowMs - fix.timestampMs) / 1000L).coerceAtLeast(0L)
    val ageText = if (ageSec < 60) "${ageSec}s ago" else "${ageSec / 60}m ${ageSec % 60}s ago"
    val accuracyText = fix.accuracyM?.let { "${"%.0f".format(it)}m" } ?: "unknown"
    val statusText =
        if (fix.accepted) {
            "$ageText, accepted, accuracy $accuracyText"
        } else {
            "$ageText, DISCARDED (accuracy $accuracyText), ${fix.consecutiveDiscards} in a row"
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .semantics(mergeDescendants = true) {},
    ) {
        Text(
            "Last GPS fix:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (fix.accepted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DebugRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .semantics(mergeDescendants = true) {},
    ) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
