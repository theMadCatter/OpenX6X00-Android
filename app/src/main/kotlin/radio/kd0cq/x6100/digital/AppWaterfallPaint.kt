// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Before: Ft8Waterfall and AppWaterfallPicture set rowH = paneHeight / frames.
// Three frames on a tall pane were each about 70 px, and the 96-line history
// was still about 7 px a row once it filled. lv_waterfall in app_waterfall.c
// (what ft8_window.c and js8_window.c embed) scrolls one pixel per line.
// A short history leaves black under the lines. It does not grow them.
//
// One screen pixel is the row. One dp would be about three pixels on the
// S24 and would bring that blockiness back, so the row stays one pixel.
// Newest is y = 0. Older lines move down. A y with no line yet stays empty.

package radio.kd0cq.x6100.digital

object AppWaterfallPaint {
    /** lv_waterfall's line is one pixel. The painter must not divide the pane by the line count. */
    const val ROW_PX = 1

    /**
     * Lines kept so a phone pane can fill at [ROW_PX].
     *
     * A portrait FT8 waterfall on a 1080×2340 screen is under 2048 px once
     * the lane, the keys and the system bars are out of it. 1024 would still
     * leave the bottom of that pane black. 96 was the old cap, and it is why
     * a full history was stretched.
     */
    const val KEPT = 2048

    /**
     * Which stored frame paints pixel row [y], or −1 when that pixel is still black.
     *
     * [frameCount] is an oldest-first list, the order the socket appends.
     * y = 0 is the newest frame. The result does not depend on the pane height:
     * a short list occupies the top [frameCount] pixels and nothing else.
     */
    fun sourceIndex(y: Int, frameCount: Int): Int {
        if (y < 0 || frameCount <= 0 || y >= frameCount) return -1
        return frameCount - 1 - y
    }

    /**
     * The bin that owns pixel [x].
     *
     * The old rects started at `bin * (width / bins)` and were that wide.
     * The bin drawn last is the one that has already started at [x], which
     * is `floor(x / binWidth)`. The extra pixel those rects overlapped is
     * covered by the next bin, so it does not change this index.
     */
    fun binIndex(x: Int, width: Int, bins: Int): Int {
        if (width <= 0 || bins <= 1 || x <= 0) return 0
        val binW = width.toFloat() / bins
        if (binW <= 0f) return 0
        return (x / binW).toInt().coerceIn(0, bins - 1)
    }

    /**
     * Gauss table index for a dB value. The table is 256 entries. The app
     * waterfall runs from the noise floor to 30 dB over it, the same window
     * ft8_window.c hands the palette. Below the floor stays 0. Above 30
     * stays the last entry.
     */
    fun gaussLevel(db: Int, maxDb: Int = 30): Int {
        if (maxDb <= 0) return 0
        return db.coerceIn(0, maxDb) * 255 / maxDb
    }

    /**
     * How many frames [next] added onto [previous], or −1 when the list was
     * replaced and the bitmap ring has to be built again.
     *
     * The socket appends one frame and, at the cap, drops the oldest. Both
     * of those share the frame objects, so the check is identity, not a
     * copy of the bytes. A cleared waterfall comes back as −1.
     */
    fun addedCount(previous: List<*>, next: List<*>): Int {
        if (previous.isEmpty() || next.isEmpty()) return -1
        if (next.size >= previous.size) {
            var prefix = true
            for (i in previous.indices) {
                if (previous[i] !== next[i]) {
                    prefix = false
                    break
                }
            }
            if (prefix) return next.size - previous.size
        }
        val maxShift = minOf(8, previous.size, next.size)
        for (shift in 1..maxShift) {
            val overlap = minOf(previous.size - shift, next.size - shift)
            if (overlap <= 0) continue
            var match = true
            for (i in 0 until overlap) {
                if (next[i] !== previous[i + shift]) {
                    match = false
                    break
                }
            }
            if (!match) continue
            val fresh = next.size - overlap
            if (fresh == shift) return shift
        }
        return -1
    }
}

/**
 * Where a lane label sits, and which row it may use.
 *
 * Every call was placed at y = 0. Two stations a few hertz apart painted
 * on top of each other, which on the landscape strip read as one blob at
 * the top of the window. The first label in the list wins a collision.
 * Calls to us are already first in [ft8Heard].
 */
fun laneEdge(fraction: Float, textWidth: Float, laneWidth: Float): Pair<Float, Float> {
    if (laneWidth <= 0f || textWidth <= 0f) return 0f to textWidth.coerceAtLeast(0f)
    val left = ((laneWidth - textWidth).coerceAtLeast(0f) * fraction.coerceIn(0f, 1f))
    return left to left + textWidth
}

/** Row index for each label, or −1 when every row is already taken there. */
fun laneRows(left: FloatArray, right: FloatArray, rows: Int, gap: Float = 2f): IntArray {
    val out = IntArray(left.size) { -1 }
    if (rows <= 0) return out
    val placed = Array(rows) { ArrayList<Pair<Float, Float>>() }
    for (i in left.indices) {
        val a = left[i]
        val b = right[i]
        for (row in 0 until rows) {
            val boxes = placed[row]
            if (boxes.any { b + gap > it.first && a < it.second + gap }) continue
            boxes += a to b
            out[i] = row
            break
        }
    }
    return out
}
