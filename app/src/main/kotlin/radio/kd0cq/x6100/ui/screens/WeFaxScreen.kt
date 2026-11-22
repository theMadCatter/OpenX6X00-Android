// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The WeFax screen, matched to the radio's chart (src/wefax_window.c).
//
// The panel sends "wefax {...}" for the state and, only after "wefax rows 1",
// grey scan lines as binary 0xFFFE. The radio draws those rows as the picture,
// newest at the bottom, with the station, the frequency and Auto / Plan on
// the status line. This screen does the same. Portrait gives the picture the
// leftover height. Landscape puts the picture on the left and the controls
// on the right, so a short window does not clip the chart under a header.
//
// Opening the screen asks for rows. It does not send "wefax open". The
// buttons are the words the panel dispatches. None of them is a transmit
// command. Each one still needs the operate role and Take control, because
// the radio refuses them otherwise. Save writes the chart on the radio.
// Clear drops it there. Neither runs from composition.

package radio.kd0cq.x6100.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import radio.kd0cq.x6100.digital.WeFaxProtocol
import radio.kd0cq.x6100.digital.WeFaxScan
import radio.kd0cq.x6100.ui.RadioViewModel
import radio.kd0cq.x6100.ui.theme.RfGuru

@Composable
fun WeFaxScreen(viewModel: RadioViewModel, onBack: () -> Unit) {
    val status by viewModel.wefax.collectAsState()
    val rows by viewModel.wefaxRows.collectAsState()
    val role by viewModel.role.collectAsState()
    val control by viewModel.control.collectAsState()
    var refusal by remember { mutableStateOf<String?>(null) }
    val held = control.mine && role.canOperate

    DisposableEffect(Unit) {
        viewModel.wefaxWatch(true)
        onDispose { viewModel.wefaxWatch(false) }
    }

    val picture = remember(rows.size, rows.lastOrNull()?.number, rows.firstOrNull()?.grey?.size) {
        faxBitmap(rows)
    }

    DigitalSplit(
        header = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "WeFax",
                    color = RfGuru.gold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) { Text("Back", color = RfGuru.goldText) }
            }
            Text(WeFaxProtocol.statusLine(status), color = RfGuru.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 3)
            refusal?.let { Text(it, color = RfGuru.red, fontSize = 12.sp) }
        },
        picture = {
            if (picture == null) {
                Text(
                    "The chart appears when the radio sends a row. Asking for rows needs no role.",
                    color = RfGuru.dim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                Image(
                    bitmap = picture,
                    contentDescription = "WeFax chart",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        list = { modifier ->
            Text(
                if (rows.isEmpty()) "No rows yet." else "${rows.size} rows on this phone, newest at the bottom.",
                color = RfGuru.dim,
                fontSize = 13.sp,
                modifier = modifier.padding(vertical = 8.dp),
            )
        },
        pictureGrows = true,
        pictureShare = 0.62f,
        controls = {
            if (!held) {
                OutlinedButton(onClick = { viewModel.takeControl() }, enabled = role.canOperate) {
                    Text("Take control")
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "open" to "Open",
                    "close" to "Close",
                    "auto" to if (status?.auto == true) "Auto on" else "Auto",
                    "cont" to "Cont",
                    "save" to "Save",
                    "clear" to "Clear",
                    "plan" to if (status?.plan == true) "Plan on" else "Plan",
                    "unplan" to "Unplan",
                ).forEach { (word, label) ->
                    OutlinedButton(
                        onClick = {
                            refusal = if (viewModel.wefaxOperate(word)) null else "Take control before this."
                        },
                        enabled = held,
                    ) { Text(label) }
                }
            }
        },
    )
}

/**
 * The grey bytes as one bitmap, top to bottom in the order they arrived.
 * Rebuilt when a row is added. The radio paints the same bytes, one row
 * at a time, and this is that picture rather than a second decoder.
 */
private fun faxBitmap(rows: List<WeFaxScan>): androidx.compose.ui.graphics.ImageBitmap? {
    val width = rows.firstOrNull()?.grey?.size ?: return null
    if (width <= 0 || rows.isEmpty()) return null
    val height = rows.size
    val pixels = IntArray(width * height)
    rows.forEachIndexed { y, row ->
        val n = minOf(width, row.grey.size)
        for (x in 0 until n) {
            val grey = row.grey[x].toInt() and 0xFF
            pixels[y * width + x] = (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
        }
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}
