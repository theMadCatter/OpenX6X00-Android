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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = RadioFace.wideWindow(maxWidth.value, maxHeight.value)
        if (wide) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                header()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(pictureShare).fillMaxHeight().background(Color.Black)) {
                        picture()
                    }
                    Column(Modifier.weight(1f - pictureShare).fillMaxHeight().padding(start = 8.dp)) {
                        list(Modifier.weight(1f).fillMaxWidth())
                        Column(
                            Modifier
                                .heightIn(max = 168.dp)
                                .verticalScroll(rememberScrollState()),
                        ) { controls() }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
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
                            .height(100.dp)
                            .background(Color.Black),
                    ) { picture() }
                    list(Modifier.weight(1f).fillMaxWidth())
                }
                Column(
                    Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                ) { controls() }
            }
        }
    }
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
