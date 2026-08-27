package com.boldexplorer.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boldexplorer.audio.AudioLogEntry
import com.boldexplorer.audio.spanAnnouncement
import com.boldexplorer.audio.spanButtonLabel
import com.boldexplorer.audio.spanElapsedText
import com.boldexplorer.location.RawFixEvent
import com.boldexplorer.shared.location.detailAt
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
    val audioTestStatus by viewModel.audioTestStatus.collectAsStateWithLifecycle()
    val useGnss by viewModel.useGnss.collectAsStateWithLifecycle()
    var showNewLogDialog by remember { mutableStateOf(false) }
    val logEntries by viewModel.logEntries.collectAsStateWithLifecycle()
    val logStatus by viewModel.logStatus.collectAsStateWithLifecycle()
    val showMarkerDialog by viewModel.showMarkerDialog.collectAsStateWithLifecycle()
    val showSpanDialog by viewModel.showSpanDialog.collectAsStateWithLifecycle()
    val openSpan by viewModel.openSpan.collectAsStateWithLifecycle()
    val lastRawFix by viewModel.lastRawFix.collectAsStateWithLifecycle()
    val accuracyHapticsEnabled by viewModel.accuracyHapticsEnabled.collectAsStateWithLifecycle()
    val shadowMatchEnabled by viewModel.shadowMatchEnabled.collectAsStateWithLifecycle()
    val shadowMatch by viewModel.shadowMatch.collectAsStateWithLifecycle()

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

        // Marked sections: the interval counterpart to IMPORTANT!. On the 2026-08-12 walk the
        // intervals ("stop start"/"stop end") carried more than the point markers did, and were
        // bracketed by hand.
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Button(
                onClick = { viewModel.onSpanButtonPressed() },
                modifier = Modifier.fillMaxWidth(),
                colors =
                    if (openSpan != null) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
            ) {
                Text(spanButtonLabel(openSpan))
            }
            openSpan?.let { span ->
                // Elapsed time ticks, so it is plain text read on focus — never a live region.
                Text(
                    "Open for ${spanElapsedText(nowMs - span.startedAtMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Announcement(spanAnnouncement(openSpan))
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

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("GPS degradation", style = MaterialTheme.typography.titleSmall)

                // #62: switchback matching cannot be falsified under good GPS, because the nearest
                // arm is then also the correct arm. These presets manufacture the ambiguity.
                val degradation by viewModel.degradationConfig.collectAsStateWithLifecycle()
                val degrading = degradation.isActiveAt(nowMs)
                val detail = degradation.detailAt(nowMs)

                // Plain text, and nothing on this screen announces degradation state. See the note
                // on detailAt(): DebugScreen recomposes at 2 Hz, so a live region here re-announces
                // at 2 Hz whatever its string says. Reported accuracy already shows when it is armed.
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (degrading) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )

                if (degrading) {
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { viewModel.disarmDegradation() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Stop degrading GPS")
                    }
                } else {
                    // Drift is aimed perpendicular to travel, because pushing a fix *across* the
                    // trail is what lands it on the wrong arm of a switchback. Aiming it along the
                    // trail mostly just shifts along-track position, which is not the hazard.
                    val bearing = (heading?.trueNorth ?: heading?.magnetic ?: 0.0) + 90.0
                    DebugViewModel.DegradationPreset.entries.forEach { preset ->
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = { viewModel.armDegradation(preset, bearing) },
                            modifier =
                                Modifier.fillMaxWidth().semantics {
                                    // a11y: the visible label names the scenario but not what
                                    // arming it does — the drift direction and the 30-minute
                                    // expiry are the parts you need before pressing, not after.
                                    contentDescription =
                                        "Degrade GPS: ${preset.label}, drifting right of travel, for 30 minutes"
                                },
                        ) {
                            Text(preset.label)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Debug diagnostic for #23: keeps buzzing on whichever screen is open (unlike
                // reading this Debug screen, which was itself suspected of masking the freeze by
                // forcing a recomposition when you switch to it and back).
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                // a11y: explains the buzz pattern, which the visible label + switch
                                // alone don't convey.
                                contentDescription =
                                    "Accuracy haptics ${if (accuracyHapticsEnabled) "on" else "off"}: " +
                                    "every 5 seconds, one buzz if fixes were accepted, three quick " +
                                    "buzzes if any were discarded"
                            },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Accuracy haptics", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = accuracyHapticsEnabled,
                        onCheckedChange = { viewModel.setAccuracyHapticsEnabled(it) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Trail-match logging (ADR 0001, S4). Temporary: this readout exists to confirm the matcher
        // is alive during a field walk. The log file, not this screen, is the record. The switch
        // governs the log only — since S5a the matcher itself always runs while following, because
        // wrong-way detection reads it.
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Trail match logging", style = MaterialTheme.typography.titleSmall)
                    Switch(
                        checked = shadowMatchEnabled,
                        onCheckedChange = { viewModel.setShadowMatchEnabled(it) },
                    )
                }
                // Stated as visible text rather than a contentDescription so it reaches everyone,
                // and because the switch's own state is already announced natively.
                Text(
                    "Logs one record per GPS fix while following a trail, so a walk can be " +
                        "replayed later against different constants. Roughly 2 MB per hour.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(8.dp))

                // Refreshed at most every 5 seconds, and deliberately not a live region: at that
                // cadence an announcement would talk over guidance for the whole walk.
                val match = shadowMatch
                if (!shadowMatchEnabled) {
                    Text("Logging off. Matching still runs.", style = MaterialTheme.typography.bodyMedium)
                } else if (match == null) {
                    Text("Waiting for a trail follow to start.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("State: ${match.state}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Along trail: ${match.alongM?.let { "%.0f m".format(it) } ?: "not acquired"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Off trail: ${match.crossM?.let { "%.1f m".format(it) } ?: "no candidate"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Travelled: ${"%.0f m".format(match.travelledM)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Reason: ${match.disposition}", style = MaterialTheme.typography.bodySmall)
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

        // #114: exercises rare-mode-pause vs. frequent-mode-silence-filler behavior without needing
        // GPS/navigation active. Do not run while a real navigation/alignment session is running —
        // AudioEngine is a shared singleton and both would fight over the same track.
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Audio test", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    "5 beeps 5s apart (rare mode), then 5 beeps 2s apart (frequent mode). " +
                        "Not while GPS navigation is running.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                audioTestStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Announcement(it)
                }
                Button(
                    onClick = { viewModel.testAudioModes() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Run Audio Test")
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
                        onClick = { showNewLogDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("New Log")
                    }
                    if (showNewLogDialog) {
                        AlertDialog(
                            onDismissRequest = { showNewLogDialog = false },
                            title = { Text("Start new log?") },
                            text = { Text("This will clear the current log.") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        viewModel.newLog()
                                        showNewLogDialog = false
                                    },
                                ) {
                                    Text("New Log")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showNewLogDialog = false },
                                ) {
                                    Text("Cancel")
                                }
                            },
                        )
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

    if (showSpanDialog) {
        SpanLabelDialog(
            onConfirm = { label -> viewModel.confirmSpan(label) },
            onDismiss = { viewModel.dismissSpan() },
        )
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

/**
 * An invisible node that speaks [text] when — and only when — it changes.
 *
 * This has to be its own composable rather than an inline `Modifier.semantics`. `DebugScreen` reads
 * a 2 Hz clock in its own body, so its whole restart scope recomposes twice a second and any
 * semantics block written inline re-emits its node at that rate; TalkBack announces on node update,
 * not on string change, so it speaks again every time. Taking the string as a parameter makes this
 * skippable, so an unchanged [text] does not re-emit anything.
 *
 * The GPS degradation readout learned this the hard way: stabilising its announcement string left it
 * announcing once a second regardless, and it was removed rather than fixed.
 */
@Composable
private fun Announcement(text: String) {
    Text(
        "",
        modifier =
            Modifier.size(1.dp).semantics {
                // a11y: the span button's own label carries the state, and a label change on an
                // unfocused button is silent; this is the only thing that reports open and close.
                contentDescription = text
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/**
 * Names a marked section before opening it.
 *
 * Shorter than [MarkerNoteDialog] on purpose: this is pressed at the *start* of something, when the
 * interesting events have not happened yet, so there is no recent-events list worth showing. The
 * label wants to be a couple of words, because it is read back on the button as "End: <label>".
 */
@Composable
private fun SpanLabelDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start marked section") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Name it (e.g. stopped, bad gps)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(label) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
