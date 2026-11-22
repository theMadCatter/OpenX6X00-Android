// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The JS8 and GhostNet screen, matched to the radio's own JS8 window
// (src/js8_window.c) and adapted to a phone.
//
// Before the layout fix, the message list was the weighted child of a column
// whose waterfall and controls were already taller than a landscape phone,
// and nearly as tall as a portrait one. The list got almost no height, and
// landscape clipped everything under the waterfall because the column did
// not scroll. DigitalSplit gives the list the leftover height and, when the
// window is wide, puts the waterfall beside the list.
//
// The row text is format_row. The colours are message_bg, night theme.
// Show is the radio's All / No HB / Directed, plus GhostNet. The waterfall
// is the same 0xFFFD picture, with the TX offset and the selected station
// marked the way the finder marks them.
//
// What this screen sends on its own is "js8 wf 1" while it is open and
// "js8 wf 0" when it leaves. Watching needs no role. It does not send
// "js8 open", and it does not turn Auto or HB on.
//
// Send, CQ, auto CQ, HB, HB now and Auto stay disabled until this session
// may operate, holds control, may transmit, and the dial plus the TX offset
// sits inside a US Advanced data segment. Send, CQ and auto CQ ask in a
// dialog. The dialog's Transmit button is the only place those three lines
// are handed to the view model. Nothing in composition calls them.

package radio.kd0cq.x6100.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.kd0cq.x6100.digital.GhostNet
import radio.kd0cq.x6100.digital.Js8Look
import radio.kd0cq.x6100.digital.Js8Protocol
import radio.kd0cq.x6100.digital.Js8Row
import radio.kd0cq.x6100.ui.RadioViewModel
import radio.kd0cq.x6100.ui.theme.RfGuru

private data class Js8Confirm(val title: String, val detail: String, val line: String)

