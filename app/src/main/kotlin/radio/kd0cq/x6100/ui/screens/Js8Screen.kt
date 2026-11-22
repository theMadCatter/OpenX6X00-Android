// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The JS8 and GhostNet screen, driven by the radio's own JS8 engine.
//
// Before: the buttons only wrote a local note. Transmit was "js8 tx", which
// the firmware does not have, and the screen never held a view model, so it
// could not see a message or a waterfall. The words below are the ones
// remote_server.c dispatches as of OpenX6X00 v0.3.13.
//
// What this screen sends on its own is "js8 wf 1" while it is open and
// "js8 wf 0" when it leaves. Watching needs no role. It does not send
// "js8 open", and it does not turn Auto or HB on. Those stay as the radio
// last reported them.
//
// Send, CQ, auto CQ, HB, HB now and Auto stay disabled until this session
// may operate, holds control, may transmit, and the dial plus the TX offset
// sits inside a US Advanced data segment. Send, CQ and auto CQ ask in a
// dialog. The dialog's Transmit button is the only place those three lines
// are handed to the view model. Nothing in composition calls them.

package radio.kd0cq.x6100.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.kd0cq.x6100.digital.GhostNet
import radio.kd0cq.x6100.digital.Js8Protocol
import radio.kd0cq.x6100.digital.Js8Row
import radio.kd0cq.x6100.net.AppWaterfallFrame
import radio.kd0cq.x6100.ui.RadioViewModel
import radio.kd0cq.x6100.ui.theme.RfGuru

private data class Js8Confirm(val title: String, val detail: String, val line: String)

