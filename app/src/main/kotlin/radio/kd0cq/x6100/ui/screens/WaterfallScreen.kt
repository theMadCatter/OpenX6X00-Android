// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import radio.kd0cq.x6100.net.SpectrumFrame
import radio.kd0cq.x6100.ui.RadioViewModel

/**
 * The waterfall: the spectrum frames the panel link sends, and a swipe to tune.
 *
 * TODO(waterfall): this draws one frame as bars. A real waterfall keeps the last frames and scrolls
 * them, the way panel.html's canvas does, and paints the passband and the tuning line from the
 * state's filter edges.
 */
@Composable
fun WaterfallScreen(viewModel: RadioViewModel, onPair: () -> Unit) {
    val frame by viewModel.spectrum.collectAsState()
    val state by viewModel.state.collectAsState()
    val meter by viewModel.meter.collectAsState()
    val phase by viewModel.phase.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                state?.let { "${it.freqHz / 1_000_000.0} MHz  ${it.modeLabel}" } ?: "no state yet",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text("S ${"%.1f".format(meter)} dB", fontFamily = FontFamily.Monospace)
        }
        SpectrumBars(
            frame = frame,
            modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black)
                .pointerInput(phase) {
                    detectHorizontalDragGestures { change, drag ->
                        if (kotlin.math.abs(drag) > 12f) viewModel.tuneBy(if (drag > 0) 1 else -1)
                        change.consume()
                    }
                },
        )
        Text(
            frame?.let { "${it.bins} bins  centre ${it.centreHz} Hz  span ${it.widthHz} Hz" }
                ?: "no spectrum frame yet",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (!viewModel.paired) OutlinedButton(onClick = onPair) { Text("Pair to operate") }
        Text(
            "The waterfall is a placeholder: frames are drawn as bars, and the audio and the key are still to come.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SpectrumBars(frame: SpectrumFrame?, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val f = frame
        val bins = f?.bins ?: 0
        if (f == null || bins == 0) {
            drawLine(
                color = line,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
            return@Canvas
        }
        val stepX = size.width / bins
        val floor = -128f
        for (i in 0 until bins) {
            val db = f.db[i].toInt()
            val v = ((db - floor) / (0f - floor)).coerceIn(0f, 1f)
            drawLine(
                color = Color.Green.copy(alpha = 0.35f + 0.65f * v),
                start = Offset(i * stepX, size.height),
                end = Offset(i * stepX, size.height * (1f - v)),
                strokeWidth = stepX.coerceAtLeast(1f),
            )
        }
    }
}
