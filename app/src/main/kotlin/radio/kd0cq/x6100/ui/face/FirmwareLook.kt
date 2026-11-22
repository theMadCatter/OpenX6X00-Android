// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The radio's own spectrum and waterfall, as Guru-RF/OpenX6X00 draws them on main
// 69f950291bafe8bbefad3e599d2d37abc5c28f3c (newer than release v0.3.13, which does
// not yet have these spectrum_view.c / waterfall_view.c / dsp.cpp revisions).
//
// Before this, the phone drew a white gradient under a white line and stretched the
// colour scale to whatever the latest frame's percentiles were. The radio, on the
// RF.Guru defaults, draws something else:
//
// - dsp.cpp smooths the spectrum 0.6 of the way up and 0.2 of the way down, then
//   spectrum_data() holds peaks for 5 s and sinks them 0.5 dB a frame.
// - The fill is a solid column of the theme line colour (#94A3B8) from that trace
//   to the bottom. The peak is a 1 px line in the "off" colour (#475569), painted
//   first so the fill covers it everywhere the trace has caught up.
// - auto_levels() paints the waterfall from 5 dB under the noise floor across 48 dB.
//   The floor is the 25th percentile of 8-bin power groups, edges left out, and a
//   slow tracker (dsp_math.h DspFloorTrack).
// - The passband is the screen background at 50% over the spectrum and the VFO red
//   at 20% over the waterfall. The VFO marker is 3 px, #FF2020, and the spectrum
//   hides it in CW and CW-R. Reference frequencies are 1 px dashes down the waterfall.
//
// The Panel socket sends the waterfall FFT (1024 int8 dB), not the separate
// screen-width spectrum FFT. These routines apply the radio's coefficients to that
// frame. They allocate nothing on a later frame: the scratch they need is built once.

package radio.kd0cq.x6100.ui.face

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

object FirmwareLook {

    /** theme.h RF.Guru `line`. The filled trace. */
    const val LINE = 0xFF94A3B8.toInt()

    /** theme.h RF.Guru `off`. The peak line. */
    const val PEAK = 0xFF475569.toInt()

    /** theme.h RF.Guru `panel` and `bg`. Spectrum ground, and the spectrum passband tint. */
    const val PANEL = 0xFF08090C.toInt()

    /**
     * theme.h RF.Guru `text`. freq_scale.c paints the waterfall dashes in this colour.
     * The opacity is [GRID_OPA] (LV_OPA_40) and the pattern is [GRID_DASH] px on,
     * [GRID_DASH] px off, one panel pixel wide. A phone draws that same colour and
     * opacity, but the stroke has to be at least 1 dp: one phone pixel is antialiased
     * away on a Gauss waterfall.
     */
    const val GRID = 0xFFF1F5F9.toInt()

    /** LV_OPA_40. freq_scale.c draws the dashes at this opacity. */
    const val GRID_OPA = 102

    /** freq_scale.c line dash width and gap, in panel pixels. */
    const val GRID_DASH = 4

    /** LV_OPA_50. spectrum_view.c shades the passband with this at filter-shade 4. */
    const val SPEC_SHADE = 127

    /** LV_OPA_20. waterfall_view.c shades its passband with this at filter-shade 4. */
    const val WATER_SHADE = 51

    /** spectrum_view.c and the waterfall's zoom-1 line: 3 px, #FF2020 (`vfo_line`). */
    const val VFO_PX = 3f

    /** smeter.h. S9 is −73, S1 is −121, an S unit is 6 dB. */
    const val S_MIN = -127f
    const val S8 = -79f
    const val TX_MIN = -103f
    const val TX_MAX = -53f

    /** dsp.cpp. Black starts this far under the tracked noise, and the ramp is this tall. */
    const val COLOUR_BELOW = 5f
    const val COLOUR_RANGE = 48f

    /** dsp.cpp spectrum_frame. A carrier rises quickly; the noise falls slowly. */
    const val RISE = 0.6f
    const val FALL = 0.2f

    /** params.c defaults: hold 5 s, then sink `speed * 0.1` dB, and speed is 5. */
    const val PEAK_HOLD_MS = 5_000L
    const val PEAK_SINK_DB = 0.5f

