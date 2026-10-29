// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import radio.kd0cq.x6100.net.PanelMode
import radio.kd0cq.x6100.net.PanelSettings
import radio.kd0cq.x6100.ui.RadioViewModel

private val AGC_NAMES = listOf("OFF", "SLOW", "FAST", "AUTO")
private val STEP_HZ = listOf(10, 100, 500, 1000, 5000)

/** The knobs and switches: everything the panel's "set" line carries. */
@Composable
fun ControlsScreen(viewModel: RadioViewModel) {
    val state by viewModel.state.collectAsState()
    val role by viewModel.role.collectAsState()
    val control by viewModel.control.collectAsState()
    val modes by viewModel.modes.collectAsState()
    val volumeMax by viewModel.volumeMax.collectAsState()
    val meters by viewModel.txMeters.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Controls", style = MaterialTheme.typography.headlineSmall)
        Text(
            "role ${role.role} · control ${control.label}${if (control.mine) " (us)" else ""}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (!control.free && !control.mine) {
            OutlinedButton(onClick = { viewModel.takeControl() }) { Text("Take control") }
        } else if (control.mine) {
            OutlinedButton(onClick = { viewModel.releaseControl() }) { Text("Give control back") }
        }

        // The button needs TX permission as well as a role that may operate. `role operate 0`
        // is what remote_server.c sends when the session's tx_ok flag is clear; the knobs below
        // stay on canOperate, because tuning is not the key.
        TransmitSection(viewModel, role.canOperate, role.txAllowed)

        val s = state
        Text(s?.let { "${it.freqHz} Hz  ${it.modeLabel}" } ?: "no state yet",
            style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.tuneBy(-1) }, enabled = role.canOperate) { Text("◀") }
            OutlinedButton(onClick = { viewModel.tuneBy(1) }, enabled = role.canOperate) { Text("▶") }
            OutlinedButton(onClick = { viewModel.send("set vfo 1") }, enabled = role.canOperate) { Text("VFO") }
            OutlinedButton(onClick = { viewModel.send("set band 1") }, enabled = role.canOperate) { Text("Band +") }
        }

        Text("Mode", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelMode.entries.take(modes).forEachIndexed { index, mode ->
                FilterChip(
                    selected = s?.mode == index,
                    onClick = { viewModel.setMode(index) },
                    label = { Text(mode.label) },
                    enabled = role.canOperate,
                )
            }
        }

        Text("AGC", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AGC_NAMES.forEachIndexed { index, name ->
                FilterChip(
                    selected = s?.agc == index,
                    onClick = { viewModel.setAgc(index) },
                    label = { Text(name) },
                    enabled = role.canOperate,
                )
            }
        }

        Text("Step", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STEP_HZ.forEachIndexed { index, hz ->
                FilterChip(
                    selected = s?.stepIndex == index,
                    onClick = { viewModel.setStep(index) },
                    label = { Text("$hz") },
                    enabled = role.canOperate,
                )
            }
        }

        SwitchRow("Preamp", s?.preOn ?: false, role.canOperate) { viewModel.toggle(PanelSettings.PRE, it) }
        SwitchRow("Attenuator", s?.attenuatorOn ?: false, role.canOperate) { viewModel.toggle(PanelSettings.ATT, it) }
        SwitchRow("Noise blanker", s?.noiseBlankerOn ?: false, role.canOperate) { viewModel.toggle(PanelSettings.NB, it) }
        SwitchRow("Noise reduce", s?.noiseReduceOn ?: false, role.canOperate) { viewModel.toggle(PanelSettings.NR, it) }
        SwitchRow("ATU", s?.atuOn ?: false, role.canOperate) { viewModel.toggle(PanelSettings.ATU, it) }
        SwitchRow("Lock frequency", s?.freqLocked ?: false, role.canOperate) { viewModel.toggle(PanelSettings.LOCK, it) }

        SliderRow("Volume", s?.volume ?: 0, 0f..volumeMax.toFloat(), role.canOperate) { viewModel.setVolume(it) }
        SliderRow("RF gain", s?.rfGain ?: 0, 0f..100f, role.canOperate) { viewModel.send("set rfg $it") }
        SliderRow("Squelch", s?.squelch ?: 0, 0f..100f, role.canOperate) { viewModel.send("set sql $it") }
        SliderRow("Power, W", ((s?.powerWatts ?: 0.0) * 10).toInt(), 10f..1000f, role.canOperate) { viewModel.setPower(it) }

        Text("Meters", style = MaterialTheme.typography.titleMedium)
        Text(
            "filter ${s?.filterLowHz ?: 0}…${s?.filterHighHz ?: 0} Hz   vfo ${s?.vfo ?: 0}   batt ${s?.battery ?: 0}   " +
                "tx ${"%.1f".format(meters.watts)} W  swr ${"%.2f".format(meters.swr)}  alc ${"%.1f".format(meters.alc)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * The key.
 *
 * It is press-and-hold, because that is what the radio's own page is: a press is a lease the app renews
 * once a second, so a phone that stops renewing — dropped link, killed service, this screen gone — is
 * off the air within three seconds on the Panel path and within half a second on the audio path, and the
 * app lets go at once rather than waiting for either. The button releases in a `finally`, so a pointer
 * that never lifts cleanly, a rotation, or a screen disposed mid-call cannot leave the transmitter
 * holding.
 *
 * The switch under it picks the microphone: at the radio (a Panel lease, no permission, the default) or
 * in this phone (TCI audio, which needs RECORD_AUDIO and refuses to key without it — the radio's own
 * microphone is never quietly substituted).
 */
@Composable
private fun TransmitSection(viewModel: RadioViewModel, enabled: Boolean, txAllowed: Boolean) {
    val keyed by viewModel.pttKeyed.collectAsState()
    val refusal by viewModel.pttRefusal.collectAsState()
    val audio by viewModel.audioRunning.collectAsState()
    val audioProblem by viewModel.audioProblem.collectAsState()
    val fromPhone = viewModel.transmitAudioFromPhone

    /** The grant's answer, kept here because it is this screen's news and not the key's. */
    var refused by remember { mutableStateOf(false) }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Refused is not fatal: the switch stays where it was and the lease path keys with the radio's
        // own microphone. It is shown, because a silent refusal to key is the thing an operator cannot
        // debug.
        refused = !granted
        if (granted) viewModel.transmitAudioFromPhone = true
    }

    Text("Transmit", style = MaterialTheme.typography.titleMedium)
    // The gesture, not the Button click, is the key. Gating only `enabled` would leave pointerInput
    // live for a session whose role may operate but whose tx_ok flag is clear.
    val mayKey = enabled && txAllowed
    Button(
        onClick = { },
        enabled = mayKey,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (keyed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = if (keyed) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.fillMaxWidth().height(88.dp).pointerInput(mayKey) {
            if (!mayKey) return@pointerInput
            detectTapGestures(onPress = {
                viewModel.pressPtt()
                try {
                    tryAwaitRelease()
                } finally {
                    viewModel.releasePtt()
                }
            })
        },
    ) {
        Text(if (keyed) "ON AIR" else "PTT", style = MaterialTheme.typography.titleLarge)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (audio) "audio 24 kHz" else "audio stopped",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            if (fromPhone) "mic: this phone" else "mic: at the radio",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
    if (refused) {
        Text(
            "no microphone permission: the key still works, with the microphone at the radio",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    (refusal ?: audioProblem)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Transmit audio from this phone", style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = fromPhone,
            enabled = enabled,
            onCheckedChange = { want ->
                if (want && !viewModel.hasMicrophonePermission()) {
                    askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    viewModel.transmitAudioFromPhone = want
                }
            },
        )
    }
    Text(
        "The key lets go on its own at 170 s on the air, when the link drops, when someone else takes " +
            "the controls, and the moment this app leaves the foreground.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SliderRow(label: String, value: Int, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat().coerceIn(range),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range,
            enabled = enabled,
        )
    }
}
