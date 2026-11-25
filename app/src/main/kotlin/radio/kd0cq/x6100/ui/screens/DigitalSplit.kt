// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The phone layout for the JS8 window only.
//
// src/js8_window.c stacks a lane, a short clear waterfall, a TX line and then
// the message list. On a tall phone that list was a sliver under the controls.
// On a short landscape phone the column was taller than the screen and did
// not scroll. This split gives the list the leftover height, and on a wide
// window puts the waterfall beside the list.
//
// FT8, WeFax and CW do not use this. Each of those is drawn by its own
// firmware file, and copying this split made them look like JS8.

package radio.kd0cq.x6100.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import radio.kd0cq.x6100.digital.Js8Look
import radio.kd0cq.x6100.digital.waterfallTicks
import radio.kd0cq.x6100.digital.waterfallX
import radio.kd0cq.x6100.net.AppWaterfallFrame
import radio.kd0cq.x6100.ui.face.RadioFace
import radio.kd0cq.x6100.ui.theme.RfGuru

/**
 * [picture] is the waterfall or the fax. [list] is the scrolling decode list
 * and receives the modifier that gives it the leftover height. [controls]
 * stay in a capped block under the list, and that block scrolls on its own
 * when the GhostNet field or the button row is taller than the cap.
 */
@Composable
fun DigitalSplit(
    header: @Composable () -> Unit,
    picture: @Composable BoxScope.() -> Unit,
    list: @Composable (Modifier) -> Unit,
    controls: @Composable () -> Unit,
    /**
     * The JS8 list takes the leftover height. [pictureGrows] is unused by
     * that window and is kept so a caller that already passed it still builds.
     */
    pictureGrows: Boolean = false,
    /** Share of a wide window given to the picture. The list gets the rest. */
    pictureShare: Float = 0.42f,
) {
    // The wide window sizes the control scroll from the column it is given.
    // Portrait pads for the system navigation bar and does not cap the
    // controls, so the net reminder is in the column at its full height.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = RadioFace.wideWindow(maxWidth.value, maxHeight.value)
        // Both orientations keep the system bar, including the gesture floor,
        // under the column. The landscape budget subtracts that same pad, so
        // Set is above the bar on the first frame rather than under it.
        val bar = navigationBottomInset()
        if (wide) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .padding(bottom = bar),
            ) {
                header()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(pictureShare).fillMaxHeight().background(Color.Black)) {
                        picture()
                    }
                    WideControls(
                        pictureShare = pictureShare,
                        list = list,
                        controls = controls,
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .padding(bottom = bar),
            ) {
                header()
                if (pictureGrows) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.Black),
                    ) { picture() }
                    list(Modifier.fillMaxWidth())
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(Js8Chrome.PORTRAIT_PICTURE_DP.dp)
                            .background(Color.Black),
                    ) { picture() }
                    list(Modifier.weight(1f).fillMaxWidth())
                }
                PortraitControls(controls)
            }
        }
    }
}

/**
 * The right column. The scroll viewport is [Js8Chrome.controlCapDp]: at
 * least the offset row when the column can hold it, and never taller than
 * the column. Set is in that first viewport. The GhostNet block and the
 * message are under it and scroll.
 */
@Composable
private fun RowScope.WideControls(
    pictureShare: Float,
    list: @Composable (Modifier) -> Unit,
    controls: @Composable () -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .weight(1f - pictureShare)
            .fillMaxHeight()
            .padding(start = 8.dp, end = 4.dp),
    ) {
        val cap = Js8Chrome.controlCapDp(maxHeight.value).dp
        Column(Modifier.fillMaxHeight()) {
            list(Modifier.weight(1f).fillMaxWidth())
            Column(
                Modifier
                    .heightIn(max = cap)
                    .verticalScroll(rememberScrollState()),
            ) { controls() }
        }
    }
}

/**
 * Portrait controls. [Js8Chrome.PORTRAIT_CONTROL_MAX_DP] is 0, so there is
 * no cap: the North America reminder wraps at its own height above the
 * navigation pad the column already applied. A positive cap would be the
 * old block that ended on the bar.
 */
@Composable
private fun PortraitControls(controls: @Composable () -> Unit) {
    if (Js8Chrome.PORTRAIT_CONTROL_MAX_DP > 0) {
        Column(
            Modifier
                .heightIn(max = Js8Chrome.PORTRAIT_CONTROL_MAX_DP.dp)
                .verticalScroll(rememberScrollState()),
        ) { controls() }
    } else {
        Column(Modifier.fillMaxWidth()) { controls() }
    }
}

