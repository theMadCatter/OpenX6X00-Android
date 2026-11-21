// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The pieces of the radio face: status pills, needle meter, frequency readout, the spectrum with
// its scrolling waterfall, the F1–F5 bar, and the hold-to-talk key.
//
// The waterfall used to be one frame of green bars, and the key lived on a separate controls
// page. These widgets paint the same state the firmware's main screen paints, and the key gesture
// is the one the lease was written for: press, renew, and a release in a finally so a cancelled
// pointer cannot leave the carrier up.

package radio.kd0cq.x6100.ui.face

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.StateFlow
import radio.kd0cq.x6100.net.PlanSegment
import radio.kd0cq.x6100.net.RadioState
import radio.kd0cq.x6100.net.SpectrumFrame
import radio.kd0cq.x6100.net.Spot
import radio.kd0cq.x6100.service.BackgroundRun
import radio.kd0cq.x6100.net.TxMeters
import radio.kd0cq.x6100.ui.theme.RfGuru
import radio.kd0cq.x6100.ui.theme.packedArgb

/**
 * One bitmap at the view's pixel size. Each pixel is interpolated between the FFT bins at that
 * frequency, so the bitmap is never a narrow strip that the canvas then stretches into blocks.
 * A short history is stretched down the pane (see [WaterfallDisplay.historyRowAt]) until there
 * is a row per pixel. The same bitmap is rewritten as frames arrive; a new one is made only
 * when the pane changes size.
 */
private class WaterfallSheet {
    private var bitmap: Bitmap? = null
    private var image: androidx.compose.ui.graphics.ImageBitmap? = null
    private var pixels = IntArray(0)

    fun paint(
        rows: List<SpectrumSlice>,
        width: Int,
        height: Int,
        floor: Int,
        ceiling: Int,
        colors: IntArray,
    ): androidx.compose.ui.graphics.ImageBitmap? {
        if (width <= 1 || height <= 1 || rows.isEmpty() || colors.isEmpty()) return null
        val view = rows.first()
        var bmp = bitmap
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = bmp
            image = bmp.asImageBitmap()
        }
        val need = width * height
        if (pixels.size != need) pixels = IntArray(need)
        val blank = colors.first()
        // Stretch a short history so every pixel of the pane is a row. A 1:1 map left the
        // bottom half black until a row had arrived for every pixel, which a 10-frame
        // stream does not do in the time the operator is looking at it.
        for (y in 0 until height) {
            val offset = y * width
            val row = rows[WaterfallDisplay.historyRowAt(y, height, rows.size)]
            if (row.db.isEmpty() || row.widthHz <= 0 || view.widthHz <= 0) {
                pixels.fill(blank, offset, offset + width)
                continue
            }
            for (x in 0 until width) {
                pixels[offset + x] = WaterfallDisplay.colorAt(
                    db = row.db,
                    rowCentreHz = row.centreHz,
                    rowWidthHz = row.widthHz,
                    viewCentreHz = view.centreHz,
                    viewWidthHz = view.widthHz,
                    pixel = x,
                    pixels = width,
                    floor = floor,
                    ceiling = ceiling,
                    colors = colors,
                )
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return image
    }
}

private val PillShape = RoundedCornerShape(6.dp)
private val KeyShape = RoundedCornerShape(10.dp)
private val Mono = FontFamily.Monospace

@Composable
fun Pill(
    text: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    placeholder: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val border = when {
        on -> RfGuru.gold
        placeholder -> RfGuru.mute
        else -> RfGuru.border
    }
    val fg = when {
        on -> RfGuru.goldText
        placeholder -> RfGuru.mute
        else -> RfGuru.text
    }
    val bg = if (on) RfGuru.goldDim else Color.Transparent
    Text(
        text = text,
        color = fg,
        fontSize = 11.sp,
        fontFamily = Mono,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, border, PillShape)
            .background(bg, PillShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = if (placeholder) "$text. $PENDING_FIRMWARE" else text },
    )
}

/** Wi-Fi, Bluetooth, audio, CAT, GPS and the cluster link. The Panel line does not report them. */
@Composable
fun StatusIconRow(onPlaceholder: (String) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf(
        RadioIcons.Wifi to "Wi-Fi",
        RadioIcons.Bluetooth to "Bluetooth",
        RadioIcons.Music to "A2DP",
        RadioIcons.Keyboard to "CAT",
        RadioIcons.Pin to "GPS",
        RadioIcons.Flag to "DX cluster link",
    )
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (icon, name) ->
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = "$name. $PENDING_FIRMWARE",
                tint = RfGuru.mute,
                modifier = Modifier
                    .height(16.dp)
                    .width(16.dp)
                    .clickable { onPlaceholder(name) },
            )
        }
    }
}

@Composable
fun ClockBlock(batteryPercent: Int?, onPlaceholder: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Text(
            "--:--:--",
            color = RfGuru.mute,
            fontFamily = Mono,
            fontSize = 14.sp,
            modifier = Modifier.clickable { onPlaceholder("Clock") }.semantics { contentDescription = "Clock. $PENDING_FIRMWARE" },
        )
        Text(
            if (batteryPercent == null) "—%" else "$batteryPercent%",
            color = RfGuru.goldText,
            fontFamily = Mono,
            fontSize = 13.sp,
        )
        Text(
            "~ —",
            color = RfGuru.mute,
            fontFamily = Mono,
            fontSize = 11.sp,
            modifier = Modifier.clickable { onPlaceholder("Battery time remaining") }
                .semantics { contentDescription = "Battery time remaining. $PENDING_FIRMWARE" },
        )
    }
}

