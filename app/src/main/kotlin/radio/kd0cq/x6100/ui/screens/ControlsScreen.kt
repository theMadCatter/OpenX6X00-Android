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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
