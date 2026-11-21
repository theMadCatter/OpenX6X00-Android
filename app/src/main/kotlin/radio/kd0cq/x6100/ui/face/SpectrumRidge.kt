// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The 3D spectrum Chris Fink (KD0CQ) asked for, beside the 2D trace.
//
// Two radios set the look. AetherSDR draws a dense green-yellow lawn: a solid
// surface, newest ridge in front, colour by level. The Yaesu FT-710 / FTDX10
// 3DSS (3-Dimension Spectrum Stream) uses the same axes — frequency across,
// strength up, time receding to a vanishing point — with a cyan-to-white
// surface, a strong trapezoid, and faint grid lines on the floor. The manual
// calls the axes out: X frequency, Y intensity, Z time.
//
// The first drawing filled each ridge with black and stroked the crest, so the
// phone showed a teal wireframe. A ridge is a solid wall from its baseline up
// to the signal, opaque, so the nearer wall hides the one behind it.
//
// A later pass squared the noise and sampled 96 points across 1024 bins, so a
// real carrier fell between the samples and the lawn went flat and dark. Each
// column now keeps the strongest bin in its slice. Noise within a few dB of
// the floor is grass, with its height left intact. Anything above that rises
// toward the top of the pane. The back row sits near the top; the vanishing
// point is above the pane.
//
// No Android types. The canvas calls these with buffers it already owns.

package radio.kd0cq.x6100.ui.face

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What shares the scope pane. [BOTH] is the radio's usual stack. [SPECTRUM] gives the
 * trace or the 3D surface the whole pane (scale and band plan stay). [WATERFALL] gives
 * that room to the scrolling history.
 */
enum class ScopeLayout(val chip: String, val stored: String) {
    BOTH("S+W", "both"),
    SPECTRUM("Spec", "spectrum"),
    WATERFALL("WF", "waterfall"),
    ;

    fun next(): ScopeLayout {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        /** A missing preference is the combined scope. */
        fun fromStored(value: String?): ScopeLayout =
            entries.firstOrNull { it.stored == value } ?: BOTH
    }
}

/** Which drawing sits in the spectrum pane. 2D is the trace the radio face already had. */
enum class SpectrumView(val chip: String, val stored: String) {
    TRACE("2D", "2d"),
    RIDGE("3D", "3d"),
    ;

    fun next(): SpectrumView {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        /** An unknown or missing preference is the 2D trace. */
        fun fromStored(value: String?): SpectrumView =
            entries.firstOrNull { it.stored == value } ?: TRACE
    }
}

/**
 * Colour of the solid 3D surface. Independent of the waterfall palette: Gauss is a
 * dark-blue table and would paint the floor navy.
 *
 * [AETHER] is the green-yellow-red lawn. [FT710] is the cyan body and white crest
 * of the FT-710 3DSS. A long-press on the scope chip switches them.
 */
enum class RidgeStyle(val stored: String, val chip: String) {
    AETHER("aether", "3D"),
    FT710("ft710", "FT"),
    ;

    fun next(): RidgeStyle {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        fun fromStored(value: String?): RidgeStyle =
            entries.firstOrNull { it.stored == value } ?: AETHER
    }
}

/**
 * Perspective, level shaping, and colour of one ridge.
 *
 * [projectX] and [projectY] return floats so the canvas can fill a reused buffer.
 * Age 0 is the newest frame, full width along the bottom. Older ages step up
 * and in a little, and [depthT] packs them closer together toward the back, so
 * the comb stays readable: wide gaps in front, tight ones at the far edge.
 * [RISE] puts the oldest baseline near the top of the pane. The line is the
 * picture. The fill under it is a dark veil, not a coloured carpet.
 */
object SpectrumRidge {
    /** Frames drawn. The same count in the short strip and the full-scope pane. */
    const val DEPTH = 64

    /** Frames the ring keeps. Drawing uses [DEPTH]; the extra slots are unused history. */
    const val MAX_DEPTH = 128

    /**
     * Samples across one ridge. A 1024-bin FFT with only a few dozen columns
     * skips a one-bin carrier. Each column still peak-holds the bins it covers,
     * and 256 columns keep that carrier a narrow ridge.
     */
    const val SAMPLES = 256

    /** Slot size. The panel sends WATERFALL_NFFT, which is 1024. */
    const val BINS = 1024

    /** Solid colours a row is split into. One path per colour, not one path per sample. */
    const val COLOR_STEPS = 8