@Composable
fun NeedleMeter(
    db: Double,
    transmitting: Boolean,
    tx: TxMeters,
    modifier: Modifier = Modifier,
) {
    val reading = RadioFace.sMeter(db)
    val txView = RadioFace.txMeter(tx)
    Box(
        modifier
            .background(RfGuru.bar, RoundedCornerShape(8.dp))
            .border(1.dp, RfGuru.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (transmitting) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                MeterBar("W", txView.wattsText, txView.watts, RfGuru.gold)
                MeterBar("SWR", txView.swrText, txView.swr, if (tx.swr >= 2.0) RfGuru.red else RfGuru.goldText)
                MeterBar("ALC", txView.alcText, txView.alc, RfGuru.blue)
            }
        } else {
            // The radio's dial is an amber arc with S1, the odd S units, 9, +20 and +40.
            // Three ticks and no words left the needle with nothing to be read against.
            val measurer = rememberTextMeasurer()
            val labelStyle = TextStyle(color = RfGuru.goldText, fontSize = 8.sp, fontFamily = Mono)
            Canvas(Modifier.fillMaxSize()) {
                val pivot = Offset(size.width / 2f, size.height * 0.82f)
                val radius = minOf(size.width * 0.46f, size.height * 0.72f)
                drawArc(
                    color = RfGuru.gold,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(pivot.x - radius, pivot.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 2.2f),
                )
                RadioFace.sMeterMarks().forEach { (mark, label) ->
                    val rad = Math.toRadians(180.0 + mark * 180.0)
                    val cosR = cos(rad).toFloat()
                    val sinR = sin(rad).toFloat()
                    val major = label == "S1" || label == "9" || label == "+40"
                    val inner = radius * if (major) 0.78f else 0.86f
                    val outer = radius * 0.98f
                    drawLine(
                        RfGuru.gold,
                        Offset(pivot.x + cosR * inner, pivot.y + sinR * inner),
                        Offset(pivot.x + cosR * outer, pivot.y + sinR * outer),
                        strokeWidth = if (major) 2.2f else 1.3f,
                    )
                    val measured = measurer.measure(label, labelStyle)
                    val labelR = radius * 0.58f
                    val left = (pivot.x + cosR * labelR - measured.size.width / 2f)
                        .coerceIn(0f, (size.width - measured.size.width).coerceAtLeast(0f))
                    val top = (pivot.y + sinR * labelR - measured.size.height / 2f)
                        .coerceIn(0f, (size.height - measured.size.height).coerceAtLeast(0f))
                    drawText(measured, topLeft = Offset(left, top))
                }
                val rad = Math.toRadians(180.0 + reading.needle * 180.0)
                drawLine(
                    packedArgb(RadioFace.VFO_LINE_ARGB),
                    pivot,
                    Offset(
                        pivot.x + (cos(rad) * radius * 0.9).toFloat(),
                        pivot.y + (sin(rad) * radius * 0.9).toFloat(),
                    ),
                    strokeWidth = 2.4f,
                    cap = StrokeCap.Round,
                )
            }
            Text(
                "${reading.caption}  ${RadioFace.formatDb(db)}",
                color = RfGuru.goldText,
                fontFamily = Mono,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun MeterBar(label: String, value: String, fraction: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = RfGuru.dim, fontSize = 10.sp, modifier = Modifier.width(28.dp))
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .background(RfGuru.border, RoundedCornerShape(3.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .background(color, RoundedCornerShape(3.dp)),
            )
        }
        Text(value, color = RfGuru.text, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.width(52.dp))
    }
}

@Composable
fun FrequencyReadout(
    freqHz: Int,
    group: FreqGroup,
    onGroup: (FreqGroup) -> Unit,
    onBump: (Int) -> Unit,
    large: Boolean,
    modifier: Modifier = Modifier,
    textSize: androidx.compose.ui.unit.TextUnit? = null,
) {
    val parts = RadioFace.frequencyParts(freqHz)
    val readout = RadioFace.formatFrequency(freqHz)
    // The 7-segment glyphs had a fillMaxWidth underline, so the first group ("14") took the
    // whole row and ".100.000" was clipped, with a scrap of a digit against the mode chip.
    // Tabular monospace is sized to this slot: the whole string stays on one line, and a wide
    // slot is at least as large as the old gold text.
    BoxWithConstraints(modifier) {
        // maxHeight is often the whole header, which this readout shares with another row.
        // Spending that height on the digits overflowed the 64 dp landscape header. The line
        // budget is the height the digits themselves may use; the width still shrinks them
        // when the slot is narrow.
        val lineBudget = if (large) 40f else 28f
        val heightDp = if (maxHeight.value.isFinite()) minOf(maxHeight.value, lineBudget) else lineBudget
        val fitted = RadioFace.frequencyTextSp(maxWidth.value, heightDp, readout)
        // A caller size is a ceiling, never a size the slot cannot hold. That ceiling is how
        // a fixed 20 sp used to be fine, and a larger fixed size is how ".100.000" was lost.
        val size = (textSize?.value?.let { minOf(it, fitted) } ?: fitted).coerceAtLeast(12f).sp
        val bump = (size.value * 0.45f).coerceIn(11f, 16f).sp
        val digits = TextStyle(
            fontFamily = Mono,
            fontSize = size,
            fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum",
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            FreqPart(parts.first, group == FreqGroup.MHZ, digits) { onGroup(FreqGroup.MHZ) }
            Text(".", color = RfGuru.gold, style = digits)
            FreqPart(parts.second, group == FreqGroup.KHZ, digits) { onGroup(FreqGroup.KHZ) }
            Text(".", color = RfGuru.gold, style = digits)
            FreqPart(parts.third, group == FreqGroup.HZ, digits) { onGroup(FreqGroup.HZ) }
            Column(Modifier.padding(start = 4.dp)) {
                Text("+", color = RfGuru.gold, fontFamily = Mono, fontSize = bump, modifier = Modifier.clickable { onBump(1) })
                Text("−", color = RfGuru.gold, fontFamily = Mono, fontSize = bump, modifier = Modifier.clickable { onBump(-1) })
            }
        }
    }
}

@Composable
private fun FreqPart(
    text: String,
    selected: Boolean,
    style: TextStyle,
    onClick: () -> Unit,
) {
    // The underline sits under the group. A single digit is too small a target on a phone,
    // so the tap selects MHz, kHz or Hz and the +/− beside the readout steps that group.
    // The underline is the width of the text, not fillMaxWidth: that call made the first
    // group consume the row and pushed the rest of the frequency off the screen.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (selected) "$text selected" else text },
    ) {
        Text(
            text = text,
            color = if (selected) RfGuru.goldText else RfGuru.gold,
            style = style,
            maxLines = 1,
            softWrap = false,
        )
        Box(
            Modifier
                .padding(top = 1.dp)
                .height(2.dp)
                .width((text.length * style.fontSize.value * 0.55f).dp)
                .background(if (selected) RfGuru.gold else Color.Transparent),
        )
    }
}

