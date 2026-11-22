// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The FT8 / FT4 / FT2 window, from src/ft8_window.c and src/ft8/decode_list.c.
//
// Before this pass the screen was the JS8 split: a short waterfall, then a
// list that took the leftover height, then one scrolling row of every
// control, and in landscape the waterfall beside the list. That is
// js8_window.c adapted to a phone. The FT8 window is a different picture.
//
// On the radio the lane of calls is a solid strip at the top. Under it the
// waterfall fills the dialog, on the main waterfall palette, from the noise
// floor to 30 dB over it. The TX marker is a half-opaque band as wide as
// the mode (50 Hz FT8, 83 Hz FT4, 167 Hz FT2), not a hairline. The message
// list is see-through and lies on the lower part of that waterfall, leaving
// 40 px clear so a gap in the band can be seen. Turning the main knob fades
// the list for a second. View replaces the list with a 3 by 3 board of the
// stations heard. The controls are five pages of the button bar, not a
// second copy of the JS8 buttons.
//
// CQ, Auto, TX Call and a reply still ask, and they still need Take control
// plus the US Advanced check. Open is a button. Nothing here sends on
// composition except "ft8 wf 1". A button the panel does not dispatch is
// drawn, because it is on the radio, and a press does not invent a command.
// Show and View are this screen's own, the same as the radio keeping them
// in the GUI. A sideways drag is the main knob: it sends "ft8 txfreq" and
// fades the list.

package radio.kd0cq.x6100.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.kd0cq.x6100.digital.Ft8Heard
import radio.kd0cq.x6100.digital.Ft8Key
import radio.kd0cq.x6100.digital.Ft8Line
import radio.kd0cq.x6100.digital.Ft8Look
import radio.kd0cq.x6100.digital.finderBand
import radio.kd0cq.x6100.digital.ft8Heard
import radio.kd0cq.x6100.digital.ft8KeySends
import radio.kd0cq.x6100.digital.ft8Lines
import radio.kd0cq.x6100.digital.ft8Page
import radio.kd0cq.x6100.digital.waterfallX
import radio.kd0cq.x6100.net.Ft8Row
import radio.kd0cq.x6100.net.PanelCommands
import radio.kd0cq.x6100.ui.RadioViewModel
import radio.kd0cq.x6100.ui.face.WaterfallDisplay
import radio.kd0cq.x6100.ui.face.WfColormap
import radio.kd0cq.x6100.ui.theme.RfGuru

private data class Ft8Confirm(val title: String, val detail: String, val line: String, val enabling: Boolean)

private const val NOT_ON_PANEL = "The panel does not offer that control."