    /** How far the oldest ridge pulls in from each side. Small: the pane stays wide at the back. */
    const val INSET = 0.10f

    /** How far the oldest baseline rises. Near 1, so that edge sits at the top of the pane. */
    const val RISE = 0.92f

    /** How far a full-scale peak on the front ridge rises, as a fraction of the height. */
    const val AMPLITUDE = 0.90f

    /**
     * How much of that lift is kept on the oldest ridge. Small enough that a carrier
     * is still a ridge at the back, large enough that the back does not fill the pane.
     */
    const val DISTANCE_SHRINK = 0.28f

    /**
     * How much brightness the oldest stroke loses. The front row stays at full
     * colour. The back row is about a third, so the comb reads as depth.
     */
    const val FOG = 0.65f

    /**
     * dB above the eased floor that is still noise. A signal past this is left
     * unsmoothed, so a carrier stays a spike instead of a boil.
     */
    const val GRASS_DB = 6f

    /**
     * Height of the top of that noise, as a fraction of a full-scale peak.
     * Small on purpose: the quiet band is a wiggle on the line, not a carpet.
     */
    const val GRASS_HEIGHT = 0.10f

    /**
     * Ages are not spaced evenly. Below 1, the first rows take more of the
     * pane and the last rows squeeze together. 1 would be a ruler.
     */
    const val DEPTH_CURVE = 0.70f

    /** Veil opacity, 0xE6 is 90%. Near enough to opaque that a nearer row hides the one behind it. */
    const val VEIL_ALPHA = 0xE6

    /**
     * Quiet-band span, in dB. A few dB of noise then has visible height. A louder
     * frame opens the span to its own peak, so the peak is not stuck at this size.
     */
    const val MIN_SPAN_DB = 12f

    /** Faint floor grid, ARGB. Drawn over the surface so it stays visible on the sheet. */
    const val GRID_ARGB = 0x5590C8BC.toInt()

    /**
     * How many ridges to draw. Always [DEPTH].
     *
     * A taller pane used to add rows until they touched and read as one sheet.
     * Sixty-four lines, with [depthT] opening gaps at the front, stay a comb
     * in both the short strip and the full-scope view. [spectrumPx] is unused
     * and kept so the call site can still pass the pane.
     */
    fun rowsForHeight(spectrumPx: Float): Int {
        if (spectrumPx < 0f) return DEPTH
        return DEPTH
    }

    /** 0 at the newest row, 1 at the oldest, compressed toward the back. */
    fun depthT(age: Int, depth: Int): Float {
        if (depth <= 1) return 0f
        val u = age.coerceIn(0, depth - 1) / (depth - 1).toFloat()
        return u.pow(DEPTH_CURVE)
    }

    /**
     * Peak fraction for a pane of [spectrumPx]. The combined pane uses [AMPLITUDE].
     * Extra height raises it a little, capped, so a full-scope peak is taller in pixels
     * than a stretched copy of the short plot and still leaves sky above the crest.
     */
    fun amplitudeFor(spectrumPx: Float): Float {
        val reference = 140f
        if (spectrumPx <= reference) return AMPLITUDE
        val extra = ((spectrumPx - reference) / 2000f).coerceIn(0f, 0.10f)
        return AMPLITUDE + extra
    }

    fun projectX(
        i: Int,
        sampleCount: Int,
        age: Int,
        depth: Int,
        width: Float,
        inset: Float = INSET,
    ): Float {
        if (width <= 0f || sampleCount <= 1 || depth <= 1) return 0f
        val t = depthT(age, depth)
        val side = width * inset * t
        val span = (width - 2f * side).coerceAtLeast(0f)
        val along = i.coerceIn(0, sampleCount - 1) / (sampleCount - 1).toFloat()
        return side + along * span
    }

    fun projectY(
        age: Int,
        depth: Int,
        level: Float,
        height: Float,
        rise: Float = RISE,
        amplitude: Float = AMPLITUDE,
        distanceShrink: Float = DISTANCE_SHRINK,
    ): Float {
        if (height <= 0f || depth <= 1) return height.coerceAtLeast(0f)
        val t = depthT(age, depth)
        val baseline = height * (1f - rise * t)
        val lift = height * amplitude * level.coerceIn(0f, 1f) * (1f - distanceShrink * t)
        return (baseline - lift).coerceIn(0f, height)
    }

