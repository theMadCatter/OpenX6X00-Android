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
import androidx.compose.foundation.layout.width
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
import radio.kd0cq.x6100.net.isPairingCode
import radio.kd0cq.x6100.ui.RadioViewModel

/**
 * Pairing: "pair?" puts a six-digit code on the radio's screen for two minutes, and "pair CODE NAME"
 * trades it for the token every later connection authenticates with.
 */
@Composable
fun PairingScreen(viewModel: RadioViewModel, onDone: () -> Unit) {
    val role by viewModel.role.collectAsState()
    val error by viewModel.lastError.collectAsState()
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("Android") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pair with the radio", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Press Pair on the radio's Remote page, type the six digits it shows, and this app keeps the " +
                "token it answers with so the next connection needs no code.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { viewModel.askForPairingCode() }) { Text("Show a code on the radio") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { if (it.length <= 6) code = it.filter(Char::isDigit) },
                label = { Text("Code") },
                singleLine = true,
                modifier = Modifier.width(160.dp),
            )
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("This app is") }, singleLine = true)
        }
        Button(onClick = { viewModel.pair(code, name) }, enabled = isPairingCode(code)) { Text("Pair") }

        Text("Now", style = MaterialTheme.typography.titleMedium)
        Text("role ${role.role}  may transmit ${role.txAllowed}  as ${role.sessionName.ifBlank { "-" }}",
            style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onDone) { Text("Back") }
    }
}
