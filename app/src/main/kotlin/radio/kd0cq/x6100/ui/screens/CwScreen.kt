// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The CW decoder panel, from src/text_panel.cpp.
//
// The earlier screen treated src/cw_field.c as the decoder. That file is the
// keyboard field: one line being keyed, with the keyer speed beside it. The
// decoder's own display is the text panel over the waterfall. It holds the
// last lines that fit, in the large decoder face, and colours a word the way
// cw_parse.c classifies it. A confirmed call is the accent gold, an
// abbreviation is dim, an RST is the header gold. The info line above the
// panel is the speed. The socket's "cw" line is the text only, so this
// screen does not invent a WPM figure.
//
// RTTY's tuning scope sits in the corner of the same panel. RTTY is not on
// the socket, so that scope is not drawn. There is no command. Opening the
// screen does not start the decoder and does not transmit. The spectrum
// still does not paint this text: on the radio the panel covers the band
// plan, which is why it lives on its own screen here.

package radio.kd0cq.x6100.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.kd0cq.x6100.digital.CwLook
import radio.kd0cq.x6100.ui.RadioViewModel
import radio.kd0cq.x6100.ui.theme.RfGuru

@Composable
fun CwScreen(viewModel: RadioViewModel, onBack: () -> Unit) {
    val lines by viewModel.cwLines.collectAsState()
    val text = lines.joinToString(separator = "")

    Column(Modifier.fillMaxSize().background(RfGuru.bg).padding(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                "Speed is on the radio, above this panel. It is not in the cw text.",
                color = RfGuru.dim,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("Back", color = RfGuru.goldText) }
        }
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(Color(0xFF08090C), RoundedCornerShape(8.dp))
                .border(1.dp, RfGuru.border, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            val maxLines = (maxHeight.value / 28f).toInt().coerceAtLeast(1)
            val columns = (maxWidth.value / 14f).toInt().coerceAtLeast(8)
            val shown = CwLook.wrappedTail(text, maxLines, columns).ifEmpty {
                "Nothing decoded yet. The radio sends text when its CW decoder is running."
            }
            Text(
                if (text.isEmpty()) AnnotatedString(shown) else cwInk(text, shown),
                color = if (text.isEmpty()) RfGuru.dim else Color(CwLook.TEXT),
                fontFamily = FontFamily.Monospace,
                fontSize = 24.sp,
                lineHeight = 28.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The panel's recolour, applied to the lines that still fit. */
private fun cwInk(full: String, shown: String): AnnotatedString = buildAnnotatedString {
    val parts = shown.split(Regex("(?<=[ \n])|(?=[ \n])"))
    parts.forEach { part ->
        if (part.isEmpty() || part == " " || part == "\n") {
            append(part)
        } else {
            val kind = CwLook.kind(part)
            val color = CwLook.ink(kind, CwLook.confirmed(full, part))
            withStyle(SpanStyle(color = Color(color))) { append(part) }
        }
    }
}
