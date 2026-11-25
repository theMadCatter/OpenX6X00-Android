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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import radio.kd0cq.x6100.ui.theme.RfGuru
import radio.kd0cq.x6100.discovery.FoundRadio
import radio.kd0cq.x6100.discovery.looksLikeHost
import radio.kd0cq.x6100.discovery.portOr
import radio.kd0cq.x6100.net.LinkPhase
import radio.kd0cq.x6100.net.PANEL_PORT
import radio.kd0cq.x6100.net.RadioEndpoint
import radio.kd0cq.x6100.net.TCI_PORT
import radio.kd0cq.x6100.ui.RadioViewModel

/**
 * Finding the radio on the LAN, or naming it by hand when the resolver is not our friend.
 *
 * A fresh install has no token. The socket still comes up, view only, and the operating chrome
 * (Pair, Radio) used to stay hidden until a token existed — so this screen was a dead end.
 * Once the link is up, Pair and Open the radio are both here.
 *
 * The help and the status used to take Material's default content colour, which
 * on this black page came out dark grey. They are [RfGuru.text] and [RfGuru.dim]
 * now, the same roles the rest of the face uses, so they stay readable in the
 * night theme and in the sunlight theme.
 *
 * The host box used to be created at [RadioEndpoint.DEFAULT_HOST] and forget
 * whatever had been typed. It starts from the last host that was entered.
 */
@Composable
fun ConnectScreen(viewModel: RadioViewModel, onOpenRadio: () -> Unit, onPair: () -> Unit) {
    val found by viewModel.foundRadios.collectAsState()
    val phase by viewModel.phase.collectAsState()
    val error by viewModel.lastError.collectAsState()
    val endpoint by viewModel.endpoint.collectAsState()
    var host by rememberSaveable { mutableStateOf(viewModel.rememberedHost()) }
    var panelPort by remember { mutableStateOf(PANEL_PORT.toString()) }
    var tciPort by remember { mutableStateOf(TCI_PORT.toString()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Radio", style = MaterialTheme.typography.headlineSmall, color = RfGuru.gold)
        Text(
            "The radio advertises itself as x6100.local; its panel server answers on 50002.",
            style = MaterialTheme.typography.bodyMedium,
            color = RfGuru.text,
        )
        PhaseLine(phase, endpoint?.host, viewModel.paired)
        error?.let {
            Text(it, color = RfGuru.red, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.search() }) { Text("Search") }
            OutlinedButton(onClick = { viewModel.stopSearching() }) { Text("Stop") }
            OutlinedButton(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
        }

        Text("Found", style = MaterialTheme.typography.titleMedium, color = RfGuru.gold)
        if (found.isEmpty()) {
            Text(
                "Nothing yet. The radio and this phone must be on the same network.",
                style = MaterialTheme.typography.bodySmall,
                color = RfGuru.text,
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(found, key = { "${it.host}:${it.service}" }) { radio ->
                RadioRow(radio) {
                    host = radio.host
                    viewModel.connectTo(radio)
                }
            }
        }

        Text("By hand", style = MaterialTheme.typography.titleMedium, color = RfGuru.gold)
        OutlinedTextField(
            value = host,
            onValueChange = {
                host = it
                if (looksLikeHost(it)) viewModel.rememberHost(it)
            },
            label = { Text("Host or address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = hostFieldColors(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = panelPort,
                onValueChange = { panelPort = it },
                label = { Text("Panel") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = hostFieldColors(),
            )
            OutlinedTextField(
                value = tciPort,
                onValueChange = { tciPort = it },
                label = { Text("TCI") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = hostFieldColors(),
            )
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

        if (phase.online) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenRadio) { Text("Open the radio") }
                if (!viewModel.paired) {
                    OutlinedButton(onClick = onPair) { Text("Pair") }
                }
            }
            if (!viewModel.paired) {
                Text(
                    "View only until you pair. The radio screen still opens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RfGuru.text,
                )
            }
        }
    }
}

@Composable
private fun RadioRow(radio: FoundRadio, onConnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = RfGuru.bar, contentColor = RfGuru.text),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onConnect).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(radio.instance, style = MaterialTheme.typography.titleMedium, color = RfGuru.text)
                Text(radio.host, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = RfGuru.text)
                Text("panel ${radio.panelPort} · tci ${radio.tciPort}", style = MaterialTheme.typography.bodySmall, color = RfGuru.dim)
            }
            Text(
                if (radio.service == "manual") "typed" else "found",
                style = MaterialTheme.typography.labelMedium,
                color = RfGuru.dim,
            )
        }
    }
}

/** The typed host, the labels and the cursor. Material's unfocused grey sat too close to the page. */
@Composable
private fun hostFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = RfGuru.text,
    unfocusedTextColor = RfGuru.text,
    focusedLabelColor = RfGuru.gold,
    unfocusedLabelColor = RfGuru.dim,
    cursorColor = RfGuru.gold,
    focusedBorderColor = RfGuru.gold,
    unfocusedBorderColor = RfGuru.border,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
)

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
    Text(words, style = MaterialTheme.typography.bodyLarge, color = RfGuru.text)
}
