// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The FT8 / FT4 / FT2 screen, matched to the radio's one window
// (src/ft8_window.c and src/ft8/decode_list.c).
//
// Before this, the screen was a column of buttons over a reversed list.
// CQ, Auto and a row tap sent as soon as the session could operate. There
// was no Take control, no confirmation, and no band-edge check. The list
// also had no slot line, and a call to us was only red text.
//
// The radio keeps one app and cycles the protocol with Mode: FT8, then FT4,
// then FT2. The title is that name. The list is oldest first. A slot's time
// is one centred line, and the row under it is the message without the time.
// A call to us is the red row, our TX is the blue row, a CQ has a green edge
// and a CQ or "worked" tag, and the other traffic is grey. SNR, kilometres
// and the country sit at the end of the row, which is what the radio draws
// there. The waterfall is the same 0xFFFD picture JS8 uses. The radio has
// one client flag for both, so this screen asks only while it is open.
//
// CQ, Auto, TX Call and a reply ask in a dialog, and they are refused until
// this session holds control, may transmit, and the dial plus the TX offset
// plus that protocol's tone width sits in a US Advanced data segment.
// Turning CQ, Auto or TX Call off does not need the band check, so a bad
// offset cannot leave the transmitter on. Open is a button. Nothing here
// sends on composition except "ft8 wf 1".

package radio.kd0cq.x6100.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.kd0cq.x6100.digital.Ft8Line
import radio.kd0cq.x6100.digital.Ft8Look
import radio.kd0cq.x6100.digital.ft8Lines
import radio.kd0cq.x6100.net.Ft8Row
import radio.kd0cq.x6100.net.PanelCommands
import radio.kd0cq.x6100.ui.RadioViewModel
import radio.kd0cq.x6100.ui.theme.RfGuru

private data class Ft8Confirm(val title: String, val detail: String, val line: String, val enabling: Boolean)

@Composable
fun Ft8Screen(viewModel: RadioViewModel, onJs8: () -> Unit = {}) {
    val rows by viewModel.ft8Rows.collectAsState()
    val status by viewModel.ft8Status.collectAsState()
    val waterfall by viewModel.appWaterfall.collectAsState()
    val role by viewModel.role.collectAsState()
    val control by viewModel.control.collectAsState()
    var confirm by remember { mutableStateOf<Ft8Confirm?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val lines = remember(rows) { ft8Lines(rows) }
    val name = Ft8Look.protocolName(status?.protocol ?: 1)

    DisposableEffect(Unit) {
        viewModel.ft8Watch(true)
        onDispose { viewModel.ft8Watch(false) }
    }

    val held = control.mine && role.canOperate
    val blocked = viewModel.ft8TransmitBlocked()
    val mayTransmit = blocked == null
    val txOffset = status?.txFreqHz

    LaunchedEffect(lines.size) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (visible < 0 || visible >= last - 2) listState.scrollToItem(last)
    }

    DigitalSplit(
        header = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, color = RfGuru.gold, fontFamily = FontFamily.Monospace, fontSize = 20.sp)
                if (status?.sending == true) {
                    Text(
                        "TX",
                        color = RfGuru.onTx,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.background(RfGuru.tx).padding(horizontal = 8.dp),
                    )
                }
            }
            Text(Ft8Look.statusLine(status), color = RfGuru.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2)
            refusal?.let { Text(it, color = RfGuru.red, fontSize = 12.sp) }
        },
        picture = {
            AppWaterfallPicture(
                frames = waterfall,
                txHz = txOffset,
                selectedHz = null,
                sending = status?.sending == true,
                lane = emptyList(),
                empty = "The waterfall appears when the radio sends one. Watching needs no role.",
            )
        },
        list = { modifier ->
            if (lines.isEmpty()) {
                Text("Wait sync", color = RfGuru.dim, fontSize = 13.sp, modifier = modifier.padding(vertical = 8.dp))
            } else {
                LazyColumn(modifier, state = listState) {
                    itemsIndexed(lines, key = { index, line ->
                        when (line) {
                            is Ft8Line.Slot -> "slot-$index-${line.time}"
                            is Ft8Line.Decode -> "row-${line.row.id}-$index"
                        }
                    }) { _, line ->
                        when (line) {
                            is Ft8Line.Slot -> Text(
                                line.time,
                                color = Color(Ft8Look.MUTE),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                            is Ft8Line.Decode -> Ft8Decode(line.row, line.body) {
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
        },
        controls = {
            if (!held) {
                OutlinedButton(onClick = { viewModel.takeControl() }, enabled = role.canOperate) {
                    Text("Take control")
                }
            } else if (blocked != null) {
                Text(blocked, color = RfGuru.red, fontSize = 12.sp)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { operate(viewModel, "open") { refusal = it } }, enabled = held) { Text("Open") }
                OutlinedButton(onClick = { operate(viewModel, "close") { refusal = it } }, enabled = held) { Text("Close") }
                OutlinedButton(
                    onClick = {
                        val on = status?.callingCq == true
                        confirm = Ft8Confirm(
                            if (on) "Stop CQ" else "CQ",
                            if (on) "The radio will stop calling CQ." else "The radio will call CQ.",
                            PanelCommands.ft8("cq"),
                            enabling = !on,
                        )
                    },
                    enabled = if (status?.callingCq == true) held else mayTransmit,
                ) { Text(if (status?.callingCq == true) "CQ on" else "CQ") }
                OutlinedButton(onClick = { operate(viewModel, "stop") { refusal = it } }, enabled = held) { Text("Stop") }
                OutlinedButton(
                    onClick = {
                        val on = status?.autoOn == true
                        confirm = Ft8Confirm(
                            if (on) "Auto off" else "Auto",
                            if (on) "The radio will stop answering on its own."
                            else "The radio will answer calls on its own.",
                            PanelCommands.ft8("auto"),
                            enabling = !on,
                        )
                    },
                    enabled = if (status?.autoOn == true) held else mayTransmit,
                ) { Text(if (status?.autoOn == true) "Auto on" else "Auto") }
                OutlinedButton(onClick = { operate(viewModel, "hold") { refusal = it } }, enabled = held) {
                    Text(if (status?.hold == true) "Hold on" else "Hold")
                }
                OutlinedButton(onClick = { operate(viewModel, "mode") { refusal = it } }, enabled = held) {
                    Text("Mode: $name")
                }
                OutlinedButton(
                    onClick = {
                        val on = status?.transmitting == true
                        confirm = Ft8Confirm(
                            if (on) "TX Call off" else "TX Call",
                            if (on) "The radio will stop transmitting the call."
                            else "The radio will transmit when it answers a call.",
                            PanelCommands.ft8("txcall"),
                            enabling = !on,
                        )
                    },
                    enabled = if (status?.transmitting == true) held else mayTransmit,
                ) { Text(if (status?.transmitting == true) "TX Call on" else "TX Call") }
                OutlinedButton(onClick = onJs8) { Text("JS8") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "TX ${txOffset ?: "—"} Hz",
                    color = RfGuru.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        val hz = txOffset ?: return@OutlinedButton
                        operate(viewModel, "txfreq", hz - 10) { refusal = it }
                    },
                    enabled = held && txOffset != null,
                ) { Text("−10") }
                OutlinedButton(
                    onClick = {
                        val hz = txOffset ?: return@OutlinedButton
                        operate(viewModel, "txfreq", hz + 10) { refusal = it }
                    },
                    enabled = held && txOffset != null,
                ) { Text("+10") }
            }
        },
    )

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
                    val reason = if (pending.enabling || ft8WordIsTransmit(pending.line)) {
                        viewModel.ft8Transmit(pending.line, pending.enabling)
                    } else {
                        null
                    }
                    refusal = reason
                }) { Text(if (pending.enabling) "Transmit" else "Send", color = RfGuru.red) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Cancel", color = RfGuru.text) }
            },
        )
    }
}

