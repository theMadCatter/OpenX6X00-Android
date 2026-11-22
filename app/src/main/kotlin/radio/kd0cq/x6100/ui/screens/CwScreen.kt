// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The CW decoder strip, matched to the radio's field (src/cw_field.c).
//
// On the radio that field is one line of decoded text over the buttons,
// clipped at the right, with the keyer speed beside it. The panel sends
// the text ("cw \"...\""). It does not send the speed, so this screen does
// not invent a WPM figure. The spectrum still does not paint the line:
// that covered the band plan. Here the same text fills the screen and
// scrolls, which is the strip made readable on a phone in either orientation.
//
// There is no command. Opening the screen does not start the decoder and
// does not transmit. The radio decodes when its own decoder is on.

package radio.kd0cq.x6100.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.kd0cq.x6100.ui.RadioViewModel
import radio.kd0cq.x6100.ui.theme.RfGuru

@Composable
fun CwScreen(viewModel: RadioViewModel, onBack: () -> Unit) {
    val lines by viewModel.cwLines.collectAsState()
    val text = lines.joinToString(separator = "")
    val scroll = rememberScrollState()

    LaunchedEffect(text.length) {
        scroll.scrollTo(scroll.maxValue)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                "CW decoder",
                color = RfGuru.gold,
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("Back", color = RfGuru.goldText) }
        }
        Text(
            "The radio's decoder strip. Speed is not on this socket, so it is not shown.",
            color = RfGuru.dim,
            fontSize = 12.sp,
        )
        Text(
            text.ifEmpty { "Nothing decoded yet. The radio sends text when its CW decoder is running." },
            color = if (text.isEmpty()) RfGuru.dim else RfGuru.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 18.sp,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(top = 12.dp),
        )
    }
}
