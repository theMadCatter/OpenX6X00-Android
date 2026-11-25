// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The app waterfall used to divide the pane by the number of lines it had.
// These lock the radio's rule: one pixel a line, newest at the top, and a
// pixel with no line left empty. The bin columns stay the ones the rects used.

package radio.kd0cq.x6100.digital

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import radio.kd0cq.x6100.net.AppWaterfallFrame

class AppWaterfallPaintTest {

    @Test
    fun onePixelPerLineNewestAtTheTopAndTheRestStaysEmpty() {
        assertEquals(1, AppWaterfallPaint.ROW_PX)
        assertTrue(AppWaterfallPaint.KEPT >= 1024)
        // Three lines on a 100 px pane used to be ~33 px each. They now occupy
        // y = 0, 1 and 2, and y = 3 is empty whatever the pane height is.
        assertEquals(2, AppWaterfallPaint.sourceIndex(0, 3))
        assertEquals(1, AppWaterfallPaint.sourceIndex(1, 3))
        assertEquals(0, AppWaterfallPaint.sourceIndex(2, 3))
        assertEquals(-1, AppWaterfallPaint.sourceIndex(3, 3))
        assertEquals(-1, AppWaterfallPaint.sourceIndex(99, 3))
        assertEquals(-1, AppWaterfallPaint.sourceIndex(0, 0))
    }

    @Test
    fun horizontalBinsStayTheColumnsTheRectsUsed() {
        // width 10, 5 bins: each bin is 2 px. Pixel 0 is bin 0, pixel 2 is bin 1.
        assertEquals(0, AppWaterfallPaint.binIndex(0, 10, 5))
        assertEquals(0, AppWaterfallPaint.binIndex(1, 10, 5))
        assertEquals(1, AppWaterfallPaint.binIndex(2, 10, 5))
        assertEquals(4, AppWaterfallPaint.binIndex(9, 10, 5))
        assertEquals(0, AppWaterfallPaint.binIndex(0, 10, 1))
        assertEquals(0, AppWaterfallPaint.gaussLevel(0))
        assertEquals(127, AppWaterfallPaint.gaussLevel(15))
        assertEquals(255, AppWaterfallPaint.gaussLevel(30))
        assertEquals(0, AppWaterfallPaint.gaussLevel(-4))
        assertEquals(255, AppWaterfallPaint.gaussLevel(80))
    }

    @Test
    fun aNewLineIsOneAddedFrameAndAClearedListIsRebuilt() {
        val a = line(1)
        val b = line(2)
        val c = line(3)
        val grown = listOf(a, b, c)
        assertEquals(1, AppWaterfallPaint.addedCount(listOf(a, b), grown))
        assertEquals(0, AppWaterfallPaint.addedCount(grown, grown))
        // At the cap the oldest object is dropped and one new object is appended.
        val d = line(4)
        val e = line(5)
        assertEquals(1, AppWaterfallPaint.addedCount(listOf(a, b, c), listOf(b, c, d)))
        assertEquals(2, AppWaterfallPaint.addedCount(listOf(a, b, c), listOf(c, d, e)))
        assertEquals(-1, AppWaterfallPaint.addedCount(grown, listOf(line(9))))
        assertEquals(-1, AppWaterfallPaint.addedCount(emptyList<AppWaterfallFrame>(), grown))
    }

    @Test
    fun laneLabelsThatShareAnEdgeTakeTheNextRow() {
        val left = floatArrayOf(0f, 10f, 80f, 8f)
        val right = floatArrayOf(40f, 50f, 120f, 48f)
        val one = laneRows(left, right, rows = 1)
        assertEquals(0, one[0])
        assertEquals(-1, one[1])
        assertEquals(0, one[2])
        assertEquals(-1, one[3])
        val two = laneRows(left, right, rows = 2)
        assertEquals(0, two[0])
        assertEquals(1, two[1])
        assertEquals(0, two[2])
        assertEquals(-1, two[3])
        val edge = laneEdge(0.5f, 20f, 100f)
        assertEquals(40f, edge.first, 0.01f)
        assertEquals(60f, edge.second, 0.01f)
    }

    private fun line(db: Int) = AppWaterfallFrame(bins = 1, lowHz = 0, highHz = 1, db = byteArrayOf(db.toByte()))
}
