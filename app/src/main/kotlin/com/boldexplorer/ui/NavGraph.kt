package com.boldexplorer.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.boldexplorer.BuildConfig
import com.boldexplorer.ui.collections.CollectionsScreen
import com.boldexplorer.ui.debug.DebugScreen
import com.boldexplorer.ui.gps.GpsRoute
import com.boldexplorer.ui.gps.GpsViewModel
import com.boldexplorer.ui.settings.SettingsScreen
import com.boldexplorer.ui.trails.TrailsScreen
import com.boldexplorer.ui.waypoints.WaypointsScreen

private sealed class Screen(
    val route: String,
    val label: String,
) {
    object Gps : Screen("gps", "GPS")

    object Waypoints : Screen("waypoints", "Waypoints")

    object Trails : Screen("trails", "Trails")

    object Collections : Screen("collections", "Collections")

    object Settings : Screen("settings", "Settings")

    object Debug : Screen("debug", "Debug")
}

@Composable
fun NavGraph() {
    val gpsVm: GpsViewModel = hiltViewModel()
    val announcement by gpsVm.announcement.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val screens =
        buildList {
            add(Screen.Gps)
            add(Screen.Waypoints)
            add(Screen.Trails)
            add(Screen.Collections)
            add(Screen.Settings)
            if (BuildConfig.SHOW_DEBUG_FEATURES) add(Screen.Debug)
        }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination
                    screens.forEach { screen ->
                        val selected =
                            currentDestination
                                ?.hierarchy
                                ?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            label = { Text(screen.label) },
                            icon = {}, // Accessible label is sufficient; icon would need extended icons dep
                        )
                    }
                }
            },
        ) { innerPadding ->
            // App-wide live region — always in composition regardless of active tab.
            // 1×1 dp invisible text; TalkBack reads it on every change via liveRegion.
            Text(
                text = announcement.text,
                modifier =
                    Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            // a11y: TalkBack doesn't reliably re-announce on `text` alone for this
                            // live-region node; contentDescription is the actual delivery mechanism.
                            // announcement carries a sequence number (see LiveRegionAnnouncement)
                            // so two consecutive identical announcements still change this state.
                            contentDescription = announcement.text
                        },
            )
            NavHost(navController = navController, startDestination = Screen.Gps.route) {
                composable(Screen.Gps.route) { GpsRoute(innerPadding, gpsVm) }
                composable(Screen.Waypoints.route) { WaypointsScreen(innerPadding) }
                composable(Screen.Trails.route) { TrailsScreen(innerPadding) }
                composable(Screen.Collections.route) { CollectionsScreen(innerPadding) }
                composable(Screen.Settings.route) { SettingsScreen(innerPadding) }
                composable(Screen.Debug.route) { DebugScreen(innerPadding) }
            }
        }
    }
}
