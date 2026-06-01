package com.boldexplorer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.boldexplorer.ui.NavGraph
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // After foreground/notification grants resolve, check if we still need background.
        if (hasAnyForegroundLocationPermission() && !hasBackgroundLocationPermission()) {
            promptForBackgroundLocation()
        }
    }

    // Separate launcher for background location — Android requires it to be requested alone.
    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result handled in onResume */ }

    // Prevents showing the rationale dialog more than once per session (e.g. on repeated onResume).
    private var backgroundLocationPrompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NavGraph()
        }
        requestStartupPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Re-check on resume so that returning from the system settings page works.
        if (!backgroundLocationPrompted &&
            hasAnyForegroundLocationPermission() &&
            !hasBackgroundLocationPermission()
        ) {
            promptForBackgroundLocation()
        }
    }

    private fun requestStartupPermissions() {
        val permissions = buildList {
            if (!hasAnyForegroundLocationPermission()) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else if (!hasBackgroundLocationPermission()) {
            // Foreground already granted from a previous run — go straight to background prompt.
            promptForBackgroundLocation()
        }
    }

    /**
     * Shows a plain dialog explaining why "Allow all the time" is needed, then launches the
     * system background-location request.  On Android 11+ the system redirects to Settings;
     * on Android 10 it shows an in-process dialog with an "Allow all the time" option.
     *
     * Background location is only a separate permission on API 29+.
     */
    private fun promptForBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        backgroundLocationPrompted = true
        android.app.AlertDialog.Builder(this)
            .setTitle("Background location needed")
            .setMessage(
                "Bold Explorer needs \"Allow all the time\" location access so it can keep " +
                "guiding you when the screen is off. Tap OK, then choose " +
                "\"Allow all the time\" on the next screen."
            )
            .setPositiveButton("OK") { _, _ ->
                backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun hasAnyForegroundLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
}
