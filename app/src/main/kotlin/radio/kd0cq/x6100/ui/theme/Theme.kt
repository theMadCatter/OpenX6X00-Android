// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Before: the theme followed the phone's light/dark setting, with a green waterfall palette that
// was not the radio's. The RF.Guru firmware paints one dark theme (theme.h, the first column of
// THEME_COLORS) and does not have a day mode on the radio face. This file forces those colours
// for every screen, including Connect, Pairing and Settings, so a phone in light mode cannot
// wash the waterfall out.

package radio.kd0cq.x6100.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The RF.Guru roles this UI actually paints. Values are the firmware's theme.h column for RF.Guru. */
object RfGuru {
    val bg = Color(0xFF08090C)
    val bar = Color(0xFF13161D)
    val barBottom = Color(0xFF0C0E13)
    val border = Color(0xFF262B36)
    val button = Color(0xFF1B1F29)
    val gold = Color(0xFFE5A823)
    val goldText = Color(0xFFF2D886)
    val goldDim = Color(0xFF2A2410)
    val blue = Color(0xFF4F8BF5)
    val blueDim = Color(0xFF0A1A33)
    val red = Color(0xFFFF2020)
    val text = Color(0xFFF1F5F9)
    val dim = Color(0xFF94A3B8)
    val mute = Color(0xFF64748B)
    val ok = Color(0xFF34D399)
    val tx = Color(0xFFB91C1C)
    val onTx = Color(0xFFF1F5F9)
}

/** ARGB from the face model (a packed Int) into a Compose colour. */
fun packedArgb(argb: Int): Color = Color(argb.toLong() and 0xFFFFFFFFL)

private val RfScheme = darkColorScheme(
    primary = RfGuru.gold,
    onPrimary = RfGuru.barBottom,
    primaryContainer = RfGuru.goldDim,
    onPrimaryContainer = RfGuru.goldText,
    secondary = RfGuru.blue,
    onSecondary = RfGuru.blueDim,
    secondaryContainer = RfGuru.blueDim,
    onSecondaryContainer = RfGuru.text,
    tertiary = RfGuru.goldText,
    onTertiary = RfGuru.barBottom,
    background = RfGuru.bg,
    onBackground = RfGuru.text,
    surface = RfGuru.bar,
    onSurface = RfGuru.text,
    surfaceVariant = RfGuru.button,
    onSurfaceVariant = RfGuru.dim,
    outline = RfGuru.border,
    outlineVariant = RfGuru.border,
    error = Color(0xFFF87171),
    onError = Color(0xFF3A1414),
    errorContainer = RfGuru.tx,
    onErrorContainer = RfGuru.onTx,
    inverseSurface = Color(0xFFE2E8F0),
    inverseOnSurface = RfGuru.bar,
    scrim = Color(0xFF000000),
)

/**
 * Always the radio's night colours. [unusedSystemDark] is accepted and ignored so a caller that
 * still passes the system flag cannot turn the waterfall into a light page.
 */
@Composable
fun OpenX6X00Theme(
    @Suppress("UNUSED_PARAMETER") unusedSystemDark: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = RfScheme,
        content = content,
    )
}