/**
 * Spectrum, frequency scale, band-plan strip and a scrolling waterfall.
 *
 * The spectrum flow is collected here, not in the screen. A frame then recomposes this pane
 * and not the keys, the header or the PTT. The canvas sits on its own graphics layer for the
 * same reason: the rest of the screen is not redrawn to scroll one waterfall row.
 *
 * A drag turns the main knob. A pinch maps onto the radio's zoom steps (fingers apart narrows
 * the span, the same direction as a tap on the Zoom chip). A tap tunes to the frequency under
 * the finger, or to a spot when the tap lands within [RadioFace.slopHz] of one. Those gestures
 * are the canvas, so they work on the 2D trace and on the 3D ridges.
 *
 * The 3D plot is a solid surface, newest ridge in front: the AetherSDR lawn, or the FT-710
 * 3DSS cyan-to-white look on a long-press of the same chip. 2D stays the default.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpectrumStack(
    spectrum: StateFlow<SpectrumFrame?>,
    state: RadioState?,
    plan: List<PlanSegment>,
    spots: List<Spot>,
    cwLine: String?,
    canOperate: Boolean,
    onTuneHz: (Int) -> Unit,
    onKnob: (Int) -> Unit,
    onZoom: (narrower: Boolean) -> Unit,
    waterfallShare: Float = 0f,
    modifier: Modifier = Modifier,
) {
    // Off while the activity is stopped. Collecting here is what scrolls the waterfall
    // and pushes 3D rows, so a phone in a pocket does not keep doing that work.
    val drawScope by BackgroundRun.drawScope.collectAsState()
    var frame by remember { mutableStateOf(spectrum.value) }
    LaunchedEffect(drawScope) {
        if (!drawScope) return@LaunchedEffect
        spectrum.collect { next -> frame = next }
    }
    // Start at the firmware's cap, then trim to the pane so a tall landscape waterfall keeps a
    // row per pixel instead of stretching 48 rows into blocks.
    val history = remember { SpectrumHistory(WaterfallDisplay.MAX_ROWS) }
    // The ring holds the full-scope depth. A combined pane only draws the front DEPTH rows.
    val ridgeRing = remember { SpectrumRidgeRing(SpectrumRidge.MAX_DEPTH) }
    val ridgeFills = remember { Array(SpectrumRidge.COLOR_STEPS) { Path() } }
    val ridgeScratch = remember { RidgeScratch() }
    val leveler = remember { WaterfallLeveler() }
    val sheet = remember { WaterfallSheet() }
    var tick by remember { mutableIntStateOf(0) }
    var mapName by rememberSaveable { mutableStateOf(WfColormap.GAUSS.name) }
    // The preference is the copy that survives a new process. rememberSaveable covers rotation,
    // which can happen before the preference write has been read back.
    val context = LocalContext.current
    val viewPreference = remember { SpectrumViewPreference(context) }
    var viewStored by rememberSaveable { mutableStateOf(viewPreference.read().stored) }
    var styleStored by rememberSaveable { mutableStateOf(viewPreference.readStyle().stored) }
    var layoutStored by rememberSaveable { mutableStateOf(viewPreference.readLayout().stored) }
    val spectrumView = SpectrumView.fromStored(viewStored)
    val ridgeStyle = RidgeStyle.fromStored(styleStored)
    val scopeLayout = ScopeLayout.fromStored(layoutStored)
    val ridge = spectrumView == SpectrumView.RIDGE
    val colormap = WfColormap.entries.firstOrNull { it.name == mapName } ?: WfColormap.GAUSS
    val colors = remember(colormap) { WaterfallDisplay.palette(colormap) }
    var paneWidth by remember { mutableIntStateOf(0) }
    var paneHeight by remember { mutableIntStateOf(0) }
    var waterRows by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(drawScope, frame?.sequence, frame?.centreHz, frame?.widthHz) {
        if (!drawScope) return@LaunchedEffect
        val current = frame ?: return@LaunchedEffect
        history.push(current.db, current.centreHz, current.widthHz)
        // Same frames the waterfall keeps. The ring copies into slots it already owns.
        ridgeRing.push(current.db, current.centreHz, current.widthHz)
        tick++
    }
    val rows = remember(tick) { history.snapshot() }
    // The window eases toward the newest row. Taking the percentile of the whole history, or
    // replacing it every frame, is what made a quiet band shimmer.
    val window = remember(tick) {
        rows.firstOrNull()?.db?.let { leveler.update(it) } ?: (leveler.floor to leveler.ceiling)
    }
    // Painted at the view's pixel width, one history row per pixel. Not inside draw: drawImage
    // holds the bitmap until the frame is presented, and recycling it there blanks the waterfall.
    val waterfall = remember(tick, paneWidth, waterRows, colormap) {
        sheet.paint(rows, paneWidth, waterRows, window.first, window.second, colors)
    }
    val measurer = rememberTextMeasurer()
    val span = frame?.let { RadioFace.spanOf(it.centreHz, it.widthHz) }
    val labelStyle = TextStyle(color = RfGuru.dim, fontSize = 10.sp, fontFamily = Mono)
    val spotStyle = TextStyle(color = RfGuru.blue, fontSize = 10.sp, fontFamily = Mono, fontWeight = FontWeight.Medium)
    val alertStyle = spotStyle.copy(color = Color(0xFFF472B6))
    val planText = TextStyle(color = RfGuru.bg, fontSize = 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
    val cwStyle = TextStyle(color = RfGuru.goldText, fontSize = 12.sp, fontFamily = Mono)

    Box(
        modifier
            .background(Color.Black)
            .onSizeChanged { size ->
                paneWidth = size.width
                paneHeight = size.height
            },
    ) {
        // The split depends on the 2D/3D choice, not only on the pane's size. onSizeChanged
        // does not run again when the chip is tapped, so the waterfall depth is recomputed here.
        val scalePx = with(density) { 16.dp.toPx() }
        val planPx = with(density) { 14.dp.toPx() }
        val guardPx = with(density) { 24.dp.toPx() }
        LaunchedEffect(paneWidth, paneHeight, ridge, waterfallShare, scalePx, planPx, guardPx, scopeLayout) {
            if (paneWidth <= 0 || paneHeight <= 0) return@LaunchedEffect
            val split = RadioFace.scopeSplit(
                paneHeight.toFloat(), scalePx, planPx, waterfallShare, guardPx, ridge, scopeLayout,
            )
            val rowsHigh = split.waterfallPx.roundToInt().coerceIn(1, WaterfallDisplay.MAX_ROWS)
            waterRows = rowsHigh
            history.resize(rowsHigh)
        }
        Canvas(
            Modifier
                .fillMaxSize()
                // Own layer: scrolling the waterfall does not repaint the keys or the header.
                .graphicsLayer()
                .pointerInput(canOperate, span, spots) {
                    if (span == null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var total = 0f
                        var bucket = 0f
                        var dragged = false
                        var pinched = false
                        var pinch = 1f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                // Fingers apart is a narrower span, the same way a tap on Zoom is.
                                pinched = true
                                val a = pressed[0]
                                val b = pressed[1]
                                val dist = hypot(a.position.x - b.position.x, a.position.y - b.position.y)
                                val prev = hypot(
                                    a.previousPosition.x - b.previousPosition.x,
                                    a.previousPosition.y - b.previousPosition.y,
                                )
                                if (prev > 8f && dist > 8f && canOperate) {
                                    pinch *= dist / prev
                                    if (pinch >= 1.18f) {
                                        onZoom(true)
                                        pinch = 1f
                                    } else if (pinch <= 1f / 1.18f) {
                                        onZoom(false)
                                        pinch = 1f
                                    }
                                }
                                pressed.forEach { it.consume() }
                            } else if (!pinched) {
                                val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                val dx = change.positionChange().x
                                total += dx
                                bucket += dx
                                if (abs(bucket) >= 36f && canOperate) {
                                    val steps = (bucket / 36f).toInt()
                                    if (steps != 0) {
                                        onKnob(steps)
                                        bucket -= steps * 36f
                                        dragged = true
                                        change.consume()
                                    }
                                }
                                if (abs(total) > 12f) dragged = true
                            }
                        }
                        if (!pinched && !dragged && canOperate) {
                            val fraction = (down.position.x / size.width).coerceIn(0f, 1f)
                            val hz = RadioFace.hzAtFraction(fraction, span)
                            val spot = RadioFace.nearestSpot(hz, spots, RadioFace.slopHz(span))
                            onTuneHz(spot?.freqHz ?: hz)
                        }
                    }
                },
        ) {
            val scaleH = 16.dp.toPx()
            val planH = 14.dp.toPx()
            // Zoom sits in the top-right of the trace. The split keeps that band at least as
            // tall as the chip, so "Zoom x1" cannot land on the last frequency tick.
            val zoomGuard = 24.dp.toPx()
            val split = RadioFace.scopeSplit(
                size.height, scaleH, planH, waterfallShare, zoomGuard, ridge, scopeLayout,
            )
            val specH = split.spectrumPx
            val waterTop = specH + scaleH + planH
            val waterH = split.waterfallPx

            val spectrumFrame = frame
            if (scopeLayout == ScopeLayout.WATERFALL || specH < 2f) {
                // The waterfall has the pane. The scale and the plan are still drawn below.
            } else if (spectrumFrame == null || spectrumFrame.bins <= 0) {
                drawLine(RfGuru.dim, Offset(0f, specH / 2f), Offset(size.width, specH / 2f), strokeWidth = 1f)
            } else if (ridge) {
                drawRidgePlot(
                    ring = ridgeRing,
                    fills = ridgeFills,
                    scratch = ridgeScratch,
                    style = ridgeStyle,
                    specH = specH,
                    viewWidth = size.width,
                    floor = window.first,
                    ceiling = window.second,
                    viewCentreHz = spectrumFrame.centreHz,
                    viewWidthHz = spectrumFrame.widthHz,
                )
            } else {
                // One sample about every two pixels, linearly between bins, so a 1024-bin FFT
                // does not draw as 1024 rectangles. The fill fades from the line down to the
                // background, which is how the radio draws a filled trace.
                val samples = (size.width / 2f).toInt().coerceIn(64, 900)
                val dbSpan = (window.second - window.first).coerceAtLeast(1).toFloat()
                val outline = Path()
                for (i in 0 until samples) {
                    val hz = WaterfallDisplay.hzAtPixel(i, samples, spectrumFrame.centreHz, spectrumFrame.widthHz)
                    val index = WaterfallDisplay.binAtHz(hz, spectrumFrame.centreHz, spectrumFrame.widthHz, spectrumFrame.bins)
                    val db = WaterfallDisplay.sampleBin(spectrumFrame.db, index) ?: window.first.toFloat()
                    val v = ((db - window.first) / dbSpan).coerceIn(0f, 1f)
                    val x = if (samples == 1) 0f else i * size.width / (samples - 1).toFloat()
                    val y = specH * (1f - v * 0.92f)
                    if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
                }
                val fill = Path().apply {
                    addPath(outline)
                    lineTo(size.width, specH)
                    lineTo(0f, specH)
                    close()
                }
                drawPath(
                    fill,
                    Brush.verticalGradient(
                        0f to Color(0xCCF1F5F9),
                        1f to Color(0x0008090C),
                        startY = 0f,
                        endY = specH.coerceAtLeast(1f),
                    ),
                )
                drawPath(outline, Color(0xFFF1F5F9), style = Stroke(width = 1.5f))
            }

            if (waterfall != null && waterH > 1f) {
                // The bitmap is already the view's pixel size. Stretching it here is what made
                // the blocks, so it is drawn 1:1 and any leftover pixel stays the background.
                drawImage(
                    image = waterfall,
                    dstOffset = androidx.compose.ui.unit.IntOffset(0, waterTop.roundToInt()),
                    filterQuality = FilterQuality.None,
                )
            }

            span?.let { hzSpan ->
                // Fewer labels than the view can hold. placeScaleLabels then drops any that still
                // collide, and keeps the survivors inside the width so 14.140 is not cut in half.
                val maxLabels = (size.width / 78f).toInt().coerceIn(2, 5)
                val ticks = RadioFace.scaleTicks(hzSpan, maxLabels)
                val measured = ticks.map { measurer.measure(RadioFace.scaleLabel(it), labelStyle) }
                val centers = ticks.map { RadioFace.fractionForHz(it, hzSpan).coerceIn(0f, 1f) * size.width }
                // If the box is too short to put the scale under the chip, keep the last
                // label out of the chip's corner instead of drawing 14.150 through "Zoom x1".
                val labelWidth = if (specH + 2f < zoomGuard) (size.width - 72.dp.toPx()).coerceAtLeast(1f) else size.width
                val lefts = RadioFace.placeScaleLabels(
                    centers,
                    measured.map { it.size.width.toFloat() },
                    labelWidth,
                    8f,
                )
                ticks.indices.forEach { i ->
                    val x = centers[i]
                    drawLine(RfGuru.mute, Offset(x, specH), Offset(x, specH + scaleH), strokeWidth = 1f)
                    val left = lefts[i] ?: return@forEach
                    drawText(measured[i], topLeft = Offset(left, specH + 1f))
                }
                RadioFace.planSlices(hzSpan, plan).forEach { slice ->
                    val left = slice.start * size.width
                    val right = slice.end * size.width
                    drawRect(
                        packedArgb(RadioFace.planColor(slice.type)),
                        topLeft = Offset(left, specH + scaleH),
                        size = Size((right - left).coerceAtLeast(1f), planH),
                    )
                    if (slice.label.isNotEmpty() && right - left > 28f) {
                        val measured = measurer.measure(slice.label, planText)
                        drawText(
                            measured,
                            topLeft = Offset(left + (right - left - measured.size.width) / 2f, specH + scaleH + 1f),
                        )
                    }
                }
                state?.let { radio ->
                    val (lo, hi) = RadioFace.passbandHz(radio.freqHz, radio.filterLowHz, radio.filterHighHz)
                    val x0 = RadioFace.fractionForHz(lo, hzSpan) * size.width
                    val x1 = RadioFace.fractionForHz(hi, hzSpan) * size.width
                    val bandLeft = minOf(x0, x1).coerceIn(0f, size.width)
                    val bandRight = maxOf(x0, x1).coerceIn(0f, size.width)
                    drawRect(
                        packedArgb(RadioFace.VFO_LINE_ARGB).copy(alpha = 0.18f),
                        topLeft = Offset(bandLeft, 0f),
                        size = Size((bandRight - bandLeft).coerceAtLeast(1f), size.height),
                    )
                    val vfoX = RadioFace.fractionForHz(radio.freqHz, hzSpan).coerceIn(0f, 1f) * size.width
                    drawLine(packedArgb(RadioFace.VFO_LINE_ARGB), Offset(vfoX, 0f), Offset(vfoX, size.height), strokeWidth = 2f)
                }
                // With no waterfall, the labels sit in the spectrum just above the scale.
                val spotTop = if (waterH > 8f) waterTop else (specH - 28f).coerceAtLeast(0f)
                spots.forEach { spot ->
                    val x = RadioFace.fractionForHz(spot.freqHz, hzSpan) * size.width
                    if (x < -8f || x > size.width + 8f) return@forEach
                    drawLine(RfGuru.blue.copy(alpha = 0.7f), Offset(x, spotTop), Offset(x, spotTop + 10f), strokeWidth = 1f)
                    val measured = measurer.measure(spot.call, if (spot.alert) alertStyle else spotStyle)
                    val left = (x - measured.size.width / 2f).coerceIn(0f, (size.width - measured.size.width).coerceAtLeast(0f))
                    drawText(measured, topLeft = Offset(left, spotTop + 10f))
                }
            }

            cwLine?.takeIf { it.isNotBlank() }?.let { line ->
                drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(0f, size.height - 18.dp.toPx()), size = Size(size.width, 18.dp.toPx()))
                val measured = measurer.measure(line, cwStyle)
                drawText(measured, topLeft = Offset(6.dp.toPx(), size.height - 16.dp.toPx()))
            }
        }

        Text(
            text = "Zoom x${state?.zoom ?: 1}",
            color = RfGuru.goldText,
            fontSize = 11.sp,
            fontFamily = Mono,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 2.dp, end = 4.dp)
                .background(RfGuru.bg.copy(alpha = 0.72f), PillShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onZoom(true) },
                        onLongPress = { onZoom(false) },
                    )
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .semantics { contentDescription = "Spectrum zoom. Tap narrows, hold widens" },
        )
        Row(
            Modifier.align(Alignment.TopStart).padding(top = 2.dp, start = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = colormap.label,
                color = RfGuru.goldText,
                fontSize = 11.sp,
                fontFamily = Mono,
                modifier = Modifier
                    .background(RfGuru.bg.copy(alpha = 0.72f), PillShape)
                    .clickable {
                        val next = colormap.next()
                        mapName = next.name
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .semantics { contentDescription = "Waterfall colours ${colormap.label}. Tap for the next palette" },
            )
            Text(
                text = SpectrumRidge.chipLabel(spectrumView, ridgeStyle),
                color = RfGuru.goldText,
                fontSize = 11.sp,
                fontFamily = Mono,
                modifier = Modifier
                    .background(RfGuru.bg.copy(alpha = 0.72f), PillShape)
                    .combinedClickable(
                        onClick = {
                            val next = spectrumView.next()
                            viewStored = next.stored
                            viewPreference.write(next)
                        },
                        onLongClick = {
                            // The second 3D colour. Holding the chip swaps the lawn for the
                            // FT-710 cyan stream, and opens 3D if the trace was showing.
                            val next = ridgeStyle.next()
                            styleStored = next.stored
                            viewPreference.writeStyle(next)
                            if (spectrumView == SpectrumView.TRACE) {
                                viewStored = SpectrumView.RIDGE.stored
                                viewPreference.write(SpectrumView.RIDGE)
                            }
                        },
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .semantics {
                        contentDescription = "Spectrum view ${SpectrumRidge.chipLabel(spectrumView, ridgeStyle)}. Tap for 2D or 3D. Hold for the other 3D colours"
                    },
            )
            Text(
                text = scopeLayout.chip,
                color = RfGuru.goldText,
                fontSize = 11.sp,
                fontFamily = Mono,
                modifier = Modifier
                    .background(RfGuru.bg.copy(alpha = 0.72f), PillShape)
                    .clickable {
                        val next = scopeLayout.next()
                        layoutStored = next.stored
                        viewPreference.writeLayout(next)
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .semantics {
                        contentDescription = "Scope layout ${scopeLayout.chip}. Tap to cycle spectrum and waterfall, spectrum only, or waterfall only"
                    },
            )
        }
    }
}

/**
 * Solid ridges, oldest first.
 *
 * Each column is a wall from that row's baseline up to the signal, filled with the colour
 * of its level. Columns of one colour share a path, so a quiet row is one polygon and a
 * busy row is a handful, not a line per sample. The fill is opaque and fogged by age:
 * the nearer wall covers the one behind it, and the back of the stream is darker.
 *
 * On a mid-range phone this is about sixty rows times eight paths, once per spectrum
 * frame (the radio sends at most ~15 a second). The paths and the sample buffers are
 * the ones the pane allocated.
 */
