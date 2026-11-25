// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The radio face as plain data: formatting, the S-meter scale, the band-plan colours, the F1–F5
// pages, and the waterfall's hit testing. No Android types, so the unit tests can run it on a JVM.
//
// Before this file the screens each invented a label ("S 12.0 dB", a one-frame bar graph, a tune
// button only on the newest spot). The intent is one mapping of the Panel state onto the labels
// and colours the RF.Guru firmware already uses (theme.h, band_plan.h, button_bar.cpp, smeter.h),
// and a FaceCommand for every control PanelCommands can send. Anything the firmware shows and the
// Panel line cannot carry comes back as Pending, with the same words on every screen.

package radio.kd0cq.x6100.ui.face

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import radio.kd0cq.x6100.net.Band
import radio.kd0cq.x6100.net.PanelMode
import radio.kd0cq.x6100.net.PanelSettings
import radio.kd0cq.x6100.net.PlanSegment
import radio.kd0cq.x6100.net.RadioState
import radio.kd0cq.x6100.net.Spot
import radio.kd0cq.x6100.net.TxMeters

/** Shown on every control the Panel socket cannot ask for yet. */
const val PENDING_FIRMWARE = "Pending firmware Panel support"

/**
 * The five bottom keys, grouped the way [button_bar.cpp] groups them.
 * GEN is the radio's front-panel key that walks VOL and MFK; KEY walks the keyer and the CW decoder.
 */
enum class FaceGroup(val strip: String) {
    GEN("GEN"),
    APP("APP"),
    KEY("KEY"),
    DFN("DFN"),
    DFL("DFL"),
    MEM("MEM"),
}

/** Which frequency digits a tap on the readout is stepping. A finger gets a triplet, not one pixel. */
enum class FreqGroup(val quantumHz: Int) {
    MHZ(1_000_000),
    KHZ(1_000),
    HZ(10),
}

/** What a softkey does. The wire names are [PanelSettings], never a string invented here. */
sealed class FaceAction {
    /** The page button. Tap moves forward in the group, a hold moves back. Firmware does the same. */
    data object NextPage : FaceAction()

    /** A number the panel `set` line already accepts. Tap adds [step], a hold subtracts it. */
    data class Adjust(
        val setting: String,
        val field: AdjustField,
        val step: Int,
        val min: Int,
        val max: Int,
    ) : FaceAction()

    data class Toggle(val setting: String, val field: ToggleField) : FaceAction()

    /** AGC 0..3, the same cycle as the radio's AGC key. */
    data object CycleAgc : FaceAction()

    /** Spectrum zoom. [narrower] true raises the zoom index the way "Span −" does. */
    data class Zoom(val narrower: Boolean) : FaceAction()

    data object PickMode : FaceAction()
    data object PickBand : FaceAction()

    /** Another screen of this app. [route] is a [radio.kd0cq.x6100.ui.Route] path. */
    data class Navigate(val route: String) : FaceAction()

    /** The firmware has the control. The Panel protocol does not, so the button only explains that. */
    data class Pending(val feature: String) : FaceAction()
}

enum class AdjustField { VOLUME, SQL, RFG, POWER_TENTHS, FILTER_LOW, FILTER_HIGH, FILTER_BW }

enum class ToggleField { PRE, ATT, NB, NR, ATU, LOCK }

data class FaceKey(val id: String, val title: String, val action: FaceAction)

data class FacePage(val group: FaceGroup, val badge: String, val keys: List<FaceKey>)

/** The two lines painted on one softkey. [pending] is the dim second line, not a live value. */
data class FaceKeyLabel(val title: String, val value: String, val on: Boolean, val pending: Boolean)

sealed class FaceCommand {
    data object NextPage : FaceCommand()
    data object PreviousPage : FaceCommand()
    data class SetInt(val setting: String, val value: Int) : FaceCommand()
    data class SetFilter(val lowHz: Int, val highHz: Int) : FaceCommand()
    data object PickMode : FaceCommand()
    data object PickBand : FaceCommand()
    data class Navigate(val route: String) : FaceCommand()
    data class Pending(val feature: String) : FaceCommand()
}

data class SMeterReading(val db: Double, val needle: Float, val caption: String)

data class TxMeterView(
    val watts: Float,
    val swr: Float,
    val alc: Float,
    val wattsText: String,
    val swrText: String,
    val alcText: String,
)

data class HzSpan(val leftHz: Int, val rightHz: Int) {
    val widthHz: Int get() = rightHz - leftHz
}

data class PlanSlice(val start: Float, val end: Float, val type: Int, val label: String)

data class PendingFeature(val group: String, val name: String, val note: String = PENDING_FIRMWARE)

/** One remembered spectrum line. The painter scrolls these; the hit testing uses the newest span. */
data class SpectrumSlice(val db: ByteArray, val centreHz: Int, val widthHz: Int) {
    override fun equals(other: Any?): Boolean =
        other is SpectrumSlice && centreHz == other.centreHz && widthHz == other.widthHz && db.contentEquals(other.db)

    override fun hashCode(): Int = 31 * (31 * centreHz + widthHz) + db.contentHashCode()
}

/**
 * Scrolling waterfall rows. Newest is index 0 of [snapshot]: the line under the spectrum is the
 * frame that just arrived, and older frames move down.
 *
 * The depth is fixed when the ring is created. It is [WaterfallDisplay.MAX_ROWS] on the screen,
 * at least 4096 raw lines, and it does not follow the pane. A short waterfall used to trim the
 * deque to its pixel height and throw away every older line, so growing the pane (landscape,
 * or waterfall-only) had nothing to paint below the new lines. The painter asks
 * [WaterfallHold.linesFor] for as many of these lines as the bitmap is tall and leaves the rest
 * stored.
 */
class SpectrumHistory(capacity: Int = WaterfallDisplay.MAX_ROWS) {
    var capacity: Int = capacity.coerceIn(1, WaterfallDisplay.MAX_ROWS)
        private set

    private val rows = ArrayDeque<SpectrumSlice>()

    fun clear() = rows.clear()

    fun count(): Int = rows.size

    fun snapshot(): List<SpectrumSlice> = rows.toList()

    fun push(db: ByteArray, centreHz: Int, widthHz: Int): List<SpectrumSlice> {
        // The stored line keeps its own bytes. The frame that supplied them can be replaced.
        rows.addFirst(SpectrumSlice(db.copyOf(), centreHz, widthHz))
        while (rows.size > capacity) rows.removeLast()
        return rows.toList()
    }
}

/**
 * The waterfall's raw lines for the life of the screen.
 *
 * The spectrum pane used to allocate its own deque and resize it to the bitmap. A rotation
 * disposes that pane, and a layout change to a taller waterfall had already dropped every
 * line past the short height, so the new bitmap was a thin band of fresh lines over an
 * empty floor for about a minute. This holder is the ring. The bitmap is painted from
 * [linesFor] whenever its height changes, in either direction: a shorter pane shows the
 * newest lines, and growing it again still has the older ones.
 *
 * A repeated sequence is the frame still current when the pane comes back. Accepting it
 * again would scroll a duplicate of that line and push the oldest one toward the cap.
 */
class WaterfallHold(capacity: Int = WaterfallDisplay.MAX_ROWS) {
    val history = SpectrumHistory(capacity)

    /**
     * The colour window. It lives with the lines so a new pane paints the stored
     * history in the window those lines were collected under. A leveler created
     * with the pane would start at its default and recolour that history until
     * the tracker caught up.
     */
    val leveler = WaterfallLeveler()
    private var lastSequence = Long.MIN_VALUE

    fun accept(db: ByteArray, centreHz: Int, widthHz: Int, sequence: Long) {
        if (sequence == lastSequence) return
        lastSequence = sequence
        history.push(db, centreHz, widthHz)
        leveler.update(db, centreHz, widthHz)
    }