    /** dsp.cpp FRAME_MS. The tracker steps once per spectrum frame, at 15 a second. */
    const val FRAME_S = 1.0 / 15.0

    /** dsp_math.h dsp_noise_floor. Groups of 8, the 25th percentile, 4% (n/25) dropped at each end. */
    const val FLOOR_GROUP = 8
    const val FLOOR_PCT = 0.25f

    /**
     * Noise floor in the same dB the bins already use.
     *
     * The radio averages linear power, then takes the percentile, then converts back.
     * A carrier fills one group and leaves the percentile where the quiet groups are,
     * which is why one strong station does not lift the colour scale.
     *
     * [groups] is scratch. The call writes only the groups it formed. Returns NaN when
     * the frame is too short to form a group.
     */
    fun noiseFloorDb(db: ByteArray, length: Int, groups: FloatArray): Float {
        val n = length.coerceIn(0, db.size)
        if (n == 0 || groups.isEmpty()) return Float.NaN
        val edge = n / 25
        var g = 0
        var i = edge
        while (i + FLOOR_GROUP <= n - edge && g < groups.size) {
            var sum = 0.0
            for (k in 0 until FLOOR_GROUP) {
                // The bin is already dB. 10^(dB/10) is its power, which is what the
                // radio averages. Double, because a quiet bin is far below 1.
                sum += 10.0.pow(db[i + k].toInt() / 10.0)
            }
            groups[g] = (sum / FLOOR_GROUP).toFloat()
            g++
            i += FLOOR_GROUP
        }
        if (g == 0) return Float.NaN
        java.util.Arrays.sort(groups, 0, g)
        // C converts `pct * (size - 1)` with a truncating cast. The same index here.
        val at = (FLOOR_PCT * (g - 1)).toInt().coerceIn(0, g - 1)
        val lin = groups[at].toDouble()
        if (lin <= 0.0) return S_MIN
        return (10.0 * log10(lin)).toFloat()
    }

    /**
     * The colour window for one tracked floor.
     *
     * min is the floor minus 5 dB, held inside S_MIN..S8 the way auto_levels() clamps it.
     * max is 48 dB above that. A carrier past the top stays on the last palette stop.
     */
    fun colourWindow(floorDb: Float): Pair<Float, Float> {
        val min = (floorDb - COLOUR_BELOW).coerceIn(S_MIN, S8)
        return min to (min + COLOUR_RANGE)
    }

    /**
     * How many dB a spectrum-only pane keeps even when the band is quiet.
     * Less than this and a flat noise floor would be stretched into a solid block.
     */
    const val PANE_MIN_SPAN_DB = 12f

    /**
     * Where the loudest bin sits, as a fraction of the way up the spectrum-only pane.
     * The radio's own spectrum widget is about 76 px (p_spectrum_height), so the 48 dB
     * colour window fills it. The phone's spectrum-only pane is the whole scope, and
     * that same window leaves the trace as a ribbon on the floor with empty pane above it.
     */
    const val PANE_PEAK_FRACTION = 0.94f

    /**
     * Where the noise floor sits in that same pane. The fill under the noise is this
     * fraction of the height, so the ground is a band and the rest of the pane is the
     * signal. The combined spectrum-and-waterfall view does not use this; it keeps
     * [colourWindow], which is what the waterfall colours use.
     */
    const val PANE_FLOOR_FRACTION = 0.12f

    /**
     * Vertical scale for a spectrum that has the whole pane.
     *
     * [floorDb] is the tracked noise, not the colour window's clamped minimum.
     * [peakDb] is the loudest smoothed bin. The peak is placed at [PANE_PEAK_FRACTION]
     * and the noise at [PANE_FLOOR_FRACTION], with at least [PANE_MIN_SPAN_DB] between
     * them. A carrier then reaches the top of the pane the way the 3D field does,
     * instead of sitting in the bottom of a 48 dB window.
     */
    fun paneWindow(floorDb: Float, peakDb: Float): Pair<Float, Float> {
        val floor = if (floorDb.isNaN()) S_MIN else floorDb
        val peak = if (peakDb.isNaN()) floor else peakDb
        val span = (peak - floor).coerceAtLeast(PANE_MIN_SPAN_DB)
        val used = (PANE_PEAK_FRACTION - PANE_FLOOR_FRACTION).coerceAtLeast(0.5f)
        val window = span / used
        val min = floor - PANE_FLOOR_FRACTION * window
        return min to (min + window)
    }

