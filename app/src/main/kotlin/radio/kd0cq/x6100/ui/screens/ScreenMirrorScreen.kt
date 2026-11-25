// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The radio's own framebuffer. `screen 1` asks for JPEG frames (the same ones the web panel
// shows). Touch keys send `key NAME` down and up, which is what the firmware's input device
// expects. PTT is not in that list: the lease on the radio screen is the only key path.

package radio.kd0cq.x6100.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import radio.kd0cq.x6100.ui.face.ScreenPicture
import radio.kd0cq.x6100.ui.face.WaterfallDisplay
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
    // The holder, not rememberSaveable, is what survives the recreation this flag causes.
    // rememberSaveable can miss the write when setRequestedOrientation saves state in the
    // same turn. A plain remember would close the picture on that recreation.
    var fullScreen by remember { mutableStateOf(FullscreenLock.open) }
    val activity = LocalContext.current.findActivity()
    DisposableEffect(fullScreen, activity) {
        val window = activity?.window
        val bars = if (window != null) WindowInsetsControllerCompat(window, window.decorView) else null
        if (fullScreen && activity != null && bars != null) {
            FullscreenLock.open = true
            if (!FullscreenLock.havePrevious) {
                FullscreenLock.previous = activity.requestedOrientation
                FullscreenLock.havePrevious = true
            }
            // Sensor landscape, not a fixed side: the 800×480 framebuffer then uses the
            // phone's long edge instead of sitting in a portrait letterbox.
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            // A configuration change disposes this effect on the way out. Restoring the
            // orientation there would undo the lock before the new instance applies it.
            if (!fullScreen || activity == null || activity.isChangingConfigurations) return@onDispose
            val back = if (FullscreenLock.havePrevious) FullscreenLock.previous else null
            FullscreenLock.open = false
            FullscreenLock.havePrevious = false
            if (back != null) activity.requestedOrientation = back
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // Sharp is the picture a good link can carry. Fast (half size) is only asked for once
    // frames are actually late. An unchanged screen repeats about once a second, and that
    // gap is not treated as lag.
    var picture by remember { mutableStateOf(PanelCommands.Picture.SHARP) }
    var fellBack by remember { mutableStateOf(false) }
    val gaps = remember { ArrayDeque<Long>() }
    var lastFrameAt by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        viewModel.wantScreenFrames(true, PanelCommands.Picture.SHARP)
        onDispose { viewModel.wantScreenFrames(false) }
    }
    LaunchedEffect(frame?.sequence) {
        if (frame == null) return@LaunchedEffect
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastFrameAt != 0L && picture == PanelCommands.Picture.SHARP) {
            gaps.addLast(now - lastFrameAt)
            while (gaps.size > 6) gaps.removeFirst()
            val next = WaterfallDisplay.nextScreenPicture(ScreenPicture.SHARP, gaps.toList())
            if (next == ScreenPicture.FAST) {
                picture = PanelCommands.Picture.FAST
                fellBack = true
                viewModel.wantScreenFrames(true, PanelCommands.Picture.FAST)
            }
        }
        lastFrameAt = now
    }

    val image = remember(frame?.sequence) {
        val jpeg = frame?.jpeg ?: return@remember null
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()
    }
    val keys = (if (reported.isEmpty()) fallbackKeys else reported)
        .filterNot { RadioFace.isCarrierKey(it) || it.equals("POWER", ignoreCase = true) }

    fun choosePicture(next: PanelCommands.Picture) {
        picture = next
        fellBack = false
        gaps.clear()
        lastFrameAt = 0L
        viewModel.wantScreenFrames(true, next)
    }

    if (fullScreen && image != null) {
        // The framebuffer is 800×480, the radio's landscape screen. Tapping it fills the phone
        // with that picture; tapping again closes it. The keys stay on the page underneath.
        Dialog(
            onDismissRequest = { fullScreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            val dialogView = LocalView.current
            DisposableEffect(dialogView) {
                val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
                if (dialogWindow != null) {
                    WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
                    val dialogBars = WindowInsetsControllerCompat(dialogWindow, dialogView)
                    dialogBars.hide(WindowInsetsCompat.Type.systemBars())
                    dialogBars.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                onDispose { }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { fullScreen = false },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = image,
                    contentDescription = "Radio screen, full screen. Tap to close",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RfGuru.bg)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // One scrolling row clipped "Full screen" to "Full" at the edge of the phone.
        // Two rows keep every word on screen; nothing here depends on a sideways drag.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Screen", color = RfGuru.gold, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                PictureChip("Fast", picture == PanelCommands.Picture.FAST) {
                    choosePicture(PanelCommands.Picture.FAST)
                }
                PictureChip("Balanced", picture == PanelCommands.Picture.BALANCED) {
                    choosePicture(PanelCommands.Picture.BALANCED)
                }
                PictureChip("Sharp", picture == PanelCommands.Picture.SHARP) {
                    choosePicture(PanelCommands.Picture.SHARP)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PictureChip(if (pictureOnImage) "Keys on picture" else "Keys below", false) {
                    pictureOnImage = !pictureOnImage
                }
                if (image != null) {
                    PictureChip("Full screen", false) { fullScreen = true }
                }
                if (fellBack) {
                    Text("Fast: frames were late", color = RfGuru.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        // The framebuffer is 800×480. A weight(1) box letterboxed it with a tall black margin.
        // The card is the picture's own shape; the knobs and keys use the space under it.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(800f / 480f)
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
                    contentDescription = "Radio screen. Tap for the full landscape picture",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (!pictureOnImage) Modifier.clickable { fullScreen = true } else Modifier),
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
        // FlowRow's overflow overload is not in this Compose Foundation, and calling it
        // crashed the tab. Rows packed to the width keep every name, including MSG, whole.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Captured here: inside the Column the BoxWithConstraints receiver is gone.
            val rowWidth = maxWidth.value
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RadioFace.packKeyRows(keys, rowWidth).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        line.forEach { name ->
                            Text(
                                name,
                                color = RfGuru.text,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .background(RfGuru.button, RoundedCornerShape(8.dp))
                                    .pointerInput(name) { keyGesture(viewModel, name) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
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
private fun PictureChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) RfGuru.barBottom else RfGuru.goldText,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(if (selected) RfGuru.gold else RfGuru.button, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * Open while the radio picture is full screen, and the orientation to put back on close.
 *
 * KD0CQ: this sits outside composition because locking the activity to sensor
 * landscape recreates it. The new instance reads [open] and shows the picture again; the
 * orientation we captured is the one the phone had before the lock.
 */
private object FullscreenLock {
    var open: Boolean = false
    var previous: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    var havePrevious: Boolean = false
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
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
