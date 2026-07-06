package com.boldexplorer.ui.gps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
 * screen. TalkBack reads the delta once when focus lands on it (ordinary contentDescription, no live
 * region — so it never spams as the compass drifts). For continuous guidance the user opts in via the
 * "Automatically read alignment every 5 seconds" action/toggle; "Read alignment now" gives a one-shot.
 *
 * (We deliberately do NOT gate the ticker on input focus: Compose `onFocusChanged` tracks input focus,
 * not TalkBack accessibility focus, so a focus-gated loop never fires for the screen-reader users this
 * screen is built for. An explicit opt-in toggle is the reliable equivalent.)
 *
 * Dismissing the modal does not stop alignment — only the explicit "Stop alignment" action does.
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
    var autoRead by remember { mutableStateOf(false) }

    val headingCardinal = state.headingDeg?.let { BearingComputer.toCardinal(it) } ?: "—"
    val headingDegText = state.headingDeg?.let { "${"%.0f".format(it)}°" } ?: "—"
    val targetBearingText = "%.0f".format(state.alignmentBearingDeg ?: 0.0)
    val deltaText =
        state.alignmentRelativeDeg?.let { BearingComputer.toAlignmentRelative(it) }
            ?: "Waiting for compass…"

    var autoReadAnnouncement by remember { mutableStateOf("") }
    val latestDeltaText by rememberUpdatedState(deltaText)

    // Foreground spoken guidance intentionally stays quiet while this dialog is visible. Use an
    // opt-in live region, changing its raw description each tick even when the rounded delta is stable.
    LaunchedEffect(autoRead, state.alignmentActive) {
        if (autoRead && state.alignmentActive) {
            var tick = 0
            while (true) {
                tick++
                autoReadAnnouncement = latestDeltaText + if (tick % 2 == 0) "" else "."
                delay(5_000L)
            }
        } else {
            autoReadAnnouncement = ""
        }
    }

    val deltaActions =
        listOf(
            CustomAccessibilityAction("Read alignment now") {
                onAction(GpsAction.SpeakAlignmentDelta)
                true
            },
            if (autoRead) {
                CustomAccessibilityAction("Stop automatic alignment readout") {
                    autoRead = false
                    true
                }
            } else {
                CustomAccessibilityAction("Automatically read alignment every 5 seconds") {
                    autoRead = true
                    true
                }
            },
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
                )

                Text(
                    "Alignment: $targetBearingText° — $deltaText",
                    style = MaterialTheme.typography.titleMedium,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics {
                                customActions = deltaActions
                            },
                )

                Text(
                    text = autoReadAnnouncement,
                    modifier =
                        Modifier
                            .size(1.dp)
                            .alpha(0f)
                            .semantics {
                                liveRegion = LiveRegionMode.Assertive
                            },
                )

                // Visible toggle for the opt-in 5 s readout (counterpart to the custom action).
                TextButton(
                    onClick = { autoRead = !autoRead },
                    modifier =
                        Modifier.semantics {
                            // a11y: names the action tapping performs; the visible label only shows
                            // current state ("Auto-read: On/Off"), not what tapping it will do.
                            contentDescription =
                                if (autoRead) {
                                    "Stop automatic alignment readout"
                                } else {
                                    "Automatically read alignment every 5 seconds"
                                }
                        },
                ) { Text(if (autoRead) "Auto-read: On" else "Auto-read: Off") }

                // Visible counterparts to the custom actions on the delta readout.
                TextButton(
                    onClick = { onAction(GpsAction.ResetAlignment) },
                ) { Text("Align to compass heading") }

                if (state.bearingDeg != null) {
                    TextButton(
                        onClick = { onAction(GpsAction.AlignToTarget) },
                    ) { Text(state.targetName?.let { "Align to $it" } ?: "Align to target") }
                }

                TextButton(
                    onClick = {
                        bearingInput = ""
                        bearingInputError = false
                        showBearingInput = true
                    },
                ) { Text("Set bearing manually") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAction(GpsAction.StopAlignment)
                    onDismiss()
                },
            ) { Text("Stop alignment") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                // a11y: dismissing this dialog does NOT stop alignment (only the "Stop alignment"
                // action does) — visible "Close" alone would wrongly suggest it does.
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
