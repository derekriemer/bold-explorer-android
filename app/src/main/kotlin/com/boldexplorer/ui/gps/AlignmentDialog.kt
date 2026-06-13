package com.boldexplorer.ui.gps

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.boldexplorer.shared.navigation.BearingComputer
import kotlinx.coroutines.delay

/**
 * Modal that owns all bearing-alignment controls, replacing the inline alignment row on the GPS
 * screen. The live delta readout re-speaks every 5 s, but **only while TalkBack focus rests on it**,
 * so the modal never talks over the rest of the UI. Dismissing the modal does not stop alignment —
 * only the explicit "Stop alignment" action does.
 */
@Composable
fun AlignmentDialog(
    state: GpsUiState,
    onAction: (GpsAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var showBearingInput by remember { mutableStateOf(false) }
    var bearingInput by remember { mutableStateOf("") }
    var bearingInputError by remember { mutableStateOf(false) }
    var deltaFocused by remember { mutableStateOf(false) }

    // Focus-gated ticker: TalkBack reads the delta once when focus lands (via the polite live region);
    // thereafter the app re-speaks it every 5 s for as long as focus stays put. Losing focus or
    // alignment going inactive cancels the loop.
    LaunchedEffect(deltaFocused, state.alignmentActive) {
        if (deltaFocused && state.alignmentActive) {
            while (true) {
                delay(5_000L)
                onAction(GpsAction.SpeakAlignmentDelta)
            }
        }
    }

    val headingCardinal = state.headingDeg?.let { BearingComputer.toCardinal(it) } ?: "—"
    val headingDegText = state.headingDeg?.let { "${"%.0f".format(it)}°" } ?: "—"
    val targetBearingText = "%.0f".format(state.alignmentBearingDeg ?: 0.0)
    val deltaText =
        state.alignmentRelativeDeg?.let { BearingComputer.toAlignmentRelative(it) }
            ?: "Waiting for compass…"

    val deltaActions =
        listOf(
            CustomAccessibilityAction("Align to compass heading") {
                onAction(GpsAction.ResetAlignment)
                true
            },
            CustomAccessibilityAction("Align to target") {
                onAction(GpsAction.AlignToTarget)
                true
            },
            CustomAccessibilityAction("Stop alignment") {
                onAction(GpsAction.StopAlignment)
                onDismiss()
                true
            },
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alignment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Heading: $headingCardinal ($headingDegText) · ${state.compassModeLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                "Heading: $headingCardinal $headingDegText, ${state.compassModeLabel}"
                        },
                )

                Text(
                    "Alignment: $targetBearingText° — $deltaText",
                    style = MaterialTheme.typography.titleMedium,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusable()
                            .onFocusChanged { deltaFocused = it.isFocused }
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = "Alignment target $targetBearingText degrees, $deltaText"
                                customActions = deltaActions
                            },
                )

                // Visible counterparts to the custom actions on the delta readout.
                TextButton(
                    onClick = { onAction(GpsAction.ResetAlignment) },
                    modifier =
                        Modifier.semantics { contentDescription = "Align to current compass heading" },
                ) { Text("Align to compass heading") }

                if (state.bearingDeg != null) {
                    TextButton(
                        onClick = { onAction(GpsAction.AlignToTarget) },
                        modifier =
                            Modifier.semantics {
                                contentDescription =
                                    state.targetName?.let { "Align to $it" } ?: "Align to current target"
                            },
                    ) { Text(state.targetName?.let { "Align to $it" } ?: "Align to target") }
                }

                TextButton(
                    onClick = {
                        bearingInput = ""
                        bearingInputError = false
                        showBearingInput = true
                    },
                    modifier = Modifier.semantics { contentDescription = "Enter a bearing in degrees" },
                ) { Text("Set bearing manually") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAction(GpsAction.StopAlignment)
                    onDismiss()
                },
                modifier = Modifier.semantics { contentDescription = "Stop alignment guidance" },
            ) { Text("Stop alignment") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Close, keep alignment running" },
            ) { Text("Close") }
        },
    )

    // Manual numeric bearing entry — a plain nested form (no custom actions).
    if (showBearingInput) {
        AlertDialog(
            onDismissRequest = { showBearingInput = false },
            title = { Text("Set Alignment Bearing") },
            text = {
                Column {
                    OutlinedTextField(
                        value = bearingInput,
                        onValueChange = {
                            bearingInput = it
                            bearingInputError = false
                        },
                        label = { Text("Bearing (0–360°)") },
                        isError = bearingInputError,
                        singleLine = true,
                        modifier = Modifier.semantics { contentDescription = "Bearing in degrees, 0 to 360" },
                    )
                    if (bearingInputError) {
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
                    val deg = bearingInput.toDoubleOrNull()
                    if (deg == null || deg < 0 || deg > 360) {
                        bearingInputError = true
                    } else {
                        onAction(GpsAction.SetAlignmentBearing(deg))
                        showBearingInput = false
                    }
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showBearingInput = false }) { Text("Cancel") }
            },
        )
    }
}