@Composable
fun Ft8Screen(viewModel: RadioViewModel, onJs8: () -> Unit = {}) {
    // The radio's FT8 window has no key that opens JS8. The parameter stays
    // so the navigation call site does not change, and this screen does not
    // paint that key.
    @Suppress("UNUSED_VARIABLE")
    val unusedJs8 = onJs8
    val rows by viewModel.ft8Rows.collectAsState()
    val status by viewModel.ft8Status.collectAsState()
    val waterfall by viewModel.appWaterfall.collectAsState()
    val role by viewModel.role.collectAsState()
    val control by viewModel.control.collectAsState()
    var confirm by remember { mutableStateOf<Ft8Confirm?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var showAll by remember { mutableStateOf(true) }
    var tiles by remember { mutableStateOf(false) }
    var tilePage by remember { mutableIntStateOf(0) }
    var selectedCall by remember { mutableStateOf("") }
    var cursorHz by remember { mutableStateOf<Int?>(null) }
    var faded by remember { mutableStateOf(false) }
    var fadeTick by remember { mutableIntStateOf(0) }
    var dragCarry by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val gauss = remember { WaterfallDisplay.palette(WfColormap.GAUSS) }
    val kept = remember(rows, showAll) { rows.filter { Ft8Look.showKeeps(it.cellType, showAll) } }
    val lines = remember(kept) { ft8Lines(kept) }
    val heard = remember(rows, showAll) { ft8Heard(rows, showAll) }
    val name = Ft8Look.protocolName(status?.protocol ?: 1)
    val widthHz = Ft8Look.toneWidthHz(status?.protocol ?: 1)

    DisposableEffect(Unit) {
        viewModel.ft8Watch(true)
        onDispose { viewModel.ft8Watch(false) }
    }

    val held = control.mine && role.canOperate
    val blocked = viewModel.ft8TransmitBlocked()
    val mayTransmit = blocked == null
    val txOffset = status?.txFreqHz
    val listAlpha by animateFloatAsState(if (faded) 0f else 1f, label = "ft8-list-fade")

    LaunchedEffect(lines.size, tiles) {
        if (tiles) return@LaunchedEffect
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (visible < 0 || visible >= last - 2) listState.scrollToItem(last)
    }
    LaunchedEffect(fadeTick) {
        if (!faded) return@LaunchedEffect
        kotlinx.coroutines.delay(1000)
        faded = false
    }

    Column(Modifier.fillMaxSize().background(RfGuru.bg)) {
        Ft8Lane(
            heard = heard,
            txHz = txOffset,
            lowHz = waterfall.lastOrNull()?.lowHz,
            highHz = waterfall.lastOrNull()?.highHz,
            selectedCall = if (tiles) selectedCall else "",
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(held, txOffset, waterfall.lastOrNull()?.lowHz, waterfall.lastOrNull()?.highHz) {
                    val frame = waterfall.lastOrNull()
                    // The gesture block keeps the offset it last sent. Reading the
                    // status captured when the gesture started would add every
                    // step to that first value, so a long drag would stay at +10 Hz.
                    var hz = txOffset
                    detectHorizontalDragGestures(
                        onDragStart = {
                            if (!held) refusal = "Take control before this."
                            dragCarry = 0f
                        },
                        onHorizontalDrag = { _, dx ->
                            val current = hz ?: return@detectHorizontalDragGestures
                            val span = frame ?: return@detectHorizontalDragGestures
                            if (!held || span.highHz <= span.lowHz) return@detectHorizontalDragGestures
                            dragCarry += dx
                            val stepPx = 24.dp.toPx()
                            val steps = (dragCarry / stepPx).toInt()
                            if (steps == 0) return@detectHorizontalDragGestures
                            dragCarry -= steps * stepPx
                            val next = (current + steps * 10).coerceIn(span.lowHz, span.highHz)
                            hz = next
                            val line = PanelCommands.ft8("txfreq", next.toString())
                            refusal = if (viewModel.ft8Operate(line)) null else "Take control before this."
                            faded = true
                            fadeTick++
                        },
                    )
                },
        ) {
            Ft8Waterfall(
                frames = waterfall,
                txHz = txOffset,
                widthHz = widthHz,
                cursorHz = cursorHz,
                sending = status?.sending == true,
                gauss = gauss,
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = Ft8Look.PEEK_PX.dp)
                    .graphicsLayer { alpha = listAlpha },
            ) {
                if (status?.open != true && lines.isEmpty() && !tiles) {
                    Text(
                        "The decoder starts with Open. Watching the waterfall needs no role.",
                        color = RfGuru.dim,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(8.dp),
                    )
                } else if (tiles) {
                    Ft8Tiles(
                        heard = heard,
                        page = tilePage,
                        selected = selectedCall,
                        onPage = { tilePage = it },
                        onCall = { station ->
                            selectedCall = station.call
                            cursorHz = station.freqHz
                            if (!Ft8Look.canReply(station.cellType)) return@Ft8Tiles
                            confirm = Ft8Confirm(
                                "Reply",
                                station.call,
                                PanelCommands.ft8("reply", station.id.toString()),
                                enabling = true,
                            )
                        },
                    )
                } else if (lines.isEmpty()) {
                    Text(
                        "Wait sync",
                        color = Color(Ft8Look.MUTE),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    LazyColumn(Modifier.weight(1f), state = listState) {
                        itemsIndexed(lines, key = { index, line ->
                            when (line) {
                                is Ft8Line.Slot -> "slot-$index-${line.time}"
                                is Ft8Line.Decode -> "row-${line.row.id}-$index"
                            }
                        }) { _, line ->
                            when (line) {
                                is Ft8Line.Slot -> Ft8Slot(line.time)
                                is Ft8Line.Decode -> Ft8Decode(line.row, line.body) {
                                    cursorHz = line.row.freqHz
                                    if (!Ft8Look.canReply(line.row.cellType)) return@Ft8Decode
                                    confirm = Ft8Confirm(
                                        "Reply",
                                        line.body.ifBlank { line.row.text },
                                        PanelCommands.ft8("reply", line.row.id.toString()),
                                        enabling = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        refusal?.let {
            Text(it, color = RfGuru.red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
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
                    onClick = { refusal = operateResult(viewModel, "open") },
                    enabled = held,
                ) { Text("Open") }
            } else {
                OutlinedButton(
                    onClick = { refusal = operateResult(viewModel, "close") },
                    enabled = held,
                ) { Text("Close") }
            }
            OutlinedButton(
                onClick = { refusal = operateResult(viewModel, "stop") },
                enabled = held,
            ) { Text("Stop") }
        }
        Ft8Keys(
            page = page,
            statusOpen = status?.open == true,
            callingCq = status?.callingCq == true,
            transmitting = status?.transmitting == true,
            autoOn = status?.autoOn == true,
            hold = status?.hold == true,
            hound = status?.hound == true,
            repeats = status?.repeats ?: 0,
            modeName = name,
            showAll = showAll,
            tiles = tiles,
            held = held,
            mayTransmit = mayTransmit,
            onKey = { key ->
                when (key) {
                    Ft8Key.PAGE -> page = (page + 1) % 5
                    Ft8Key.SHOW -> showAll = !showAll
                    Ft8Key.VIEW -> {
                        tiles = !tiles
                        tilePage = 0
                    }
                    Ft8Key.CQ -> confirm = toggleConfirm(
                        on = status?.callingCq == true,
                        onTitle = "Stop CQ",
                        offTitle = "CQ",
                        onDetail = "The radio will stop calling CQ.",
                        offDetail = "The radio will call CQ.",
                        word = "cq",
                    )
                    Ft8Key.TX_CALL -> confirm = toggleConfirm(
                        on = status?.transmitting == true,
                        onTitle = "TX Call off",
                        offTitle = "TX Call",
                        onDetail = "The radio will stop transmitting the call.",
                        offDetail = "The radio will transmit when it answers a call.",
                        word = "txcall",
                    )
                    Ft8Key.AUTO -> confirm = toggleConfirm(
                        on = status?.autoOn == true,
                        onTitle = "Auto off",
                        offTitle = "Auto",
                        onDetail = "The radio will stop answering on its own.",
                        offDetail = "The radio will answer calls on its own.",
                        word = "auto",
                    )
                    Ft8Key.HOLD -> refusal = operateResult(viewModel, "hold")
                    Ft8Key.MODE -> refusal = operateResult(viewModel, "mode")
                    else -> refusal = NOT_ON_PANEL
                }
            },
        )
    }

    confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = RfGuru.bar,
            titleContentColor = RfGuru.gold,
            textContentColor = RfGuru.text,
            title = { Text(pending.title) },
            text = { Text(pending.detail, color = RfGuru.text) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    refusal = viewModel.ft8Transmit(pending.line, pending.enabling)
                }) { Text(if (pending.enabling) "Transmit" else "Send", color = RfGuru.red) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Cancel", color = RfGuru.text) }
            },
        )
    }
}

private fun toggleConfirm(
    on: Boolean,
    onTitle: String,
    offTitle: String,
    onDetail: String,
    offDetail: String,
    word: String,
) = Ft8Confirm(
    title = if (on) onTitle else offTitle,
    detail = if (on) onDetail else offDetail,
    line = PanelCommands.ft8(word),
    enabling = !on,
)

private fun operateResult(viewModel: RadioViewModel, what: String): String? {
    val line = PanelCommands.ft8(what)
    return if (viewModel.ft8Operate(line)) null else "Take control before this."
}

@Composable
private fun Ft8Lane(
    heard: List<Ft8Heard>,
    txHz: Int?,
    lowHz: Int?,
    highHz: Int?,
    selectedCall: String,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(Ft8Look.LANE_PX.dp)
            .background(Color(Ft8Look.PANEL)),
    ) {
        val low = lowHz ?: 0
        val high = highHz ?: 0
        if (txHz != null && high > low) {
            val x = waterfallX(txHz, low, high, 1f)
            if (x != null) {
                Text(
                    "TX",
                    color = Color(Ft8Look.WARN),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.placeAt(x),
                )
            }
        }
        heard.forEach { station ->
            val x = if (high > low) waterfallX(station.freqHz, low, high, 1f) else null
            if (x == null) return@forEach
            val ink = Ft8Look.laneInk(
                toMe = station.toMe,
                cq = station.cellType == Ft8Look.CELL_CQ,
                selected = station.call.equals(selectedCall, ignoreCase = true),
            )
            Text(
                station.call,
                color = Color(ink),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.placeAt(x),
            )
        }
    }
}

/** Fraction across the lane, kept on the strip so a call at the right edge is still readable. */
private fun Modifier.placeAt(fraction: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val x = ((constraints.maxWidth - placeable.width) * fraction).toInt().coerceAtLeast(0)
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(x, 0)
    }
}

@Composable
private fun Ft8Waterfall(
    frames: List<radio.kd0cq.x6100.net.AppWaterfallFrame>,
    txHz: Int?,
    widthHz: Int,
    cursorHz: Int?,
    sending: Boolean,
    gauss: IntArray,
) {
    val latest = frames.lastOrNull()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (frames.isEmpty()) {
            Text(
                "The waterfall appears when the radio sends one.",
                color = RfGuru.dim,
                fontSize = 12.sp,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val shown = frames.takeLast(size.height.toInt().coerceAtLeast(1))
                if (shown.isEmpty()) return@Canvas
                val rowH = size.height / shown.size
                shown.forEachIndexed { index, frame ->
                    val bins = frame.bins.coerceAtLeast(1)
                    val binW = size.width / bins
                    for (bin in frame.db.indices) {
                        val db = frame.db[bin].toInt().coerceIn(0, 30)
                        drawRect(
                            color = Color(gauss[db * 255 / 30]),
                            topLeft = androidx.compose.ui.geometry.Offset(bin * binW, index * rowH),
                            size = androidx.compose.ui.geometry.Size(binW + 1f, rowH + 1f),
                        )
                    }
                }
                val low = latest?.lowHz ?: return@Canvas
                val high = latest.highHz
                val band = if (txHz != null) finderBand(txHz, widthHz, low, high, size.width) else null
                if (band != null) {
                    drawRect(
                        color = Color(0xFF08090C).copy(alpha = 0.5f),
                        topLeft = androidx.compose.ui.geometry.Offset(band.first, 0f),
                        size = androidx.compose.ui.geometry.Size(band.second - band.first, size.height),
                    )
                    val edge = Color(Ft8Look.TEXT).copy(alpha = 0.5f)
                    drawLine(edge, androidx.compose.ui.geometry.Offset(band.first, 0f), androidx.compose.ui.geometry.Offset(band.first, size.height), 1f)
                    drawLine(edge, androidx.compose.ui.geometry.Offset(band.second, 0f), androidx.compose.ui.geometry.Offset(band.second, size.height), 1f)
                }
                val mark = cursorHz?.let { waterfallX(it, low, high, size.width) }
                if (mark != null) {
                    drawLine(
                        Color(Ft8Look.TEXT),
                        androidx.compose.ui.geometry.Offset(mark, 0f),
                        androidx.compose.ui.geometry.Offset(mark, size.height),
                        1f,
                    )
                }
            }
            if (sending) {
                Box(Modifier.fillMaxSize().border(2.dp, RfGuru.tx))
            }
            if (latest != null) {
                Text(
                    "${latest.lowHz}–${latest.highHz} Hz",
                    color = RfGuru.dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(2.dp),
                )
            }
        }
    }
}

@Composable
private fun Ft8Slot(time: String) {
    Row(
        Modifier.fillMaxWidth().background(Color(Ft8Look.PANEL).copy(alpha = Ft8Look.LIST_ALPHA)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(Color(Ft8Look.LINE).copy(alpha = 0.5f)))
        Text(
            time,
            color = Color(Ft8Look.MUTE),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(Color(Ft8Look.LINE).copy(alpha = 0.5f)))
    }
}

@Composable
private fun Ft8Decode(row: Ft8Row, body: String, onReply: () -> Unit) {
    val cq = row.cellType == Ft8Look.CELL_CQ
    val toMe = row.cellType == Ft8Look.CELL_TO_ME
    val ours = row.cellType == Ft8Look.CELL_TX
    val info = row.cellType == Ft8Look.CELL_INFO || row.cellType == Ft8Look.CELL_START
    val background = when {
        toMe -> Color(Ft8Look.TO_ME)
        ours -> Color(Ft8Look.TX)
        info -> Color(Ft8Look.ROW)
        else -> Color(Ft8Look.PANEL)
    }.copy(alpha = Ft8Look.LIST_ALPHA)
    val ink = if (cq || toMe || ours || info) Color(Ft8Look.TEXT) else Color(Ft8Look.MUTE)
    val strike = cq && row.workedType == Ft8Look.WORKED_SAME
    val tag = when {
        cq && row.workedType == Ft8Look.WORKED_NO -> "CQ"
        cq -> "worked"
        else -> ""
    }
    val tagInk = if (tag == "CQ") Color(Ft8Look.OK) else Color(Ft8Look.MUTE)
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(enabled = Ft8Look.canReply(row.cellType), onClick = onReply)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cq) {
            Box(
                Modifier
                    .padding(vertical = 2.dp)
                    .width(3.dp)
                    .height(16.dp)
                    .background(Color(Ft8Look.CQ_EDGE).copy(alpha = if (row.workedType == Ft8Look.WORKED_NO) 1f else 0.4f)),
            )
        }
        Text(
            body,
            color = ink.copy(alpha = if (cq && row.workedType == Ft8Look.WORKED_YES) 0.8f else 1f),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (info) TextAlign.Center else TextAlign.Start,
            textDecoration = if (strike) TextDecoration.LineThrough else TextDecoration.None,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
        )
        if (tag.isNotEmpty()) {
            Text(
                tag,
                color = tagInk,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier
                    .background(Color(Ft8Look.CHIP), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (row.pota != 0) {
            Text(
                "POTA",
                color = ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 4.dp).border(1.dp, ink, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
            )
        }
        if (!info && !ours && row.country.isNotEmpty()) {
            Text(row.country, color = ink, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
        }
        if (!info && !ours && row.distanceKm > 0) {
            Text("${row.distanceKm} km", color = ink, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
        }
        if (!info && !ours) {
            Text("${row.snrDb} dB", color = ink, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp))
        }
    }
}

@Composable
private fun Ft8Tiles(
    heard: List<Ft8Heard>,
    page: Int,
    selected: String,
    onPage: (Int) -> Unit,
    onCall: (Ft8Heard) -> Unit,
) {
    val pages = (heard.size + 8) / 9
    val first = page.coerceAtLeast(0) * 9
    Column(Modifier.fillMaxSize().background(Color(Ft8Look.PANEL))) {
        if (heard.isEmpty()) {
            Text(
                "Listening... the stations heard come here",
                color = RfGuru.dim,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(12.dp),
            )
        } else {
            Column(Modifier.weight(1f).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in 0 until 3) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (col in 0 until 3) {
                            val station = heard.getOrNull(first + row * 3 + col)
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (station != null) Ft8Tile(station, station.call.equals(selected, ignoreCase = true)) { onCall(station) }
                            }
                        }
                    }
                }
            }
        }
        if (pages > 1) {
            Text(
                "${page + 1}/$pages",
                color = RfGuru.dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable { onPage((page + 1) % pages) }.padding(2.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Ft8Tile(station: Ft8Heard, selected: Boolean, onCall: () -> Unit) {
    val fresh = station.toMe
    val tag = when {
        fresh -> "calls you"
        station.cellType == Ft8Look.CELL_CQ && station.pota != 0 -> "CQ POTA"
        station.cellType == Ft8Look.CELL_CQ && station.workedType == Ft8Look.WORKED_NO -> "CQ"
        station.cellType == Ft8Look.CELL_CQ -> "worked"
        station.workedType != Ft8Look.WORKED_NO -> "worked"
        else -> "QSO"
    }
    val tagInk = when (tag) {
        "CQ", "CQ POTA" -> Color(Ft8Look.OK)
        "calls you" -> Color(Ft8Look.TEXT)
        else -> Color(Ft8Look.MUTE)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(if (fresh) Color(Ft8Look.ROW_TO_ME) else Color(Ft8Look.ROW), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) Color(Ft8Look.WARN) else Color.Transparent,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onCall)
            .padding(6.dp),
    ) {
        Text(station.call, color = Color(Ft8Look.TEXT), fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            val lit = Ft8Look.signalBars(station.snrDb)
            for (bar in 0 until 5) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height((6 + bar * 3).dp)
                        .background(if (bar < lit) Color(Ft8Look.OK) else Color(Ft8Look.LINE)),
                )
            }
        }
        val where = buildString {
            if (station.country.isNotEmpty()) append(station.country)
            if (station.distanceKm > 0) {
                if (isNotEmpty()) append("  ")
                append(station.distanceKm)
                append(" km")
            }
        }
        if (where.isNotEmpty()) {
            Text(where, color = if (fresh) Color(Ft8Look.TEXT) else Color(Ft8Look.MUTE), fontSize = 11.sp, maxLines = 1)
        }
        Text(tag, color = tagInk, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Ft8Keys(
    page: Int,
    statusOpen: Boolean,
    callingCq: Boolean,
    transmitting: Boolean,
    autoOn: Boolean,
    hold: Boolean,
    hound: Boolean,
    repeats: Int,
    modeName: String,
    showAll: Boolean,
    tiles: Boolean,
    held: Boolean,
    mayTransmit: Boolean,
    onKey: (Ft8Key) -> Unit,
) {
    val keys = ft8Page(page)
    Row(
        Modifier.fillMaxWidth().height(58.dp).background(RfGuru.barBottom),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        keys.forEach { key ->
            if (key == null) {
                Box(Modifier.weight(1f).fillMaxHeight())
            } else {
                val label = ft8Label(
                    key, page, callingCq, transmitting, autoOn, hold, hound, repeats, modeName, showAll, tiles,
                )
                val enabled = when (key) {
                    Ft8Key.PAGE, Ft8Key.SHOW, Ft8Key.VIEW -> true
                    Ft8Key.CQ, Ft8Key.AUTO, Ft8Key.TX_CALL -> if (keyOn(key, callingCq, transmitting, autoOn)) held else held && mayTransmit
                    else -> if (ft8KeySends(key)) held else true
                }
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
                        color = if (enabled) RfGuru.text else RfGuru.mute,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
    if (!statusOpen) {
        Text(
            "Open starts the decoder on the radio. The five keys are the radio's button bar.",
            color = RfGuru.dim,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun keyOn(key: Ft8Key, cq: Boolean, tx: Boolean, auto: Boolean): Boolean = when (key) {
    Ft8Key.CQ -> cq
    Ft8Key.TX_CALL -> tx
    Ft8Key.AUTO -> auto
    else -> false
}

private fun ft8Label(
    key: Ft8Key,
    page: Int,
    cq: Boolean,
    tx: Boolean,
    auto: Boolean,
    hold: Boolean,
    hound: Boolean,
    repeats: Int,
    modeName: String,
    showAll: Boolean,
    tiles: Boolean,
): String = when (key) {
    Ft8Key.PAGE -> "(FT8 ${page + 1}:5)"
    Ft8Key.CQ -> "TX CQ:\n${if (cq) "Enabled" else "Disabled"}"
    Ft8Key.TX_CALL -> "TX Call:\n${if (tx) "Enabled" else "Disabled"}"
    Ft8Key.SHOW -> "Show:\n${if (showAll) "All" else "CQ"}"
    Ft8Key.VIEW -> "View:\n${if (tiles) "Tiles" else "Traffic"}"
    Ft8Key.AUTO -> "Auto:\n${if (auto) "Enabled" else "Disabled"}"
    Ft8Key.HOLD -> "Hold Freq:\n${if (hold) "Enabled" else "Disabled"}"
    Ft8Key.RETRIES -> "Retries:\n$repeats"
    Ft8Key.FORCE_SAVE -> "Force QSO\nsave"
    Ft8Key.MODE -> "Mode:\n$modeName"
    Ft8Key.FREQ -> "Freq:\nPreset"
    Ft8Key.HOUND -> "Hound:\n${if (hound) "On" else "Off"}"
    Ft8Key.POWER -> "Pwr"
    Ft8Key.CQ_MOD -> "CQ\nModifier"
    Ft8Key.TIME_SYNC -> "Time\nSync"
    Ft8Key.LIVE_MAP -> "Live\nMap"
    Ft8Key.TUNE -> "Tune"
    Ft8Key.LOG_MAP -> "Log\nMap"
    Ft8Key.FOR_YOU -> "For you"
}