    /**
     * Height of one bin after the floor is subtracted.
     *
     * [aboveFloorDb] is the bin minus the eased floor. [spanDb] is the eased
     * ceiling minus that floor. The first [GRASS_DB] are drawn in proportion, so
     * the lawn has the texture of the noise instead of a flat sheet. Past that,
     * the level runs to full scale at the ceiling. A carrier at the ceiling is
     * therefore as tall as [AMPLITUDE] allows, not a wrinkle on the grass.
     */
    fun shapeDb(aboveFloorDb: Float, spanDb: Float): Float {
        val above = aboveFloorDb.coerceAtLeast(0f)
        val span = spanDb.coerceAtLeast(MIN_SPAN_DB)
        val grass = GRASS_DB.coerceAtMost(span * 0.5f)
        if (grass <= 0f) return if (above <= 0f) 0f else 1f
        if (above <= grass) return (above / grass) * GRASS_HEIGHT
        val t = ((above - grass) / (span - grass)).coerceIn(0f, 1f)
        return GRASS_HEIGHT + t * (1f - GRASS_HEIGHT)
    }

    /**
     * The loudest bin in one column.
     *
     * The column is a slice of the view. A sample at the column's centre misses
     * a carrier that sits between two centres, which is what a 96-wide ridge did
     * to the signal at the VFO. The slice is read from [rowCentreHz] / [rowWidthHz]
     * because an older row can have been taken on a different span.
     */
    fun columnPeakDb(
        db: ByteArray,
        liveBins: Int,
        column: Int,
        columns: Int,
        viewCentreHz: Int,
        viewWidthHz: Int,
        rowCentreHz: Int,
        rowWidthHz: Int,
    ): Float {
        val n = liveBins.coerceIn(0, db.size)
        if (n == 0 || columns <= 0 || viewWidthHz <= 0 || rowWidthHz <= 0) return Float.NEGATIVE_INFINITY
        val hz0 = edgeHz(column, columns, viewCentreHz, viewWidthHz)
        val hz1 = edgeHz(column + 1, columns, viewCentreHz, viewWidthHz)
        val b0 = WaterfallDisplay.binAtHz(min(hz0, hz1), rowCentreHz, rowWidthHz, n)
        val b1 = WaterfallDisplay.binAtHz(max(hz0, hz1), rowCentreHz, rowWidthHz, n)
        var i0 = floor(b0).toInt()
        var i1 = ceil(b1).toInt() - 1
        if (i1 < i0) i1 = i0
        i0 = i0.coerceIn(0, n - 1)
        i1 = i1.coerceIn(0, n - 1)
        var peak = db[i0].toInt()
        for (i in i0..i1) {
            val v = db[i].toInt()
            if (v > peak) peak = v
        }
        return peak.toFloat()
    }

    /** Left edge of column [edge], in Hz, across [columns] columns of the view. */
    private fun edgeHz(edge: Int, columns: Int, centreHz: Int, widthHz: Int): Float {
        val left = centreHz - widthHz / 2f
        return left + edge.coerceIn(0, columns).toFloat() / columns * widthHz
    }

    /**
     * Smooth the grass only. A column above [keepAbove] is a signal and is copied
     * unchanged, including its neighbours' average, so the ridge stays one column wide.
     */
    fun smoothSpatial(src: FloatArray, dst: FloatArray, count: Int, keepAbove: Float = GRASS_HEIGHT) {
        if (count <= 0) return
        if (count == 1) {
            dst[0] = src[0]
            return
        }
        dst[0] = if (src[0] > keepAbove) src[0] else src[0] * 0.72f + (if (src[1] <= keepAbove) src[1] else src[0]) * 0.28f
        for (i in 1 until count - 1) {
            val cur = src[i]
            if (cur > keepAbove) {
                dst[i] = cur
                continue
            }
            var sum = cur
            var weight = 1f
            val prev = src[i - 1]
            val next = src[i + 1]
            if (prev <= keepAbove) {
                sum += prev
                weight += 1f
            }
            if (next <= keepAbove) {
                sum += next
                weight += 1f
            }
            val avg = sum / weight
            dst[i] = cur * 0.45f + avg * 0.55f
        }
        val last = count - 1
        dst[last] = if (src[last] > keepAbove) src[last] else src[last] * 0.72f + (if (src[last - 1] <= keepAbove) src[last - 1] else src[last]) * 0.28f
    }

