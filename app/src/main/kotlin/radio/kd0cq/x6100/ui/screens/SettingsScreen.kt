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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import radio.kd0cq.x6100.ui.face.PENDING_FIRMWARE
import radio.kd0cq.x6100.ui.face.RadioFace
import radio.kd0cq.x6100.ui.theme.RfGuru
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.provider.Settings
import radio.kd0cq.x6100.net.LinkPhase
import radio.kd0cq.x6100.service.BackgroundPreference
import radio.kd0cq.x6100.ui.RadioViewModel

/**
 * What the link is, what the radio is, who holds it and who is paired with it, and the two ways to get
 * here without mDNS. Nothing on this screen is a guess: every line comes from the radio itself.
 */
@Composable
fun SettingsScreen(viewModel: RadioViewModel, onBack: () -> Unit = {}) {
    val phase by viewModel.phase.collectAsState()
    val tciPhase by viewModel.tciPhase.collectAsState()
    val hello by viewModel.hello.collectAsState()
    val role by viewModel.role.collectAsState()
    val control by viewModel.control.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val endpoint by viewModel.endpoint.collectAsState()
    val error by viewModel.lastError.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back", color = RfGuru.gold) }
        Text("Setup", style = MaterialTheme.typography.headlineSmall, color = RfGuru.gold)

        Text("Link", style = MaterialTheme.typography.titleMedium)
        BackgroundSwitch()
        Line("panel", phase.name.lowercase())
        Line("tci", tciPhase.name.lowercase())
        Line("radio", endpoint?.let { "${it.name} ${it.host}:${it.panelPort} / ${it.tciPort}" } ?: "-")
        Line("firmware", hello?.let { "v${it.version} boot ${it.bootId}" } ?: "-")
        Line("role", "${role.role.wire} tx=${if (role.txAllowed) 1 else 0} name=${role.sessionName.ifBlank { "-" }}")
        Line("control", control.label)
        error?.let { Line("last word", it) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::search) { Text("Search LAN") }
            OutlinedButton(onClick = viewModel::disconnect) { Text("Disconnect") }
        }
        OutlinedButton(onClick = { viewModel.forgetPairing() }, enabled = viewModel.paired) { Text("Forget this pairing") }

        Text("Paired sessions", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = viewModel::askSessions, enabled = role.canAdmin) {
            Text(if (role.canAdmin) "Ask the radio" else "Admin role needed")
        }
        if (sessions.isEmpty()) {
            Text("Not asked, or none.", style = MaterialTheme.typography.bodyMedium)
        }
        sessions.forEach { session ->
            Line(
                "#${session.index} ${session.name}",
                "${session.role} tx=${if (session.txAllowed) 1 else 0} ${if (session.online) "online" else "away"} ${session.ip}" +
                    if (session.mine) " (us)" else "",
            )
        }

        Text("By hand", style = MaterialTheme.typography.titleMedium)
        Text(
            "The radio answers x6100.local and advertises _http._tcp, _https._tcp and _tci._tcp; it " +
                "advertises no panel service, so the panel port 50002 is only ever typed or kept from " +
                "the last discovery. Enter the radio's address if the LAN's resolver does not know it.",
            style = MaterialTheme.typography.bodySmall,
        )
        listOf("x6100.local", "192.168.1.10").forEach { host ->
            OutlinedButton(onClick = { viewModel.connectToHost(host) }, enabled = phase != LinkPhase.CONNECTING) {
                Text("Connect to $host")
            }
        }

        Text("Radio menus the Panel link does not carry", style = MaterialTheme.typography.titleMedium, color = RfGuru.gold)
        Text(
            "These are on the radio. $PENDING_FIRMWARE, so the buttons on the face only name them.",
            style = MaterialTheme.typography.bodySmall,
        )
        RadioFace.pendingFeatures().forEach { feature ->
            Line(feature.group, feature.name)
        }

        Text("What the audio path does not do", style = MaterialTheme.typography.titleMedium)
        Text(
            "The receiver plays 24 kHz mono through the phone's speaker or whatever the phone itself " +
                "routes audio to, and the microphone path captures unprocessed, with no echo " +
                "cancellation — so with the speaker up loud and the phone's microphone keyed, the radio " +
                "hears the phone. Nothing decodes on the phone, and the transmit meter is only what the " +
                "radio reports. DESIGN.md keeps the list of what is known to be missing.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Keep the receiver up with the screen off. On until the operator turns it off.
 *
 * The system can still freeze a background app under battery optimization. This
 * does not ask for an exemption on its own. The button opens the system list,
 * where the operator can allow it if the radio drops out in Doze.
 */
@Composable
private fun BackgroundSwitch() {
    val context = LocalContext.current
    val preference = remember { BackgroundPreference(context) }
    var on by remember { mutableStateOf(preference.read()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Run in background", style = MaterialTheme.typography.titleMedium, color = RfGuru.gold)
            Text(
                "Keep the radio link and the receiver playing when the screen is off. " +
                    "An on-screen PTT is let go. Transmitting stays on the key you are holding.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = on,
            onCheckedChange = { next ->
                on = next
                preference.write(next)
            },
        )
    }
    TextButton(onClick = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }) { Text("Battery optimization", color = RfGuru.gold) }
}

@Composable
private fun Line(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