    /**
     * The newest lines the bitmap can show. [pixelRows] is the waterfall's pixel height.
     * This does not drop anything from [history]: a later, taller pane reads further
     * into the same ring.
     */
    fun linesFor(pixelRows: Int): List<SpectrumSlice> {
        if (pixelRows <= 0) return emptyList()
        val all = history.snapshot()
        if (all.size <= pixelRows) return all
        return all.subList(0, pixelRows)
    }
}

object RadioFace {

    /** S1 = −121 dBm, 6 dB per S unit, S9 = −73, +40 = −33. From the firmware's smeter.h. */
    const val S1_DB = -121.0
    const val S9_DB = -73.0
    const val S9_40_DB = -33.0

    /** Display full-scale for the TX watts bar. The X6100's HF rating; the number under the bar is the real report. */
    const val TX_WATTS_FULL_SCALE = 10.0

    const val FILTER_STEP_HZ = 50

    /** Indices the controls screen already sent. The radio maps the index through its own step table. */
    val stepHzTable: IntArray = intArrayOf(10, 100, 500, 1_000, 5_000)

    val zoomSteps: IntArray = intArrayOf(1, 2, 4, 8)

    /**
     * Band-plan colours from theme.h RF.Guru: bp_cw, bp_dig, bp_ssb, bp_bcn.
     * Firmware type 2 is "all modes" and is painted with the SSB colour; the short label is SSB.
     */
    val planArgb: IntArray = intArrayOf(0xFF4F8BF5.toInt(), 0xFFE5A823.toInt(), 0xFF34D399.toInt(), 0xFFF87171.toInt())

    const val VFO_LINE_ARGB = 0xFFFF2020.toInt()

    val agcLabels: List<String> = listOf("OFF", "SLOW", "FAST", "AUTO")

    /**
     * Names used only when the radio's `bands` list has not claimed this frequency.
     * A name the radio sent always wins, including a band the operator renamed.
     */
    private val fallbackBands: List<Band> = listOf(
        Band("160m", 1_800_000, 2_000_000),
        Band("80m", 3_500_000, 4_000_000),
        Band("60m", 5_250_000, 5_450_000),
        Band("40m", 7_000_000, 7_300_000),
        Band("30m", 10_100_000, 10_150_000),
        Band("20m", 14_000_000, 14_350_000),
        Band("17m", 18_068_000, 18_168_000),
        Band("15m", 21_000_000, 21_450_000),
        Band("12m", 24_890_000, 24_990_000),
        Band("10m", 28_000_000, 29_700_000),
        Band("6m", 50_000_000, 54_000_000),
        Band("2m", 144_000_000, 148_000_000),
        Band("70cm", 420_000_000, 450_000_000),
    )

    /**
     * Digital modes the firmware opens on the radio and the Panel line does not stream.
     *
     * JS8, FT8 (which is also FT4 and FT2), CW decode and WeFax left this list
     * because the panel sends their text or their picture. PSK, RTTY, Olivia,
     * SSTV, WSPR and Navtex still have no panel or TCI stream. They stay here
     * so the mode list does not grow a screen that would have nothing to show.
     */
    val pendingDigitalModes: List<String> = listOf(
        "PSK", "RTTY", "Olivia", "SSTV", "WSPR", "Navtex",
    )

    val pages: List<FacePage> = buildPages()

    /**
     * Panel settings the status row, the frequency strip and the walkie rail send themselves.
     * The softkey pages cover the rest. Together they are every name in [PanelSettings].
     */
    val controlsOutsideTheSoftkeys: List<String> = listOf(
        PanelSettings.FREQ,
        PanelSettings.MODE,
        PanelSettings.STEP,
        PanelSettings.BAND,
        PanelSettings.VFO,
        PanelSettings.LOCK,
        PanelSettings.ATU,
    )

    fun page(index: Int): FacePage = pages[index.coerceIn(0, pages.lastIndex)]

    fun nextPageIndex(index: Int): Int = stepPage(index, +1)

    fun previousPageIndex(index: Int): Int = stepPage(index, -1)

    fun groupStartIndex(group: FaceGroup): Int =
        pages.indexOfFirst { it.group == group }.let { if (it < 0) 0 else it }

    /** "14.074.000" — MHz, then three kHz digits, then three Hz digits. Dots, never a locale comma. */
    fun formatFrequency(hz: Int): String {
        val absHz = abs(hz.toLong())
        val mhz = absHz / 1_000_000
        val khz = (absHz / 1_000) % 1_000
        val rest = absHz % 1_000
        val body = String.format(Locale.US, "%d.%03d.%03d", mhz, khz, rest)
        return if (hz < 0) "-$body" else body
    }

    /** The three pieces of [formatFrequency], so the readout can underline the one a tap is stepping. */
    fun frequencyParts(hz: Int): Triple<String, String, String> {
        val text = formatFrequency(hz)
        val bits = text.removePrefix("-").split('.')
        val sign = if (hz < 0) "-" else ""
        return Triple(sign + bits[0], bits[1], bits[2])
    }

    /**
     * Sp size for [text] so the whole readout, including the +/− column, fits in the slot.
     *
     * A fixed size overflowed the landscape header and the row clipped everything after "14".
     * Monospace advance is taken as 0.62 em, a little wider than the font, so the last digit
     * stays inside the slot. The height of the slot caps the size; a wide slot still grows
     * past the old 20 sp gold text.
     */
    /**
     * True when the window itself is wider than it is tall. The navigation chrome and the
     * operating face both use this, so a rotation cannot show one of them in the other
     * orientation. A zero or unfinished size is not wide: the portrait face is the one that
     * keeps the keys, the bottom bar and PTT on a tall phone.
     */
    fun wideWindow(widthDp: Float, heightDp: Float): Boolean =
        widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f && widthDp > heightDp

    /**
     * The percent to paint, or null when the face should leave the battery off.
     *
     * Before this, the header printed `bat` as a percent even when the radio
     * sent -1, so external power (and the moment before the base has measured
     * the pack) read "-1%". `radio_get_battery` in radio.h is the percent on
     * the radio's own screen, and -1 in exactly those two cases. A value
     * outside 0..100 is that sentinel. 0 stays, because an empty pack is a
     * reading. The state line is the only place the percent arrives. TCI
     * does not send one.
     */
    fun batteryOnFace(percent: Int?): Int? = percent?.takeIf { it in 0..100 }

    /**
     * The clock the header shows.
     *
     * The panel `state` line has no time field, and TCI has no time command.
     * `clk? MS` exists, and the radio answers with its own unix milliseconds,
     * but that answer is step 1 of setting the radio's clock from the phone.
     * It is not a status field the face is pushed. The header therefore uses
     * the phone's clock, in UTC, so the digits move and they are not the
     * radio's local zone.
     */
    fun utcClock(epochMs: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.ofEpochMilli(epochMs))

    /**
     * Portrait header clock, and the gap between it and the Pair pill.
     *
     * On the S24 Ultra at 1080 px wide (450 dpi, 384 dp, 372 dp after the
     * page padding) the 14 sp clock sat on that pill. Pair is pinned beside
     * the clock, the clock is 12 sp, and 8 dp of padding is the gap. The
     * advance is the same 0.62 em the frequency readout uses, so the test
     * and the slot agree.
     */
    const val PORTRAIT_CLOCK_SP = 12f
    const val PORTRAIT_CLOCK_GAP_DP = 8f
    const val PORTRAIT_PAIR_GAP_DP = 4f

    /** "Pair" at 11 sp, plus the pill's own padding and border. The start gap is separate. */
    const val PORTRAIT_PAIR_PILL_DP = 44f