    /** Where [db] sits in the spectrum pane. The ceiling is the top, the floor is the bottom. */
    fun traceY(db: Float, min: Float, max: Float, height: Float): Float {
        if (height <= 0f) return 0f
        val span = (max - min).coerceAtLeast(1f)
        val v = (db - min) / span
        return ((1f - v) * height).coerceIn(0f, height)
    }

    /**
     * dsp_math.h DspFloorTrack.
     *
     * The first reading is kept as it is. The next second follows with a 0.15 s time
     * constant, so a band change settles. After that a rising floor takes 4 s and a
     * falling floor takes 2 s, and a carrier that appears does not wash the colours out.
     */
    class FloorTrack {
        private var db = Float.NaN
        private var quickS = 0f

        fun reset() {
            db = Float.NaN
        }

        fun update(estimateDb: Float, dtS: Double = FRAME_S): Float {
            if (db.isNaN()) {
                db = estimateDb
                quickS = 1f
                return db
            }
            val tau = if (quickS > 0f) 0.15f else if (estimateDb > db) 4f else 2f
            quickS -= dtS.toFloat()
            val step = (1.0 - exp(-dtS / tau)).toFloat()
            db += (estimateDb - db) * step
            return db
        }
    }
}

/**
 * The smoothed spectrum and its peaks, for one pane.
 *
 * The radio keeps this state in the DSP and in spectrum_view.c. The phone sees a new
 * 1024-bin frame about 15 times a second and has to keep the same memory itself, or
 * every frame would be drawn raw and the trace would boil.
 *
 * A tune slides the memory by whole bins before the new frame is blended in, so a
 * carrier stays on its frequency while the view moves. A zoom replaces the memory:
 * the bins are no longer the same frequencies, and blending across that would smear
 * the old span into the new one. The first frame is taken as it arrived.
 */
class SpectrumTrace(capacity: Int = 1024) {
    private val shown = FloatArray(capacity) { FirmwareLook.S_MIN }
    private val peaks = FloatArray(capacity) { FirmwareLook.S_MIN }
    private val raisedAt = LongArray(capacity)
    private val scratch = FloatArray(capacity)
    private val timeScratch = LongArray(capacity)
    private var n = 0
    private var centre = 0
    private var width = 0
    private var primed = false

    /**
     * The loudest smoothed bin. Spectrum-only uses this as the top of the pane.
     * It is not the minimum: a tune fills the bins that just entered with [FirmwareLook.S_MIN],
     * and taking that as the floor would stretch the scale and put the trace back in a ribbon.
     */
    private var loud = FirmwareLook.S_MIN

    val length: Int get() = n

    /** The loudest bin last folded in, or [FirmwareLook.S_MIN] before the first frame. */
    fun loudest(): Float = loud

    fun levelAt(index: Float): Float? = read(shown, index)

    fun peakAt(index: Float): Float? = read(peaks, index)

