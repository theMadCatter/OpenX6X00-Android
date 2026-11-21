// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// FT8 rows from the radio. A tap sends "ft8 reply ID", which is the app's transmit.
// CQ is gold, a call to us is red, a station already in the local log is dimmed.
// The slot clock uses the radio's in/slot when it has sent them.

package radio.kd0cq.x6100.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.kd0cq.x6100.digital.Ft8List
import radio.kd0cq.x6100.net.Ft8Row
import radio.kd0cq.x6100.station.StationPrefs
import radio.kd0cq.x6100.ui.RadioViewModel
import radio.kd0cq.x6100.ui.theme.RfGuru

@Composable
fun Ft8Screen(viewModel: RadioViewModel, onJs8: () -> Unit = {}) {
    val rows by viewModel.ft8Rows.collectAsState()
    val status by viewModel.ft8Status.collectAsState()
    val role by viewModel.role.collectAsState()
    val qsos by viewModel.qsos.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { StationPrefs(context) }
    var myCall by remember { mutableStateOf(prefs.myCall()) }
    val tx = status?.transmitting == true || status?.sending == true
    val left = status?.let { Ft8List.remainingMs(System.currentTimeMillis(), it.slotMs, it.inMs) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("FT8", color = RfGuru.gold, fontFamily = FontFamily.Monospace, fontSize = 20.sp)
            if (tx) {
                Text("TX", color = RfGuru.onTx, fontFamily = FontFamily.Monospace, modifier = Modifier.background(RfGuru.tx).padding(horizontal = 8.dp))
            }
        }
        Text(
            buildString {
                append(status?.let { "cq ${it.callingCq}  auto ${it.autoOn}  next ${it.nextMessage}" } ?: "waiting for the app")
                if (left != null) append("   slot ${left / 1000}s")
            },
            color = RfGuru.dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        OutlinedTextField(
            value = myCall,
            onValueChange = {
                myCall = it.uppercase()
                prefs.writeCall(myCall)
            },
            label = { Text("My call") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("open", "close", "cq", "stop", "auto").forEach { what ->
                OutlinedButton(onClick = { viewModel.ft8(what) }, enabled = role.canOperate) { Text(what) }
            }
        }
        OutlinedButton(onClick = onJs8) { Text("JS8") }
        if (rows.isEmpty()) Text("No decoded rows yet.", color = RfGuru.dim)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(rows.reversed(), key = { it.id }) { row ->
                Ft8RowLine(row, myCall, Ft8List.worked(row.call, qsos)) {
                    if (role.canOperate) viewModel.ft8("reply", row.id.toString())
                }
            }
        }
    }
}

@Composable
private fun Ft8RowLine(row: Ft8Row, myCall: String, worked: Boolean, onReply: () -> Unit) {
    val cq = Ft8List.isCq(row.text)
    val mine = row.toMe || Ft8List.mentionsMe(row.text, myCall)
    val color = when {
        mine -> RfGuru.red
        cq -> RfGuru.gold
        worked -> RfGuru.mute
        else -> RfGuru.text
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onReply).padding(vertical = 4.dp)) {
        Text(row.text, color = color, fontFamily = FontFamily.Monospace)
        Text(
            "${row.call}  ${row.grid}  ${row.snrDb} dB  ${row.freqHz} Hz" +
                (if (worked) "  worked" else "") + (if (cq) "  CQ" else ""),
            color = if (worked) RfGuru.mute else RfGuru.dim,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
