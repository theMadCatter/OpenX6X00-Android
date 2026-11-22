// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The phone layout for a digital app the radio draws as a waterfall over a list.
//
// Before this, JS8 was one column: a fixed waterfall, then every control, then
// the list with weight. On a tall phone the controls used the height and the
// list was a sliver. On a short landscape phone the column was taller than
// the screen and did not scroll, so everything under the waterfall was clipped.
// Weight inside an unbounded scroll does not work either: the list is given
// no height and Compose throws.
//
// Portrait keeps the radio's order (waterfall, then the list, then the
// controls) and caps the control block, so the list is the piece that grows
// and the piece that scrolls. Landscape puts the waterfall on the left and
// the list plus the controls on the right, which is the same information
// with the two panes the short window can actually show.

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
     * JS8 and FT8 keep a short waterfall and give the list the leftover
     * height, which is how those windows are built. WeFax is the chart, so
     * the picture takes the leftover height instead.
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
 * The JS8 and FT8 app waterfall.
 *
 * The bytes are dB over the noise floor. The radio's palette runs from the
 * floor to 30 dB over it (`WF_MAX_DB` in js8_window.c). A red border is the
 * radio's "on the air" border. The vertical lines are the TX offset and the
 * selected station, which the radio draws with its finder.
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