private fun ft8WordIsTransmit(line: String): Boolean {
    val word = line.trim().removePrefix("ft8 ").substringBefore(' ')
    return word == "cq" || word == "auto" || word == "reply" || word == "txcall"
}

private fun operate(viewModel: RadioViewModel, what: String, hz: Int? = null, refusal: (String?) -> Unit) {
    val line = if (hz != null) PanelCommands.ft8(what, hz.toString()) else PanelCommands.ft8(what)
    refusal(if (viewModel.ft8Operate(line)) null else "Take control before this.")
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
        else -> Color(Ft8Look.PANEL)
    }
    val ink = if (cq || toMe || ours || info) Color(Ft8Look.TEXT) else Color(Ft8Look.MUTE)
    val strike = cq && row.workedType == Ft8Look.WORKED_SAME
    val tag = when {
        cq && row.workedType == Ft8Look.WORKED_NO -> "CQ"
        cq -> "worked"
        else -> ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .height(IntrinsicSize.Min)
            .background(background)
            .clickable(enabled = Ft8Look.canReply(row.cellType), onClick = onReply),
    ) {
        if (cq) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(Color(Ft8Look.CQ_EDGE).copy(alpha = if (row.workedType == Ft8Look.WORKED_NO) 1f else 0.4f)),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 3.dp)) {
            Text(
                buildString {
                    append(body)
                    if (tag.isNotEmpty()) append("  ").append(tag)
                    if (row.pota != 0) append("  POTA")
                },
                color = ink.copy(alpha = if (cq && row.workedType == Ft8Look.WORKED_YES) 0.8f else 1f),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                textAlign = if (info) TextAlign.Center else TextAlign.Start,
                textDecoration = if (strike) TextDecoration.LineThrough else TextDecoration.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!info && !ours) {
            Column(Modifier.padding(end = 6.dp, top = 3.dp)) {
                Text("${row.snrDb} dB", color = ink, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                if (row.distanceKm > 0) {
                    Text("${row.distanceKm} km", color = ink, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                if (row.country.isNotEmpty()) {
                    Text(row.country, color = ink, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}
