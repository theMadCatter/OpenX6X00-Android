// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Before: the bottom bar was five letters (W C F S L) and the radio was split into a waterfall
// page and a controls page. The face is now one screen. Screen, FT8, Spots and Log stay one
// tap away, with a vector icon and a short label. Pairing and Setup stay off that bar so the
// operating row is the radio, not the connection.
//
// The rail and the bottom bar used to be two copies of the navigation host, chosen from the
// configuration orientation. That flag flips before the window has finished resizing, so a
// landscape-to-portrait turn showed the portrait chrome around the landscape face: the keys
// sat under the band plan and the bottom bar and PTT were missing until the window caught up.
// One host stays composed. The chrome and the face both read [LocalWideWindow], which is the
// window's own width against its height, so they change on the same frame the size does.

package radio.kd0cq.x6100.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import radio.kd0cq.x6100.ui.face.RadioFace
import radio.kd0cq.x6100.ui.face.RadioIcons
import radio.kd0cq.x6100.ui.screens.ConnectScreen
import radio.kd0cq.x6100.ui.screens.CwScreen
import radio.kd0cq.x6100.ui.screens.Ft8Screen
import radio.kd0cq.x6100.ui.screens.Js8Screen
import radio.kd0cq.x6100.ui.screens.WeFaxScreen
import radio.kd0cq.x6100.ui.screens.LogScreen
import radio.kd0cq.x6100.ui.screens.PairingScreen
import radio.kd0cq.x6100.ui.screens.RadioScreen
import radio.kd0cq.x6100.ui.screens.ScreenMirrorScreen
import radio.kd0cq.x6100.ui.screens.SettingsScreen
import radio.kd0cq.x6100.ui.screens.SpotsScreen
import radio.kd0cq.x6100.ui.theme.RfGuru

/**
 * True while the window itself is wider than it is tall. The navigation graph is built once,
 * so a value closed over in a destination would stay at whatever the first frame measured.
 * The destination reads this local instead, and the provider updates it with the window.
 */
val LocalWideWindow = compositionLocalOf { false }

/** Where the app goes. Connect and Pairing are the way in; Radio is the operating face. */
enum class Route(val path: String, val label: String, val icon: ImageVector?) {
    CONNECT("connect", "Connect", RadioIcons.Connect),
    PAIRING("pairing", "Pair", RadioIcons.Link),
    RADIO("radio", "Radio", RadioIcons.Radio),
    SCREEN("screen", "Screen", RadioIcons.Screen),
    FT8("ft8", "FT8", RadioIcons.Ft8),
    SPOTS("spots", "Spots", RadioIcons.Spots),
    LOG("log", "Log", RadioIcons.Log),
    SETTINGS("settings", "Setup", RadioIcons.Setup);

    companion object {
        val operating = listOf(RADIO, SCREEN, FT8, SPOTS, LOG)
        val railExtra = listOf(PAIRING, SETTINGS, CONNECT)
    }
}