    private const val MONO_ADVANCE = 0.62f

    fun utcClockWidthDp(fontSp: Float): Float = "HH:mm:ssZ".length * fontSp * MONO_ADVANCE

    /** 1080 px at 450 dpi, minus the portrait page's 6 dp on each side. */
    fun s24PortraitContentWidthDp(): Float = 1080f / 2.8125f - 12f

    /**
     * Right edge of the scrolling pills, then Pair, then the gap, then the
     * clock. The clock's left edge is the content width minus the clock.
     */
    fun portraitClockLeftDp(contentWidthDp: Float): Float =
        contentWidthDp - utcClockWidthDp(PORTRAIT_CLOCK_SP)

    fun portraitPairRightDp(contentWidthDp: Float): Float =
        portraitClockLeftDp(contentWidthDp) - PORTRAIT_CLOCK_GAP_DP

    fun portraitPairLeftDp(contentWidthDp: Float): Float =
        portraitPairRightDp(contentWidthDp) - PORTRAIT_PAIR_PILL_DP

    fun portraitPillsRightDp(contentWidthDp: Float): Float =
        portraitPairLeftDp(contentWidthDp) - PORTRAIT_PAIR_GAP_DP

    /** The pill box and the clock box do not meet, and the pills still have a slot. */
    fun portraitClockClearsThePills(contentWidthDp: Float): Boolean {
        val pillsRight = portraitPillsRightDp(contentWidthDp)
        val pairLeft = portraitPairLeftDp(contentWidthDp)
        val pairRight = portraitPairRightDp(contentWidthDp)
        val clockLeft = portraitClockLeftDp(contentWidthDp)
        return pillsRight >= 200f &&
            pillsRight <= pairLeft + 0.01f &&
            pairRight + PORTRAIT_CLOCK_GAP_DP <= clockLeft + 0.01f
    }

    fun frequencyTextSp(widthDp: Float, heightDp: Float, text: String): Float {
        val advance = 0.62f
        val chars = text.length.coerceAtLeast(1) + 1.5f
        val fromWidth = if (widthDp <= 0f) 28f else widthDp / (chars * advance)
        val fromHeight = if (heightDp <= 0f) fromWidth else heightDp * 0.78f
        return minOf(fromWidth, fromHeight).coerceIn(12f, 36f)
    }

    fun bandLabel(freqHz: Int, bands: List<Band>): String {
        fun pick(list: List<Band>): Band? =
            list.filter { it.name.isNotBlank() && freqHz in it.startHz..it.stopHz }
                .minByOrNull { it.stopHz - it.startHz }
        return (pick(bands) ?: pick(fallbackBands))?.name ?: "—"
    }

    fun agcLabel(index: Int): String = agcLabels.getOrElse(index) { "AGC $index" }

    fun vfoLabel(index: Int): String = if (index == 0) "VFO-A" else "VFO-B"

    fun modeLabel(index: Int): String = PanelMode.fromIndex(index)?.label ?: "M$index"

    fun stepHz(index: Int): Int = stepHzTable.getOrElse(index) { 100 }

    fun stepLabel(index: Int): String {
        val hz = stepHzTable.getOrNull(index) ?: return "step $index"
        return if (hz >= 1_000) "${hz / 1_000} kHz" else "$hz Hz"
    }

    fun nextStepIndex(index: Int): Int = (index + 1).mod(stepHzTable.size)

    fun nextAgc(index: Int): Int = (index + 1).mod(agcLabels.size)

    fun nextMode(index: Int, modeCount: Int): Int {
        val n = modeCount.coerceAtLeast(1)
        return (index + 1).mod(n)
    }

    /** [narrower] moves toward a tighter span (zoom 1 → 2 → 4 → 8). Wider moves back. */
    fun nextZoom(zoom: Int, narrower: Boolean): Int {
        val steps = zoomSteps
        val at = steps.indexOfFirst { it >= zoom }.let { if (it < 0) steps.lastIndex else it }
        val next = if (narrower) (at + 1).coerceAtMost(steps.lastIndex) else (at - 1).coerceAtLeast(0)
        // Sitting on a value the table does not list (the radio sent 3): snap to the neighbour, not to itself.
        if (steps[at] != zoom && !narrower && at > 0 && steps[at] > zoom) return steps[at - 1]
        return steps[next]
    }

    fun clampFrequency(hz: Long): Int = hz.coerceIn(0L, 470_000_000L).toInt()

    fun steppedFrequency(freqHz: Int, stepIndex: Int, direction: Int): Int =
        clampFrequency(freqHz.toLong() + stepHz(stepIndex).toLong() * direction.sign())

    fun bumpFrequency(freqHz: Int, group: FreqGroup, direction: Int): Int =
        clampFrequency(freqHz.toLong() + group.quantumHz.toLong() * direction.sign())

    fun formatWatts(watts: Double): String = String.format(Locale.US, "%.1f W", watts)

    fun formatDb(db: Double): String = String.format(Locale.US, "%.1f", db)

    fun sMeter(db: Double): SMeterReading {
        val needle = ((db.coerceIn(S1_DB, S9_40_DB) - S1_DB) / (S9_40_DB - S1_DB)).toFloat()
        val caption = if (db >= S9_DB) {
            val over = (db - S9_DB).roundToInt()
            if (over <= 0) "S9" else "+$over"
        } else {
            val unit = (1.0 + (db - S1_DB) / 6.0).roundToInt().coerceIn(1, 9)
            "S$unit"
        }
        return SMeterReading(db, needle, caption)
    }

    /**
     * The amber dial's marks, in the same units as [SMeterReading.needle]: 0 is S1, 1 is +40.
     * S3, S5 and S7 are every 6 dB from S1. +20 is 20 dB over S9. These are the labels the
     * radio prints on the arc; the caption under the needle is the live reading.
     */
    fun sMeterMarks(): List<Pair<Float, String>> {
        val span = S9_40_DB - S1_DB
        fun at(db: Double) = ((db - S1_DB) / span).toFloat()
        return listOf(
            at(S1_DB) to "S1",
            at(S1_DB + 12.0) to "3",
            at(S1_DB + 24.0) to "5",
            at(S1_DB + 36.0) to "7",
            at(S9_DB) to "9",
            at(S9_DB + 20.0) to "+20",
            at(S9_40_DB) to "+40",
        )
    }

    /**
     * The arc card's two bands. The dBm readout used to be drawn on top of the dial,
     * centred on the bottom edge, so a reading such as −72 sat on the "+40" mark.
     * The readout now owns [readoutBottom], and every scale mark is drawn in the arc
     * that starts there. A wider value stays in the readout band. It cannot reach a mark.
     */
    data class MeterFace(val readoutBottom: Float, val arcTop: Float, val arcHeight: Float)

    fun meterFace(cardHeightPx: Float, readoutPx: Float): MeterFace {
        val height = cardHeightPx.coerceAtLeast(0f)
        val readout = readoutPx.coerceIn(0f, height)
        return MeterFace(readout, readout, height - readout)
    }

    /** Centre of the dial, and the radius of the amber arc, inside the arc's own box. */
    data class DialPoint(val x: Float, val y: Float)

    fun dialPivot(arcWidth: Float, arcHeight: Float): DialPoint =
        DialPoint(arcWidth / 2f, arcHeight * 0.82f)

    fun dialRadius(arcWidth: Float, arcHeight: Float): Float =
        minOf(arcWidth * 0.46f, arcHeight * 0.72f)