    /**
     * Fold one frame in.
     *
     * [nowMs] is the clock the 5 s hold is measured on. [transmitting] freezes the
     * peaks and is the radio's `!tx` gate in spectrum_data(). The draw still uses
     * the S4..S9+20 scale for that frame; this method only owns the trace memory.
     */
    fun accept(
        db: ByteArray,
        bins: Int,
        centreHz: Int,
        widthHz: Int,
        nowMs: Long,
        transmitting: Boolean,
    ) {
        val count = bins.coerceIn(0, minOf(db.size, shown.size))
        if (count == 0) {
            n = 0
            loud = FirmwareLook.S_MIN
            return
        }
        val zoomed = primed && (widthHz != width || count != n)
        if (!primed || zoomed) {
            for (i in 0 until count) {
                val v = db[i].toInt().toFloat()
                shown[i] = v
                peaks[i] = v
                raisedAt[i] = nowMs
            }
            n = count
            centre = centreHz
            width = widthHz
            primed = true
            rememberLoudest(count)
            return
        }
        if (width > 0 && centreHz != centre) {
            val df = centreHz - centre
            // spectrum_move: whole bins, truncating. shift_peaks: rounded to the nearest bin.
            val traceShift = (df.toLong() * count / width).toInt()
            val binHz = width.toDouble() / count
            val peakShift = ((df.toDouble() + binHz / 2.0) / binHz).toInt()
            slide(shown, traceShift, count, FirmwareLook.S_MIN)
            slide(peaks, peakShift, count, FirmwareLook.S_MIN)
            slideTimes(peakShift, count, nowMs)
        }
        centre = centreHz
        width = widthHz
        for (i in 0 until count) {
            val v = db[i].toInt().toFloat()
            val cur = shown[i]
            shown[i] = cur + (v - cur) * (if (v > cur) FirmwareLook.RISE else FirmwareLook.FALL)
            if (transmitting) continue
            val smoothed = shown[i]
            if (smoothed > peaks[i]) {
                peaks[i] = smoothed
                raisedAt[i] = nowMs
            } else if (nowMs - raisedAt[i] > FirmwareLook.PEAK_HOLD_MS) {
                peaks[i] = (peaks[i] - FirmwareLook.PEAK_SINK_DB).coerceAtLeast(FirmwareLook.S_MIN)
            }
        }
        n = count
        rememberLoudest(count)
    }

    /**
     * One pass over the bins this frame kept. The scratch used by [slide] is not
     * touched: this only reads [shown], and it runs after the slide has finished.
     */
    private fun rememberLoudest(count: Int) {
        var hi = FirmwareLook.S_MIN
        for (i in 0 until count) {
            val v = shown[i]
            if (v > hi) hi = v
        }
        loud = hi
    }

    /** Linear sample, the same rule as [WaterfallDisplay.sampleBin]. */
    private fun read(values: FloatArray, index: Float): Float? {
        if (n == 0) return null
        if (index < 0f || index > n.toFloat()) return null
        if (n == 1) return values[0]
        val clamped = index.coerceAtMost((n - 1).toFloat())
        val i = clamped.toInt().coerceIn(0, n - 2)
        val frac = clamped - i
        return values[i] + (values[i + 1] - values[i]) * frac
    }

    /**
     * Move [values] by [shift] bins. Positive shift follows a tune up: what was on the
     * right slides left, and the side that just entered the span starts at [empty].
     * The scratch is the one allocated with this trace.
     */
    private fun slide(values: FloatArray, shift: Int, count: Int, empty: Float) {
        if (shift == 0) return
        if (shift >= count || shift <= -count) {
            values.fill(empty, 0, count)
            return
        }
        if (shift > 0) {
            values.copyInto(scratch, destinationOffset = 0, startIndex = shift, endIndex = count)
            scratch.copyInto(values, destinationOffset = 0, endIndex = count - shift)
            values.fill(empty, count - shift, count)
        } else {
            val s = -shift
            values.copyInto(scratch, destinationOffset = 0, startIndex = 0, endIndex = count - s)
            scratch.copyInto(values, destinationOffset = s, endIndex = count - s)
            values.fill(empty, 0, s)
        }
    }

    private fun slideTimes(shift: Int, count: Int, nowMs: Long) {
        if (shift == 0) return
        if (shift >= count || shift <= -count) {
            raisedAt.fill(nowMs, 0, count)
            return
        }
        if (shift > 0) {
            raisedAt.copyInto(timeScratch, destinationOffset = 0, startIndex = shift, endIndex = count)
            timeScratch.copyInto(raisedAt, destinationOffset = 0, endIndex = count - shift)
            raisedAt.fill(nowMs, count - shift, count)
        } else {
            val s = -shift
            raisedAt.copyInto(timeScratch, destinationOffset = 0, startIndex = 0, endIndex = count - s)
            timeScratch.copyInto(raisedAt, destinationOffset = s, endIndex = count - s)
            raisedAt.fill(nowMs, 0, s)
        }
    }
}
