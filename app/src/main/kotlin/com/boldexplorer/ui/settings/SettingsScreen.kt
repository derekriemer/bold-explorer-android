package com.boldexplorer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boldexplorer.shared.settings.BearingDisplayMode
import com.boldexplorer.shared.settings.CompassMode
import com.boldexplorer.shared.settings.Units

@Composable
fun SettingsScreen(
    paddingValues: PaddingValues,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .padding(paddingValues)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )

        HorizontalDivider()

        // Units
        SettingRow(label = "Units") {
            TextButton(
                onClick = { viewModel.setUnits(Units.METRIC) },
                modifier = Modifier.semantics { contentDescription = "Use metric units" },
            ) {
                Text(
                    "Metric",
                    color = if (settings.units == Units.METRIC)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
            }
            TextButton(
                onClick = { viewModel.setUnits(Units.IMPERIAL) },
                modifier = Modifier.semantics { contentDescription = "Use imperial units" },
            ) {
                Text(
                    "Imperial",
                    color = if (settings.units == Units.IMPERIAL)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        HorizontalDivider()

        // Bearing display
        SettingRow(label = "Bearing Display") {
            TextButton(
                onClick = { viewModel.setBearingMode(BearingDisplayMode.RELATIVE) },
                modifier = Modifier.semantics { contentDescription = "Show bearing as relative degrees" },
            ) {
                Text(
                    "Relative",
                    color = if (settings.bearingDisplayMode == BearingDisplayMode.RELATIVE)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
            }
            TextButton(
                onClick = { viewModel.setBearingMode(BearingDisplayMode.CLOCK) },
                modifier = Modifier.semantics { contentDescription = "Show bearing as clock position" },
            ) {
                Text(
                    "Clock",
                    color = if (settings.bearingDisplayMode == BearingDisplayMode.CLOCK)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
            }
            TextButton(
                onClick = { viewModel.setBearingMode(BearingDisplayMode.TRUE_NORTH) },
                modifier = Modifier.semantics { contentDescription = "Show bearing as absolute degrees" },
            ) {
                Text(
                    "Absolute",
                    color = if (settings.bearingDisplayMode == BearingDisplayMode.TRUE_NORTH)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        HorizontalDivider()

        // Audio cues
        SwitchRow(
            label = "Audio Cues",
            checked = settings.audioCuesEnabled,
            onCheckedChange = { viewModel.setAudioCues(it) },
            contentDescription = if (settings.audioCuesEnabled) "Audio cues enabled" else "Audio cues disabled",
        )

        HorizontalDivider()

        // True north
        SwitchRow(
            label = "Use True North",
            checked = settings.compassMode == CompassMode.TRUE,
            onCheckedChange = { viewModel.setCompassMode(if (it) CompassMode.TRUE else CompassMode.MAGNETIC) },
            contentDescription = if (settings.compassMode == CompassMode.TRUE)
                "True north enabled" else "True north disabled, using magnetic north",
        )

        HorizontalDivider()
    }
}

@Composable
private fun SettingRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { this.contentDescription = contentDescription },
        )
    }
}
