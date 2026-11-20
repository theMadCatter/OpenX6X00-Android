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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import kotlin.math.roundToInt
import kotlin.math.sin
import radio.kd0cq.x6100.net.PlanSegment
import radio.kd0cq.x6100.net.RadioState
import radio.kd0cq.x6100.net.SpectrumFrame
import radio.kd0cq.x6100.net.Spot
import radio.kd0cq.x6100.net.TxMeters
import radio.kd0cq.x6100.ui.theme.RfGuru
import radio.kd0cq.x6100.ui.theme.packedArgb

private fun waterfallImage(rows: List<SpectrumSlice>): androidx.compose.ui.graphics.ImageBitmap? {
    if (rows.isEmpty()) return null
    val bins = rows.first().db.size
    if (bins <= 0) return null
    val bmp = Bitmap.createBitmap(bins, rows.size, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(bins * rows.size)
    for (y in rows.indices) {
        val db = rows[y].db
        for (x in 0 until bins) {
            val sample = if (x < db.size) db[x].toInt() else -128
            pixels[y * bins + x] = RadioFace.waterfallArgb(sample)
        }
    }
    bmp.setPixels(pixels, 0, bins, 0, 0, bins, rows.size)
    return bmp.asImageBitmap()
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
            Canvas(Modifier.fillMaxSize()) {
                val pivot = Offset(size.width / 2f, size.height * 0.86f)
                val radius = minOf(size.width * 0.46f, size.height * 0.78f)
                drawArc(
                    color = RfGuru.gold.copy(alpha = 0.85f),
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(pivot.x - radius, pivot.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 2.5f),
                )
                // Tick at S1, S9 and +40 so the needle has the same landmarks as the radio's dial.
                listOf(0f, (RadioFace.S9_DB - RadioFace.S1_DB).toFloat() / (RadioFace.S9_40_DB - RadioFace.S1_DB).toFloat(), 1f)
                    .forEach { mark ->
                        val rad = Math.toRadians((180.0 + mark * 180.0))
                        val inner = radius * 0.82f
                        val outer = radius * 0.98f
                        drawLine(
                            RfGuru.dim,
                            Offset(pivot.x + (cos(rad) * inner).toFloat(), pivot.y + (sin(rad) * inner).toFloat()),
                            Offset(pivot.x + (cos(rad) * outer).toFloat(), pivot.y + (sin(rad) * outer).toFloat()),
                            strokeWidth = 2f,
                        )
                    }
                val rad = Math.toRadians(180.0 + reading.needle * 180.0)
                drawLine(
                    packedArgb(RadioFace.VFO_LINE_ARGB),
                    pivot,
                    Offset(pivot.x + (cos(rad) * radius * 0.9).toFloat(), pivot.y + (sin(rad) * radius * 0.9).toFloat()),
                    strokeWidth = 2.5f,
                )
            }
            Text(
                "${reading.caption}  ${RadioFace.formatDb(db)}",
                color = RfGuru.text,
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
) {
    val parts = RadioFace.frequencyParts(freqHz)
    val size = if (large) 32.sp else 26.sp
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        FreqPart(parts.first, group == FreqGroup.MHZ, size) { onGroup(FreqGroup.MHZ) }
        Text(".", color = RfGuru.gold, fontSize = size, fontFamily = Mono)
        FreqPart(parts.second, group == FreqGroup.KHZ, size) { onGroup(FreqGroup.KHZ) }
        Text(".", color = RfGuru.gold, fontSize = size, fontFamily = Mono)
        FreqPart(parts.third, group == FreqGroup.HZ, size) { onGroup(FreqGroup.HZ) }
        Column(Modifier.padding(start = 6.dp)) {
            Text("+", color = RfGuru.gold, fontFamily = Mono, fontSize = 14.sp, modifier = Modifier.clickable { onBump(1) })
            Text("−", color = RfGuru.gold, fontFamily = Mono, fontSize = 14.sp, modifier = Modifier.clickable { onBump(-1) })
        }
    }
}

@Composable
private fun FreqPart(text: String, selected: Boolean, size: androidx.compose.ui.unit.TextUnit, onClick: () -> Unit) {
    // The underline sits under the triplet. A single digit is too small a target on a phone,
    // so the tap selects MHz, kHz or Hz and the +/− beside the readout steps that group.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (selected) "$text selected" else text },
    ) {
        Text(
            text = text,
            color = if (selected) RfGuru.goldText else RfGuru.text,
            fontSize = size,
            fontWeight = FontWeight.Medium,
            fontFamily = Mono,
        )
        Box(
            Modifier
                .padding(top = 1.dp)
                .height(2.dp)
                .width((text.length * 12).dp)
                .background(if (selected) RfGuru.gold else Color.Transparent),
        )
    }
}

/**
 * Spectrum, frequency scale, band-plan strip and a scrolling waterfall.
 *
 * A swipe turns the main knob. A tap tunes to the frequency under the finger, or to a spot when
 * the tap lands within [RadioFace.slopHz] of one. Every spot in the list is a target; the radio
 * sends newest first and a tie keeps the newer one.
 */