@Composable
fun AppNav(viewModel: RadioViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val onOperating = Route.operating.any { it.path == current?.route }
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = RfGuru.barBottom,
        selectedTextColor = RfGuru.goldText,
        indicatorColor = RfGuru.gold,
        unselectedIconColor = RfGuru.dim,
        unselectedTextColor = RfGuru.dim,
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(RfGuru.bg)) {
        val wide = RadioFace.wideWindow(maxWidth.value, maxHeight.value)
        // The side rail is the landscape operating chrome. Connect, Pair and Setup keep the
        // plain page in either orientation, the same as the scaffold did.
        val rail = wide && onOperating
        CompositionLocalProvider(LocalWideWindow provides wide) {
            Row(
                Modifier
                    .fillMaxSize()
                    .then(if (rail) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier),
            ) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (!rail && onOperating) {
                        // The bar used to start at y = 0, under the status clock. The inset is part of
                        // the bar's own height so "OpenX6X00" sits below 9:23, not through it.
                        Row(
                            Modifier.background(RfGuru.bar).statusBarsPadding(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "OpenX6X00",
                                color = RfGuru.gold,
                                modifier = Modifier.weight(1f).padding(start = 12.dp),
                            )
                            Route.railExtra.forEach { route ->
                                IconButton(onClick = {
                                    nav.navigate(route.path) { launchSingleTop = true }
                                }) {
                                    Icon(route.icon ?: RadioIcons.Radio, contentDescription = route.label, tint = RfGuru.goldText)
                                }
                            }
                        }
                    }
                    NavHost(
                        navController = nav,
                        startDestination = Route.CONNECT.path,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(hostInsets(rail, onOperating, current?.route)),
                    ) { screens(nav, viewModel) }
                    if (!rail && onOperating) {
                        NavigationBar(containerColor = RfGuru.bar) {
                            Route.operating.forEach { route ->
                                val selected = current?.hierarchy?.any { it.route == route.path } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { go(nav, route) },
                                    icon = { Icon(route.icon ?: RadioIcons.Radio, contentDescription = route.label) },
                                    label = { Text(route.label) },
                                    colors = colors,
                                )
                            }
                        }
                    }
                }
                if (rail) {
                    Column(
                        Modifier
                            .width(52.dp)
                            .fillMaxHeight()
                            .background(RfGuru.bar)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        (Route.operating + Route.railExtra).forEach { route ->
                            val selected = current?.hierarchy?.any { it.route == route.path } == true
                            IconButton(onClick = { go(nav, route) }) {
                                Icon(
                                    route.icon ?: RadioIcons.Radio,
                                    contentDescription = route.label,
                                    tint = if (selected) RfGuru.gold else RfGuru.dim,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Status-bar pad for a page that is not the landscape rail.
 *
 * Connect, Pair and Setup also take the navigation-bar pad. JS8 does not:
 * the screen pads its own bottom from the root insets, including the gesture
 * floor. Doing it here as well stacked a second pad on a phone that already
 * reported the bar, and on the S24 the Compose inset at this host was the
 * one that read 0 while the bar still covered the net reminder.
 */
@Composable
private fun hostInsets(rail: Boolean, onOperating: Boolean, route: String?): Modifier {
    if (rail || onOperating) return Modifier
    val status = Modifier.statusBarsPadding()
    return if (route == "js8") status else status.navigationBarsPadding()
}

private fun go(nav: NavHostController, route: Route) {
    nav.navigate(route.path) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun androidx.navigation.NavGraphBuilder.screens(nav: NavHostController, viewModel: RadioViewModel) {
    composable(Route.CONNECT.path) {
        ConnectScreen(
            viewModel,
            onOpenRadio = { nav.navigate(Route.RADIO.path) { launchSingleTop = true } },
            onPair = { nav.navigate(Route.PAIRING.path) { launchSingleTop = true } },
        )
    }
    composable(Route.PAIRING.path) {
        PairingScreen(viewModel, onDone = { nav.popBackStack() })
    }
    composable(Route.RADIO.path) {
        RadioScreen(viewModel, onNavigate = { path ->
            nav.navigate(path) { launchSingleTop = true }
        })
    }
    composable(Route.SCREEN.path) { ScreenMirrorScreen(viewModel) }
    composable(Route.FT8.path) {
        Ft8Screen(
            viewModel,
            onJs8 = { nav.navigate("js8") { launchSingleTop = true } },
            onPair = { nav.navigate(Route.PAIRING.path) { launchSingleTop = true } },
        )
    }
    composable("js8") {
        Js8Screen(
            viewModel,
            onBack = { nav.popBackStack() },
            onPair = { nav.navigate(Route.PAIRING.path) { launchSingleTop = true } },
        )
    }
    composable("wefax") {
        WeFaxScreen(
            viewModel,
            onBack = { nav.popBackStack() },
            onPair = { nav.navigate(Route.PAIRING.path) { launchSingleTop = true } },
        )
    }
    composable("cw") {
        CwScreen(viewModel, onBack = { nav.popBackStack() })
    }
    composable(Route.SPOTS.path) { SpotsScreen(viewModel) }
    composable(Route.LOG.path) { LogScreen(viewModel) }
    composable(Route.SETTINGS.path) { SettingsScreen(viewModel, onBack = { nav.popBackStack() }) }
}
