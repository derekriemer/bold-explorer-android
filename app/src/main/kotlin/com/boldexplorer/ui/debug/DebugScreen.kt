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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DebugScreen(
    paddingValues: PaddingValues,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val location by viewModel.location.collectAsStateWithLifecycle()
    val heading by viewModel.heading.collectAsStateWithLifecycle()
    val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()
    val accuracyBeaconEnabled by viewModel.accuracyBeaconEnabled.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .padding(paddingValues)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Debug",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )

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
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Accuracy beacon ${if (accuracyBeaconEnabled) "on" else "off"}: beeps on every GPS update, pitch maps to fix quality"
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
                    "Current accuracy: ${location?.accuracy?.let { "${"%.0f".format(it)} m → ${"%.0f".format(880.0 - (880.0 - 220.0) * (it.coerceIn(0.0, 30.0) / 30.0))} Hz" } ?: "no fix"}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Button(
                    onClick = { viewModel.testAccuracyBeacon() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Play accuracy beacon tone for current GPS accuracy" },
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Export all waypoints to Downloads folder as G P X file" },
                ) {
                    Text("Export Waypoints GPX")
                }
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
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
