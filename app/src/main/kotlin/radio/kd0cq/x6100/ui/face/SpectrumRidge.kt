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
// phone showed a teal wireframe. A ridge is now a solid wall from its baseline
// up to the signal, opaque, so the nearer wall hides the one behind it.
//
// No Android types. The canvas calls these with buffers it already owns.

package radio.kd0cq.x6100.ui.face

import kotlin.math.roundToInt

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
 * Age 0 is the newest frame, full width along the bottom. Larger ages step toward
 * the vanishing point: up, and in from both sides. [INSET] and [RISE] are the
 * trapezoid. [AMPLITUDE] is the vertical exaggeration that turns a carrier into
 * a wall instead of a wrinkle.
 */
object SpectrumRidge {
    /** Frames kept. The FT-710 stream is deep; 64 still fits a phone if each row is one fill per colour. */
    const val DEPTH = 64

    /** Samples across one ridge. Wide enough to be smooth after the spatial pass, few enough to fill quickly. */
    const val SAMPLES = 96

    /** Slot size. The panel sends WATERFALL_NFFT, which is 1024. */
    const val BINS = 1024

    /** Solid colours a row is split into. One path per colour, not one path per sample. */
    const val COLOR_STEPS = 8

    /** How far the oldest ridge pulls in from each side, as a fraction of the width. */
    const val INSET = 0.38f

    /** How far the oldest baseline rises from the bottom, as a fraction of the height. */
    const val RISE = 0.66f

    /** How far a full-scale peak on the front ridge rises, as a fraction of the height. */
    const val AMPLITUDE = 0.78f

    /**
     * How much of that lift is kept on the oldest ridge. Small enough that a carrier
     * is still a ridge at the back, large enough that the back does not fill the pane.
     */
    const val DISTANCE_SHRINK = 0.28f

    /** How much brightness the oldest row loses. Opaque, so a nearer wall still covers it. */
    const val FOG = 0.62f

    /**
     * Levels at or below this, after the floor is subtracted, are noise. They compress
     * into a low sheet. Above it, the level is kept so a real signal stands up.
     */
    const val KNEE = 0.36f

    /** Faint floor grid, ARGB. Drawn over the surface so it stays visible on the sheet. */
    const val GRID_ARGB = 0x5590C8BC.toInt()

    fun projectX(
        i: Int,
        sampleCount: Int,
        age: Int,
        depth: Int,
        width: Float,
        inset: Float = INSET,
    ): Float {
        if (width <= 0f || sampleCount <= 1 || depth <= 1) return 0f
        val t = age.coerceIn(0, depth - 1) / (depth - 1).toFloat()
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
        val t = age.coerceIn(0, depth - 1) / (depth - 1).toFloat()
        val baseline = height * (1f - rise * t)
        val lift = height * amplitude * level.coerceIn(0f, 1f) * (1f - distanceShrink * t)
        return (baseline - lift).coerceIn(0f, height)
    }

    /**
     * Soft knee after the eased floor has already been subtracted.
     *
     * The raw level of a quiet band still wanders across the lower third of the window,
     * and drawing that wander is the scribble. Below [KNEE] the level is squared down
     * into a thin sheet. Above it the rest of the range is kept, so a carrier stays tall.
     */
    fun shapeLevel(raw: Float): Float {
        val v = raw.coerceIn(0f, 1f)
        if (v <= KNEE) {
            val t = v / KNEE
            return FLOOR_SHEET * t * t
        }
        val t = (v - KNEE) / (1f - KNEE)
        return (FLOOR_SHEET + t * (1f - FLOOR_SHEET)).coerceIn(0f, 1f)
    }

    /**
     * Three-tap smooth. A sample that stands well above both neighbours keeps most of
     * its height, so a real peak is not averaged away. The floor, which does not stand
     * up, is what gets flattened.
     */
    fun smoothSpatial(src: FloatArray, dst: FloatArray, count: Int) {
        if (count <= 0) return
        if (count == 1) {
            dst[0] = src[0]
            return
        }
        dst[0] = src[0] * 0.72f + src[1] * 0.28f
        for (i in 1 until count - 1) {
            val prev = src[i - 1]
            val cur = src[i]
            val next = src[i + 1]
            val avg = prev * 0.22f + cur * 0.56f + next * 0.22f
            val neighbors = maxOf(prev, next)
            dst[i] = if (cur > neighbors + 0.15f) cur * 0.85f + avg * 0.15f else avg
        }
        dst[count - 1] = src[count - 1] * 0.72f + src[count - 2] * 0.28f
    }

    /** A light blend with the older row. A carrier in both rows stays; a one-bin flicker drops. */
    fun smoothTemporal(current: Float, older: Float): Float =
        current * 0.74f + older * 0.26f

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

    /** Farther rows are darker. Alpha stays opaque so the fill still hides what is behind it. */
    fun fogArgb(argb: Int, age: Int, depth: Int): Int {
        if (depth <= 1) return argb
        val t = age.coerceIn(0, depth - 1) / (depth - 1).toFloat()
        return scaleRgb(argb, 1f - FOG * t)
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

    /**
     * How high the compressed noise sits, as a fraction of full scale.
     * High enough that the sheet is a visible lawn, low enough that a signal
     * above the knee still stands clear of it.
     */
    private const val FLOOR_SHEET = 0.20f

    private val AETHER_AT = floatArrayOf(0f, 0.16f, 0.38f, 0.60f, 0.80f, 1f)

    /** Deep teal floor, green body, yellow, orange, red peak. The AetherSDR lawn. */
    private val AETHER_RGB = intArrayOf(0x072E36, 0x0E7A4E, 0x3AD15A, 0xE4DC38, 0xF09A1C, 0xFF3A16)

    private val FT_AT = floatArrayOf(0f, 0.18f, 0.42f, 0.68f, 1f)

    /** Navy floor, blue, cyan, white crest. The FT-710 3DSS. */
    private val FT_RGB = intArrayOf(0x04101C, 0x0A3C78, 0x1498C4, 0x8EE6F6, 0xF6FCFF)
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
