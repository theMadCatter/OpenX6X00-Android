// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Before: the theme followed the phone's light/dark setting, with a green waterfall palette that
// was not the radio's. The RF.Guru firmware paints one dark theme (theme.h, the first column of
// THEME_COLORS). The night face is still the default. Setup can switch to a high-contrast
// sunlight face: a white page and black text, for reading the radio outdoors. The spectrum
// colours stay the firmware palettes either way, because those are signal level, not chrome.

package radio.kd0cq.x6100.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import radio.kd0cq.x6100.ui.face.FaceChoices

/** The RF.Guru roles this UI actually paints. Night values are the firmware's theme.h column. */
object RfGuru {
    var bg by mutableStateOf(Color(0xFF08090C))
    var bar by mutableStateOf(Color(0xFF13161D))
    var barBottom by mutableStateOf(Color(0xFF0C0E13))
    var border by mutableStateOf(Color(0xFF262B36))
    var button by mutableStateOf(Color(0xFF1B1F29))
    var gold by mutableStateOf(Color(0xFFE5A823))
    var goldText by mutableStateOf(Color(0xFFF2D886))
    var goldDim by mutableStateOf(Color(0xFF2A2410))
    var blue by mutableStateOf(Color(0xFF4F8BF5))
    var blueDim by mutableStateOf(Color(0xFF0A1A33))
    var red by mutableStateOf(Color(0xFFFF2020))
    var text by mutableStateOf(Color(0xFFF1F5F9))
    var dim by mutableStateOf(Color(0xFF94A3B8))
    var mute by mutableStateOf(Color(0xFF64748B))
    var ok by mutableStateOf(Color(0xFF34D399))
    var tx by mutableStateOf(Color(0xFFB91C1C))
    var onTx by mutableStateOf(Color(0xFFF1F5F9))

    private var appliedSun: Boolean? = null

    /**
     * Point every role at one face. Writing only when the face changed keeps a recomposition
     * from looping: the colour states notify readers, those recompose, and this returns.
     */
    fun use(sunlight: Boolean) {
        if (appliedSun == sunlight) return
        appliedSun = sunlight
        if (sunlight) {
            bg = Color(0xFFFFFFFF)
            bar = Color(0xFFF4F4F4)
            barBottom = Color(0xFFE6E6E6)
            border = Color(0xFF000000)
            button = Color(0xFFFFFFFF)
            gold = Color(0xFF000000)
            goldText = Color(0xFF000000)
            goldDim = Color(0xFFD0D0D0)
            blue = Color(0xFF000000)
            blueDim = Color(0xFFE6E6E6)
            red = Color(0xFF000000)
            text = Color(0xFF000000)
            dim = Color(0xFF000000)
            mute = Color(0xFF1A1A1A)
            ok = Color(0xFF000000)
            tx = Color(0xFF000000)
            onTx = Color(0xFFFFFFFF)
        } else {
            bg = Color(0xFF08090C)
            bar = Color(0xFF13161D)
            barBottom = Color(0xFF0C0E13)
            border = Color(0xFF262B36)
            button = Color(0xFF1B1F29)
            gold = Color(0xFFE5A823)
            goldText = Color(0xFFF2D886)
            goldDim = Color(0xFF2A2410)
            blue = Color(0xFF4F8BF5)
            blueDim = Color(0xFF0A1A33)
            red = Color(0xFFFF2020)
            text = Color(0xFFF1F5F9)
            dim = Color(0xFF94A3B8)
            mute = Color(0xFF64748B)
            ok = Color(0xFF34D399)
            tx = Color(0xFFB91C1C)
            onTx = Color(0xFFF1F5F9)
        }
    }
}

/** ARGB from the face model (a packed Int) into a Compose colour. */
fun packedArgb(argb: Int): Color = Color(argb.toLong() and 0xFFFFFFFFL)

/**
 * Night is the radio's face. Sunlight is a light page with black text, chosen in Setup,
 * so the frequency can be read outdoors. The system dark flag is not consulted.
 */
@Composable
fun OpenX6X00Theme(
    content: @Composable () -> Unit,
) {
    val sunlight by FaceChoices.sunlight.collectAsState()
    RfGuru.use(sunlight)
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = sunlight
        controller.isAppearanceLightNavigationBars = sunlight
    }
    val scheme = if (sunlight) {
        lightColorScheme(
            primary = Color.Black,
            onPrimary = Color.White,
            background = Color.White,
            onBackground = Color.Black,
            surface = Color.White,
            onSurface = Color.Black,
            surfaceVariant = Color(0xFFF4F4F4),
            onSurfaceVariant = Color.Black,
            outline = Color.Black,
            error = Color.Black,
            onError = Color.White,
        )
    } else {
        darkColorScheme(
            primary = Color(0xFFE5A823),
            onPrimary = Color(0xFF0C0E13),
            primaryContainer = Color(0xFF2A2410),
            onPrimaryContainer = Color(0xFFF2D886),
            secondary = Color(0xFF4F8BF5),
            onSecondary = Color(0xFF0A1A33),
            secondaryContainer = Color(0xFF0A1A33),
            onSecondaryContainer = Color(0xFFF1F5F9),
            tertiary = Color(0xFFF2D886),
            onTertiary = Color(0xFF0C0E13),
            background = Color(0xFF08090C),
            onBackground = Color(0xFFF1F5F9),
            surface = Color(0xFF13161D),
            onSurface = Color(0xFFF1F5F9),
            surfaceVariant = Color(0xFF1B1F29),
            onSurfaceVariant = Color(0xFF94A3B8),
            outline = Color(0xFF262B36),
            outlineVariant = Color(0xFF262B36),
            error = Color(0xFFF87171),
            onError = Color(0xFF3A1414),
            errorContainer = Color(0xFFB91C1C),
            onErrorContainer = Color(0xFFF1F5F9),
            inverseSurface = Color(0xFFE2E8F0),
            inverseOnSurface = Color(0xFF13161D),
            scrim = Color(0xFF000000),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
