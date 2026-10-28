// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import radio.kd0cq.x6100.ui.screens.ConnectScreen
import radio.kd0cq.x6100.ui.screens.ControlsScreen
import radio.kd0cq.x6100.ui.screens.Ft8Screen
import radio.kd0cq.x6100.ui.screens.LogScreen
import radio.kd0cq.x6100.ui.screens.PairingScreen
import radio.kd0cq.x6100.ui.screens.SettingsScreen
import radio.kd0cq.x6100.ui.screens.SpotsScreen
import radio.kd0cq.x6100.ui.screens.WaterfallScreen

/** Where the app goes. Connect and Pairing are the way in; the rest is the radio. */
enum class Route(val path: String, val label: String) {
    CONNECT("connect", "Connect"),
    PAIRING("pairing", "Pair"),
    WATERFALL("waterfall", "Waterfall"),
    CONTROLS("controls", "Controls"),
    FT8("ft8", "FT8"),
    SPOTS("spots", "Spots"),
    LOG("log", "Log"),
    SETTINGS("settings", "Setup");

    companion object {
        val bottomBar = listOf(WATERFALL, CONTROLS, FT8, SPOTS, LOG)
    }
}

@Composable
fun AppNav(viewModel: RadioViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val onBottomBar = Route.bottomBar.any { it.path == current?.route }

    Scaffold(
        topBar = {
            // The three screens off the bottom bar: this is the only way to them.
            if (onBottomBar) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = Route.entries.firstOrNull { it.path == current?.route }?.label.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { nav.navigate(Route.PAIRING.path) }) { Text("Pair") }
                        TextButton(onClick = { nav.navigate(Route.SETTINGS.path) }) { Text("Setup") }
                    }
                }
            }
        },
        bottomBar = {
            if (onBottomBar) {
                NavigationBar {
                    Route.bottomBar.forEach { route ->
                        val selected = current?.hierarchy?.any { it.route == route.path } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(route.path) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Text(route.label.take(1)) },
                            label = { Text(route.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Route.CONNECT.path,
            modifier = Modifier.padding(padding),
        ) {
            composable(Route.CONNECT.path) { ConnectScreen(viewModel, onPaired = { nav.navigate(Route.WATERFALL.path) }) }
            composable(Route.PAIRING.path) { PairingScreen(viewModel, onDone = { nav.popBackStack() }) }
            composable(Route.WATERFALL.path) {
                WaterfallScreen(viewModel, onPair = { nav.navigate(Route.PAIRING.path) })
            }
            composable(Route.CONTROLS.path) { ControlsScreen(viewModel) }
            composable(Route.FT8.path) { Ft8Screen(viewModel) }
            composable(Route.SPOTS.path) { SpotsScreen(viewModel) }
            composable(Route.LOG.path) { LogScreen(viewModel) }
            composable(Route.SETTINGS.path) { SettingsScreen(viewModel) }
        }
    }
}
