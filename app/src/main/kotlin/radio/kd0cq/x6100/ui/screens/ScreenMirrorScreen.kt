// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The radio's own framebuffer. `screen 1` asks for JPEG frames (the same ones the web panel
// shows). Touch keys send `key NAME` down and up, which is what the firmware's input device
// expects. PTT is not in that list: the lease on the radio screen is the only key path.

package radio.kd0cq.x6100.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.kd0cq.x6100.net.PanelCommands
import radio.kd0cq.x6100.ui.RadioViewModel
import radio.kd0cq.x6100.ui.face.RadioFace
import radio.kd0cq.x6100.ui.theme.RfGuru

private val fallbackKeys = listOf(
    "F1", "F2", "F3", "F4", "F5",
    "GEN", "APP", "KEY", "DFN", "DFL", "VM",
    "VOL", "MFK", "AB", "PRE", "ATU", "AGC", "LOCK",
    "BAND_UP", "BAND_DOWN", "AM", "CW", "SSB", "MSG", "FST",
)

@Composable
fun ScreenMirrorScreen(viewModel: RadioViewModel) {
    val frame by viewModel.screen.collectAsState()
    val reported by viewModel.keyNames.collectAsState()
    var pictureOnImage by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        viewModel.wantScreenFrames(true)
        onDispose { viewModel.wantScreenFrames(false) }
    }

    val image = remember(frame?.sequence) {
        val jpeg = frame?.jpeg ?: return@remember null
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()
    }
    val keys = (if (reported.isEmpty()) fallbackKeys else reported)
        .filterNot { RadioFace.isCarrierKey(it) || it.equals("POWER", ignoreCase = true) }

    Column(
        Modifier.fillMaxSize().background(RfGuru.bg).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Screen", color = RfGuru.gold, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            PictureChip("Fast") { viewModel.wantScreenFrames(true, PanelCommands.Picture.FAST) }
            PictureChip("Balanced") { viewModel.wantScreenFrames(true, PanelCommands.Picture.BALANCED) }
            PictureChip("Sharp") { viewModel.wantScreenFrames(true, PanelCommands.Picture.SHARP) }
            PictureChip(if (pictureOnImage) "Keys on picture" else "Keys below") { pictureOnImage = !pictureOnImage }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(RfGuru.bar, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (image == null) {
                Text(
                    "Waiting for a frame. The radio sends one after screen 1.",
                    color = RfGuru.dim,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Image(
                    bitmap = image,
                    contentDescription = "Radio screen",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (pictureOnImage) {
                // The firmware's F1–F5 sit in the bottom band of the 800×480 framebuffer.
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(56.dp)) {
                    listOf("F1", "F2", "F3", "F4", "F5").forEach { name ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .pointerInput(name) { keyGesture(viewModel, name) },
                        )
                    }
                }
            }
        }
        Text("Knobs", color = RfGuru.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KnobPair("Main", Modifier.weight(1f)) { viewModel.turnKnob("main", it) }
            KnobPair("VOL", Modifier.weight(1f)) { viewModel.turnKnob("vol", it) }
            KnobPair("MFK", Modifier.weight(1f)) { viewModel.turnKnob("mfk", it) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            keys.forEach { name ->
                Text(
                    name,
                    color = RfGuru.text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(RfGuru.button, RoundedCornerShape(8.dp))
                        .pointerInput(name) { keyGesture(viewModel, name) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.keyGesture(
    viewModel: RadioViewModel,
    name: String,
) {
    detectTapGestures(onPress = {
        viewModel.pressKey(name, true)
        try {
            tryAwaitRelease()
        } finally {
            viewModel.pressKey(name, false)
        }
    })
}

@Composable
private fun PictureChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = RfGuru.goldText,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(RfGuru.button, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun KnobPair(label: String, modifier: Modifier = Modifier, onTurn: (Int) -> Unit) {
    Row(
        modifier.background(RfGuru.button, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("−", color = RfGuru.gold, modifier = Modifier.clickable { onTurn(-1) }.padding(6.dp))
        Text(label, color = RfGuru.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text("+", color = RfGuru.gold, modifier = Modifier.clickable { onTurn(1) }.padding(6.dp))
    }
}
