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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import radio.kd0cq.x6100.net.PanelMode
import radio.kd0cq.x6100.net.PlanSegment
import radio.kd0cq.x6100.net.RadioState
import radio.kd0cq.x6100.net.SpectrumFrame
import radio.kd0cq.x6100.net.Spot
import radio.kd0cq.x6100.service.BackgroundRun
import radio.kd0cq.x6100.net.TxMeters
import radio.kd0cq.x6100.ui.theme.RfGuru
import radio.kd0cq.x6100.ui.theme.packedArgb

/**
 * One ARGB_8888 bitmap at the pane's pixel size, density none.
 *
 * Before, the bitmap adopted the display density and each pixel was a blend of the bins
 * beside it, and a short history was stretched so one line covered several pixels. A
 * density-aware draw then scaled that bitmap up again. The bitmap is now the screen pixels
 * themselves: one stored pixel per screen pixel, nearest bin or the loudest bin in the
 * pixel, one history line per row. It is drawn at that size with no filter. A new bitmap
 * is made only when the pane changes size; the same one is rewritten as frames arrive.
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
            // DENSITY_NONE: createBitmap otherwise stamps the display density, and a later
            // draw that honours density turns one stored pixel into density/160 screen
            // pixels. The bitmap is already in screen pixels. ARGB_8888 keeps the 256-colour
            // table; RGB_565 would crush it.
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.density = Bitmap.DENSITY_NONE
            bitmap = bmp
            image = bmp.asImageBitmap()
        }
        val need = width * height
        if (pixels.size != need) pixels = IntArray(need)
        val blank = colors.first()
        // One line, one pixel. A y past the lines received so far stays the floor colour
        // instead of a copy of an older line stretched to fill the pane.
        for (y in 0 until height) {
            val offset = y * width
            val rowIndex = WaterfallDisplay.historyRowAt(y, height, rows.size)
            if (rowIndex < 0) {
                pixels.fill(blank, offset, offset + width)
                continue
            }
            val row = rows[rowIndex]
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

/**
 * The frequency scale under the trace. The 10 sp labels need this much.
 */
private val ScaleBand = 16.dp

/**
 * The band-plan strip. It was 14 dp, and a decoder line was drawn on top of it,
 * so every layout lost a thick band at the bottom of the waterfall. 8 dp keeps
 * the colour and the short label (SSB, CW, DIG) and gives that height back.
 */
private val PlanBand = 8.dp

/**
 * How much of the right edge the Zoom chip owns when it reaches the scale.
 * "Zoom x8" plus its padding is inside this. The labels stop short of it.
 */
private val ZoomLabelReserve = 72.dp

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

/**
 * The header clock. It used to be the literal "--:--:--", because nothing on
 * the state line is a time. [RadioFace.utcClock] is the phone's UTC. It ticks
 * once a second. A tap does not open the pending-firmware note: this is a
 * clock, not a control the radio is missing.
 */
@Composable
fun UtcClock(modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 14.sp) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val text = RadioFace.utcClock(now)
    Text(
        text,
        color = RfGuru.mute,
        fontFamily = Mono,
        fontSize = fontSize,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.semantics { contentDescription = "UTC $text" },
    )
}

