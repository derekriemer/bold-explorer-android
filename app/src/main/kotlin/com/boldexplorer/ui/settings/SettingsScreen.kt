package com.boldexplorer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
        modifier =
            Modifier
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
        SettingGroupLabel("Units")
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioOption(
                label = "Metric",
                selected = settings.units == Units.METRIC,
                onClick = { viewModel.setUnits(Units.METRIC) },
                modifier = Modifier.weight(1f),
            )
            RadioOption(
                label = "Imperial",
                selected = settings.units == Units.IMPERIAL,
                onClick = { viewModel.setUnits(Units.IMPERIAL) },
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider()

        // Bearing display
        SettingGroupLabel("Bearing Display")
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioOption(
                label = "Relative",
                selected = settings.bearingDisplayMode == BearingDisplayMode.RELATIVE,
                onClick = { viewModel.setBearingMode(BearingDisplayMode.RELATIVE) },
                modifier = Modifier.weight(1f),
            )
            RadioOption(
                label = "Clock",
                selected = settings.bearingDisplayMode == BearingDisplayMode.CLOCK,
                onClick = { viewModel.setBearingMode(BearingDisplayMode.CLOCK) },
                modifier = Modifier.weight(1f),
            )
            RadioOption(
                label = "Compass",
                selected = settings.bearingDisplayMode == BearingDisplayMode.TRUE_NORTH,
                onClick = { viewModel.setBearingMode(BearingDisplayMode.TRUE_NORTH) },
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider()

        SwitchRow(
            label = "Spoken Guidance",
            checked = settings.spokenGuidanceEnabled,
            onCheckedChange = { viewModel.setSpokenGuidance(it) },
        )

        HorizontalDivider()

        SwitchRow(
            label = "Beacon Cues",
            checked = settings.beaconCuesEnabled,
            onCheckedChange = { viewModel.setBeaconCues(it) },
        )

        HorizontalDivider()

        // Minimal toggle for #14 — a faster-access HUD control is separately tracked in #15/#20.
        SwitchRow(
            label = "Silence Mode",
            checked = settings.absoluteSilenceEnabled,
            onCheckedChange = { viewModel.setAbsoluteSilence(it) },
            // a11y: explains the sticky, no-exceptions-except-explicit-requests behavior, which
            // the label + native on/off state don't convey on their own.
            description =
                if (settings.absoluteSilenceEnabled) {
                    "Silence mode, on. No automatic speech, earcons, or announcements until turned off. Explicit requests like Read alignment now still respond."
                } else {
                    null
                },
        )

        HorizontalDivider()

        // True north
        SwitchRow(
            label = "Use True North",
            checked = settings.compassMode == CompassMode.TRUE,
            onCheckedChange = { viewModel.setCompassMode(if (it) CompassMode.TRUE else CompassMode.MAGNETIC) },
            // a11y: explains what "off" actually does (falls back to magnetic north), which the
            // label + native on/off state don't convey.
            description = if (settings.compassMode == CompassMode.TRUE) null else "Use true north, off, using magnetic north",
        )

        HorizontalDivider()

        // Duck audio
        SwitchRow(
            label = "Duck Music During Beacons",
            checked = settings.duckAudioEnabled,
            onCheckedChange = { viewModel.setDuckAudio(it) },
        )

        HorizontalDivider()
    }
}

@Composable
private fun SettingGroupLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RadioOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .selectable(
                    selected = selected,
                    onClick = onClick,
                    role = Role.RadioButton,
                ).padding(vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .toggleable(
                    value = checked,
                    onValueChange = onCheckedChange,
                    role = Role.Switch,
                )
                .then(
                    if (description != null) {
                        // a11y: only pass description when the default (native switch state +
                        // merged label) needs a supplementary explanation — see call site.
                        Modifier.semantics { contentDescription = description }
                    } else {
                        Modifier
                    },
                ),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