private fun DrawScope.drawRidgePlot(
    ring: SpectrumRidgeRing,
    fills: Array<Path>,
    scratch: RidgeScratch,
    style: RidgeStyle,
    specH: Float,
    viewWidth: Float,
    floor: Int,
    ceiling: Int,
    viewCentreHz: Int,
    viewWidthHz: Int,
) {
    if (specH < 2f || viewWidth < 2f || ring.count <= 0 || viewWidthHz <= 0) return
    val span = (ceiling - floor).coerceAtLeast(1).toFloat()
    val samples = scratch.x.size
    // A tall spectrum-only pane draws more ridges, and a slightly taller peak fraction.
    // Using the ring's full depth here would squash that history into the short combined pane.
    val depth = SpectrumRidge.rowsForHeight(specH).coerceAtMost(ring.depth)
    val amplitude = SpectrumRidge.amplitudeFor(specH)
    val shown = minOf(ring.count, depth)
    var haveOlder = false
    var olderCentre = 0
    var olderWidth = 0
    // Oldest first. The opaque wall of a nearer ridge then covers what is behind it.
    for (age in shown - 1 downTo 0) {
        val row = ring.dbAt(age)
        val rowBins = ring.lengthAt(age)
        val rowCentre = ring.centreAt(age)
        val rowWidth = ring.widthAt(age)
        if (rowBins <= 0 || rowWidth <= 0) continue
        for (i in 0 until samples) {
            val hz = WaterfallDisplay.hzAtPixel(i, samples, viewCentreHz, viewWidthHz)
            val index = WaterfallDisplay.binAtHz(hz, rowCentre, rowWidth, rowBins)
            val db = WaterfallDisplay.sampleBin(row, index, rowBins) ?: floor.toFloat()
            scratch.level[i] = SpectrumRidge.shapeLevel(((db - floor) / span).coerceIn(0f, 1f))
        }
        SpectrumRidge.smoothSpatial(scratch.level, scratch.shaped, samples)
        val sameSpan = haveOlder && olderCentre == rowCentre && olderWidth == rowWidth
        if (sameSpan) {
            for (i in 0 until samples) {
                scratch.level[i] = SpectrumRidge.smoothTemporal(scratch.shaped[i], scratch.older[i])
            }
        } else {
            for (i in 0 until samples) scratch.level[i] = scratch.shaped[i]
        }
        for (i in 0 until samples) {
            scratch.older[i] = scratch.shaped[i]
            scratch.x[i] = SpectrumRidge.projectX(i, samples, age, depth, viewWidth)
            scratch.y[i] = SpectrumRidge.projectY(age, depth, scratch.level[i], specH, amplitude = amplitude)
        }
        haveOlder = true
        olderCentre = rowCentre
        olderWidth = rowWidth
        val baseline = SpectrumRidge.projectY(age, depth, 0f, specH)
        for (path in fills) path.rewind()
        // A run of columns that share a colour is one polygon: up the left side, along the
        // crest, down the right side, and back along the baseline.
        var start = 0
        while (start < samples - 1) {
            val step = SpectrumRidge.colorStep(maxOf(scratch.level[start], scratch.level[start + 1]))
            var end = start + 1
            while (end < samples - 1 &&
                SpectrumRidge.colorStep(maxOf(scratch.level[end], scratch.level[end + 1])) == step
            ) {
                end++
            }
            val path = fills[step]
            path.moveTo(scratch.x[start], baseline)
            for (k in start..end) path.lineTo(scratch.x[k], scratch.y[k])
            path.lineTo(scratch.x[end], baseline)
            path.close()
            start = end
        }
        for (step in fills.indices) {
            val color = SpectrumRidge.fogArgb(SpectrumRidge.stepArgb(step, style), age, depth)
            drawPath(fills[step], packedArgb(color))
        }
    }
    drawRidgeGrid(samples, depth, viewWidth, specH)
}