    /**
     * A light blend of two grass samples. A signal, new or still there, is not
     * pulled toward the other row: that blend was rounding carriers off.
     */
    fun smoothTemporal(current: Float, older: Float, keepAbove: Float = GRASS_HEIGHT): Float =
        if (current > keepAbove || older > keepAbove) current else current * 0.74f + older * 0.26f

    fun levelArgb(level: Float, style: RidgeStyle = RidgeStyle.AETHER): Int {
        val stops = if (style == RidgeStyle.FT710) FT_AT else AETHER_AT
        val rgb = if (style == RidgeStyle.FT710) FT_RGB else AETHER_RGB
        val v = level.coerceIn(0f, 1f)
        var seg = 0
        while (seg < stops.lastIndex && v > stops[seg + 1]) seg++
        val span = (stops[seg + 1] - stops[seg]).coerceAtLeast(0.0001f)
        val t = ((v - stops[seg]) / span).coerceIn(0f, 1f)
        return 0xFF000000.toInt() or lerpRgb(rgb[seg], rgb[seg + 1], t)
    }

    /** Bucket for one solid fill. Neighbouring columns of one colour share a path. */
    fun colorStep(level: Float): Int =
        (level.coerceIn(0f, 1f) * (COLOR_STEPS - 1)).roundToInt().coerceIn(0, COLOR_STEPS - 1)

    fun stepArgb(step: Int, style: RidgeStyle = RidgeStyle.AETHER): Int =
        levelArgb(step.coerceIn(0, COLOR_STEPS - 1) / (COLOR_STEPS - 1).toFloat(), style)

    /** Farther strokes are dimmer. The front row is unchanged. The oldest keeps about a third. */
    fun fogArgb(argb: Int, age: Int, depth: Int): Int {
        if (depth <= 1) return argb
        return scaleRgb(argb, 1f - FOG * depthT(age, depth))
    }

    /**
     * The fill under one stroke. Dark, 90% opaque, with a slight tint so a nearer
     * row hides the lines behind it without painting a coloured sheet.
     */
    fun veilArgb(style: RidgeStyle = RidgeStyle.AETHER): Int {
        val rgb = if (style == RidgeStyle.FT710) 0x070E16 else 0x071412
        return (VEIL_ALPHA shl 24) or rgb
    }

    /** The label on the scope chip. 2D, the Aether 3D, or the FT-710 style. */
    fun chipLabel(view: SpectrumView, style: RidgeStyle): String =
        if (view == SpectrumView.TRACE) SpectrumView.TRACE.chip else style.chip

    private fun scaleRgb(argb: Int, gain: Float): Int {
        val g = gain.coerceIn(0f, 1f)
        var rgb = 0
        for (shift in intArrayOf(0, 8, 16)) {
            val mixed = (((argb shr shift) and 0xFF) * g).toInt().coerceIn(0, 255)
            rgb = rgb or (mixed shl shift)
        }
        return 0xFF000000.toInt() or rgb
    }

    private fun lerpRgb(from: Int, to: Int, t: Float): Int {
        var rgb = 0
        for (shift in intArrayOf(0, 8, 16)) {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            val mixed = (a + (b - a) * t).toInt().coerceIn(0, 255)
            rgb = rgb or (mixed shl shift)
        }
        return rgb
    }

    private val AETHER_AT = floatArrayOf(0f, 0.16f, 0.38f, 0.58f, 0.78f, 1f)

    /**
     * Teal-green on the noise, green through the middle, yellow, then orange-red
     * on a real peak. The lime and chartreuse stops are what flooded the pane.
     */
    private val AETHER_RGB = intArrayOf(0x1A7A68, 0x1E9A58, 0x3CB04A, 0xE0C42E, 0xF08818, 0xE83014)

    private val FT_AT = floatArrayOf(0f, 0.22f, 0.48f, 0.74f, 1f)

    /** Cyan strokes on the noise, running up to a white peak. The FT-710 3DSS. */
    private val FT_RGB = intArrayOf(0x1A6A90, 0x38B0D4, 0x90E4F6, 0xE8F8FF, 0xFFFFFF)
}

/**
 * The 3D vertical scale, separate from the waterfall's window.
 *
 * The floor follows the quiet end of the frame. The ceiling follows the loud
 * end, and it rises quickly so a new carrier is not clipped while the scale
 * catches up. It falls slowly, so one quiet frame does not collapse the peaks.
 * A quiet band is held open by [SpectrumRidge.MIN_SPAN_DB], which gives the
 * grass room to show a few dB of texture.
 *
 * The histogram is the one allocated here. [update] does not allocate.
 */
