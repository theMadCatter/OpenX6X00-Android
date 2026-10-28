// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import radio.kd0cq.x6100.discovery.FoundRadio
import radio.kd0cq.x6100.discovery.looksLikeHost
import radio.kd0cq.x6100.discovery.portOr
import radio.kd0cq.x6100.net.LinkPhase
import radio.kd0cq.x6100.net.PANEL_PORT
import radio.kd0cq.x6100.net.RadioEndpoint
import radio.kd0cq.x6100.net.TCI_PORT
import radio.kd0cq.x6100.ui.RadioViewModel

/** Finding the radio on the LAN, or naming it by hand when the resolver is not our friend. */
@Composable
fun ConnectScreen(viewModel: RadioViewModel, onPaired: () -> Unit) {
    val found by viewModel.foundRadios.collectAsState()
    val phase by viewModel.phase.collectAsState()
    val error by viewModel.lastError.collectAsState()
    val endpoint by viewModel.endpoint.collectAsState()
    var host by remember { mutableStateOf(RadioEndpoint.DEFAULT_HOST) }
    var panelPort by remember { mutableStateOf(PANEL_PORT.toString()) }
    var tciPort by remember { mutableStateOf(TCI_PORT.toString()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Radio", style = MaterialTheme.typography.headlineSmall)
        Text("The radio advertises itself as x6100.local; its panel server answers on 50002.",
            style = MaterialTheme.typography.bodyMedium)
        PhaseLine(phase, endpoint?.host, viewModel.paired)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.search() }) { Text("Search") }
            OutlinedButton(onClick = { viewModel.stopSearching() }) { Text("Stop") }
            OutlinedButton(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
        }

        Text("Found", style = MaterialTheme.typography.titleMedium)
        if (found.isEmpty()) Text("Nothing yet. The radio and this phone must be on the same network.",
            style = MaterialTheme.typography.bodySmall)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(found, key = { "${it.host}:${it.service}" }) { radio ->
                RadioRow(radio) { viewModel.connectTo(radio) }
            }
        }

        Text("By hand", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host or address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = panelPort, onValueChange = { panelPort = it }, label = { Text("Panel") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = tciPort, onValueChange = { tciPort = it }, label = { Text("TCI") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Button(
            onClick = {
                if (looksLikeHost(host)) {
                    viewModel.connectTo(
                        FoundRadio(
                            service = "manual",
                            instance = host,
                            host = host.trim(),
                            panelPort = portOr(panelPort, PANEL_PORT),
                            tciPort = portOr(tciPort, TCI_PORT),
                        )
                    )
                }
            },
            enabled = looksLikeHost(host),
        ) { Text("Connect to $host") }

        if (phase.online && viewModel.paired) {
            OutlinedButton(onClick = onPaired) { Text("Open the radio") }
        }
    }
}

@Composable
private fun RadioRow(radio: FoundRadio, onConnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onConnect).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(radio.instance, style = MaterialTheme.typography.titleMedium)
                Text(radio.host, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text("panel ${radio.panelPort} · tci ${radio.tciPort}", style = MaterialTheme.typography.bodySmall)
            }
            Text(if (radio.service == "manual") "typed" else "found", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun PhaseLine(phase: LinkPhase, host: String?, paired: Boolean) {
    val words = when (phase) {
        LinkPhase.IDLE -> "Not connected."
        LinkPhase.CONNECTING -> "Connecting${host?.let { " to $it" } ?: ""}…"
        LinkPhase.CONNECTED -> if (paired) "Connected." else "Connected, view only: pair to operate."
        LinkPhase.PAIRING -> "Waiting for the code the radio shows."
        LinkPhase.PAIRED -> "Paired with $host."
        LinkPhase.LOST -> "Lost the radio; trying again."
        LinkPhase.STOPPED -> "Stopped."
    }
    Text(words, style = MaterialTheme.typography.bodyLarge)
}
