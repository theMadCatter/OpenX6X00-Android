// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import radio.kd0cq.x6100.net.Ft8Row
import radio.kd0cq.x6100.ui.RadioViewModel

/** The FT8 decoder's rows, and the app's switches, over the panel link. */
@Composable
fun Ft8Screen(viewModel: RadioViewModel) {
    val rows by viewModel.ft8Rows.collectAsState()
    val status by viewModel.ft8Status.collectAsState()
    val role by viewModel.role.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("FT8", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            status?.let {
                "open ${it.open}  cq ${it.callingCq}  tx ${it.transmitting}  auto ${it.autoOn}  " +
                    "next ${it.nextMessage}  txf ${it.txFreqHz}"
            } ?: "waiting for the app's state",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("open", "close", "cq", "stop", "auto").forEach { what ->
                OutlinedButton(onClick = { viewModel.ft8(what) }, enabled = role.canOperate) { Text(what) }
            }
        }
        if (rows.isEmpty()) Text("No decoded rows yet.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(rows.reversed(), key = { it.id }) { row -> Ft8RowLine(row) { viewModel.ft8("reply", row.id.toString()) } }
        }
    }
}

@Composable
private fun Ft8RowLine(row: Ft8Row, onReply: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onReply).padding(vertical = 4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.text, fontFamily = FontFamily.Monospace)
            Text("${row.snrDb} dB  ${row.freqHz} Hz", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "${row.call}  ${row.grid}  ${row.country}  ${if (row.distanceKm > 0) "${row.distanceKm} km" else ""}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
