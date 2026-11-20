// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Small stroke icons for the tabs and the status row. They replace the old single-letter
// placeholders (W / C / F / S / L). Drawn here so the app does not take a dependency on the
// extended Material icon font; Icon() tints them with the RF.Guru gold or the dim status grey.

package radio.kd0cq.x6100.ui.face

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object RadioIcons {
    val Radio: ImageVector = strokeIcon("radio") {
        moveTo(5f, 18f); lineTo(19f, 18f)
        moveTo(12f, 18f); lineTo(12f, 10f)
        moveTo(12f, 10f); curveTo(8f, 10f, 6f, 7.5f, 6f, 5f)
        moveTo(12f, 10f); curveTo(16f, 10f, 18f, 7.5f, 18f, 5f)
        moveTo(8f, 14f); lineTo(16f, 14f)
    }
    val Screen: ImageVector = strokeIcon("screen") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 16f); lineTo(4f, 16f); close()
        moveTo(8f, 19f); lineTo(16f, 19f)
        moveTo(12f, 16f); lineTo(12f, 19f)
    }
    val Ft8: ImageVector = strokeIcon("ft8") {
        moveTo(4f, 14f); lineTo(7f, 14f); lineTo(7f, 8f); lineTo(4f, 8f)
        moveTo(10f, 16f); lineTo(13f, 16f); lineTo(13f, 6f); lineTo(10f, 6f)
        moveTo(16f, 13f); lineTo(20f, 13f); lineTo(20f, 9f); lineTo(16f, 9f)
    }
    val Spots: ImageVector = strokeIcon("spots") {
        moveTo(12f, 3f); lineTo(14.2f, 9f); lineTo(20.5f, 9.4f); lineTo(15.6f, 13.4f)
        lineTo(17.4f, 19.6f); lineTo(12f, 16.2f); lineTo(6.6f, 19.6f); lineTo(8.4f, 13.4f)
        lineTo(3.5f, 9.4f); lineTo(9.8f, 9f); close()
    }
    val Log: ImageVector = strokeIcon("log") {
        moveTo(6f, 3f); lineTo(16f, 3f); lineTo(19f, 6f); lineTo(19f, 21f); lineTo(6f, 21f); close()
        moveTo(16f, 3f); lineTo(16f, 6f); lineTo(19f, 6f)
        moveTo(9f, 11f); lineTo(16f, 11f)
        moveTo(9f, 15f); lineTo(16f, 15f)
    }
    val Link: ImageVector = strokeIcon("link") {
        moveTo(9f, 12f); lineTo(15f, 12f)
        moveTo(8f, 8f); lineTo(7f, 8f); curveTo(4.8f, 8f, 3f, 9.8f, 3f, 12f); curveTo(3f, 14.2f, 4.8f, 16f, 7f, 16f); lineTo(8f, 16f)
        moveTo(16f, 8f); lineTo(17f, 8f); curveTo(19.2f, 8f, 21f, 9.8f, 21f, 12f); curveTo(21f, 14.2f, 19.2f, 16f, 17f, 16f); lineTo(16f, 16f)
    }
    val Setup: ImageVector = strokeIcon("setup") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17f); lineTo(20f, 17f)
        moveTo(8f, 7f); moveTo(8f, 5.2f); lineTo(8f, 8.8f)
        moveTo(15f, 10.2f); lineTo(15f, 13.8f)
        moveTo(11f, 15.2f); lineTo(11f, 18.8f)
    }
    val Connect: ImageVector = strokeIcon("connect") {
        moveTo(12f, 18f); lineTo(12f, 18.2f)
        moveTo(7f, 15f); curveTo(8.4f, 13.6f, 10.1f, 12.8f, 12f, 12.8f); curveTo(13.9f, 12.8f, 15.6f, 13.6f, 17f, 15f)
        moveTo(4.5f, 12f); curveTo(6.5f, 10f, 9.1f, 8.8f, 12f, 8.8f); curveTo(14.9f, 8.8f, 17.5f, 10f, 19.5f, 12f)
        moveTo(2f, 9f); curveTo(4.6f, 6.4f, 8.1f, 4.8f, 12f, 4.8f); curveTo(15.9f, 4.8f, 19.4f, 6.4f, 22f, 9f)
    }
    val Wifi: ImageVector = Connect
    val Bluetooth: ImageVector = strokeIcon("bluetooth") {
        moveTo(8f, 7f); lineTo(16f, 12f); lineTo(11f, 16f); lineTo(11f, 4f); lineTo(16f, 8f); lineTo(8f, 13f)
    }
    val Music: ImageVector = strokeIcon("music") {
        moveTo(9f, 17f); curveTo(9f, 18.7f, 7.7f, 20f, 6f, 20f); curveTo(4.3f, 20f, 3f, 18.7f, 3f, 17f); curveTo(3f, 15.3f, 4.3f, 14f, 6f, 14f); curveTo(6.7f, 14f, 7.3f, 14.2f, 7.8f, 14.5f)
        moveTo(9f, 17f); lineTo(9f, 5f); lineTo(20f, 3f); lineTo(20f, 15f)
        moveTo(18f, 15f); curveTo(18f, 16.7f, 16.7f, 18f, 15f, 18f); curveTo(13.3f, 18f, 12f, 16.7f, 12f, 15f); curveTo(12f, 13.3f, 13.3f, 12f, 15f, 12f); curveTo(15.7f, 12f, 16.3f, 12.2f, 16.8f, 12.5f)
    }
    val Keyboard: ImageVector = strokeIcon("keyboard") {
        moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(3f, 18f); close()
        moveTo(6f, 9f); lineTo(6.2f, 9f); moveTo(10f, 9f); lineTo(10.2f, 9f); moveTo(14f, 9f); lineTo(14.2f, 9f); moveTo(18f, 9f); lineTo(18.2f, 9f)
        moveTo(8f, 12.5f); lineTo(16f, 12.5f)
        moveTo(6f, 15.5f); lineTo(18f, 15.5f)
    }
    val Pin: ImageVector = strokeIcon("pin") {
        moveTo(12f, 21f); lineTo(12f, 11f)
        moveTo(12f, 11f); curveTo(9f, 11f, 7f, 8.8f, 7f, 6.5f); curveTo(7f, 4.2f, 9.2f, 2.5f, 12f, 2.5f); curveTo(14.8f, 2.5f, 17f, 4.2f, 17f, 6.5f); curveTo(17f, 8.8f, 15f, 11f, 12f, 11f)
    }
    val Flag: ImageVector = strokeIcon("flag") {
        moveTo(6f, 21f); lineTo(6f, 3f)
        moveTo(6f, 4f); lineTo(17f, 4f); lineTo(14.5f, 8f); lineTo(17f, 12f); lineTo(6f, 12f)
    }
    val Mic: ImageVector = strokeIcon("mic") {
        moveTo(12f, 3f); curveTo(10.3f, 3f, 9f, 4.3f, 9f, 6f); lineTo(9f, 12f); curveTo(9f, 13.7f, 10.3f, 15f, 12f, 15f); curveTo(13.7f, 15f, 15f, 13.7f, 15f, 12f); lineTo(15f, 6f); curveTo(15f, 4.3f, 13.7f, 3f, 12f, 3f)
        moveTo(6f, 11f); curveTo(6f, 14.3f, 8.7f, 17f, 12f, 17f); curveTo(15.3f, 17f, 18f, 14.3f, 18f, 11f)
        moveTo(12f, 17f); lineTo(12f, 21f)
        moveTo(9f, 21f); lineTo(15f, 21f)
    }
}

private fun strokeIcon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            pathBuilder = block,
        )
    }.build()