@Composable
fun Js8Screen(viewModel: RadioViewModel, onBack: () -> Unit) {
    val rows by viewModel.js8Rows.collectAsState()
    val status by viewModel.js8Status.collectAsState()
    val waterfall by viewModel.appWaterfall.collectAsState()
    val needsFirmware by viewModel.js8NeedsFirmware.collectAsState()
    val role by viewModel.role.collectAsState()
    val control by viewModel.control.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var showName by rememberSaveable { mutableStateOf(Js8Look.Show.ALL.name) }
    var region by rememberSaveable { mutableStateOf(viewModel.ghostnetGroup()) }
    var offsetText by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Js8Confirm?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    val show = runCatching { Js8Look.Show.valueOf(showName) }.getOrDefault(Js8Look.Show.ALL)
    val listState = rememberLazyListState()

    // The waterfall subscription is the screen being open. It is not "js8 open".
    DisposableEffect(Unit) {
        viewModel.js8Watch(true)
        onDispose { viewModel.js8Watch(false) }
    }

    val held = control.mine && role.canOperate
    val blocked = viewModel.js8TransmitBlocked()
    val mayTransmit = blocked == null
    val txOffset = status?.txFreqHz
    val heartbeatOk = show != Js8Look.Show.GHOSTNET || GhostNet.heartbeatInWindow(txOffset ?: 0)
    val shown = Js8Look.visible(rows, show, region)
    val selected = status?.selectedCall.orEmpty()

    LaunchedEffect(shown.size) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (visible < 0 || visible >= last - 2) listState.scrollToItem(last)
    }

    DigitalSplit(
        header = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "JS8",
                    color = RfGuru.gold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) { Text("Back", color = RfGuru.goldText) }
            }
            if (needsFirmware) {
                Text(Js8Protocol.NEEDS_FIRMWARE, color = RfGuru.red, fontSize = 13.sp)
            }
            Text(
                Js8Look.statusLine(status, rows.size),
                color = RfGuru.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 2,
            )
            refusal?.let { Text(it, color = RfGuru.red, fontSize = 12.sp) }
        },
        picture = {
            AppWaterfallPicture(
                frames = waterfall,
                txHz = txOffset,
                selectedHz = status?.selectedHz?.takeIf { it >= 0 },
                sending = status?.sending == true,
                lane = laneOf(shown, selected),
                empty = "The waterfall appears when the radio sends one. Watching needs no role.",
            )
        },
        list = { modifier ->
            if (shown.isEmpty()) {
                Text(
                    if (show == Js8Look.Show.GHOSTNET) {
                        "No @GHOSTNET, @GSTFLASH or ${GhostNet.groupToken(region).ifEmpty { GhostNet.DEFAULT_REGION }} traffic yet."
                    } else {
                        "Listening for JS8..."
                    },
                    color = RfGuru.dim,
                    fontSize = 13.sp,
                    modifier = modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(modifier, state = listState) {
                    items(shown, key = { it.id }) { row ->
                        Js8Line(row, Js8Look.selected(row, selected)) {
                            val line = Js8Protocol.select(row.from) ?: return@Js8Line
                            operate(viewModel, line) { refusal = it }
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
                OutlinedButton(onClick = { operate(viewModel, Js8Protocol.command("open")) { refusal = it } }, enabled = held) {
                    Text("Open")
                }
                OutlinedButton(onClick = { operate(viewModel, Js8Protocol.command("close")) { refusal = it } }, enabled = held) {
                    Text("Close")
                }
                OutlinedButton(
                    onClick = { confirm = Js8Confirm("CQ", "The radio will call CQ.", Js8Protocol.command("cq")) },
                    enabled = mayTransmit,
                ) { Text("CQ") }
                OutlinedButton(
                    onClick = {
                        confirm = Js8Confirm(
                            "Auto CQ",
                            "The radio will keep calling CQ until Stop.",
                            Js8Protocol.command("autocq"),
                        )
                    },
                    enabled = mayTransmit,
                ) { Text("Auto CQ") }
                OutlinedButton(
                    onClick = { heartbeat(viewModel, Js8Protocol.command("hbnow"), heartbeatOk) { refusal = it } },
                    enabled = mayTransmit && heartbeatOk,
                ) { Text("HB now") }
                OutlinedButton(
                    onClick = { heartbeat(viewModel, Js8Protocol.command("hb"), heartbeatOk) { refusal = it } },
                    enabled = mayTransmit && heartbeatOk,
                ) { Text(if (status?.heartbeat == true) "HB on" else "HB") }
                OutlinedButton(
                    onClick = { transmit(viewModel, Js8Protocol.command("auto")) { refusal = it } },
                    enabled = mayTransmit,
                ) { Text(if (status?.auto == true) "Auto on" else "Auto") }
                OutlinedButton(
                    onClick = { operate(viewModel, Js8Protocol.command("hold")) { refusal = it } },
                    enabled = held,
                ) { Text(if (status?.hold == true) "Hold on" else "Hold") }
                OutlinedButton(
                    onClick = { operate(viewModel, Js8Protocol.command("stop")) { refusal = it } },
                    enabled = held,
                ) { Text("Stop") }
                OutlinedButton(
                    onClick = { operate(viewModel, Js8Protocol.command("speed")) { refusal = it } },
                    enabled = held,
                ) { Text(Js8Protocol.speedName(status?.speed ?: 0)) }
                OutlinedButton(onClick = {
                    val next = Js8Look.Show.entries[(show.ordinal + 1) % Js8Look.Show.entries.size]
                    showName = next.name
                }) { Text("Show: ${Js8Look.showName(show)}") }
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
                        operate(viewModel, Js8Protocol.txFreq(hz - 10)) { refusal = it }
                    },
                    enabled = held && txOffset != null,
                ) { Text("−10") }
                OutlinedButton(
                    onClick = {
                        val hz = txOffset ?: return@OutlinedButton
                        operate(viewModel, Js8Protocol.txFreq(hz + 10)) { refusal = it }
                    },
                    enabled = held && txOffset != null,
                ) { Text("+10") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = offsetText,
                    onValueChange = { offsetText = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("Offset Hz", color = RfGuru.dim) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        val hz = offsetText.toIntOrNull() ?: return@OutlinedButton
                        operate(viewModel, Js8Protocol.txFreq(hz)) { refusal = it }
                    },
                    enabled = held && offsetText.isNotEmpty(),
                ) { Text("Set") }
            }
            if (show == Js8Look.Show.GHOSTNET) {
                Text(GhostNet.REMINDER, color = RfGuru.text, fontSize = 12.sp)
                Text(GhostNet.SETTINGS_NOTE, color = RfGuru.dim, fontSize = 11.sp)
                OutlinedTextField(
                    value = region,
                    onValueChange = {
                        region = it.take(16)
                        viewModel.rememberGhostnetGroup(it)
                    },
                    label = { Text("Regional group", color = RfGuru.dim) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!heartbeatOk) {
                    Text(
                        "HB and HB now stay off while the TX offset is ${txOffset ?: "unknown"} Hz. The net keeps heartbeats below 1000 Hz.",
                        color = RfGuru.red,
                        fontSize = 12.sp,
                    )
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(Js8Protocol.TEXT_MAX) },
                label = { Text("Message", color = RfGuru.dim) },
                supportingText = { Text("${draft.length}/${Js8Protocol.TEXT_MAX}", color = RfGuru.dim) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    val line = Js8Protocol.send(draft) ?: return@OutlinedButton
                    confirm = Js8Confirm("Send", draft, line)
                },
                enabled = mayTransmit && Js8Protocol.send(draft) != null,
            ) { Text("Send") }
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
                    transmit(viewModel, pending.line) { refusal = it }
                }) { Text("Transmit", color = RfGuru.red) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Cancel", color = RfGuru.text) }
            },
        )
    }
}

