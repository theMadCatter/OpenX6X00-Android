// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The 3D spectrum Chris Fink (KD0CQ) asked for, beside the 2D trace.
//
// AetherSDR draws the last few dozen spectra as ridge lines: the newest is the
// full-width ridge at the front, and older frames step up and in so they recede.
// Height is signal level. Colour runs from a blue-green floor through green and
// yellow to a red peak. This file is that geometry and the ring that holds the
// frames. It has no Android types, so the unit tests can run it on a JVM, and
// the draw code can call it without allocating a point per sample.

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
 * Perspective of one ridge, and the green-yellow-red colour of a level.
 *
 * [projectX] and [projectY] return floats so the canvas can fill a reused buffer.
 * A data class per vertex would be a few thousand allocations every frame, which
 * is the jank this view is written to avoid.
 *
 * Age 0 is the newest frame, drawn full width along the bottom of the pane.
 * Larger ages step toward the top and inward from both sides. A higher [level]
 * lifts the vertex above that age's baseline, and the lift shrinks with age so
 * a peak in the distance is shorter than the same peak in front.
 */
object SpectrumRidge {
    /** Frames kept. Inside the 30–64 the 3D pane was asked to show. */
    const val DEPTH = 48

    /** Samples across one ridge. The same interpolation as the 2D trace, fewer of them. */
    const val SAMPLES = 128

    /** Slot size. The panel sends WATERFALL_NFFT, which is 1024. */
    const val BINS = 1024

    /** How many solid colours a ridge stroke is broken into. Six is enough to show a peak. */
    const val COLOR_STEPS = 6

    /** How far the oldest ridge pulls in from each side, as a fraction of the width. */
    const val INSET = 0.22f

    /** How far the oldest baseline rises from the bottom, as a fraction of the height. */
    const val RISE = 0.62f

    /** How far a full-scale peak on the front ridge rises, as a fraction of the height. */
    const val AMPLITUDE = 0.55f

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
    ): Float {
        if (height <= 0f || depth <= 1) return height.coerceAtLeast(0f)
        val t = age.coerceIn(0, depth - 1) / (depth - 1).toFloat()
        val baseline = height * (1f - rise * t)
        // The 0.45 shrinks the lift as the ridge recedes, so the back stays inside the pane.
        val lift = height * amplitude * level.coerceIn(0f, 1f) * (1f - 0.45f * t)
        return (baseline - lift).coerceIn(0f, height)
    }

    /**
     * Blue-green floor, green, yellow, red peak. This is the AetherSDR ridge, not the
     * waterfall palette: Gauss is a dark-blue table and would paint the floor navy.
     */
    fun levelArgb(level: Float): Int {
        val v = level.coerceIn(0f, 1f)
        var seg = 0
        while (seg < STOP_AT.lastIndex && v > STOP_AT[seg + 1]) seg++
        val span = (STOP_AT[seg + 1] - STOP_AT[seg]).coerceAtLeast(0.0001f)
        val t = ((v - STOP_AT[seg]) / span).coerceIn(0f, 1f)
        return 0xFF000000.toInt() or lerpRgb(STOP_RGB[seg], STOP_RGB[seg + 1], t)
    }

    /** Bucket for a stroke run. Nearby samples of one colour share one path. */
    fun colorStep(level: Float): Int =
        (level.coerceIn(0f, 1f) * (COLOR_STEPS - 1)).roundToInt().coerceIn(0, COLOR_STEPS - 1)

    fun stepArgb(step: Int): Int =
        levelArgb(step.coerceIn(0, COLOR_STEPS - 1) / (COLOR_STEPS - 1).toFloat())

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

    private val STOP_AT = floatArrayOf(0f, 0.20f, 0.48f, 0.74f, 1f)

    /** RGB, no alpha. Floor, green, yellow-green, yellow, red. */
    private val STOP_RGB = intArrayOf(0x0E6E68, 0x2FDC6A, 0xC6E04A, 0xF5C518, 0xFF2D1A)
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

/** Reused sample buffers for one ridge. Created once with the pane, filled every frame. */
class RidgeScratch(samples: Int = SpectrumRidge.SAMPLES) {
    val x = FloatArray(samples)
    val y = FloatArray(samples)
    val level = FloatArray(samples)
}
