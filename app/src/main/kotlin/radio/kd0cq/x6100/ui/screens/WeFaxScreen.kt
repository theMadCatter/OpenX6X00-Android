// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The WeFax window, from src/wefax_window.c.
//
// Before this pass the chart was dropped into the JS8 split: a header, the
// picture, a line that said how many rows had arrived, and one scrolling
// row of every word. Landscape put the picture beside that line. The radio
// does not draw WeFax that way.
//
// The app opens in the Big view, which is the common app frame. The left of
// the frame names the station and the frequency. The right names RX Auto or
// RX Cont, and Auto Plan's next slot when that is on. The status line is the
// hint the radio prints under the frame. The body is the grey chart, newest
// at the bottom. View cycles Big, Full and Small the way the button does:
// Big keeps the newest rows, Full fits the whole chart, Small is the 152 px
// strip. On the radio that strip sits over the main waterfall. This route
// has no waterfall of its own, so the frame colour fills the space above it.
// The buttons are the four pages of five. A word the panel does not
// dispatch is drawn and does not send.
//
// Opening this screen asks for rows. It does not send "wefax open". None of
// the panel words keys the transmitter. Each one that does send still needs
// the operate role and Take control.

package radio.kd0cq.x6100.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.kd0cq.x6100.digital.FaxKey
import radio.kd0cq.x6100.digital.WeFaxProtocol
import radio.kd0cq.x6100.digital.WeFaxScan
import radio.kd0cq.x6100.digital.faxKeySends
import radio.kd0cq.x6100.digital.faxPage
import radio.kd0cq.x6100.ui.RadioViewModel
import radio.kd0cq.x6100.ui.theme.RfGuru

/** The radio's three views, in wefax_window.c's order: Small, Big, Full. */
private enum class FaxView { SMALL, BIG, FULL }

private const val NOT_ON_PANEL = "The panel does not offer that control."
private const val HINT = "Page 2: Freq., View   Page 3: Align, Tilt (MFK)   Page 4: Night"
private val frameColor = Color(0xFF0C0E13)