    /**
     * A point on the dial. [mark] is 0 at S1 and 1 at +40, the same units as
     * [SMeterReading.needle]. [radiusFraction] is 1 on the arc, 0.58 on the labels.
     */
    fun dialPoint(arcWidth: Float, arcHeight: Float, mark: Float, radiusFraction: Float): DialPoint {
        val pivot = dialPivot(arcWidth, arcHeight)
        val radius = dialRadius(arcWidth, arcHeight) * radiusFraction
        val rad = Math.toRadians(180.0 + mark.toDouble() * 180.0)
        return DialPoint(
            (pivot.x + kotlin.math.cos(rad) * radius).toFloat(),
            (pivot.y + kotlin.math.sin(rad) * radius).toFloat(),
        )
    }

    /** Top-left of one scale label, clamped inside the arc so it is not drawn into the readout. */
    fun dialLabelTopLeft(
        arcWidth: Float,
        arcHeight: Float,
        mark: Float,
        labelWidth: Float,
        labelHeight: Float,
    ): DialPoint {
        val at = dialPoint(arcWidth, arcHeight, mark, 0.58f)
        val left = (at.x - labelWidth / 2f).coerceIn(0f, (arcWidth - labelWidth).coerceAtLeast(0f))
        val top = (at.y - labelHeight / 2f).coerceIn(0f, (arcHeight - labelHeight).coerceAtLeast(0f))
        return DialPoint(left, top)
    }

    /**
     * Landscape keys are about 56 dp wide. "TX power" becomes "TX pow…" there; these shorter
     * words are the same control, and portrait still uses the full title.
     */
    fun compactKeyTitle(title: String): String = when (title) {
        "TX power" -> "TX pwr"
        "RF gain" -> "RF"
        "Volume" -> "Vol"
        "Squelch" -> "Sql"
        else -> title
    }

    /**
     * Which segments of a digit are lit. Bit 0 is the top bar (a), then upper-right (b),
     * lower-right (c), bottom (d), lower-left (e), upper-left (f), middle (g).
     */
    fun segmentMask(ch: Char): Int = when (ch) {
        '0' -> 0b0111111
        '1' -> 0b0000110
        '2' -> 0b1011011
        '3' -> 0b1001111
        '4' -> 0b1100110
        '5' -> 0b1101101
        '6' -> 0b1111101
        '7' -> 0b0000111
        '8' -> 0b1111111
        '9' -> 0b1101111
        else -> 0
    }

    /**
     * Bars beside the numbers the radio sent. Watts are drawn against 10 W so a QRP carrier still
     * moves the needle; SWR fills from 1.0 to 3.0; ALC fills at 100, which is the scale the text shows.
     */
    fun txMeter(meters: TxMeters): TxMeterView = TxMeterView(
        watts = (meters.watts / TX_WATTS_FULL_SCALE).toFloat().coerceIn(0f, 1f),
        swr = ((meters.swr - 1.0) / 2.0).toFloat().coerceIn(0f, 1f),
        alc = (meters.alc / 100.0).toFloat().coerceIn(0f, 1f),
        wattsText = formatWatts(meters.watts),
        swrText = String.format(Locale.US, "%.2f", meters.swr),
        alcText = String.format(Locale.US, "%.0f", meters.alc),
    )

    fun spanOf(centreHz: Int, widthHz: Int): HzSpan {
        val width = widthHz.coerceAtLeast(0)
        val left = centreHz.toLong() - width / 2
        return HzSpan(clampFrequency(left), clampFrequency(left + width))
    }

    fun fractionForHz(hz: Int, span: HzSpan): Float {
        val width = span.widthHz
        if (width <= 0) return 0.5f
        return (hz - span.leftHz).toFloat() / width.toFloat()
    }

    fun hzAtFraction(fraction: Float, span: HzSpan): Int {
        val width = span.widthHz
        if (width <= 0) return span.leftHz
        return clampFrequency(span.leftHz.toLong() + (fraction.coerceIn(0f, 1f) * width).toLong())
    }

    /** How far from a tap a spot still counts, so a callsign a few pixels wide is a real target. */
    fun slopHz(span: HzSpan): Int = maxOf(span.widthHz / 40, 400)

    /**
     * The spot under a tap. [spots] is newest-first, the order the radio sends. A tie goes to the
     * newer spot because the scan keeps the first winner and only replaces it on a strictly closer hit.
     */
    fun nearestSpot(hz: Int, spots: List<Spot>, slopHz: Int): Spot? {
        var best: Spot? = null
        var bestDist = Int.MAX_VALUE
        for (spot in spots) {
            val dist = abs(spot.freqHz - hz)
            if (dist <= slopHz && dist < bestDist) {
                best = spot
                bestDist = dist
            }
        }
        return best
    }

    /**
     * A short scale label. `14.074.000` becomes `14.074`; a non-zero hertz group keeps its digits
     * until it would be wider than the tick it sits on.
     */
    fun scaleLabel(hz: Int): String {
        val text = formatFrequency(hz)
        val trimmed = if (text.endsWith(".000")) text.dropLast(4) else text
        return if (trimmed.length > 7) trimmed.take(7) else trimmed
    }

    /**
     * Where each scale label's left edge goes, or null when the label would sit on the one before it.
     *
     * The ticks include both edges of the span. On a phone the edge label is half outside the view
     * if it is centred, and the next tick is only a few pixels in, so the two words draw on top of
     * each other. Clamping keeps a label inside the view. Dropping the next one keeps a gap.
     */
    fun placeScaleLabels(
        centersPx: List<Float>,
        widthsPx: List<Float>,
        viewWidthPx: Float,
        gapPx: Float,
    ): List<Float?> {
        val placed = ArrayList<Float?>(centersPx.size)
        var previousRight = Float.NEGATIVE_INFINITY
        for (i in centersPx.indices) {
            val width = widthsPx.getOrElse(i) { 0f }.coerceAtLeast(0f)
            if (viewWidthPx <= width || width == 0f) {
                placed += null
                continue
            }
            val left = (centersPx[i] - width / 2f).coerceIn(0f, viewWidthPx - width)
            if (left < previousRight + gapPx) {
                placed += null
            } else {
                placed += left
                previousRight = left + width
            }
        }
        return placed
    }

    /**
     * How wide the frequency labels may be, given the Zoom chip in the top-right corner.
     *
     * Landscape spectrum-plus-waterfall keeps the trace only as tall as [zoomGuardPx],
     * which is the chip. The right-hand label is then clamped to the edge of the scale,
     * and that edge is directly under the chip. Reserving [zoomReservePx] pulls the label
     * clear. A taller trace (portrait, or spectrum on its own) has the scale below the
     * chip, so the labels use the whole width.
     *
     * The old test was `spectrum + 2 < zoomGuard`. The split already lifts the trace up
     * to the guard, so that test was false on the layout that collides and the inset
     * never ran.
     */
    fun scaleLabelWidth(
        viewWidthPx: Float,
        spectrumPx: Float,
        zoomGuardPx: Float,
        scalePx: Float,
        zoomReservePx: Float,
        layout: ScopeLayout = ScopeLayout.BOTH,
    ): Float {
        if (viewWidthPx <= 0f) return 0f
        // Waterfall-only keeps Zoom in the chip row, above this strip. Reserving the
        // corner there would push the right-hand label inward for a chip that is not
        // on the strip. Combined and spectrum-only reserve it when the trace is short
        // enough that the chip reaches this strip. A landscape spectrum-plus-waterfall
        // trace is about a third of the pane, so the chip stays above the ticks.
        val chipReachesScale = layout != ScopeLayout.WATERFALL && spectrumPx <= zoomGuardPx + scalePx
        val usable = if (chipReachesScale) viewWidthPx - zoomReservePx else viewWidthPx
        return usable.coerceAtLeast(1f)
    }

