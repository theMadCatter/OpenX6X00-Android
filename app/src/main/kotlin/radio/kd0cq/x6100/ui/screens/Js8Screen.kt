// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// JS8. The screen is here. The commands are not sent.
// remote_server.c has ft8 and does not have js8, js8r or js8 wf. A button that
// transmitted anyway would be answered "err unknown command" and would not be
// a real transmit. Heartbeat, CQ and the compose box stay visible and say so.

package radio.kd0cq.x6100.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.kd0cq.x6100.digital.JS8_IN_FIRMWARE
import radio.kd0cq.x6100.digital.Js8Protocol
import radio.kd0cq.x6100.digital.Js8Row
import radio.kd0cq.x6100.ui.face.PENDING_FIRMWARE
import radio.kd0cq.x6100.ui.theme.RfGuru

@Composable
fun Js8Screen(rows: List<Js8Row> = emptyList()) {
    var compose by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(PENDING_FIRMWARE) }
    val activity = rows.filter { !it.directed }
    val directed = rows.filter { it.directed }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("JS8", color = RfGuru.gold, fontFamily = FontFamily.Monospace, fontSize = 20.sp)
        Text(note, color = RfGuru.mute, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("TX only through js8 tx, js8 cq or js8 hb. None of those are sent on this firmware.", color = RfGuru.dim, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("cq" to "CQ", "hb" to "Heartbeat", "wf" to "Waterfall").forEach { (what, label) ->
                OutlinedButton(onClick = {
                    note = if (JS8_IN_FIRMWARE) {
                        if (what == "wf") Js8Protocol.waterfall(true) else Js8Protocol.command(what)
                    } else {
                        PENDING_FIRMWARE
                    }
                }) { Text(label) }
            }
        }
        Text("Activity", color = RfGuru.goldText)
        if (activity.isEmpty()) Text("No JS8 activity. The radio does not send js8r yet.", color = RfGuru.dim)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(activity, key = { "a${it.id}${it.text}" }) { row -> Js8Line(row) }
        }
        Text("Directed", color = RfGuru.goldText)
        if (directed.isEmpty()) Text("No directed messages.", color = RfGuru.dim)
        directed.forEach { Js8Line(it) }
        OutlinedTextField(
            value = compose,
            onValueChange = { compose = it },
            label = { Text("Compose") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = {
            note = if (JS8_IN_FIRMWARE && compose.isNotBlank()) Js8Protocol.command("tx", compose) else PENDING_FIRMWARE
        }) { Text("Transmit") }
    }
}

@Composable
private fun Js8Line(row: radio.kd0cq.x6100.digital.Js8Row) {
    Text(
        "${row.call}  ${row.snr} dB  ${row.text}",
        color = if (row.directed) RfGuru.gold else RfGuru.text,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}