@Composable
fun SpectrumStack(
    frame: SpectrumFrame?,
    state: RadioState?,
    plan: List<PlanSegment>,
    spots: List<Spot>,
    cwLine: String?,
    canOperate: Boolean,
    onTuneHz: (Int) -> Unit,
    onKnob: (Int) -> Unit,
    onZoom: (narrower: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val history = remember { SpectrumHistory(48) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(frame?.sequence, frame?.centreHz, frame?.widthHz) {
        val current = frame ?: return@LaunchedEffect
        history.push(current.db, current.centreHz, current.widthHz)
        tick++
    }
    val rows = remember(tick) { history.snapshot() }
    // Built beside the canvas, not inside draw. drawImage keeps the bitmap until the frame is
    // presented; recycling it in the draw pass blanks the waterfall.
    val waterfall = remember(tick) { waterfallImage(rows) }
    val measurer = rememberTextMeasurer()
    val span = frame?.let { RadioFace.spanOf(it.centreHz, it.widthHz) }
    val labelStyle = TextStyle(color = RfGuru.dim, fontSize = 10.sp, fontFamily = Mono)
    val spotStyle = TextStyle(color = RfGuru.blue, fontSize = 10.sp, fontFamily = Mono, fontWeight = FontWeight.Medium)
    val alertStyle = spotStyle.copy(color = Color(0xFFF472B6))
    val planText = TextStyle(color = RfGuru.bg, fontSize = 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
    val cwStyle = TextStyle(color = RfGuru.goldText, fontSize = 12.sp, fontFamily = Mono)

    Box(modifier.background(Color.Black)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(canOperate, span, spots) {
                    if (span == null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var total = 0f
                        var bucket = 0f
                        var dragged = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dx = change.positionChange().x
                            total += dx
                            bucket += dx
                            if (abs(bucket) >= 36f && canOperate) {
                                val steps = (bucket / 36f).toInt()
                                if (steps != 0) {
                                    onKnob(if (steps > 0) steps else steps)
                                    bucket -= steps * 36f
                                    dragged = true
                                    change.consume()
                                }
                            }
                            if (abs(total) > 12f) dragged = true
                        }
                        if (!dragged && canOperate) {
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
            val body = (size.height - scaleH - planH).coerceAtLeast(0f)
            val specH = body * 0.40f
            val waterTop = specH + scaleH + planH
            val waterH = (size.height - waterTop).coerceAtLeast(0f)

            if (frame == null || frame.bins <= 0) {
                drawLine(RfGuru.dim, Offset(0f, specH / 2f), Offset(size.width, specH / 2f), strokeWidth = 1f)
            } else {
                val bins = frame.bins
                val stepX = size.width / bins
                val outline = Path()
                for (i in 0 until bins) {
                    val db = frame.db.getOrElse(i) { 0 }.toInt()
                    val v = ((db + 128) / 128f).coerceIn(0f, 1f)
                    val x = i * stepX
                    val y = specH * (1f - v * 0.92f)
                    if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
                }
                val fill = Path().apply {
                    addPath(outline)
                    lineTo(size.width, specH)
                    lineTo(0f, specH)
                    close()
                }
                drawPath(fill, Color.White.copy(alpha = 0.16f))
                drawPath(outline, Color.White.copy(alpha = 0.92f), style = Stroke(width = 1.6f))
            }

            if (waterfall != null && waterH > 1f) {
                drawImage(
                    image = waterfall,
                    dstOffset = androidx.compose.ui.unit.IntOffset(0, waterTop.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt().coerceAtLeast(1), waterH.toInt().coerceAtLeast(1)),
                )
            }

            span?.let { window ->
                val ticks = RadioFace.scaleTicks(window)
                ticks.forEach { hz ->
                    val x = RadioFace.fractionForHz(hz, window).coerceIn(0f, 1f) * size.width
                    drawLine(RfGuru.mute, Offset(x, specH), Offset(x, specH + scaleH), strokeWidth = 1f)
                    val label = RadioFace.formatFrequency(hz).removeSuffix(".000").let { trimmed ->
                        if (trimmed.length > 7) trimmed.take(7) else trimmed
                    }
                    val measured = measurer.measure(label, labelStyle)
                    drawText(measured, topLeft = Offset((x - measured.size.width / 2f).coerceAtLeast(0f), specH + 1f))
                }
                RadioFace.planSlices(window, plan).forEach { slice ->
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
                    val x0 = RadioFace.fractionForHz(lo, window) * size.width
                    val x1 = RadioFace.fractionForHz(hi, window) * size.width
                    val bandLeft = minOf(x0, x1).coerceIn(0f, size.width)
                    val bandRight = maxOf(x0, x1).coerceIn(0f, size.width)
                    drawRect(
                        packedArgb(RadioFace.VFO_LINE_ARGB).copy(alpha = 0.18f),
                        topLeft = Offset(bandLeft, 0f),
                        size = Size((bandRight - bandLeft).coerceAtLeast(1f), size.height),
                    )
                    val vfoX = RadioFace.fractionForHz(radio.freqHz, window).coerceIn(0f, 1f) * size.width
                    drawLine(packedArgb(RadioFace.VFO_LINE_ARGB), Offset(vfoX, 0f), Offset(vfoX, size.height), strokeWidth = 2f)
                }
                spots.forEach { spot ->
                    val x = RadioFace.fractionForHz(spot.freqHz, window) * size.width
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
                .padding(4.dp)
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
) {
    Row(modifier.height(58.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                label.title,
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