    /**
     * Key names broken into rows that fit [widthDp].
     *
     * Compose Foundation in this project does not have the `FlowRow` overflow overload the
     * compiler emits, and that call crashed the Screen tab (`NoSuchMethodError`). A single
     * scrolling row also cut the last name in half at the screen edge. Each chip is about
     * [charDp] per letter plus [chromeDp] for its padding and the gap, which is enough to keep
     * `MSG` and `BAND_DOWN` whole on a plain row.
     */
    fun packKeyRows(
        labels: List<String>,
        widthDp: Float,
        charDp: Float = 7.5f,
        chromeDp: Float = 26f,
    ): List<List<String>> {
        if (labels.isEmpty()) return emptyList()
        if (widthDp <= 0f) return listOf(labels)
        val rows = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var used = 0f
        for (label in labels) {
            val chip = label.length * charDp + chromeDp
            if (current.isNotEmpty() && used + chip > widthDp) {
                rows += current
                current = ArrayList()
                used = 0f
            }
            current += label
            used += chip
        }
        if (current.isNotEmpty()) rows += current
        return rows
    }

    /**
     * How a scope pane divides between the spectrum trace and the waterfall.
     *
     * [waterfallShare] is the fraction of the whole pane the waterfall should keep.
     * On a phone lying on its side the trace is held at 30–35% of that pane. The scale
     * and the band plan used to be subtracted from the trace after the waterfall had
     * already taken about three quarters, so on an S24-class landscape scope the trace
     * was only the zoom chip, about a 20 px ribbon, and the strips sat in the trace's
     * budget. They now come out of what is left, which is the waterfall. The trace still
     * keeps [zoomGuardPx] when the pane is too short for that share, which holds the
     * Zoom chip above the frequency ticks.
     * A share of 0 is the portrait split: the trace is 40% of the room left after the scale and plan.
     * [ridge] gives the 3D plot a little of that room back. The ridges need the height; the
     * waterfall stays the larger part of a landscape pane.
     * [layout] can give the whole room to the spectrum or to the waterfall. The scale and
     * the band plan are outside that room, so they stay either way.
     */
    data class ScopeSplit(val spectrumPx: Float, val waterfallPx: Float)

    fun scopeSplit(
        heightPx: Float,
        scalePx: Float,
        planPx: Float,
        waterfallShare: Float,
        zoomGuardPx: Float,
        ridge: Boolean = false,
        layout: ScopeLayout = ScopeLayout.BOTH,
    ): ScopeSplit {
        if (heightPx <= 0f) return ScopeSplit(0f, 0f)
        val room = (heightPx - scalePx - planPx).coerceAtLeast(0f)
        if (layout == ScopeLayout.SPECTRUM) return ScopeSplit(room, 0f)
        if (layout == ScopeLayout.WATERFALL) return ScopeSplit(0f, room)
        val spectrum = if (waterfallShare <= 0f) {
            val fraction = if (ridge) 0.55f else 0.40f
            maxOf(room * fraction, minOf(zoomGuardPx, room))
        } else {
            // 1 - share is the trace. A share of 0.68 asks for 32%. The clamp is the
            // 30–35% the landscape face keeps, so a leftover 0.73 from an older call
            // still lands in that band instead of shrinking the trace to the chip.
            // The 3D field takes a little more. It stays under half, so the waterfall
            // is still the larger part of the pane.
            val trace = (1f - waterfallShare).coerceIn(0.30f, 0.35f)
            val fraction = if (ridge) (trace + 0.08f).coerceAtMost(0.45f) else trace
            (heightPx * fraction).coerceIn(minOf(zoomGuardPx, room), room)
        }
        return ScopeSplit(spectrum, (room - spectrum).coerceAtLeast(0f))
    }

    /**
     * Where the chips, the trace, the scale, the plan and the waterfall sit in one scope.
     *
     * Waterfall-only used to paint the scale at the top of the plot and float the chips
     * on that same band, so the labels and the chips covered each other and the first
     * lines of the waterfall. The chip row is now its own band above the plot. The scale
     * starts where that row ends. Spectrum-only and the combined view keep the chips
     * painted over the trace, so they do not take height away from a landscape waterfall.
     * Spectrum-only still gives the trace every pixel the plot has above the scale and
     * the plan: nothing is left under the band-plan bar.
     */
    data class ScopeBands(
        val chipPx: Float,
        val spectrumPx: Float,
        val scalePx: Float,
        val planPx: Float,
        val waterfallPx: Float,
    ) {
        /** Top of the frequency labels. The chip row, when it has one, ends here. */
        val scaleTop: Float get() = chipPx + spectrumPx

        fun total(): Float = chipPx + spectrumPx + scalePx + planPx + waterfallPx
    }

    fun scopeBands(
        heightPx: Float,
        chipPx: Float,
        scalePx: Float,
        planPx: Float,
        waterfallShare: Float,
        zoomGuardPx: Float,
        ridge: Boolean = false,
        layout: ScopeLayout = ScopeLayout.BOTH,
    ): ScopeBands {
        val chips = if (layout == ScopeLayout.WATERFALL) chipPx.coerceAtLeast(0f) else 0f
        val plot = (heightPx - chips).coerceAtLeast(0f)
        val split = scopeSplit(plot, scalePx, planPx, waterfallShare, zoomGuardPx, ridge, layout)
        return ScopeBands(chips, split.spectrumPx, scalePx.coerceAtLeast(0f), planPx.coerceAtLeast(0f), split.waterfallPx)
    }

    /** Nice round ticks across the span, including both edges when they are not already a tick. */
    fun scaleTicks(span: HzSpan, maxLabels: Int = 6): List<Int> {
        val width = span.widthHz
        if (width <= 0) return listOf(span.leftHz)
        val rough = (width / maxLabels.coerceAtLeast(1)).coerceAtLeast(1)
        val mag = Math.pow(10.0, floor(log10(rough.toDouble()))).toInt().coerceAtLeast(1)
        val step = listOf(1, 2, 5, 10).map { it * mag }.firstOrNull { it >= rough } ?: rough
        val ticks = ArrayList<Int>(maxLabels + 2)
        ticks += span.leftHz
        var hz = (span.leftHz / step) * step
        if (hz <= span.leftHz) hz += step
        while (hz < span.rightHz && ticks.size < maxLabels + 4) {
            if (hz - ticks.last() > step / 5) ticks += hz
            hz += step
        }
        if (ticks.last() != span.rightHz) ticks += span.rightHz
        return ticks
    }

    fun planLabel(type: Int): String = when (type) {
        0 -> "CW"
        1 -> "DIG"
        2 -> "SSB"
        3 -> "BCN"
        else -> ""
    }

    fun planColor(type: Int): Int = planArgb.getOrElse(type) { 0xFF262B36.toInt() }

    /** Segments of [plan] that cross the span, as fractions of the width, clipped to the window. */
    fun planSlices(span: HzSpan, plan: List<PlanSegment>): List<PlanSlice> {
        if (span.widthHz <= 0) return emptyList()
        val out = ArrayList<PlanSlice>()
        for (seg in plan) {
            if (seg.stopHz <= span.leftHz || seg.startHz >= span.rightHz) continue
            val start = fractionForHz(seg.startHz.coerceIn(span.leftHz, span.rightHz), span).coerceIn(0f, 1f)
            val end = fractionForHz(seg.stopHz.coerceIn(span.leftHz, span.rightHz), span).coerceIn(0f, 1f)
            if (end <= start) continue
            out += PlanSlice(start, end, seg.type, planLabel(seg.type))
        }
        return out
    }

    /**
     * The smallest window [waterfallArgb] will paint. The radio's own ramp is 48 dB
     * (dsp.cpp COLOUR_RANGE), which is already wider than this.
     */
    const val WATERFALL_MIN_SPAN_DB = 36

