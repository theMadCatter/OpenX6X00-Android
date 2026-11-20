// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.StateFlow
import radio.kd0cq.x6100.net.PlanSegment
import radio.kd0cq.x6100.net.RadioState
import radio.kd0cq.x6100.net.SpectrumFrame
import radio.kd0cq.x6100.net.Spot
import radio.kd0cq.x6100.net.TxMeters
import radio.kd0cq.x6100.ui.theme.RfGuru
import radio.kd0cq.x6100.ui.theme.packedArgb

/**
 * One bitmap at the view's pixel size. Rows are painted 1:1, each pixel interpolated between
 * the FFT bins at that frequency, so the bitmap is never a narrow strip that the canvas then
 * stretches into blocks. The same bitmap is rewritten as frames arrive; a new one is made only
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
        val shown = min(rows.size, height)
        for (y in 0 until height) {
            val offset = y * width
            val row = if (y < shown) rows[y] else null
            if (row == null || row.db.isEmpty() || row.widthHz <= 0 || view.widthHz <= 0) {
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
    // Segment digits, not a proportional face. The landscape header passes an explicit size so
    // the +/− pair stays on one line and does not steal a row from the waterfall.
    val digitH = when {
        textSize != null && textSize.value <= 22f -> 22.dp
        large -> 34.dp
        else -> 28.dp
    }
    val bump = if (textSize != null) 12.sp else 14.sp
    val lit = RfGuru.goldText
    val dim = RfGuru.text
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        FreqPart(parts.first, group == FreqGroup.MHZ, digitH, lit, dim) { onGroup(FreqGroup.MHZ) }
        SegmentGlyph('.', lit, digitH)
        FreqPart(parts.second, group == FreqGroup.KHZ, digitH, lit, dim) { onGroup(FreqGroup.KHZ) }
        SegmentGlyph('.', lit, digitH)
        FreqPart(parts.third, group == FreqGroup.HZ, digitH, lit, dim) { onGroup(FreqGroup.HZ) }
        Column(Modifier.padding(start = 4.dp)) {
            Text("+", color = RfGuru.gold, fontFamily = Mono, fontSize = bump, modifier = Modifier.clickable { onBump(1) })
            Text("−", color = RfGuru.gold, fontFamily = Mono, fontSize = bump, modifier = Modifier.clickable { onBump(-1) })
        }
    }
}

@Composable
private fun FreqPart(
    text: String,
    selected: Boolean,
    digitH: androidx.compose.ui.unit.Dp,
    lit: Color,
    dim: Color,
    onClick: () -> Unit,
) {
    // The underline sits under the triplet. A single digit is too small a target on a phone,
    // so the tap selects MHz, kHz or Hz and the +/− beside the readout steps that group.
    val color = if (selected) lit else dim
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (selected) "$text selected" else text },
    ) {
        Row {
            text.forEach { ch -> SegmentGlyph(ch, color, digitH) }
        }
        Box(
            Modifier
                .padding(top = 1.dp)
                .height(2.dp)
                .fillMaxWidth()
                .background(if (selected) RfGuru.gold else Color.Transparent),
        )
    }
}

/** One 7-segment digit, or a decimal point. Unlit bars stay faintly on, as on the radio's display. */
@Composable
private fun SegmentGlyph(ch: Char, color: Color, height: androidx.compose.ui.unit.Dp) {
    if (ch == '.') {
        Box(
            Modifier.width(height * 0.28f).height(height),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .padding(bottom = height * 0.12f)
                    .size(height * 0.16f)
                    .background(color, CircleShape),
            )
        }
        return
    }
    val mask = RadioFace.segmentMask(ch)
    Canvas(Modifier.width(height * 0.62f).height(height)) {
        val w = size.width
        val h = size.height
        val t = (h * 0.12f).coerceAtLeast(1.4f)
        val gap = t * 0.45f
        val mid = h / 2f
        val left = gap + t / 2f
        val right = w - gap - t / 2f
        fun seg(bit: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
            val on = mask and bit != 0
            drawLine(
                color = if (on) color else color.copy(alpha = 0.16f),
                start = Offset(x0, y0),
                end = Offset(x1, y1),
                strokeWidth = t,
                cap = StrokeCap.Round,
            )
        }
        seg(1, left, t, right, t)
        seg(2, right, t + gap, right, mid - gap)
        seg(4, right, mid + gap, right, h - t - gap)
        seg(8, left, h - t, right, h - t)
        seg(16, left, mid + gap, left, h - t - gap)
        seg(32, left, t + gap, left, mid - gap)
        seg(64, left, mid, right, mid)
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
 * the finger, or to a spot when the tap lands within [RadioFace.slopHz] of one.
 */
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
    val frame by spectrum.collectAsState()
    // Start at the firmware's cap, then trim to the pane so a tall landscape waterfall keeps a
    // row per pixel instead of stretching 48 rows into blocks.
    val history = remember { SpectrumHistory(WaterfallDisplay.MAX_ROWS) }
    val leveler = remember { WaterfallLeveler() }
    val sheet = remember { WaterfallSheet() }
    var tick by remember { mutableIntStateOf(0) }
    var mapName by rememberSaveable { mutableStateOf(WfColormap.GAUSS.name) }
    val colormap = WfColormap.entries.firstOrNull { it.name == mapName } ?: WfColormap.GAUSS
    val colors = remember(colormap) { WaterfallDisplay.palette(colormap) }
    var paneWidth by remember { mutableIntStateOf(0) }
    var waterRows by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(frame?.sequence, frame?.centreHz, frame?.widthHz) {
        val current = frame ?: return@LaunchedEffect
        history.push(current.db, current.centreHz, current.widthHz)
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
                val scalePx = with(density) { 16.dp.toPx() }
                val planPx = with(density) { 14.dp.toPx() }
                val guardPx = with(density) { 24.dp.toPx() }
                val split = RadioFace.scopeSplit(size.height.toFloat(), scalePx, planPx, waterfallShare, guardPx)
                val rowsHigh = split.waterfallPx.roundToInt().coerceIn(1, WaterfallDisplay.MAX_ROWS)
                waterRows = rowsHigh
                history.resize(rowsHigh)
            },
    ) {
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
            val split = RadioFace.scopeSplit(size.height, scaleH, planH, waterfallShare, zoomGuard)
            val specH = split.spectrumPx
            val waterTop = specH + scaleH + planH
            val waterH = split.waterfallPx

            val spectrumFrame = frame
            if (spectrumFrame == null || spectrumFrame.bins <= 0) {
                drawLine(RfGuru.dim, Offset(0f, specH / 2f), Offset(size.width, specH / 2f), strokeWidth = 1f)
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
                spots.forEach { spot ->
                    val x = RadioFace.fractionForHz(spot.freqHz, hzSpan) * size.width
                    if (x < -8f || x > size.width + 8f) return@forEach
                    drawLine(RfGuru.blue.copy(alpha = 0.7f), Offset(x, waterTop), Offset(x, waterTop + 10f), strokeWidth = 1f)
                    val measured = measurer.measure(spot.call, if (spot.alert) alertStyle else spotStyle)
                    val left = (x - measured.size.width / 2f).coerceIn(0f, (size.width - measured.size.width).coerceAtLeast(0f))
                    drawText(measured, topLeft = Offset(left, waterTop + 10f))
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
        Text(
            text = colormap.label,
            color = RfGuru.goldText,
            fontSize = 11.sp,
            fontFamily = Mono,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 2.dp, start = 4.dp)
                .background(RfGuru.bg.copy(alpha = 0.72f), PillShape)
                .clickable {
                    val next = colormap.next()
                    mapName = next.name
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .semantics { contentDescription = "Waterfall colours ${colormap.label}. Tap for the next palette" },
        )
    }
}

@Composable
fun GroupStrip(selected: FaceGroup, onSelect: (FaceGroup) -> Unit, modifier: Modifier = Modifier) {
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
                                if (compact) RadioFace.compactKeyTitle(label.title) else label.title,
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
fun MicSwitch(checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.Icon(RadioIcons.Mic, contentDescription = null, tint = RfGuru.dim, modifier = Modifier.height(16.dp))
        Text(if (checked) "Phone mic" else "Radio mic", color = RfGuru.dim, fontSize = 11.sp, fontFamily = Mono)
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
