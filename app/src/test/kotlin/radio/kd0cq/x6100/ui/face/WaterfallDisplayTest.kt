// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The waterfall's colours, the pixel-to-bin interpolation, and the level glide. These are the
// pieces that used to turn a 1024-bin FFT into a blocky, shimmering bitmap.

package radio.kd0cq.x6100.ui.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterfallDisplayTest {

    @Test
    fun gaussAndClassicMatchTheFirmwareTables() {
        val gauss = WaterfallDisplay.palette(WfColormap.GAUSS)
        val classic = WaterfallDisplay.palette(WfColormap.CLASSIC)
        assertEquals(256, gauss.size)
        assertEquals(256, classic.size)
        // First and last entries of wf_palettes.c, with the alpha byte a bitmap needs.
        assertEquals(0xFF000021.toInt(), gauss[0])
        assertEquals(0xFFFFFFFF.toInt(), gauss[255])
        assertEquals(0xFF000000.toInt(), classic[0])
        assertEquals(0xFF000004.toInt(), classic[1])
        assertEquals(0xFFFFFFFF.toInt(), classic[255])
        // The two tables are not the same ramp wearing different names.
        assertTrue(gauss[40] != classic[40])
    }

    @Test
    fun rampedPalettesUseTheFirmwareStops() {
        val grey = WaterfallDisplay.palette(WfColormap.GREY)
        val fire = WaterfallDisplay.palette(WfColormap.FIRE)
        val ice = WaterfallDisplay.palette(WfColormap.ICE)
        val rainbow = WaterfallDisplay.palette(WfColormap.RAINBOW)
        assertEquals(0xFF000000.toInt(), grey[0])
        assertEquals(0xFFFFFFFF.toInt(), grey[255])
        // Mid grey is the integer midpoint of black and white, not a rounded float.
        val mid = grey[128]
        val channel = mid and 0xFF
        assertTrue(channel in 120..136)
        assertEquals(0xFF000000.toInt(), fire[0])
        assertEquals(0xFFFFFFFF.toInt(), fire[255])
        assertEquals(0xFF000000.toInt(), ice[0])
        assertEquals(0xFFFFFFFF.toInt(), ice[255])
        assertEquals(0xFF000000.toInt(), rainbow[0])
        // Rainbow's strong end is red, the last stop, not white.
        assertEquals(0xFFFF0000.toInt(), rainbow[255])
        // A tap walks the list the radio shows, and comes back around.
        assertEquals(WfColormap.GAUSS, WfColormap.CLASSIC.next())
        assertEquals(WfColormap.CLASSIC, WfColormap.RAINBOW.next())
    }

    @Test
    fun pixelsInterpolateBetweenBinsInsteadOfStepping() {
        val db = byteArrayOf(0, 100)
        val mid = WaterfallDisplay.sampleBin(db, 0.5f)
        assertEquals(50f, mid!!, 0.01f)
        assertEquals(0f, WaterfallDisplay.sampleBin(db, 0f)!!, 0.01f)
        assertEquals(100f, WaterfallDisplay.sampleBin(db, 1f)!!, 0.01f)
        // Outside the row is "no data", not a smear of the edge bin.
        assertNull(WaterfallDisplay.sampleBin(db, -0.1f))
        assertNull(WaterfallDisplay.sampleBin(db, 2.1f))

        // bin 0 sits on the left edge of the span, bin n on the right edge.
        assertEquals(0f, WaterfallDisplay.binAtHz(500f, centreHz = 1000, widthHz = 1000, bins = 10), 0.01f)
        assertEquals(5f, WaterfallDisplay.binAtHz(1000f, 1000, 1000, 10), 0.01f)
        assertEquals(10f, WaterfallDisplay.binAtHz(1500f, 1000, 1000, 10), 0.01f)

        // A pixel in the middle of a two-bin row is between the bins, not a copy of one of them.
        val colors = IntArray(256) { index -> index }
        val left = WaterfallDisplay.colorAt(
            db = db,
            rowCentreHz = 1000,
            rowWidthHz = 1000,
            viewCentreHz = 1000,
            viewWidthHz = 1000,
            pixel = 0,
            pixels = 8,
            floor = 0,
            ceiling = 100,
            colors = colors,
        )
        // Pixel 3 of 8 sits between the two bins. Pixel 4 is already on the last bin.
        val center = WaterfallDisplay.colorAt(
            db = db,
            rowCentreHz = 1000,
            rowWidthHz = 1000,
            viewCentreHz = 1000,
            viewWidthHz = 1000,
            pixel = 3,
            pixels = 8,
            floor = 0,
            ceiling = 100,
            colors = colors,
        )
        assertTrue(center in (left + 1)..254)
        // The same bins, drawn while the view has moved on, do not paint the old edge across the new span.
        val shifted = WaterfallDisplay.colorAt(
            db = db,
            rowCentreHz = 1000,
            rowWidthHz = 1000,
            viewCentreHz = 3000,
            viewWidthHz = 1000,
            pixel = 4,
            pixels = 8,
            floor = 0,
            ceiling = 100,
            colors = colors,
        )
        assertEquals(0, shifted)
    }

    @Test
    fun paletteIndexIsTheFirmwareScale() {
        assertEquals(0, WaterfallDisplay.paletteIndex(-110f, floor = -110, ceiling = -74))
        assertEquals(255, WaterfallDisplay.paletteIndex(-74f, -110, -74))
        // (18 / 36) * 255 = 127.5, truncated the way the firmware casts to uint8.
        assertEquals(127, WaterfallDisplay.paletteIndex(-92f, -110, -74))
        assertEquals(0, WaterfallDisplay.paletteIndex(-128f, -110, -74))
        assertEquals(255, WaterfallDisplay.paletteIndex(0f, -110, -74))
    }

    @Test
    fun levelGlidesTowardTheNewestRow() {
        val quiet = ByteArray(80) { (-108).toByte() }
        // Half the bins up at −40, so the strong percentile actually moves. One bin would
        // sit past the 99.5th and the window would not notice it.
        val loud = ByteArray(80) { index -> if (index < 40) (-108).toByte() else (-40).toByte() }
        val leveler = WaterfallLeveler(follow = 0.2f)
        val (floor, ceiling) = leveler.update(quiet)
        assertTrue(ceiling - floor >= RadioFace.WATERFALL_MIN_SPAN_DB)
        val (nextFloor, nextCeiling) = leveler.update(loud)
        // The ceiling may rise, but it does not jump the whole way in one frame.
        val raw = RadioFace.waterfallWindow(listOf(loud))
        if (raw.second != ceiling) {
            val moved = kotlin.math.abs(nextCeiling - ceiling)
            val gap = kotlin.math.abs(raw.second - ceiling)
            assertTrue(moved in 1 until gap)
        }
        assertTrue(nextCeiling - nextFloor >= RadioFace.WATERFALL_MIN_SPAN_DB)
        // And it moved toward the new reading, not away from it.
        if (raw.first != floor) {
            assertTrue(kotlin.math.abs(raw.first - nextFloor) < kotlin.math.abs(raw.first - floor))
        }
    }

    @Test
    fun sharpStaysOnAStillScreenAndDropsWhenFramesLag() {
        val still = listOf(1000L, 980L, 1100L, 1020L)
        assertEquals(ScreenPicture.SHARP, WaterfallDisplay.nextScreenPicture(ScreenPicture.SHARP, still))
        val lag = listOf(600L, 700L, 550L, 800L)
        assertEquals(ScreenPicture.FAST, WaterfallDisplay.nextScreenPicture(ScreenPicture.SHARP, lag))
        val late = listOf(1600L, 1800L, 2000L, 1500L)
        assertEquals(ScreenPicture.FAST, WaterfallDisplay.nextScreenPicture(ScreenPicture.SHARP, late))
        // Not enough evidence yet, and a mode the operator picked is left alone.
        assertEquals(ScreenPicture.SHARP, WaterfallDisplay.nextScreenPicture(ScreenPicture.SHARP, listOf(800L, 800L)))
        assertEquals(ScreenPicture.BALANCED, WaterfallDisplay.nextScreenPicture(ScreenPicture.BALANCED, lag))
        val moving = listOf(120L, 140L, 100L, 160L)
        assertEquals(ScreenPicture.SHARP, WaterfallDisplay.nextScreenPicture(ScreenPicture.SHARP, moving))
    }

    @Test
    fun historyFollowsThePaneAndTheDialHasTheRadioMarks() {
        val history = SpectrumHistory(4)
        repeat(6) { history.push(byteArrayOf(it.toByte()), 1, 10) }
        assertEquals(4, history.snapshot().size)
        history.resize(2)
        assertEquals(2, history.capacity)
        assertEquals(2, history.snapshot().size)
        assertEquals(5, history.snapshot()[0].db[0].toInt())
        history.resize(WaterfallDisplay.MAX_ROWS + 40)
        assertEquals(WaterfallDisplay.MAX_ROWS, history.capacity)

        val marks = RadioFace.sMeterMarks()
        assertEquals("S1", marks.first().second)
        assertEquals(0f, marks.first().first, 0.001f)
        assertEquals("+40", marks.last().second)
        assertEquals(1f, marks.last().first, 0.001f)
        assertEquals("9", marks.first { it.second == "9" }.second)
        val s9 = marks.first { it.second == "9" }.first
        assertEquals(((RadioFace.S9_DB - RadioFace.S1_DB) / (RadioFace.S9_40_DB - RadioFace.S1_DB)).toFloat(), s9, 0.001f)
        assertEquals(0b1111111, RadioFace.segmentMask('8'))
        assertEquals(0b0000110, RadioFace.segmentMask('1'))
        assertEquals("TX pwr", RadioFace.compactKeyTitle("TX power"))
        assertEquals("RF", RadioFace.compactKeyTitle("RF gain"))
        assertEquals("Modes", RadioFace.compactKeyTitle("Modes"))
    }

    @Test
    fun shortHistoryFillsThePaneAndAFullHistoryIsOneRowPerPixel() {
        // A 1:1 map left the bottom of a tall pane at the darkest stop until a row existed for
        // every pixel. Stretching the rows that have arrived covers every y, and a history as
        // tall as the pane goes back to one row per pixel.
        assertEquals(0, WaterfallDisplay.historyRowAt(0, 10, 10))
        assertEquals(9, WaterfallDisplay.historyRowAt(9, 10, 10))
        assertEquals(0, WaterfallDisplay.historyRowAt(0, 10, 2))
        assertEquals(1, WaterfallDisplay.historyRowAt(5, 10, 2))
        assertEquals(1, WaterfallDisplay.historyRowAt(9, 10, 2))
        val covered = (0 until 10).map { WaterfallDisplay.historyRowAt(it, 10, 2) }.toSet()
        assertEquals(setOf(0, 1), covered)
        assertEquals(9, WaterfallDisplay.historyRowAt(9, 10, 20))
        assertEquals(0, WaterfallDisplay.historyRowAt(0, 0, 4))
        assertEquals(0, WaterfallDisplay.historyRowAt(3, 10, 0))
    }
}