    /**
     * Floor and ceiling for one waterfall row, before the tracker eases them.
     *
     * This is auto_levels() on the bins the Panel sent: the grouped noise floor, then
     * 5 dB under it, then 48 dB of colour. One carrier does not move the floor, and the
     * window is never tighter than [WATERFALL_MIN_SPAN_DB].
     */
    fun waterfallWindow(rows: List<ByteArray>): Pair<Int, Int> {
        val row = rows.lastOrNull { it.isNotEmpty() } ?: return -120 to (-120 + WATERFALL_MIN_SPAN_DB)
        val groups = FloatArray(row.size / FirmwareLook.FLOOR_GROUP + 1)
        val noise = FirmwareLook.noiseFloorDb(row, row.size, groups)
        val (min, max) = FirmwareLook.colourWindow(if (noise.isNaN()) -120f else noise)
        val floor = min.roundToInt()
        val ceiling = max.roundToInt()
        return floor to maxOf(ceiling, floor + WATERFALL_MIN_SPAN_DB)
    }

    /**
     * Waterfall colour, weak to strong: near-black, blue, cyan, green, yellow, red, white.
     *
     * [floor] and [ceiling] are the window from [waterfallWindow]. The default window is the whole
     * int8 range, which is what the endpoint checks use. A gamma below 1 opens the bottom of that
     * window into the blue stops; without it a 10 dB wiggle in the noise stays one colour.
     */
    fun waterfallArgb(db: Int, floor: Int = -128, ceiling: Int = 0): Int {
        val span = (ceiling - floor).coerceAtLeast(1)
        val linear = ((db - floor).toFloat() / span).coerceIn(0f, 1f)
        val t = linear.toDouble().pow(0.65).toFloat()
        val stops = floatArrayOf(0f, 0.18f, 0.36f, 0.52f, 0.66f, 0.78f, 0.90f, 1f)
        val colors = intArrayOf(
            0xFF000814.toInt(),
            0xFF001A66.toInt(),
            0xFF0040E0.toInt(),
            0xFF00A0FF.toInt(),
            0xFF00E080.toInt(),
            0xFFFFE000.toInt(),
            0xFFFF2020.toInt(),
            0xFFFFFFFF.toInt(),
        )
        var i = 1
        while (i < stops.size && t > stops[i]) i++
        if (i >= stops.size) return colors.last()
        val stopSpan = (stops[i] - stops[i - 1]).takeIf { it > 0f } ?: 1f
        val u = ((t - stops[i - 1]) / stopSpan).coerceIn(0f, 1f)
        return mixArgb(colors[i - 1], colors[i], u)
    }

    /**
     * Where the filter sits on the dial, in absolute Hz, low edge then high edge.
     *
     * Before this, the two cuts were added to the carrier. The radio stores them as
     * positive audio Hz for every mode (`cfg_cur_filter_low` / `cfg_cur_filter_high`,
     * the `flo` and `fhi` of the state line), and the view flips the side. LSB at
     * 300..2700 was therefore drawn 300..2700 Hz above the VFO, on the USB side of
     * the marker, on both the spectrum and the waterfall.
     *
     * The side is `spectrum_view.c` `on_filter_change`. A lower-sideband mode is
     * `f - high_cut .. f - low_cut`, so the whole filter is below the carrier and the
     * edge nearer the VFO is the low cut. The radio's waterfall later slides a CW
     * filter by the key tone so the pitch sits on the line. That slide puts the high
     * edge of a 300..2700 Hz filter 400 Hz above the VFO when the tone is 700 Hz.
     * The shade here stays on the side the mode listens on. [keyToneHz] is the `kt`
     * the state line carries. It is not added.
     *
     * USB and DATA-U (DIGU) stay above: `f + low_cut .. f + high_cut`.
     * LSB, DATA-L (DIGL) and RTTY stay below: `f - high_cut .. f - low_cut`.
     * The radio has no RTTY index. RTTY runs as LSB or DATA-L, so it takes that side.
     * CW-R is always below. CW is below only when [cwToneRises] is set, which is the
     * X6200 (`cwrise` on the state line). CW on the X6100 stays above.
     * AM, NFM, SAM and WFM are symmetric: `f - high_cut .. f + high_cut`.
     *
     * [mode] defaults to USB so a caller that already holds signed offsets, or the
     * older two-cut check, still lands above the carrier.
     */
    fun passbandHz(
        freqHz: Int,
        lowHz: Int,
        highHz: Int,
        mode: Int = PanelMode.Usb.index,
        keyToneHz: Int = 0,
        cwToneRises: Boolean = false,
    ): Pair<Int, Int> {
        val (from, to) = passbandOffsets(mode, lowHz, highHz, keyToneHz, cwToneRises)
        val a = freqHz.toLong() + from
        val b = freqHz.toLong() + to
        return clampFrequency(minOf(a, b)) to clampFrequency(maxOf(a, b))
    }

    /**
     * The same edges as Hz from the carrier, before they are added to the dial.
     * The first of the pair is the low side of the filter, the second the high side.
     */
    @Suppress("UNUSED_PARAMETER") // keyToneHz is documented above: accepted, not added.
    fun passbandOffsets(
        mode: Int,
        lowHz: Int,
        highHz: Int,
        keyToneHz: Int = 0,
        cwToneRises: Boolean = false,
    ): Pair<Int, Int> {
        // keyToneHz is the state's kt. It is accepted with the rest of the state and
        // not added: a tone inside the filter would slide the high edge across the carrier.
        return when (PanelMode.fromIndex(mode)) {
            PanelMode.Lsb, PanelMode.DigL -> -highHz to -lowHz
            PanelMode.Usb, PanelMode.DigU -> lowHz to highHz
            PanelMode.Cw, PanelMode.Cwr -> {
                // radio_cw_tone_dir: CW-R is always the lower side. CW is the lower side
                // only when the radio's own CW tone rises with the dial (cwrise, the X6200).
                // The key tone is not added: the filter stays wholly on that side.
                val below = mode == PanelMode.Cwr.index || (mode == PanelMode.Cw.index && cwToneRises)
                if (below) -highHz to -lowHz else lowHz to highHz
            }
            PanelMode.Am, PanelMode.Fm, PanelMode.Sam, PanelMode.Wfm -> -highHz to highHz
            // An index this build does not number. Two positive cuts are the upper side.
            null -> lowHz to highHz
        }
    }

    fun nudgeFilter(lowHz: Int, highHz: Int, field: AdjustField, direction: Int): Pair<Int, Int> {
        val delta = FILTER_STEP_HZ * direction.sign()
        return when (field) {
            AdjustField.FILTER_LOW -> {
                val next = lowHz + delta
                if (next < highHz) next to highHz else lowHz to highHz
            }
            AdjustField.FILTER_HIGH -> {
                val next = highHz + delta
                if (next > lowHz) lowHz to next else lowHz to highHz
            }
            AdjustField.FILTER_BW -> {
                val center = (lowHz + highHz) / 2
                val half = ((highHz - lowHz) / 2 + delta).coerceAtLeast(FILTER_STEP_HZ)
                (center - half) to (center + half)
            }
            else -> lowHz to highHz
        }
    }

