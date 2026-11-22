// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The waterfall's colours, the crisp bin mapping, and the level glide. Linear sampling and a
// stretched history used to soften a 1024-bin FFT across the pane.

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
    fun pixelsCopyTheBinUnderThemInsteadOfBlending() {
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

        // Two bins across eight pixels. Each bin is a flat run, not a ramp between them.
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
        val stillLeft = WaterfallDisplay.colorAt(
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
        val right = WaterfallDisplay.colorAt(
            db = db,
            rowCentreHz = 1000,
            rowWidthHz = 1000,
            viewCentreHz = 1000,
            viewWidthHz = 1000,
            pixel = 4,
            pixels = 8,
            floor = 0,
            ceiling = 100,
            colors = colors,
        )
        assertEquals(left, stillLeft)
        assertTrue(right > left)
        assertEquals(
            right,
            WaterfallDisplay.colorAt(db, 1000, 1000, 1000, 1000, 7, 8, 0, 100, colors),
        )
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
    fun aPixelThatCoversSeveralBinsKeepsTheLoudest() {
        // Eight bins into two pixels. The carrier sits off the pixel centre. A sample at the
        // centre would read the quiet neighbour; the pixel keeps the carrier.
        val db = byteArrayOf(1, 90, 2, 3, 4, 5, 6, 7)
        val colors = IntArray(256) { it }
        val left = WaterfallDisplay.crispDb(db, 1000, 1000, 1000, 1000, pixel = 0, pixels = 2)
        val right = WaterfallDisplay.crispDb(db, 1000, 1000, 1000, 1000, pixel = 1, pixels = 2)
        assertEquals(90f, left!!, 0.01f)
        assertEquals(7f, right!!, 0.01f)
        assertEquals(
            WaterfallDisplay.paletteIndex(90f, 0, 100),
            WaterfallDisplay.colorAt(db, 1000, 1000, 1000, 1000, 0, 2, 0, 100, colors),
        )
    }

    @Test
    fun zoomShowsTheBinsInsideTheNarrowerSpan() {
        // A 100 kHz row of 8 bins. The zoomed span is narrower than one bin and sits on the
        // carrier, so every pixel of the pane is that bin. The neighbours do not blend in.
        val db = byteArrayOf(0, 0, 0, 10, 80, 0, 0, 0)
        val wide = 100_000
        val binHz = wide / db.size
        val carrierHz = 14_000_000 - wide / 2 + binHz / 2 + 4 * binHz
        val colours = (0 until 8).map { pixel ->
            WaterfallDisplay.crispDb(
                db,
                rowCentreHz = 14_000_000,
                rowWidthHz = wide,
                viewCentreHz = carrierHz,
                viewWidthHz = binHz / 2,
                pixel = pixel,
                pixels = 8,
            )
        }
        assertTrue(colours.all { it == 80f })
        // The same row at zoom 1: the quiet bin beside the carrier stays itself, not a mix.
        val beside = WaterfallDisplay.crispDb(db, 14_000_000, wide, 14_000_000, wide, pixel = 3, pixels = 8)
        assertEquals(10f, beside!!, 0.01f)
        val carrier = WaterfallDisplay.crispDb(db, 14_000_000, wide, 14_000_000, wide, pixel = 4, pixels = 8)
        assertEquals(80f, carrier!!, 0.01f)
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
        val leveler = WaterfallLeveler()
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
    fun theWaterfallHistorySurvivesATallerPane() {
        // The pane used to own the deque and resize it to its pixel height. Portrait collected
        // a couple of hundred lines, landscape asked for ~900, and the missing ones were gone,
        // so the taller bitmap was a thin band over the floor colour until the radio refilled it.
        val hold = WaterfallHold()
        assertTrue(hold.history.capacity >= 4096)
        val kept = 300
        repeat(kept) { i ->
            hold.accept(byteArrayOf(1), centreHz = 1_000 + i, widthHz = 100_000, sequence = i.toLong())
        }
        assertEquals(kept, hold.history.count())

        // 80 px shows the newest 80. The line that would sit at pixel 80 is still stored.
        val short = hold.linesFor(80)
        assertEquals(80, short.size)
        assertEquals(1_000 + kept - 1, short[0].centreHz)
        val all = hold.history.snapshot()
        assertEquals(1_000 + kept - 1 - 80, all[80].centreHz)
        assertEquals(1_000, all[kept - 1].centreHz)

        // The same holder, now asked for a taller bitmap, still has every line. Collecting the
        // frame that is already current (the pane was recreated) does not scroll them.
        val tall = hold.linesFor(900)
        assertEquals(kept, tall.size)
        assertEquals(short[0].centreHz, tall[0].centreHz)
        assertEquals(1_000, tall[kept - 1].centreHz)
        hold.accept(byteArrayOf(1), 1_000 + kept - 1, 100_000, (kept - 1).toLong())
        assertEquals(kept, hold.history.count())
        assertEquals(1_000, hold.history.snapshot()[kept - 1].centreHz)

        // Shrinking the bitmap leaves the ring alone, so growing it again finds the same lines.
        assertEquals(40, hold.linesFor(40).size)
        assertEquals(kept, hold.history.count())
        val regrown = hold.linesFor(200)
        assertEquals(200, regrown.size)
        assertEquals(tall[199].centreHz, regrown[199].centreHz)
        assertEquals(1_000 + kept - 1, regrown[0].centreHz)
    }

    @Test
    fun historyFollowsThePaneAndTheDialHasTheRadioMarks() {
        val history = SpectrumHistory(4)
        repeat(6) { history.push(byteArrayOf(it.toByte()), 1, 10) }
        assertEquals(4, history.count())
        assertEquals(4, history.snapshot().size)
        assertEquals(5, history.snapshot()[0].db[0].toInt())
        // The ring's cap is the phone's, well above the radio's 480-line screen.
        assertTrue(WaterfallDisplay.MAX_ROWS >= 4096)
        val tall = SpectrumHistory(2000)
        assertEquals(2000, tall.capacity)

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
    fun aLineIsOnePixelAndAShortHistoryDoesNotStretch() {
        assertEquals(0, WaterfallDisplay.historyRowAt(0, 10, 10))
        assertEquals(9, WaterfallDisplay.historyRowAt(9, 10, 10))
        // Two lines on a ten-pixel pane occupy the first two pixels. The rest are empty.
        assertEquals(0, WaterfallDisplay.historyRowAt(0, 10, 2))
        assertEquals(1, WaterfallDisplay.historyRowAt(1, 10, 2))
        assertEquals(-1, WaterfallDisplay.historyRowAt(2, 10, 2))
        assertEquals(-1, WaterfallDisplay.historyRowAt(9, 10, 2))
        // More lines than pixels: the newest pixels of them, still one each.
        assertEquals(9, WaterfallDisplay.historyRowAt(9, 10, 20))
        assertEquals(-1, WaterfallDisplay.historyRowAt(0, 0, 4))
        assertEquals(-1, WaterfallDisplay.historyRowAt(3, 10, 0))
    }
}
