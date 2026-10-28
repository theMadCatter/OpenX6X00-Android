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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import radio.kd0cq.x6100.net.Spot
import radio.kd0cq.x6100.ui.RadioViewModel

/**
 * The cluster spots the radio holds from its own client, newest first. The radio pushes them as they
 * change, so the list is what the radio last told us; tuning one moves the VFO by detents, which is
 * what the panel protocol allows without a frequency of its own.
 */
@Composable
fun SpotsScreen(viewModel: RadioViewModel) {
    val spots by viewModel.spots.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Spots", style = MaterialTheme.typography.headlineSmall)
        Text(
            "from the radio's cluster client, newest first",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (spots.isEmpty()) {
            Text(
                "No spots. The radio fills this once its cluster client is connected to a repeater; " +
                    "the band plan window and the FT8 decoder are separate lists.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(spots) { index, spot -> SpotRow(spot, index == 0, onTune = { viewModel.setFrequency(it) }) }
        }
    }
}

@Composable
private fun SpotRow(spot: Spot, newest: Boolean, onTune: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("%8.1f kHz".format(spot.freqHz / 1000.0), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
            Text(spot.call, style = MaterialTheme.typography.titleMedium)
            if (spot.alert) Text("alert", color = MaterialTheme.colorScheme.error)
        }
        Text(
            "${spot.modeClass}  ${spot.ageMin} min ago  by ${spot.spotter.ifBlank { "-" }}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        spot.comment.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (newest) {
            OutlinedButton(onClick = { onTune(spot.freqHz) }) {
                Text("Tune to this spot")
            }
        }
    }
}