    /**
     * [direction] +1 is a tap, −1 is a hold. Adjust keys nudge; the page key steps the deck;
     * everything else ignores the sign and does its one action.
     */
    fun commandFor(
        key: FaceKey,
        state: RadioState?,
        volumeMax: Int,
        direction: Int,
    ): FaceCommand {
        val dir = direction.sign()
        return when (val action = key.action) {
            FaceAction.NextPage -> if (dir < 0) FaceCommand.PreviousPage else FaceCommand.NextPage
            is FaceAction.Adjust -> adjust(action, state, volumeMax, if (dir == 0) 1 else dir)
            is FaceAction.Toggle -> FaceCommand.SetInt(action.setting, toggledValue(action.field, state))
            FaceAction.CycleAgc -> FaceCommand.SetInt(PanelSettings.AGC, nextAgc(state?.agc ?: 0))
            is FaceAction.Zoom -> {
                // A hold does the other direction, so the one Zoom key both narrows (tap) and widens (hold).
                val narrower = if (dir < 0) !action.narrower else action.narrower
                FaceCommand.SetInt(PanelSettings.ZOOM, nextZoom(state?.zoom ?: 1, narrower))
            }
            FaceAction.PickMode -> FaceCommand.PickMode
            FaceAction.PickBand -> FaceCommand.PickBand
            is FaceAction.Navigate -> FaceCommand.Navigate(action.route)
            is FaceAction.Pending -> FaceCommand.Pending(action.feature)
        }
    }

    /** True when the command is a `set` the radio will apply. Navigation and placeholders are not. */
    fun writesTheRadio(command: FaceCommand): Boolean = when (command) {
        is FaceCommand.SetInt, is FaceCommand.SetFilter -> true
        else -> false
    }

    /**
     * A bare `key PTT` is not a panel key this UI may send. The lease machine is the only thing
     * that keys, because it is the only thing that also sends the unkey. POWER stays available
     * for a screen that deliberately offers it; this guard is only the carrier.
     */
    fun isCarrierKey(name: String): Boolean = name.equals("PTT", ignoreCase = true)

    fun keyLabel(key: FaceKey, state: RadioState?): FaceKeyLabel {
        val action = key.action
        if (action is FaceAction.NextPage) {
            val page = pages.firstOrNull { page -> page.keys.any { it.id == key.id } }
            val badge = page?.badge ?: key.title
            val words = badge.split(' ', limit = 2)
            return FaceKeyLabel(words[0], words.getOrElse(1) { "" }, on = false, pending = false)
        }
        val value = when (action) {
            is FaceAction.Adjust -> adjustText(action.field, state)
            is FaceAction.Toggle -> if (toggleOn(action.field, state)) "on" else "off"
            FaceAction.CycleAgc -> agcLabel(state?.agc ?: 0)
            is FaceAction.Zoom -> "x${state?.zoom ?: 1}"
            FaceAction.PickMode -> state?.let { modeLabel(it.mode) } ?: "—"
            FaceAction.PickBand -> "list"
            is FaceAction.Navigate -> "open"
            is FaceAction.Pending -> "pending"
            FaceAction.NextPage -> ""
        }
        val on = action is FaceAction.Toggle && toggleOn(action.field, state)
        return FaceKeyLabel(key.title, value, on, pending = action is FaceAction.Pending)
    }

    /** Every placeholder, softkeys plus the status-bar items and the modes that have no `set`. */
    fun pendingFeatures(): List<PendingFeature> {
        val fromKeys = pages.flatMap { page ->
            page.keys.mapNotNull { key ->
                val pending = key.action as? FaceAction.Pending ?: return@mapNotNull null
                PendingFeature(page.group.strip, pending.feature)
            }
        }
        val extras = listOf(
            PendingFeature("VFO", "Split / TX shift"),
            PendingFeature("Status", "Clock"),
            PendingFeature("Status", "Battery time remaining"),
            PendingFeature("Status", "Wi-Fi"),
            PendingFeature("Status", "Bluetooth"),
            PendingFeature("Status", "A2DP"),
            PendingFeature("Status", "CAT"),
            PendingFeature("Status", "GPS"),
            PendingFeature("Status", "DX cluster link"),
            PendingFeature("DSP", "DNF"),
            PendingFeature("APP", "Birdie Cal"),
        ) + pendingDigitalModes.map { PendingFeature("Modes", it) }
        return (fromKeys + extras).distinctBy { it.group + "\u0000" + it.name }
    }

    fun firstAdjustableId(page: FacePage): String? =
        page.keys.firstOrNull { it.action is FaceAction.Adjust }?.id

    private fun adjust(action: FaceAction.Adjust, state: RadioState?, volumeMax: Int, direction: Int): FaceCommand {
        val current = adjustCurrent(action.field, state)
        if (action.field == AdjustField.FILTER_LOW || action.field == AdjustField.FILTER_HIGH || action.field == AdjustField.FILTER_BW) {
            val (low, high) = nudgeFilter(state?.filterLowHz ?: 0, state?.filterHighHz ?: 0, action.field, direction)
            return FaceCommand.SetFilter(low, high)
        }
        val max = if (action.field == AdjustField.VOLUME) {
            volumeMax.coerceAtLeast(action.min).coerceAtLeast(1)
        } else {
            action.max
        }
        val next = (current + action.step * direction).coerceIn(action.min, max)
        return FaceCommand.SetInt(action.setting, next)
    }

    private fun adjustCurrent(field: AdjustField, state: RadioState?): Int = when (field) {
        AdjustField.VOLUME -> state?.volume ?: 0
        AdjustField.SQL -> state?.squelch ?: 0
        AdjustField.RFG -> state?.rfGain ?: 0
        AdjustField.POWER_TENTHS -> state?.powerTenths ?: 0
        AdjustField.FILTER_LOW -> state?.filterLowHz ?: 0
        AdjustField.FILTER_HIGH -> state?.filterHighHz ?: 0
        AdjustField.FILTER_BW -> (state?.let { it.filterHighHz - it.filterLowHz } ?: 0)
    }

    private fun adjustText(field: AdjustField, state: RadioState?): String = when (field) {
        AdjustField.VOLUME -> (state?.volume ?: 0).toString()
        AdjustField.SQL -> (state?.squelch ?: 0).toString()
        AdjustField.RFG -> (state?.rfGain ?: 0).toString()
        AdjustField.POWER_TENTHS -> formatWatts(state?.powerWatts ?: 0.0)
        AdjustField.FILTER_LOW -> "${state?.filterLowHz ?: 0} Hz"
        AdjustField.FILTER_HIGH -> "${state?.filterHighHz ?: 0} Hz"
        AdjustField.FILTER_BW -> "${state?.let { it.filterHighHz - it.filterLowHz } ?: 0} Hz"
    }

    private fun toggleOn(field: ToggleField, state: RadioState?): Boolean = when (field) {
        ToggleField.PRE -> state?.preOn == true
        ToggleField.ATT -> state?.attenuatorOn == true
        ToggleField.NB -> state?.noiseBlankerOn == true
        ToggleField.NR -> state?.noiseReduceOn == true
        ToggleField.ATU -> state?.atuOn == true
        ToggleField.LOCK -> state?.freqLocked == true
    }

    private fun toggledValue(field: ToggleField, state: RadioState?): Int = if (toggleOn(field, state)) 0 else 1

    private fun stepPage(index: Int, direction: Int): Int {
        val safe = index.coerceIn(0, pages.lastIndex)
        val group = pages[safe].group
        val inGroup = pages.indices.filter { pages[it].group == group }
        if (inGroup.isEmpty()) return safe
        val pos = inGroup.indexOf(safe).let { if (it < 0) 0 else it }
        val next = (pos + direction.sign()).mod(inGroup.size)
        return inGroup[next]
    }

    private fun Int.sign(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }

    private fun mixArgb(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * t).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun pageKey(id: String, title: String) = FaceKey(id, title, FaceAction.NextPage)

    private fun adjust(
        id: String,
        title: String,
        setting: String,
        field: AdjustField,
        step: Int,
        min: Int,
        max: Int,
    ) = FaceKey(id, title, FaceAction.Adjust(setting, field, step, min, max))

    private fun toggle(id: String, title: String, setting: String, field: ToggleField) =
        FaceKey(id, title, FaceAction.Toggle(setting, field))