@Composable
fun ClockBlock(
    batteryPercent: Int?,
    onPlaceholder: (String) -> Unit,
    modifier: Modifier = Modifier,
    stationGrid: String = "",
) {
    // -1 is the radio's "no pack reading". Hiding it is the face. A missing
    // state (nothing connected) still says so, with an em dash, so a blank
    // header is not mistaken for a radio that reported external power.
    val percent = RadioFace.batteryOnFace(batteryPercent)
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        UtcClock()
        if (percent != null) {
            Text(
                "$percent%",
                color = RfGuru.goldText,
                fontFamily = Mono,
                fontSize = 13.sp,
            )
        } else if (batteryPercent == null) {
            Text(
                "—%",
                color = RfGuru.goldText,
                fontFamily = Mono,
                fontSize = 13.sp,
            )
        }
        Text(
            "~ —",
            color = RfGuru.mute,
            fontFamily = Mono,
            fontSize = 11.sp,
            modifier = Modifier.clickable { onPlaceholder("Battery time remaining") }
                .semantics { contentDescription = "Battery time remaining. $PENDING_FIRMWARE" },
        )
        if (stationGrid.isNotBlank()) {
            Text(stationGrid, color = RfGuru.goldText, fontFamily = Mono, fontSize = 11.sp)
        }
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
    val meterStyle by FaceChoices.meter.collectAsState()
    Box(
        modifier
            .background(RfGuru.bar, RoundedCornerShape(8.dp))
            .border(1.dp, RfGuru.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (!transmitting && meterStyle != MeterStyle.ARC) {
            FieldMeter(reading.needle, meterStyle, reading.caption, RadioFace.formatDb(db))
        } else if (transmitting) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                MeterBar("W", txView.wattsText, txView.watts, RfGuru.gold)
                MeterBar("SWR", txView.swrText, txView.swr, if (tx.swr >= 2.0) RfGuru.red else RfGuru.goldText)
                MeterBar("ALC", txView.alcText, txView.alc, RfGuru.blue)
            }
        } else {
            // The radio's dial is an amber arc with S1, the odd S units, 9, +20 and +40.
            // The dBm line used to sit on the bottom of this same box, and a reading
            // such as −72 covered "+40". The line is its own strip. The arc, and every
            // mark, is drawn underneath it, so the widest value still ends where the
            // scale begins. meterFace is that split.
            val measurer = rememberTextMeasurer()
            val labelStyle = TextStyle(color = RfGuru.goldText, fontSize = 8.sp, fontFamily = Mono)
            Column(Modifier.fillMaxSize()) {
                Text(
                    "${reading.caption}  ${RadioFace.formatDb(db)}",
                    color = RfGuru.goldText,
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Canvas(Modifier.fillMaxWidth().weight(1f)) {
                    val pivot = RadioFace.dialPivot(size.width, size.height)
                    val radius = RadioFace.dialRadius(size.width, size.height)
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
                        val major = label == "S1" || label == "9" || label == "+40"
                        val inner = RadioFace.dialPoint(size.width, size.height, mark, if (major) 0.78f else 0.86f)
                        val outer = RadioFace.dialPoint(size.width, size.height, mark, 0.98f)
                        drawLine(
                            RfGuru.gold,
                            Offset(inner.x, inner.y),
                            Offset(outer.x, outer.y),
                            strokeWidth = if (major) 2.2f else 1.3f,
                        )
                        val measured = measurer.measure(label, labelStyle)
                        val at = RadioFace.dialLabelTopLeft(
                            size.width,
                            size.height,
                            mark,
                            measured.size.width.toFloat(),
                            measured.size.height.toFloat(),
                        )
                        drawText(measured, topLeft = Offset(at.x, at.y))
                    }
                    val tip = RadioFace.dialPoint(size.width, size.height, reading.needle, 0.9f)
                    drawLine(
                        packedArgb(RadioFace.VFO_LINE_ARGB),
                        Offset(pivot.x, pivot.y),
                        Offset(tip.x, tip.y),
                        strokeWidth = 2.4f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/** Classic needle, LED bar, or a lamp with a peak that holds. The arc stays in [NeedleMeter]. */
@Composable
private fun FieldMeter(level: Float, style: MeterStyle, caption: String, dbText: String) {
    var peak by remember { mutableFloatStateOf(0f) }
    val held = MeterPeak.hold(level, peak)
    if (held != peak) peak = held
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val n = 16
            val gap = 2f
            val cell = (size.width - gap * (n - 1)) / n
            val lit = (level * n).toInt().coerceIn(0, n)
            val peakCell = (held * n).toInt().coerceIn(0, n - 1)
            when (style) {
                MeterStyle.NEEDLE -> {
                    drawLine(RfGuru.gold, Offset(0f, size.height * 0.7f), Offset(size.width, size.height * 0.7f), 2f)
                    val x = level.coerceIn(0f, 1f) * size.width
                    drawLine(RfGuru.text, Offset(x, 0f), Offset(x, size.height), 2.4f)
                }
                MeterStyle.LED -> {
                    for (i in 0 until n) {
                        val on = i < lit
                        drawRect(
                            if (on) RfGuru.gold else RfGuru.border,
                            topLeft = Offset(i * (cell + gap), 4f),
                            size = Size(cell, size.height - 8f),
                        )
                    }
                }
                else -> {
                    drawRect(RfGuru.border, size = Size(size.width, size.height * 0.45f))
                    drawRect(RfGuru.gold, size = Size(size.width * level.coerceIn(0f, 1f), size.height * 0.45f))
                    val x = peakCell * (cell + gap)
                    drawRect(RfGuru.text, topLeft = Offset(x, 0f), size = Size(cell.coerceAtLeast(2f), size.height))
                }
            }
        }
        Text("$caption  $dbText", color = RfGuru.text, fontFamily = Mono, fontSize = 11.sp)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FrequencyReadout(
    freqHz: Int,
    group: FreqGroup,
    onGroup: (FreqGroup) -> Unit,
    onBump: (Int) -> Unit,
    large: Boolean,
    modifier: Modifier = Modifier,
    textSize: androidx.compose.ui.unit.TextUnit? = null,
    onTune: ((Int) -> Unit)? = null,
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
        // The underline is reserved under every digit, selected or not, so picking a place
        // does not shove the row down. The text is fitted to what remains of the line.
        val markDp = 4f
        val fitted = RadioFace.frequencyTextSp(maxWidth.value, (heightDp - markDp).coerceAtLeast(12f), readout)
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
        var selected by rememberSaveable { mutableIntStateOf(-1) }
        var keypad by rememberSaveable { mutableStateOf(false) }
        var typed by rememberSaveable { mutableStateOf("") }
        var carry by remember { mutableFloatStateOf(0f) }
        val step: (Int) -> Unit = { dir ->
            if (selected >= 0 && onTune != null) onTune(DigitTune.step(freqHz, selected, dir))
            else onBump(dir)
        }
        DisposableEffect(selected, freqHz, onTune) {
            DigitKeys.onStep = if (selected >= 0 && onTune != null) {
                { dir -> step(dir); true }
            } else {
                null
            }
            onDispose { DigitKeys.onStep = null }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            var index = 0
            listOf(parts.first, parts.second, parts.third).forEachIndexed { groupIndex, part ->
                if (groupIndex > 0) {
                    // The spacer matches the underline under a digit, so the dot stays on the
                    // same line as the glyphs instead of dropping to the middle of the mark.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(".", color = RfGuru.gold, style = digits, maxLines = 1, softWrap = false)
                        Box(Modifier.padding(top = 1.dp).height(3.dp))
                    }
                }
                part.forEach { ch ->
                    val digitIndex = index
                    index += 1
                    val on = digitIndex == selected
                    // The mark is this digit only. Swapping gold for the body colour was
                    // invisible on the sunlight face, where both are black. The chip uses the
                    // gold role as its fill and the bar colour as its glyph, which is dark on
                    // gold at night and light on black in sunlight. The bar is this glyph's
                    // width, not fillMaxWidth: that call once ate the rest of the frequency.
                    // selected is left set, so a drag or a volume key keeps stepping this place.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .then(
                                if (on) {
                                    Modifier.pointerInput(digitIndex) {
                                        detectVerticalDragGestures { _, drag ->
                                            carry += drag
                                            val moved = (carry / 28f).toInt()
                                            if (moved != 0) {
                                                carry -= moved * 28f
                                                step(-moved)
                                            }
                                        }
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .combinedClickable(
                                onClick = {
                                    selected = digitIndex
                                    onGroup(FreqGroup.entries[groupIndex])
                                },
                                onLongClick = { keypad = true },
                            )
                            .semantics { contentDescription = if (on) "$ch selected" else ch.toString() },
                    ) {
                        Text(
                            text = ch.toString(),
                            color = if (on) RfGuru.barBottom else RfGuru.gold,
                            style = digits,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.background(if (on) RfGuru.gold else Color.Transparent),
                        )
                        Box(
                            Modifier
                                .padding(top = 1.dp)
                                .height(3.dp)
                                .width((digits.fontSize.value * 0.62f).dp)
                                .background(if (on) RfGuru.gold else Color.Transparent),
                        )
                    }
                }
            }
            Column(Modifier.padding(start = 4.dp)) {
                Text("+", color = RfGuru.gold, fontFamily = Mono, fontSize = bump, modifier = Modifier.clickable { step(1) })
                Text("−", color = RfGuru.gold, fontFamily = Mono, fontSize = bump, modifier = Modifier.clickable { step(-1) })
            }
        }
        if (keypad) {
            AlertDialog(
                onDismissRequest = { keypad = false },
                title = { Text("Frequency, Hz") },
                text = {
                    OutlinedTextField(value = typed, onValueChange = { typed = it.filter { ch -> ch.isDigit() || ch == '.' } })
                },
                confirmButton = {
                    TextButton(onClick = {
                        DigitTune.fromKeypad(typed)?.let { onTune?.invoke(it) }
                        keypad = false
                    }) { Text("Set") }
                },
                dismissButton = { TextButton(onClick = { keypad = false }) { Text("Cancel") } },
            )
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
    canOperate: Boolean,
    onTuneHz: (Int) -> Unit,
    onKnob: (Int) -> Unit,
    onZoom: (narrower: Boolean) -> Unit,
    waterfallShare: Float = 0f,
    ridgeHold: RidgeHold,
    waterfallHold: WaterfallHold,
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
    // Paths and the sample scratch are draw buffers. The history they read is [ridgeHold],
    // which the screen keeps across rotation. A ring allocated here would be empty after
    // the pane is disposed, and the field would be a thin lawn until it filled again.
    val ridgeFills = remember { Array(SpectrumRidge.COLOR_STEPS) { Path() } }
    val ridgeVeil = remember { Path() }
    val ridgeScratch = remember { RidgeScratch() }
    // The smoothed trace and the peaks. Remembered with the pane so a recomposition
    // does not draw the raw frame, and a rotation starts from the next frame rather
    // than from a trace that belonged to a different pixel width.
    val spectrumTrace = remember { SpectrumTrace() }
    val sheet = remember { WaterfallSheet() }
    var tick by remember { mutableIntStateOf(0) }
    var mapName by rememberSaveable { mutableStateOf(WfColormap.GAUSS.name) }
    // One choice for both faces. A rememberSaveable here was a second copy, keyed
    // by this pane's place in the tree, so the landscape face restored 2D after
    // portrait had stored FT 3D in radio_face. The activity loads that file into
    // [SpectrumChoices] before the first frame, and again on configuration change.
    val context = LocalContext.current
    val spectrumView by SpectrumChoices.view.collectAsState()
    val ridgeStyle by SpectrumChoices.style.collectAsState()
    val scopeLayout by SpectrumChoices.layout.collectAsState()
    val ridge = spectrumView == SpectrumView.RIDGE
    val colormap = WfColormap.entries.firstOrNull { it.name == mapName } ?: WfColormap.GAUSS
    val colors = remember(colormap) { WaterfallDisplay.palette(colormap) }
    var paneWidth by remember { mutableIntStateOf(0) }
    var paneHeight by remember { mutableIntStateOf(0) }
    var waterRows by remember { mutableIntStateOf(0) }
    LaunchedEffect(drawScope, frame?.sequence, frame?.centreHz, frame?.widthHz) {
        if (!drawScope) return@LaunchedEffect
        val current = frame ?: return@LaunchedEffect
        // The ring is the screen's, not this pane's. A repeated sequence (the frame still
        // current after a rotation) does not scroll it. The bitmap below is rebuilt from
        // whatever lines are already stored as soon as the pane's pixel size changes.
        waterfallHold.accept(current.db, current.centreHz, current.widthHz, current.sequence)
        // The 2D trace is smoothed here, once per frame. Draw only reads it.
        spectrumTrace.accept(
            current.db,
            current.bins,
            current.centreHz,
            current.widthHz,
            System.currentTimeMillis(),
            state?.transmitting == true,
        )
        // The range includes this frame, then the ring bakes the levels with it.
        // Later draws read that bake. They do not reshape the row when the floor moves.
        // The same sequence is ignored, so coming back to this pane does not scroll.
        ridgeHold.accept(current.db, current.bins, current.centreHz, current.widthHz, current.sequence)
        tick++
    }
    // waterRows is in the key so a taller or shorter pane reads the ring again before the
    // next frame arrives. linesFor does not drop the lines that fall outside this height.
    val rows = remember(tick, waterRows) { waterfallHold.linesFor(waterRows) }
    // The window eases toward the newest row. Taking the percentile of the whole history, or
    // replacing it every frame, is what made a quiet band shimmer.
    // The leveler moved with the line, inside [waterfallHold.accept]. Reading it here
    // paints a rotated pane with the window it already had, instead of the default
    // floor, for the frames until the tracker would have caught up.
    val window = if (state?.transmitting == true) {
        FirmwareLook.TX_MIN.roundToInt() to FirmwareLook.TX_MAX.roundToInt()
    } else {
        waterfallHold.leveler.floor to waterfallHold.leveler.ceiling
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
    val planText = TextStyle(color = RfGuru.bg, fontSize = 8.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
    val density = LocalDensity.current
    // freq_scale.c uses a 4 px dash. In dp the pattern stays a dash when the stroke is 1 dp.
    val dashPx = with(density) { FirmwareLook.GRID_DASH.dp.toPx() }
    val gridDash = remember(dashPx) { PathEffect.dashPathEffect(floatArrayOf(dashPx, dashPx), 0f) }

    // The caller's modifier carries the weight. The column takes that height, so a
    // portrait spectrum-only plot grows to the meter instead of wrapping under the chips.
    // Waterfall-only puts the chips in their own row. On the trace they stay overlaid,
    // which is what leaves a landscape waterfall its height.
    Column(modifier.fillMaxWidth().fillMaxHeight()) {
    if (scopeLayout == ScopeLayout.WATERFALL) {
        ScopeControls(
            colormap = colormap,
            onPalette = { mapName = colormap.next().name },
            spectrumView = spectrumView,
            ridgeStyle = ridgeStyle,
            scopeLayout = scopeLayout,
            zoom = state?.zoom ?: 1,
            onZoom = onZoom,
            onView = { SpectrumChoices.selectView(context, spectrumView.next()) },
            onStyle = {
                SpectrumChoices.selectStyle(context, ridgeStyle.next())
                if (spectrumView == SpectrumView.TRACE) {
                    SpectrumChoices.selectView(context, SpectrumView.RIDGE)
                }
            },
            onLayout = { SpectrumChoices.selectLayout(context, scopeLayout.next()) },
        )
    }
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(Color.Black)
            .onSizeChanged { size ->
                paneWidth = size.width
                paneHeight = size.height
            },
    ) {
        // The split depends on the 2D/3D choice, not only on the pane's size. onSizeChanged
        // does not run again when the chip is tapped, so the waterfall depth is recomputed here.
        val scalePx = with(density) { ScaleBand.toPx() }
        val planPx = with(density) { PlanBand.toPx() }
        val guardPx = with(density) { 24.dp.toPx() }
        LaunchedEffect(paneWidth, paneHeight, ridge, waterfallShare, scalePx, planPx, guardPx, scopeLayout) {
            if (paneWidth <= 0 || paneHeight <= 0) return@LaunchedEffect
            val split = RadioFace.scopeSplit(
                paneHeight.toFloat(), scalePx, planPx, waterfallShare, guardPx, ridge, scopeLayout,
            )
            // The bitmap is this many pixels tall, one line each. The ring stays at its
            // own cap, so growing this number repaints lines that were already collected.
            val rowsHigh = split.waterfallPx.roundToInt().coerceIn(1, WaterfallDisplay.MAX_ROWS)
            waterRows = rowsHigh
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
            val scaleH = ScaleBand.toPx()
            val planH = PlanBand.toPx()
            // Zoom sits in the top-right of the trace. The split keeps the trace at least
            // as tall as that chip. Landscape spectrum-plus-waterfall gives the trace
            // about a third of the pane, so the chip no longer reaches the frequency
            // labels. A short trace still reserves that corner, in scaleLabelWidth.
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
                    ring = ridgeHold.ring,
                    fills = ridgeFills,
                    veil = ridgeVeil,
                    scratch = ridgeScratch,
                    style = ridgeStyle,
                    specH = specH,
                    viewWidth = size.width,
                    viewCentreHz = spectrumFrame.centreHz,
                    viewWidthHz = spectrumFrame.widthHz,
                )
            } else {
                // RF.Guru's default spectrum (spectrum_view.c, fill on, gradient off, fill-by-level
                // off). One sample per screen pixel, which is the radio's POINTS. The peak line is
                // drawn first. The solid line-coloured fill then covers it up to the trace, so a
                // held peak shows only where it still stands above the signal. Strokes are raw
                // pixels, so the edge stays a hairline at the phone's density.
                //
                // The combined view keeps the radio's 48 dB colour window: that band is about as
                // tall as the radio's spectrum widget, so the trace fills it. Spectrum-only gives
                // this same trace the whole pane. On that pane the 48 dB window left the trace as
                // a ribbon and the rest of the pane empty. The pane scale puts the loudest bin at
                // the top and the noise at the bottom, which is what the 3D field already does
                // with this height. Transmit still uses S4..S9+20.
                drawRect(packedArgb(FirmwareLook.PANEL), size = Size(size.width, specH))
                val samples = size.width.roundToInt().coerceIn(2, 4096)
                val (minDb, maxDb) = if (scopeLayout == ScopeLayout.SPECTRUM && state?.transmitting != true) {
                    FirmwareLook.paneWindow(waterfallHold.leveler.noiseDb, spectrumTrace.loudest())
                } else {
                    window.first.toFloat() to window.second.toFloat()
                }
                val peakLine = Path()
                val fill = Path()
                var drew = false
                for (i in 0 until samples) {
                    val x = i * size.width / (samples - 1).toFloat()
                    val hz = WaterfallDisplay.hzAtPixel(i, samples, spectrumFrame.centreHz, spectrumFrame.widthHz)
                    val index = WaterfallDisplay.binAtHz(
                        hz, spectrumFrame.centreHz, spectrumFrame.widthHz, spectrumTrace.length,
                    )
                    val db = spectrumTrace.levelAt(index) ?: minDb
                    val peak = spectrumTrace.peakAt(index) ?: db
                    val y = FirmwareLook.traceY(db, minDb, maxDb, specH)
                    val py = FirmwareLook.traceY(peak, minDb, maxDb, specH)
                    if (!drew) {
                        peakLine.moveTo(x, py)
                        fill.moveTo(x, y)
                        drew = true
                    } else {
                        peakLine.lineTo(x, py)
                        fill.lineTo(x, y)
                    }
                }
                if (drew) {
                    drawPath(
                        peakLine,
                        packedArgb(FirmwareLook.PEAK),
                        style = Stroke(width = 1f, cap = StrokeCap.Butt, join = StrokeJoin.Round),
                    )
                    fill.lineTo(size.width, specH)
                    fill.lineTo(0f, specH)
                    fill.close()
                    drawPath(fill, packedArgb(FirmwareLook.LINE))
                }
            }

            if (waterfall != null && waterH > 1f) {
                // The bitmap is already one pixel per screen pixel. dstSize is that size, and
                // the filter is nearest, so a mismatch cannot bilinear-scale the lines.
                drawImage(
                    image = waterfall,
                    dstOffset = IntOffset(0, waterTop.roundToInt()),
                    dstSize = IntSize(waterfall.width, waterfall.height),
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
                // Landscape S+W holds the trace at the Zoom chip's height, so the right-hand
                // label sits under the chip. scaleLabelWidth reserves that corner. A taller
                // trace keeps the full width: the scale is then below the chip.
                val labelWidth = RadioFace.scaleLabelWidth(
                    size.width,
                    specH,
                    zoomGuard,
                    scaleH,
                    ZoomLabelReserve.toPx(),
                    scopeLayout,
                )
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
                        val labelY = specH + scaleH + ((planH - measured.size.height) / 2f).coerceAtLeast(0f)
                        drawText(
                            measured,
                            topLeft = Offset(left + (right - left - measured.size.width) / 2f, labelY),
                        )
                    }
                }
                state?.let { radio ->
                    // flo and fhi are audio cuts. The mode, the key tone and cwrise decide
                    // which side of the VFO they occupy. LSB is below the marker.
                    val (lo, hi) = RadioFace.passbandHz(
                        radio.freqHz,
                        radio.filterLowHz,
                        radio.filterHighHz,
                        radio.mode,
                        radio.keyToneHz,
                        radio.cwToneRises,
                    )
                    val x0 = RadioFace.fractionForHz(lo, hzSpan) * size.width
                    val x1 = RadioFace.fractionForHz(hi, hzSpan) * size.width
                    val bandLeft = minOf(x0, x1).coerceIn(0f, size.width)
                    val bandRight = maxOf(x0, x1).coerceIn(0f, size.width)
                    val bandWidth = (bandRight - bandLeft).coerceAtLeast(1f)
                    // Spectrum shade is the screen background at LV_OPA_50. The waterfall shade
                    // is the VFO red at LV_OPA_20. Both are the RF.Guru defaults (filter shade 4,
                    // over the passband). The scale between them stays clear so the labels do.
                    if (specH > 1f) {
                        drawRect(
                            packedArgb(FirmwareLook.PANEL).copy(alpha = FirmwareLook.SPEC_SHADE / 255f),
                            topLeft = Offset(bandLeft, 0f),
                            size = Size(bandWidth, specH),
                        )
                    }
                    if (waterH > 1f) {
                        drawRect(
                            packedArgb(RadioFace.VFO_LINE_ARGB).copy(alpha = FirmwareLook.WATER_SHADE / 255f),
                            topLeft = Offset(bandLeft, waterTop),
                            size = Size(bandWidth, waterH),
                        )
                    }
                    // freq_scale.c: text colour #F1F5F9 at LV_OPA_40, a 4 px dash and a 4 px gap,
                    // down the waterfall only. The radio's stroke is 1 px on an 800-wide panel.
                    // One phone pixel is thinner than that and disappears on Gauss, including
                    // where this passband shade is drawn over the line, so the stroke is 1 dp
                    // and it is drawn after the shade. The colour and the opacity stay the radio's.
                    if (waterH > 1f) {
                        val dashColor = packedArgb(FirmwareLook.GRID).copy(alpha = FirmwareLook.GRID_OPA / 255f)
                        val dashWidth = 1.dp.toPx().coerceAtLeast(1f)
                        for (x in centers) {
                            drawLine(
                                dashColor,
                                Offset(x, waterTop),
                                Offset(x, waterTop + waterH),
                                strokeWidth = dashWidth,
                                pathEffect = gridDash,
                            )
                        }
                    }
                    val vfoX = RadioFace.fractionForHz(radio.freqHz, hzSpan).coerceIn(0f, 1f) * size.width
                    val cw = radio.mode == PanelMode.Cw.index || radio.mode == PanelMode.Cwr.index
                    val vfoColor = packedArgb(RadioFace.VFO_LINE_ARGB)
                    // The spectrum drops its marker in CW and CW-R. The waterfall keeps its centre line.
                    if (!cw && specH > 1f) {
                        drawLine(vfoColor, Offset(vfoX, 0f), Offset(vfoX, specH), strokeWidth = FirmwareLook.VFO_PX)
                    }
                    if (waterH > 1f) {
                        drawLine(
                            vfoColor,
                            Offset(vfoX, waterTop),
                            Offset(vfoX, waterTop + waterH),
                            strokeWidth = FirmwareLook.VFO_PX,
                        )
                    }
                }
                // With no waterfall, the labels sit in the spectrum just above the scale.
                val spotTop = if (waterH > 8f) waterTop else (specH - 28f).coerceAtLeast(0f)
                val visible = spots.mapIndexedNotNull { index, spot ->
                    val x = RadioFace.fractionForHz(spot.freqHz, hzSpan) * size.width
                    if (x < -8f || x > size.width + 8f) null else index to x
                }
                val spotMeasured = visible.map { (index, _) ->
                    measurer.measure(spots[index].call, if (spots[index].alert) alertStyle else spotStyle)
                }
                val spotLeft = FloatArray(visible.size) { i ->
                    val x = visible[i].second
                    (x - spotMeasured[i].size.width / 2f).coerceIn(0f, (size.width - spotMeasured[i].size.width).coerceAtLeast(0f))
                }
                val spotRight = FloatArray(visible.size) { i -> spotLeft[i] + spotMeasured[i].size.width }
                val kept = radio.kd0cq.x6100.spots.SpotLabels.keep(spotLeft, spotRight).toSet()
                visible.forEachIndexed { i, (index, x) ->
                    if (i !in kept) return@forEachIndexed
                    drawLine(RfGuru.blue.copy(alpha = 0.7f), Offset(x, spotTop), Offset(x, spotTop + 10f), strokeWidth = 1f)
                    drawText(spotMeasured[i], topLeft = Offset(spotLeft[i], spotTop + 10f))
                }
            }

            // The CW decoder used to be drawn here, a gold line on a black bar across the
            // bottom of every layout. That covered the band plan and the last waterfall
            // rows. The text still arrives on the link; it is not painted on the scope.
            // The chips sit on the trace. A short dark fade keeps those labels readable
            // without a second hit target over the drag and pinch gestures. Waterfall-only
            // has the chips in their own row, so a fade here would darken the scale strip.
            if (scopeLayout != ScopeLayout.WATERFALL) {
                val shade = 32.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent),
                        startY = 0f,
                        endY = shade,
                    ),
                    size = Size(size.width, shade),
                )
            }
        }

        // Over the trace, not over the scale. Waterfall-only draws this same row above
        // the plot, so it is not placed here.
        if (scopeLayout != ScopeLayout.WATERFALL) {
            ScopeControls(
                colormap = colormap,
                onPalette = { mapName = colormap.next().name },
                spectrumView = spectrumView,
                ridgeStyle = ridgeStyle,
                scopeLayout = scopeLayout,
                zoom = state?.zoom ?: 1,
                onZoom = onZoom,
                onView = { SpectrumChoices.selectView(context, spectrumView.next()) },
                onStyle = {
                    // The second 3D colour. Holding the chip swaps the lawn for the
                    // FT-710 cyan stream, and opens 3D if the trace was showing.
                    // The view write is the same store the other orientation reads.
                    SpectrumChoices.selectStyle(context, ridgeStyle.next())
                    if (spectrumView == SpectrumView.TRACE) {
                        SpectrumChoices.selectView(context, SpectrumView.RIDGE)
                    }
                },
                onLayout = { SpectrumChoices.selectLayout(context, scopeLayout.next()) },
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            )
        }
    }
    }
}

/**
 * Palette, 2D/3D, layout, and Zoom.
 *
 * On the combined view and on spectrum-only this row is overlaid on the trace. The trace is
 * tall enough that the row sits above the frequency scale. Waterfall-only has no trace, so
 * the same row is a column child above the plot. The scale is then its own strip and the
 * labels are not under the chips. The row itself is not clickable. A drag that starts in
 * the gap between chips still reaches the canvas.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScopeControls(
    colormap: WfColormap,
    onPalette: () -> Unit,
    spectrumView: SpectrumView,
    ridgeStyle: RidgeStyle,
    scopeLayout: ScopeLayout,
    zoom: Int,
    onZoom: (narrower: Boolean) -> Unit,
    onView: () -> Unit,
    onStyle: () -> Unit,
    onLayout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
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
                    .clickable(onClick = onPalette)
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
                    .combinedClickable(onClick = onView, onLongClick = onStyle)
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
                    .clickable(onClick = onLayout)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .semantics {
                        contentDescription = "Scope layout ${scopeLayout.chip}. Tap to cycle spectrum and waterfall, spectrum only, or waterfall only"
                    },
            )
        }
        Text(
            text = "Zoom x$zoom",
            color = RfGuru.goldText,
            fontSize = 11.sp,
            fontFamily = Mono,
            modifier = Modifier
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
    }
}

/**
 * Oldest row first. The levels were baked when the frame arrived. This pass only places them.
 *
 * Aether is a field of short vertical blades over a translucent green sheet, packed
 * tight enough that the sheet and the blades cover the floor. FT-710 stays the comb:
 * a dark veil and one thin crest line per row.
 */
private fun DrawScope.drawRidgePlot(
    ring: SpectrumRidgeRing,
    fills: Array<Path>,
    veil: Path,
    scratch: RidgeScratch,
    style: RidgeStyle,
    specH: Float,
    viewWidth: Float,
    viewCentreHz: Int,
    viewWidthHz: Int,
) {
    if (specH < 2f || viewWidth < 2f || ring.count <= 0 || viewWidthHz <= 0) return
    val samples = scratch.x.size
    val aether = style == RidgeStyle.AETHER
    // The comb stays at sixty-four, with open front gaps. The grass field uses every
    // slot the ring kept, at an even pitch, so a blade covers the next row's floor.
    val depth = (if (aether) SpectrumRidge.GRASS_ROWS else SpectrumRidge.rowsForHeight(specH))
        .coerceAtMost(ring.depth)
    val curve = if (aether) SpectrumRidge.GRASS_CURVE else SpectrumRidge.DEPTH_CURVE
    val rise = if (aether) SpectrumRidge.riseFor(viewWidth, specH) else SpectrumRidge.RISE
    val amplitude = SpectrumRidge.amplitudeFor(specH)
    val shown = minOf(ring.count, depth)
    // Hoisted. A Stroke inside the age loop would allocate once per row per frame.
    val crest = Stroke(width = 1.25.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val blade = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
    val veilColor = packedArgb(SpectrumRidge.veilArgb(style))
    for (age in shown - 1 downTo 0) {
        val rowBins = ring.lengthAt(age)
        val rowCentre = ring.centreAt(age)
        val rowWidth = ring.widthAt(age)
        if (rowBins <= 0 || rowWidth <= 0) continue
        // Frozen levels. Re-shaping here, or blending with the row behind, is what
        // made every ridge crawl when the next frame arrived.
        val baked = ring.bakedAt(age)
        for (i in 0 until samples) {
            scratch.level[i] = SpectrumRidge.columnLevel(
                baked, rowBins, i, samples, viewCentreHz, viewWidthHz, rowCentre, rowWidth,
            )
            scratch.x[i] = SpectrumRidge.projectX(i, samples, age, depth, viewWidth, curve = curve)
            // Noise blades differ by column. The offset is fixed for that column, and a
            // signal is not moved, so the carrier and the frozen rows stay put.
            val drawn = if (aether) {
                (scratch.level[i] + SpectrumRidge.bladeTexture(i, scratch.level[i])).coerceAtLeast(0f)
            } else {
                scratch.level[i]
            }
            scratch.y[i] = SpectrumRidge.projectY(
                age, depth, drawn, specH, rise = rise, amplitude = amplitude, curve = curve,
            )
        }
        val baseline = SpectrumRidge.projectY(age, depth, 0f, specH, rise = rise, curve = curve)
        if (aether) {
            drawGrassRow(fills, veil, scratch, baseline, age, depth, samples, blade)
        } else {
            drawCombRow(fills, veil, scratch, baseline, age, depth, samples, veilColor, crest, style)
        }
    }
    if (!aether) drawRidgeGrid(samples, depth, viewWidth, specH)
}

/** Translucent green under the tips, then one vertical blade per column. No crest polyline. */
private fun DrawScope.drawGrassRow(
    fills: Array<Path>,
    sheet: Path,
    scratch: RidgeScratch,
    baseline: Float,
    age: Int,
    depth: Int,
    samples: Int,
    blade: Stroke,
) {
    sheet.rewind()
    sheet.moveTo(scratch.x[0], baseline)
    for (k in 0 until samples) sheet.lineTo(scratch.x[k], scratch.y[k])
    sheet.lineTo(scratch.x[samples - 1], baseline)
    sheet.close()
    drawPath(sheet, packedArgb(SpectrumRidge.grassFillArgb(age, depth)))
    for (path in fills) path.rewind()
    for (k in 0 until samples) {
        val tip = scratch.y[k]
        if (baseline - tip < 0.75f) continue
        val x = scratch.x[k]
        val split = SpectrumRidge.bladeSplit(baseline, tip)
        fills[0].moveTo(x, baseline)
        fills[0].lineTo(x, split)
        val step = SpectrumRidge.colorStep(scratch.level[k])
        fills[step].moveTo(x, split)
        fills[step].lineTo(x, tip)
    }
    for (step in fills.indices) {
        val color = SpectrumRidge.fogArgb(
            SpectrumRidge.stepArgb(step, RidgeStyle.AETHER),
            age,
            depth,
            SpectrumRidge.GRASS_CURVE,
            SpectrumRidge.GRASS_FOG,
        )
        drawPath(fills[step], packedArgb(color), style = blade)
    }
}

/** The FT-710 row: a dark veil, then one open stroke along the crest. */
private fun DrawScope.drawCombRow(
    fills: Array<Path>,
    veil: Path,
    scratch: RidgeScratch,
    baseline: Float,
    age: Int,
    depth: Int,
    samples: Int,
    veilColor: Color,
    crest: Stroke,
    style: RidgeStyle,
) {
    veil.rewind()
    veil.moveTo(scratch.x[0], baseline)
    for (k in 0 until samples) veil.lineTo(scratch.x[k], scratch.y[k])
    veil.lineTo(scratch.x[samples - 1], baseline)
    veil.close()
    drawPath(veil, veilColor)
    for (path in fills) path.rewind()
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
        path.moveTo(scratch.x[start], scratch.y[start])
        for (k in start + 1..end) path.lineTo(scratch.x[k], scratch.y[k])
        start = end
    }
    for (step in fills.indices) {
        val color = SpectrumRidge.fogArgb(SpectrumRidge.stepArgb(step, style), age, depth)
        drawPath(fills[step], packedArgb(color), style = crest)
    }
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
    idle: String = "PTT",
    live: String = "ON AIR",
    description: String = "Push to talk",
    labelSize: androidx.compose.ui.unit.TextUnit = 18.sp,
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
            .semantics { contentDescription = if (keyed) "On air, release to unkey" else description },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (keyed) live else idle, fontSize = labelSize, fontFamily = Mono, fontWeight = FontWeight.Bold)
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
