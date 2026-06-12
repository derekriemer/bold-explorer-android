package com.boldexplorer.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Returns the current toast string to display. Automatically clears after [durationMs].
 * Call once per toast source (e.g. `viewModel.toast`, `viewModel.exportStatus`).
 */
@Composable
fun useToast(
    source: String?,
    onClear: () -> Unit,
    durationMs: Long = 2000L,
): String? {
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(source) {
        if (source != null) {
            message = source
            onClear()
            delay(durationMs)
            message = null
        }
    }
    return message
}

/** Renders a live-region toast Text, or nothing when [message] is null. */
@Composable
fun ToastMessage(message: String?) {
    message?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .padding(horizontal = 16.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = it
                    },
        )
    }
}