    private fun pending(id: String, title: String, feature: String) =
        FaceKey(id, title, FaceAction.Pending(feature))

    private fun buildPages(): List<FacePage> = listOf(
        FacePage(
            FaceGroup.GEN, "VOL 1:2",
            listOf(
                pageKey("vol-page-1", "VOL"),
                adjust("vol", "Volume", PanelSettings.VOL, AdjustField.VOLUME, 1, 0, 100),
                adjust("sql", "Squelch", PanelSettings.SQL, AdjustField.SQL, 1, 0, 100),
                adjust("rfg", "RF gain", PanelSettings.RFG, AdjustField.RFG, 1, 0, 100),
                adjust("pwr", "TX power", PanelSettings.PWR, AdjustField.POWER_TENTHS, 1, 0, 100),
            ),
        ),
        FacePage(
            FaceGroup.GEN, "VOL 2:2",
            listOf(
                pageKey("vol-page-2", "VOL"),
                pending("moni", "Moni level", "Monitor"),
                toggle("pre", "PRE", PanelSettings.PRE, ToggleField.PRE),
                toggle("att", "ATT", PanelSettings.ATT, ToggleField.ATT),
            ),
        ),
        FacePage(
            FaceGroup.GEN, "MFK 1:3",
            listOf(
                pageKey("mfk-page-1", "MFK"),
                pending("rit", "RIT", "RIT"),
                pending("xit", "XIT", "XIT"),
                FaceKey("zoom", "Zoom", FaceAction.Zoom(narrower = true)),
                pending("wf-ref", "WF ref", "Waterfall reference"),
            ),
        ),
        FacePage(
            FaceGroup.GEN, "MFK 2:3",
            listOf(
                pageKey("mfk-page-2", "MFK"),
                FaceKey("agc", "AGC", FaceAction.CycleAgc),
                pending("comp", "Comp", "COMP"),
                pending("if-shift", "IF shift", "IF shift"),
                pending("vox", "VOX", "VOX"),
            ),
        ),
        FacePage(
            FaceGroup.GEN, "MFK 3:3",
            listOf(
                pageKey("mfk-page-3", "MFK"),
                pending("wf-mode", "WF mode", "Waterfall mode"),
                FaceKey("span-wider", "Span +", FaceAction.Zoom(narrower = false)),
                FaceKey("span-narrower", "Span −", FaceAction.Zoom(narrower = true)),
                pending("screen-rec", "Screen rec", "Screen recorder"),
            ),
        ),
        FacePage(
            FaceGroup.APP, "APP 1:3",
            listOf(
                pageKey("app-page-1", "APP"),
                FaceKey("modes", "Modes", FaceAction.PickMode),
                FaceKey("cluster", "Cluster", FaceAction.Navigate("spots")),
                pending("swr", "SWR Scan", "SWR Scan"),
                pending("recorder", "RX Recorder", "RX Recorder"),
            ),
        ),
        FacePage(
            FaceGroup.APP, "APP 2:3",
            listOf(
                pageKey("app-page-2", "APP"),
                FaceKey("settings", "Settings", FaceAction.Navigate("settings")),
                pending("trainer", "CW Trainer", "CW trainer"),
                pending("field", "Field Ops", "Field Ops"),
                pending("channels", "Channels", "Memories"),
            ),
        ),
        FacePage(
            FaceGroup.APP, "APP 3:3",
            listOf(
                pageKey("app-page-3", "APP"),
                FaceKey("bands", "Bands", FaceAction.PickBand),
                pending("gps", "GPS", "GPS"),
                pending("screen-rec-2", "Screen rec", "Screen recorder"),
                pending("cw-kbd", "CW Keyboard", "CW keyboard"),
            ),
        ),
        FacePage(
            FaceGroup.KEY, "KEY 1:2",
            listOf(
                pageKey("key-page-1", "KEY"),
                pending("key-speed", "Speed", "CW keyer speed"),
                pending("key-vol", "Key vol", "CW keyer volume"),
                pending("key-train", "Train", "CW trainer"),
                pending("key-tone", "Tone", "CW keyer tone"),
            ),
        ),
        FacePage(
            FaceGroup.KEY, "KEY 2:2",
            listOf(
                pageKey("key-page-2", "KEY"),
                pending("key-mode", "Key mode", "CW keyer mode"),
                pending("iambic", "Iambic", "Iambic"),
                pending("qsk", "QSK", "QSK"),
                pending("ratio", "Ratio", "CW keyer ratio"),
            ),
        ),
        FacePage(
            FaceGroup.KEY, "CW 1:2",
            listOf(
                pageKey("cw-page-1", "CW"),
                pending("zap", "ZAP", "ZAP"),
                pending("cw-peak", "CW peak", "CW peak"),
                pending("cw-peak-q", "Peak Q", "CW peak Q"),
                FaceKey("log-qso", "Log QSO", FaceAction.Navigate("log")),
            ),
        ),
        FacePage(
            FaceGroup.KEY, "CW 2:2",
            listOf(
                pageKey("cw-page-2", "CW"),
                pending("decoder", "Decoder", "CW decoder switch"),
                pending("tuner", "Tuner", "CW tuner"),
                pending("dec-snr", "Dec SNR", "CW decoder SNR"),
                pending("parse", "Parse", "CW parse"),
            ),
        ),
        FacePage(
            FaceGroup.DFN, "DFN 1:3",
            listOf(
                pageKey("dfn-page-1", "DFN"),
                pending("dnf", "DNF", "DNF"),
                pending("dnf-freq", "DNF freq", "DNF frequency"),
                pending("dnf-width", "DNF width", "DNF width"),
                pending("dnf-auto", "DNF auto", "DNF auto"),
            ),
        ),
        FacePage(
            FaceGroup.DFN, "DFN 2:3",
            listOf(
                pageKey("dfn-page-2", "DFN"),
                toggle("nb", "NB", PanelSettings.NB, ToggleField.NB),
                pending("nb-level", "NB level", "NB level"),
                pending("nb-width", "NB width", "NB width"),
            ),
        ),
        FacePage(
            FaceGroup.DFN, "DFN 3:3",
            listOf(
                pageKey("dfn-page-3", "DFN"),
                toggle("nr", "NR", PanelSettings.NR, ToggleField.NR),
                pending("nr-level", "NR level", "NR level"),
            ),
        ),
        FacePage(
            FaceGroup.DFL, "DFL",
            listOf(
                adjust("flt-low", "Filter low", PanelSettings.FILTER, AdjustField.FILTER_LOW, FILTER_STEP_HZ, -20_000, 20_000),
                adjust("flt-high", "Filter high", PanelSettings.FILTER, AdjustField.FILTER_HIGH, FILTER_STEP_HZ, -20_000, 20_000),
                adjust("flt-bw", "Filter BW", PanelSettings.FILTER, AdjustField.FILTER_BW, FILTER_STEP_HZ, 50, 20_000),
            ),
        ),
        FacePage(
            FaceGroup.MEM, "MEM 1:2",
            listOf(
                pageKey("mem-page-1", "MEM"),
                pending("mem-1", "Set 1", "Memories"),
                pending("mem-2", "Set 2", "Memories"),
                pending("mem-3", "Set 3", "Memories"),
                pending("mem-4", "Set 4", "Memories"),
            ),
        ),
        FacePage(
            FaceGroup.MEM, "MEM 2:2",
            listOf(
                pageKey("mem-page-2", "MEM"),
                pending("mem-5", "Set 5", "Memories"),
                pending("mem-6", "Set 6", "Memories"),
                pending("mem-7", "Set 7", "Memories"),
                pending("mem-8", "Set 8", "Memories"),
            ),
        ),
    )
}