@Composable
fun WeFaxScreen(viewModel: RadioViewModel, onBack: () -> Unit) {
    val status by viewModel.wefax.collectAsState()
    val rows by viewModel.wefaxRows.collectAsState()
    val role by viewModel.role.collectAsState()
    val control by viewModel.control.collectAsState()
    var refusal by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf(FaxView.BIG) }
    val held = control.mine && role.canOperate
    val picture = remember(rows.size, rows.lastOrNull()?.number, rows.firstOrNull()?.grey?.size) {
        faxBitmap(rows)
    }
    val idle = WeFaxProtocol.idleLines(status, rows.isNotEmpty())

    DisposableEffect(Unit) {
        viewModel.wefaxWatch(true)
        onDispose { viewModel.wefaxWatch(false) }
    }

    Column(Modifier.fillMaxSize().background(frameColor)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                WeFaxProtocol.frameLeft(status),
                color = RfGuru.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                WeFaxProtocol.frameRight(status),
                color = if (status?.transmitting == true) RfGuru.tx else RfGuru.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                textAlign = TextAlign.End,
            )
            TextButton(onClick = onBack) { Text("Back", color = RfGuru.goldText) }
        }
        Text(HINT, color = RfGuru.dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
        refusal?.let { Text(it, color = RfGuru.red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(4.dp)
                .border(1.dp, RfGuru.border, RoundedCornerShape(8.dp))
                .background(Color.Black, RoundedCornerShape(8.dp)),
        ) {
            val imageMod = when (view) {
                FaxView.BIG -> Modifier.fillMaxSize()
                FaxView.FULL -> Modifier.fillMaxSize()
                FaxView.SMALL -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(152.dp)
            }
            if (picture == null) {
                if (status?.open != true) {
                    Text(
                        "The chart appears after Open, once the radio sends a row.",
                        color = RfGuru.dim,
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Image(
                    bitmap = picture,
                    contentDescription = "WeFax chart",
                    contentScale = if (view == FaxView.FULL) ContentScale.Fit else ContentScale.FillWidth,
                    alignment = Alignment.BottomCenter,
                    modifier = imageMod,
                )
            }
            if (idle != null && view != FaxView.SMALL) {
                Column(
                    Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    idle.forEach { line ->
                        if (line.isNotEmpty()) {
                            Text(line, color = RfGuru.text, fontSize = 14.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!held) {
                OutlinedButton(onClick = { viewModel.takeControl() }, enabled = role.canOperate) {
                    Text("Take control")
                }
            }
            if (status?.open != true) {
                OutlinedButton(
                    onClick = { refusal = if (viewModel.wefaxOperate("open")) null else "Take control before this." },
                    enabled = held,
                ) { Text("Open") }
            }
        }
        FaxKeys(
            page = page,
            auto = status?.auto != false,
            planLabel = WeFaxProtocol.planButton(status),
            view = view,
            held = held,
            onKey = { key ->
                when (key) {
                    FaxKey.PAGE -> page = (page + 1) % 4
                    FaxKey.VIEW -> view = when (view) {
                        FaxView.SMALL -> FaxView.BIG
                        FaxView.BIG -> FaxView.FULL
                        FaxView.FULL -> FaxView.SMALL
                    }
                    FaxKey.PLAN -> {
                        val word = if (status?.plan == true) "unplan" else "plan"
                        refusal = if (viewModel.wefaxOperate(word)) null else "Take control before this."
                    }
                    FaxKey.CLOSE -> refusal = if (viewModel.wefaxOperate("close")) null else "Take control before this."
                    FaxKey.RX -> {
                        val word = if (status?.auto == true) "cont" else "auto"
                        refusal = if (viewModel.wefaxOperate(word)) null else "Take control before this."
                    }
                    FaxKey.CLEAR -> refusal = if (viewModel.wefaxOperate("clear")) null else "Take control before this."
                    FaxKey.SAVE -> refusal = if (viewModel.wefaxOperate("save")) null else "Take control before this."
                    else -> refusal = NOT_ON_PANEL
                }
            },
        )
    }
}

@Composable
private fun FaxKeys(
    page: Int,
    auto: Boolean,
    planLabel: String,
    view: FaxView,
    held: Boolean,
    onKey: (FaxKey) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(58.dp).background(RfGuru.barBottom)) {
        faxPage(page).forEach { key ->
            if (key == null) {
                Box(Modifier.weight(1f).fillMaxHeight())
            } else {
                val label = when (key) {
                    FaxKey.PAGE -> "(FAX ${page + 1}:4)"
                    FaxKey.PLAN -> planLabel
                    FaxKey.BULLETIN -> "Bulletin"
                    FaxKey.CHARTS -> "All\nCharts"
                    FaxKey.CLOSE -> "Close"
                    FaxKey.FREQ -> "Freq."
                    FaxKey.RX -> if (auto) "RX\nAuto" else "RX\nCont"
                    FaxKey.VIEW -> when (view) {
                        FaxView.SMALL -> "View\nSmall"
                        FaxView.BIG -> "View\nBig"
                        FaxView.FULL -> "View\nFull Size"
                    }
                    FaxKey.CLEAR -> "Clear"
                    FaxKey.ALIGN -> "Align"
                    FaxKey.TILT -> "Tilt"
                    FaxKey.SAVE -> "Save"
                    FaxKey.POWER -> "Pwr"
                    FaxKey.NIGHT -> "Night"
                }
                val enabled = key == FaxKey.PAGE || key == FaxKey.VIEW || (faxKeySends(key) && held) || !faxKeySends(key)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(enabled = enabled) { onKey(key) }
                        .padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (faxKeySends(key) && !held) RfGuru.mute else RfGuru.text,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/**
 * The grey bytes as one bitmap, top to bottom in the order they arrived.
 * The radio paints the same bytes. This is that picture, not a second decoder.
 */
private fun faxBitmap(rows: List<WeFaxScan>): androidx.compose.ui.graphics.ImageBitmap? {
    val width = rows.firstOrNull()?.grey?.size ?: return null
    if (width <= 0 || rows.isEmpty()) return null
    val height = rows.size
    val pixels = IntArray(width * height)
    rows.forEachIndexed { y, row ->
        val n = minOf(width, row.grey.size)
        for (x in 0 until n) {
            val grey = row.grey[x].toInt() and 0xFF
            pixels[y * width + x] = (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
        }
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}
