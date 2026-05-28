package com.boldexplorer.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.boldexplorer.ui.collections.CollectionsScreen
import com.boldexplorer.ui.gps.GpsScreen
import com.boldexplorer.ui.settings.SettingsScreen
import com.boldexplorer.ui.trails.TrailsScreen
import com.boldexplorer.ui.waypoints.WaypointsScreen

private sealed class Screen(val route: String, val label: String) {
    object Gps : Screen("gps", "GPS")
    object Waypoints : Screen("waypoints", "Waypoints")
    object Trails : Screen("trails", "Trails")
    object Collections : Screen("collections", "Collections")
    object Settings : Screen("settings", "Settings")
}

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val screens = listOf(
        Screen.Gps,
        Screen.Waypoints,
        Screen.Trails,
        Screen.Collections,
        Screen.Settings,
    )

    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination
                    screens.forEach { screen ->
                        val selected = currentDestination?.hierarchy
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
                            modifier = Modifier.semantics {
                                contentDescription = "${screen.label} tab${if (selected) ", selected" else ""}"
                            },
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(navController = navController, startDestination = Screen.Gps.route) {
                composable(Screen.Gps.route) { GpsScreen(innerPadding) }
                composable(Screen.Waypoints.route) { WaypointsScreen(innerPadding) }
                composable(Screen.Trails.route) { TrailsScreen(innerPadding) }
                composable(Screen.Collections.route) { CollectionsScreen(innerPadding) }
                composable(Screen.Settings.route) { SettingsScreen(innerPadding) }
            }
        }
    }
}