/** The calls over the waterfall: the newest of each station, to us in red, a CQ in the text colour. */
private fun laneOf(rows: List<Js8Row>, selected: String): List<LaneMark> {
    val latest = LinkedHashMap<String, Js8Row>()
    for (row in rows) {
        if (row.transmitting || row.from.isEmpty()) continue
        latest[row.from.uppercase()] = row
    }
    return latest.values.toList().takeLast(8).map { row ->
        val color = when {
            row.from.equals(selected, ignoreCase = true) -> Color(Js8Look.ROW_BAR)
            row.toMe -> Color(Js8Look.ROW_TO_ME)
            row.cq -> Color(Js8Look.TEXT)
            else -> Color(Js8Look.MUTE)
        }
        LaneMark(row.freqHz, row.from, color)
    }
}

/** Open, close, speed, hold, stop, select, txfreq. A false result did not leave the phone. */
private fun operate(viewModel: RadioViewModel, line: String, refusal: (String?) -> Unit) {
    refusal(if (viewModel.js8Operate(line)) null else "Take control before this.")
}

/** HB and HB now. The GhostNet window is decided by the caller before this runs. */
private fun heartbeat(
    viewModel: RadioViewModel,
    line: String,
    inWindow: Boolean,
    refusal: (String?) -> Unit,
) {
    if (!inWindow) {
        refusal("Heartbeats on the North America net stay below a 1000 Hz offset.")
        return
    }
    transmit(viewModel, line, refusal)
}

/** A transmit word. The reason comes back when the line was not sent. */
private fun transmit(viewModel: RadioViewModel, line: String, refusal: (String?) -> Unit) {
    refusal(viewModel.js8Transmit(line))
}

@Composable
private fun Js8Line(row: Js8Row, selected: Boolean, onSelect: () -> Unit) {
    val kind = Js8Look.kind(row)
    val color = Color(Js8Look.foreground(kind)).let { ink ->
        if (row.lowConfidence) ink.copy(alpha = 0.6f) else ink
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .height(IntrinsicSize.Min)
            .background(Color(Js8Look.background(kind)))
            .clickable(onClick = onSelect),
    ) {
        if (selected) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(Color(Js8Look.ROW_BAR)),
            )
        }
        Text(
            Js8Look.line(row),
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (kind == Js8Look.Kind.ALERT) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}
