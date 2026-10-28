// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A dark panel's colours: the waterfall's greens on a night background, as the radio's own screen.
private val NightPalette = darkColorScheme(
    primary = Color(0xFF7FD858),
    onPrimary = Color(0xFF002106),
    secondary = Color(0xFF80D8FF),
    onSecondary = Color(0xFF001E2C),
    tertiary = Color(0xFFFFD77F),
    background = Color(0xFF0B1210),
    surface = Color(0xFF111A17),
    surfaceVariant = Color(0xFF1B2622),
    onBackground = Color(0xFFE2EEE6),
    onSurface = Color(0xFFE2EEE6),
)

private val DayPalette = lightColorScheme(
    primary = Color(0xFF22682A),
    secondary = Color(0xFF00658B),
    tertiary = Color(0xFF7A5900),
)

/** The app's one theme: dark by default, since a waterfall on a light background is a glare. */
@Composable
fun OpenX6X00Theme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) NightPalette else DayPalette,
        content = content,
    )
}