/**
 * Bottom inset for the portrait column.
 *
 * Compose's navigation-bar padding on this route was 0, because the host
 * had consumed it, and the S24 still drew the gesture bar there. This reads
 * the navigation bar, the mandatory gesture inset and the tappable element,
 * from Compose and from the root view, and [ScreenBars.bottomDp] keeps the
 * gesture floor when every one of those is 0.
 */
@Composable
private fun navigationBottomInset(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    LaunchedEffect(view) { ViewCompat.requestApplyInsets(view) }
    val composePx = maxOf(
        WindowInsets.navigationBars.getBottom(density),
        WindowInsets.mandatorySystemGestures.getBottom(density),
        WindowInsets.tappableElement.getBottom(density),
    )
    val root = ViewCompat.getRootWindowInsets(view)
    val rawPx = if (root == null) {
        0
    } else {
        maxOf(
            root.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
            root.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom,
            root.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom,
        )
    }
    val reported = with(density) { maxOf(composePx, rawPx).toDp().value }
    return ScreenBars.bottomDp(reported).dp
}

/** A call drawn on the lane over the waterfall, at its audio offset. */
data class LaneMark(val hz: Int, val label: String, val color: Color)

/**
 * The JS8 app waterfall.
 *
 * The bytes are dB over the noise floor. js8_window.c runs the palette from
 * the floor to 30 dB over it. A red border is that window's "on the air"
 * border. The vertical lines are the TX offset and the selected station.
 * FT8's finder is a band of the signal's width on the main waterfall
 * palette, drawn by Ft8Screen, not by this picture.
 */
@Composable
fun AppWaterfallPicture(
    frames: List<AppWaterfallFrame>,
    txHz: Int?,
    selectedHz: Int?,
    sending: Boolean,
    lane: List<LaneMark>,
    empty: String,
) {
    val latest = frames.lastOrNull()
    Box(
        Modifier
            .fillMaxSize()
            .then(if (sending) Modifier.border(3.dp, RfGuru.tx) else Modifier),
    ) {
        if (frames.isEmpty()) {
            Text(empty, color = RfGuru.dim, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val shown = frames.takeLast(size.height.toInt().coerceAtLeast(1))
                if (shown.isEmpty()) return@Canvas
                val rowH = size.height / shown.size
                shown.forEachIndexed { index, frame ->
                    val bins = frame.bins.coerceAtLeast(1)
                    val binW = size.width / bins
                    for (bin in frame.db.indices) {
                        drawRect(
                            color = waterfallBin(frame.db[bin].toInt()),
                            topLeft = Offset(bin * binW, index * rowH),
                            size = Size(binW + 1f, rowH + 1f),
                        )
                    }
                }
                val low = latest?.lowHz ?: return@Canvas
                val high = latest.highHz
                waterfallTicks(low, high).forEach { tick ->
                    val x = waterfallX(tick, low, high, size.width) ?: return@forEach
                    drawLine(RfGuru.dim.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                }
                marker(selectedHz, low, high, size.width, size.height, Color(Js8Look.ROW_BAR))
                marker(txHz, low, high, size.width, size.height, RfGuru.tx)
            }
            if (latest != null) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    lane.take(8).forEach { mark ->
                        val fraction = waterfallX(mark.hz, latest.lowHz, latest.highHz, 1f) ?: return@forEach
                        Text(
                            mark.label,
                            color = mark.color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            maxLines = 1,
                            modifier = Modifier.offset(x = maxWidth * fraction.coerceIn(0f, 0.82f)),
                        )
                    }
                }
                Text(
                    "${latest.lowHz}–${latest.highHz} Hz",
                    color = RfGuru.dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(2.dp),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.marker(
    hz: Int?,
    low: Int,
    high: Int,
    width: Float,
    height: Float,
    color: Color,
) {
    val at = hz ?: return
    val x = waterfallX(at, low, high, width) ?: return
    drawLine(color, Offset(x, 0f), Offset(x, height), strokeWidth = 2f)
}

/** Zero stays dark. 30 dB over the floor is the top of the radio's palette. */
private fun waterfallBin(db: Int): Color {
    val v = db.coerceIn(0, Js8Look.WF_MAX_DB) / Js8Look.WF_MAX_DB.toFloat()
    return Color(0.04f + v * 0.95f, 0.10f + v * 0.55f, 0.02f)
}