@Composable
fun Js8Screen(viewModel: RadioViewModel, onBack: () -> Unit) {
    val rows by viewModel.js8Rows.collectAsState()
    val status by viewModel.js8Status.collectAsState()
    val waterfall by viewModel.js8Waterfall.collectAsState()
    val needsFirmware by viewModel.js8NeedsFirmware.collectAsState()
    val role by viewModel.role.collectAsState()
    val control by viewModel.control.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var ghost by rememberSaveable { mutableStateOf(false) }
    var region by rememberSaveable { mutableStateOf(viewModel.ghostnetGroup()) }
    var offsetText by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Js8Confirm?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }

    // The waterfall subscription is the screen being open. It is not "js8 open".
    DisposableEffect(Unit) {
        viewModel.js8Watch(true)
        onDispose { viewModel.js8Watch(false) }
    }

    val held = control.mine && role.canOperate
    val blocked = viewModel.js8TransmitBlocked()
    val mayTransmit = blocked == null
    val txOffset = status?.txFreqHz ?: 0
    val heartbeatOk = !ghost || GhostNet.heartbeatInWindow(txOffset)
    val shown = if (ghost) GhostNet.filter(rows, region) else rows

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("JS8", color = RfGuru.gold, fontFamily = FontFamily.Monospace, fontSize = 20.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back", color = RfGuru.goldText) }
        }

        if (needsFirmware) {
            Text(Js8Protocol.NEEDS_FIRMWARE, color = RfGuru.red, fontSize = 14.sp)
        }
        Text(statusWords(status), color = RfGuru.text, fontSize = 13.sp)
        if (!held) {
            Text(
                "Take control before any command. The waterfall does not need it.",
                color = RfGuru.text,
                fontSize = 13.sp,
            )
            OutlinedButton(onClick = { viewModel.takeControl() }, enabled = role.canOperate) {
                Text("Take control")
            }
        } else if (blocked != null) {
            Text(blocked, color = RfGuru.red, fontSize = 13.sp)
        }
        refusal?.let { Text(it, color = RfGuru.red, fontSize = 13.sp) }

        Js8Waterfall(waterfall)

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { operate(viewModel, Js8Protocol.command("open")) { refusal = it } },
                enabled = held,
            ) { Text("Open") }
            OutlinedButton(
                onClick = { operate(viewModel, Js8Protocol.command("close")) { refusal = it } },
                enabled = held,
            ) { Text("Close") }
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
            ) { Text("Speed: ${Js8Protocol.speedName(status?.speed ?: 0)}") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("TX offset ${txOffset} Hz", color = RfGuru.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { operate(viewModel, Js8Protocol.txFreq(txOffset - 10)) { refusal = it } },
                enabled = held,
            ) { Text("−10") }
            OutlinedButton(
                onClick = { operate(viewModel, Js8Protocol.txFreq(txOffset + 10)) { refusal = it } },
                enabled = held,
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

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { ghost = false }) {
                Text("All", color = if (!ghost) RfGuru.gold else RfGuru.text)
            }
            OutlinedButton(onClick = { ghost = true }) {
                Text("GhostNet", color = if (ghost) RfGuru.gold else RfGuru.text)
            }
        }
        if (ghost) {
            Text(GhostNet.REMINDER, color = RfGuru.text, fontSize = 13.sp)
            Text(GhostNet.SETTINGS_NOTE, color = RfGuru.dim, fontSize = 12.sp)
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
                    "HB and HB now stay off while the TX offset is ${txOffset} Hz. The net keeps heartbeats below 1000 Hz.",
                    color = RfGuru.red,
                    fontSize = 12.sp,
                )
            }
        }

        Text(
            if (ghost) "GhostNet" else "Messages",
            color = RfGuru.goldText,
            fontSize = 14.sp,
        )
        if (shown.isEmpty()) {
            Text(
                if (ghost) "No @GHOSTNET, @GSTFLASH or ${GhostNet.groupToken(region).ifEmpty { GhostNet.DEFAULT_REGION }} traffic yet."
                else "No JS8 messages yet. The radio replays the last 64 when it has them.",
                color = RfGuru.dim,
                fontSize = 13.sp,
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(shown, key = { it.id }) { row ->
                Js8Line(row) {
                    val line = Js8Protocol.select(row.from) ?: return@Js8Line
                    operate(viewModel, line) { refusal = it }
                }
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
                    transmit(viewModel, pending.line) { refusal = it }
                }) { Text("Transmit", color = RfGuru.red) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Cancel", color = RfGuru.text) }
            },
        )
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
private fun Js8Waterfall(frames: List<AppWaterfallFrame>) {
    val latest = frames.lastOrNull()
    Box(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(Color.Black),
    ) {
        if (frames.isEmpty()) {
            Text(
                "The waterfall appears when the radio sends one. Watching needs no role.",
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
                        drawRect(
                            color = js8BinColor(frame.db[bin].toInt()),
                            topLeft = Offset(bin * binW, index * rowH),
                            size = Size(binW + 1f, rowH + 1f),
                        )
                    }
                }
            }
        }
    }
    latest?.let {
        Text(
            "${it.lowHz}–${it.highHz} Hz",
            color = RfGuru.dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

/** dB over the noise floor, as the int8 in the 0xFFFD frame. Zero stays dark. */
private fun js8BinColor(db: Int): Color {
    val v = db.coerceIn(0, 70) / 70f
    return Color(0.04f + v * 0.95f, 0.10f + v * 0.55f, 0.02f)
}

@Composable
private fun Js8Line(row: Js8Row, onSelect: () -> Unit) {
    val flash = GhostNet.isFlash(row)
    val letter = Js8Protocol.submodeLetter(row.submode)
    val marks = buildList {
        if (row.toMe) add("me")
        if (row.cq) add("CQ")
        if (row.heartbeat) add("HB")
        if (row.group) add("grp")
        if (row.partial) add("partial")
        if (letter.isNotEmpty()) add(letter)
    }
    val color = when {
        flash -> RfGuru.red
        row.toMe -> RfGuru.goldText
        else -> RfGuru.text
    }
    Text(
        "%06d  %d dB  %d Hz  %s  %s  %s  %s".format(
            row.utc,
            row.snr,
            row.freqHz,
            row.from,
            row.to,
            row.text,
            marks.joinToString(" "),
        ),
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (flash) FontWeight.Bold else FontWeight.Normal,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
    )
}

private fun statusWords(status: radio.kd0cq.x6100.digital.Js8Status?): String {
    if (status == null) return "No JS8 state from the radio yet."
    return buildString {
        append(if (status.open) "JS8 is open on the radio" else "JS8 is closed on the radio")
        if (status.sending) append(" · sending")
        if (status.busy) append(" · busy")
        if (status.autoCq) append(" · auto CQ")
        if (status.heartbeat) append(" · HB on")
        if (status.auto) append(" · auto on")
        if (status.hold) append(" · hold")
        append(" · ")
        append(Js8Protocol.speedName(status.speed))
        if (status.frames > 0) append(" · ${status.frame}/${status.frames}")
        if (status.selectedCall.isNotEmpty()) append(" · ${status.selectedCall}")
        if (status.text.isNotEmpty()) append(" · on the air: ${status.text}")
        if (status.note.isNotEmpty()) append(" · ${status.note}")
    }
}