class RidgeRange {
    var floorDb: Float = -120f
        private set
    var ceilingDb: Float = -120f + SpectrumRidge.MIN_SPAN_DB
        private set

    private val hist = IntArray(256)
    private var primed = false

    fun reset() {
        primed = false
    }

    fun update(db: ByteArray, length: Int): Pair<Float, Float> {
        val n = length.coerceIn(0, db.size)
        if (n == 0) return floorDb to ceilingDb
        hist.fill(0)
        var maxDb = -128
        for (i in 0 until n) {
            val v = db[i].toInt()
            hist[v + 128]++
            if (v > maxDb) maxDb = v
        }
        val noise = percentile(n, 0.12f).toFloat()
        val loud = max(percentile(n, 0.998f), maxDb).toFloat()
        val floorTarget = noise
        val ceilTarget = max(loud, floorTarget + SpectrumRidge.MIN_SPAN_DB)
        if (!primed) {
            floorDb = floorTarget
            ceilingDb = ceilTarget
            primed = true
        } else {
            floorDb = glide(floorDb, floorTarget, FOLLOW_FLOOR)
            val follow = if (ceilTarget > ceilingDb) FOLLOW_UP else FOLLOW_DOWN
            ceilingDb = glide(ceilingDb, ceilTarget, follow)
            if (ceilingDb < floorDb + SpectrumRidge.MIN_SPAN_DB) {
                ceilingDb = floorDb + SpectrumRidge.MIN_SPAN_DB
            }
        }
        return floorDb to ceilingDb
    }

    /** [fraction] of the way through the bins, from the quiet end. */
    private fun percentile(n: Int, fraction: Float): Int {
        val target = ((n - 1) * fraction).roundToInt().coerceIn(0, n - 1)
        var seen = 0
        for (i in hist.indices) {
            seen += hist[i]
            if (seen > target) return i - 128
        }
        return 127
    }

    private fun glide(current: Float, target: Float, follow: Float): Float {
        if (current == target) return current
        return current + (target - current) * follow
    }

    private companion object {
        const val FOLLOW_FLOOR = 0.22f
        const val FOLLOW_UP = 0.75f
        const val FOLLOW_DOWN = 0.08f
    }
}

/**
 * The last [depth] spectra, newest overwritten in a circle.
 *
 * The slots are allocated once. [push] copies bytes into the next slot, so a frame
 * does not allocate a buffer. [dbAt] is that slot: only the first [lengthAt] bytes
 * are the frame, and the caller must not keep the array past the next [push] of the
 * same age.
 */
class SpectrumRidgeRing(
    val depth: Int = SpectrumRidge.DEPTH,
    val bins: Int = SpectrumRidge.BINS,
) {
    private val db = Array(depth) { ByteArray(bins) }
    private val lengths = IntArray(depth)
    private val centres = IntArray(depth)
    private val widths = IntArray(depth)
    private var next = 0

    var count: Int = 0
        private set

    fun push(source: ByteArray, centreHz: Int, widthHz: Int) {
        val slot = db[next]
        val n = source.size.coerceAtMost(slot.size)
        if (n > 0) source.copyInto(slot, endIndex = n)
        lengths[next] = n
        centres[next] = centreHz
        widths[next] = widthHz
        next = (next + 1) % depth
        if (count < depth) count++
    }

    /** [age] 0 is the frame just pushed. Ages at or past [count] are not a frame. */
    fun lengthAt(age: Int): Int = lengths[slot(age)]

    fun centreAt(age: Int): Int = centres[slot(age)]

    fun widthAt(age: Int): Int = widths[slot(age)]

    fun dbAt(age: Int): ByteArray = db[slot(age)]

    private fun slot(age: Int): Int {
        val known = age.coerceIn(0, (count - 1).coerceAtLeast(0))
        return (next - 1 - known).mod(depth)
    }
}

/**
 * Reused sample buffers for one ridge. Created once with the pane.
 *
 * [level] is the shaped level that gets drawn. [shaped] is the spatial pass.
 * [older] is the previous row's spatial pass, for the temporal blend.
 */
class RidgeScratch(samples: Int = SpectrumRidge.SAMPLES) {
    val x = FloatArray(samples)
    val y = FloatArray(samples)
    val level = FloatArray(samples)
    val shaped = FloatArray(samples)
    val older = FloatArray(samples)
}
