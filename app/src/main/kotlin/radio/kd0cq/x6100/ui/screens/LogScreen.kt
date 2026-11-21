// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.

package radio.kd0cq.x6100.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import radio.kd0cq.x6100.log.Qso
import radio.kd0cq.x6100.net.PanelCommands
import radio.kd0cq.x6100.ui.RadioViewModel
import java.util.concurrent.ThreadLocalRandom

/**
 * A QSO into the radio's own day log. The panel takes the text the way the radio's CW keyboard writes
 * it — CALL|RSTS|RSTR|NAME|QTH|GRID|COUNTRY — and answers "logged ok|no". With an id the answer is
 * "logged ID ...", kept by the radio for the last 64, so a receipt lost in a dropped connection can be
 * asked for again with "logq? ID TEXT" and the QSO is never written twice.
 */
@Composable
fun LogScreen(viewModel: RadioViewModel) {
    val receipts by viewModel.receipts.collectAsState()
    val role by viewModel.role.collectAsState()

    var call by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf("59") }
    var received by remember { mutableStateOf("59") }
    var name by remember { mutableStateOf("") }
    var qth by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf("") }
    var lastId by remember { mutableStateOf(0L) }
    var lastText by remember { mutableStateOf("") }

    val fields = listOf(call, sent, received, name, qth, grid)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Log", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        QrzSection(viewModel, call, { call = it }, { name = it }, { qth = it }, { grid = it })
        Text(
            "role ${role.role.wire}  ·  the radio writes its own day log",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        OutlinedTextField(value = call, onValueChange = { call = it.uppercase() }, label = { Text("Call") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = sent, onValueChange = { sent = it }, label = { Text("Sent") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = received, onValueChange = { received = it }, label = { Text("Rcvd") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
        OutlinedTextField(value = qth, onValueChange = { qth = it }, label = { Text("QTH") }, singleLine = true)
        OutlinedTextField(value = grid, onValueChange = { grid = it.uppercase() }, label = { Text("Grid") }, singleLine = true)

        Button(
            enabled = call.isNotBlank() && role.canOperate,
            onClick = {
                // The id is ours and random: the radio binds the receipt to the QSO's text, so a retry
                // of the same QSO is answered and one with other fields is refused as a "conflict".
                // The local row is the copy that keeps the time. The radio line has no time column.
                val id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2)
                lastId = id
                lastText = fields.joinToString("|")
                val radio = viewModel.state.value
                viewModel.saveLoggedQso(
                    Qso(
                        call = call,
                        freqHz = radio?.freqHz ?: 0,
                        mode = radio?.modeLabel ?: "",
                        timeUtcMs = System.currentTimeMillis(),
                        rstSent = sent,
                        rstRcvd = received,
                        name = name,
                        qth = qth,
                        grid = grid,
                        country = "",
                        radioLogId = id,
                    ),
                )
            },
        ) { Text("Log this QSO") }
        if (lastId != 0L) {
            OutlinedButton(onClick = { viewModel.send(PanelCommands.logqQuery(lastId, lastText)) }) {
                Text("Ask what became of it")
            }
        }

        Text("Receipts", style = MaterialTheme.typography.titleMedium)
        if (receipts.isEmpty()) Text("Nothing sent yet.", style = MaterialTheme.typography.bodyMedium)
        receipts.asReversed().forEach { receipt ->
            val line = if (receipt.id != null) "#${receipt.id} ${receipt.result}" else receipt.result
            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