/** Frequency lines run to the vanishing point. Time lines follow the floor of every sixteenth row. */
private fun DrawScope.drawRidgeGrid(samples: Int, depth: Int, width: Float, height: Float) {
    val color = packedArgb(SpectrumRidge.GRID_ARGB)
    val marks = intArrayOf(samples / 4, samples / 2, (samples * 3) / 4)
    for (i in marks) {
        val x0 = SpectrumRidge.projectX(i, samples, 0, depth, width)
        val y0 = SpectrumRidge.projectY(0, depth, 0f, height)
        val x1 = SpectrumRidge.projectX(i, samples, depth - 1, depth, width)
        val y1 = SpectrumRidge.projectY(depth - 1, depth, 0f, height)
        drawLine(color, Offset(x0, y0), Offset(x1, y1), strokeWidth = 1f)
    }
    var age = 16
    while (age < depth) {
        val y = SpectrumRidge.projectY(age, depth, 0f, height)
        val xL = SpectrumRidge.projectX(0, samples, age, depth, width)
        val xR = SpectrumRidge.projectX(samples - 1, samples, age, depth, width)
        drawLine(color, Offset(xL, y), Offset(xR, y), strokeWidth = 1f)
        age += 16
    }
}

@Composable
fun GroupStrip(
    selected: FaceGroup,
    onSelect: (FaceGroup) -> Unit,
    modifier: Modifier = Modifier,
    distribute: Boolean = false,
) {
    // Landscape shares one row with the softkeys. A scrolling strip there hid MEM past the
    // edge and clipped DFL to DF. Equal weights put all six names on screen.
    if (distribute) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            FaceGroup.entries.forEach { group ->
                val on = group == selected
                Text(
                    group.strip,
                    color = if (on) RfGuru.barBottom else RfGuru.dim,
                    fontSize = 11.sp,
                    fontFamily = Mono,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .background(if (on) RfGuru.gold else RfGuru.button, PillShape)
                        .clickable { onSelect(group) }
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                )
            }
        }
        return
    }
    Row(
        modifier.horizontalScroll(rememberScrollState()).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FaceGroup.entries.forEach { group ->
            val on = group == selected
            Text(
                group.strip,
                color = if (on) RfGuru.barBottom else RfGuru.dim,
                fontSize = 11.sp,
                fontFamily = Mono,
                modifier = Modifier
                    .background(if (on) RfGuru.gold else RfGuru.button, PillShape)
                    .clickable { onSelect(group) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
fun SoftkeyBar(
    page: FacePage,
    state: RadioState?,
    onKey: (FaceKey, Int) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: androidx.compose.ui.unit.Dp = 58.dp,
    compact: Boolean = false,
) {
    // A 40 dp bar with 1 dp of padding clipped the value line against the bottom edge.
    // Compact landscape keys keep 4 dp inside a bar tall enough for two lines.
    val pad = if (compact) 4.dp else if (barHeight < 48.dp) 3.dp else 4.dp
    Row(modifier.height(barHeight), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (slot in 0 until 5) {
            val key = page.keys.getOrNull(slot)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (key == null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(RfGuru.bar, KeyShape)
                            .border(1.dp, RfGuru.border, KeyShape),
                    )
                } else {
                    val label = RadioFace.keyLabel(key, state)
                    val pageKey = key.action is FaceAction.NextPage
                    Surface(
                        color = if (pageKey) RfGuru.bar else if (label.on) RfGuru.goldDim else RfGuru.button,
                        shape = KeyShape,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (label.on) RfGuru.gold else RfGuru.border,
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(key.id) {
                                detectTapGestures(
                                    onTap = { onKey(key, +1) },
                                    onLongPress = { onKey(key, -1) },
                                )
                            }
                            .semantics {
                                contentDescription = if (label.pending) {
                                    "${label.title}. $PENDING_FIRMWARE"
                                } else {
                                    "${label.title} ${label.value}"
                                }
                            },
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = pad),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                when {
                                    // "VOL" / "1:2" sat next to "Vol" / "30" and read as a second
                                    // volume. It is the page key. Name it that way in the tight row.
                                    compact && pageKey -> "Page"
                                    compact -> RadioFace.compactKeyTitle(label.title)
                                    else -> label.title
                                },
                                color = if (pageKey) RfGuru.dim else RfGuru.text,
                                fontSize = 11.sp,
                                fontFamily = Mono,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            if (label.value.isNotEmpty()) {
                                Text(
                                    label.value,
                                    color = when {
                                        label.pending -> RfGuru.mute
                                        label.on -> RfGuru.gold
                                        else -> RfGuru.goldText
                                    },
                                    fontSize = 12.sp,
                                    fontFamily = Mono,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompactSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = RfGuru.gold, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.width(36.dp))
        Slider(
            value = value.coerceIn(range),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.weight(1f).height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = RfGuru.gold,
                activeTrackColor = RfGuru.gold,
                inactiveTrackColor = RfGuru.border,
                disabledThumbColor = RfGuru.mute,
                disabledActiveTrackColor = RfGuru.mute,
            ),
        )
        Text(valueText, color = RfGuru.text, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
    }
}

/**
 * Press-and-hold is the key.
 *
 * A press is a lease the app renews once a second, so a phone that stops renewing is off the air
 * within three seconds on the Panel path and within half a second on the audio path. The release
 * is in a `finally`, so a pointer that never lifts cleanly cannot leave the transmitter holding.
 * [mayKey] is operate **and** the session's tx flag: `role operate 0` must not key, and gating
 * only the Button's enabled flag would leave this gesture live.
 */
@Composable
fun HoldToTalk(
    keyed: Boolean,
    mayKey: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (keyed) RfGuru.tx else RfGuru.button,
        contentColor = if (keyed) RfGuru.onTx else RfGuru.goldText,
        shape = KeyShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (keyed) RfGuru.red else RfGuru.gold),
        modifier = modifier
            .pointerInput(mayKey) {
                if (!mayKey) return@pointerInput
                detectTapGestures(onPress = {
                    onPress()
                    try {
                        tryAwaitRelease()
                    } finally {
                        onRelease()
                    }
                })
            }
            .semantics { contentDescription = if (keyed) "On air, release to unkey" else "Push to talk" },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (keyed) "ON AIR" else "PTT", fontSize = 18.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MicSwitch(
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val micName = if (checked) "Phone mic" else "Radio mic"
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.material3.Icon(
            RadioIcons.Mic,
            contentDescription = micName,
            tint = RfGuru.dim,
            modifier = Modifier.height(16.dp),
        )
        if (!compact) {
            Text(micName, color = RfGuru.dim, fontSize = 11.sp, fontFamily = Mono, maxLines = 1, softWrap = false)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RfGuru.barBottom,
                checkedTrackColor = RfGuru.gold,
                uncheckedThumbColor = RfGuru.dim,
                uncheckedTrackColor = RfGuru.button,
                uncheckedBorderColor = RfGuru.border,
            ),
        )
    }
}
