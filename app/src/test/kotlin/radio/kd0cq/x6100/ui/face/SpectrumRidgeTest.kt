// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The 3D spectrum's projection and the ring that holds its frames. The drawing is
// the phone's canvas; these tests pin the geometry the canvas is supposed to follow.

package radio.kd0cq.x6100.ui.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumRidgeTest {

    @Test
    fun twoDIsTheDefaultAndTheChipCycles() {
        assertEquals(SpectrumView.TRACE, SpectrumView.fromStored(null))
        assertEquals(SpectrumView.TRACE, SpectrumView.fromStored("later"))
        assertEquals(SpectrumView.RIDGE, SpectrumView.fromStored("3d"))
        assertEquals(SpectrumView.TRACE, SpectrumView.fromStored("2d"))
        assertEquals(SpectrumView.RIDGE, SpectrumView.TRACE.next())
        assertEquals(SpectrumView.TRACE, SpectrumView.RIDGE.next())
        assertEquals("2D", SpectrumView.TRACE.chip)
        assertEquals("3D", SpectrumView.RIDGE.chip)
    }

    @Test
    fun frontRidgeSpansThePaneAndOlderRidgesRecede() {
        val depth = SpectrumRidge.DEPTH
        val samples = SpectrumRidge.SAMPLES
        val width = 400f
        val height = 120f
        // Newest, level 0: the baseline is the bottom edge, full width.
        assertEquals(0f, SpectrumRidge.projectX(0, samples, age = 0, depth, width), 0.01f)
        assertEquals(width, SpectrumRidge.projectX(samples - 1, samples, age = 0, depth, width), 0.01f)
        assertEquals(height, SpectrumRidge.projectY(age = 0, depth, level = 0f, height), 0.01f)
        // Oldest: inset from both sides, and higher on the screen.
        val backLeft = SpectrumRidge.projectX(0, samples, age = depth - 1, depth, width)
        val backRight = SpectrumRidge.projectX(samples - 1, samples, age = depth - 1, depth, width)
        assertTrue(backLeft > 20f)
        assertTrue(backRight < width - 20f)
        assertTrue(backLeft < backRight)
        val backBase = SpectrumRidge.projectY(age = depth - 1, depth, level = 0f, height)
        assertTrue(backBase < height * 0.5f)
        // Baselines march upward as the frame gets older, so back-to-front drawing covers them.
        var previous = SpectrumRidge.projectY(0, depth, 0f, height)
        for (age in 1 until depth) {
            val y = SpectrumRidge.projectY(age, depth, 0f, height)
            assertTrue("age $age should sit above the newer ridge", y < previous)
            previous = y
        }
    }

    @Test
    fun levelLiftsARidgeAndTheLiftShrinksWithDistance() {
        val depth = SpectrumRidge.DEPTH
        val height = 100f
        val frontBase = SpectrumRidge.projectY(0, depth, 0f, height)
        val frontPeak = SpectrumRidge.projectY(0, depth, 1f, height)
        val backBase = SpectrumRidge.projectY(depth - 1, depth, 0f, height)
        val backPeak = SpectrumRidge.projectY(depth - 1, depth, 1f, height)
        assertTrue(frontPeak < frontBase)
        assertTrue(backPeak < backBase)
        assertTrue(frontBase - frontPeak > backBase - backPeak)
        // A mid sample sits between the two ends of its ridge.
        val left = SpectrumRidge.projectX(0, 5, age = 10, depth, width = 100f)
        val mid = SpectrumRidge.projectX(2, 5, age = 10, depth, width = 100f)
        val right = SpectrumRidge.projectX(4, 5, age = 10, depth, width = 100f)
        assertTrue(left < mid && mid < right)
    }

    @Test
    fun colourRunsFromABrightGreenLawnToARedPeak() {
        val floor = SpectrumRidge.levelArgb(0f)
        val tips = SpectrumRidge.levelArgb(0.20f)
        val peak = SpectrumRidge.levelArgb(1f)
        // The lawn is already bright. A dark floor is what the emulator showed as a flat sheet.
        assertTrue(green(floor) > 160)
        assertTrue(blue(floor) > 100)
        assertTrue(green(floor) > red(floor))
        assertTrue(red(tips) + green(tips) > red(floor) + green(floor))
        assertTrue(red(peak) > 220)
        assertTrue(green(peak) < 80)
        assertTrue(red(peak) > red(tips))
        assertEquals(0, SpectrumRidge.colorStep(0f))
        assertEquals(SpectrumRidge.COLOR_STEPS - 1, SpectrumRidge.colorStep(1f))
        assertEquals(SpectrumRidge.levelArgb(0f), SpectrumRidge.stepArgb(0))
    }

    @Test
    fun ft710StyleIsCyanToWhiteAndTheChipNamesIt() {
        val floor = SpectrumRidge.levelArgb(0f, RidgeStyle.FT710)
        val peak = SpectrumRidge.levelArgb(1f, RidgeStyle.FT710)
        assertTrue(blue(floor) > green(floor))
        assertTrue(blue(floor) > red(floor))
        assertTrue(red(peak) > 230 && green(peak) > 230 && blue(peak) > 230)
        assertEquals(RidgeStyle.FT710, RidgeStyle.fromStored("ft710"))
        assertEquals(RidgeStyle.AETHER, RidgeStyle.fromStored(null))
        assertEquals(RidgeStyle.FT710, RidgeStyle.AETHER.next())
        assertEquals("2D", SpectrumRidge.chipLabel(SpectrumView.TRACE, RidgeStyle.FT710))
        assertEquals("3D", SpectrumRidge.chipLabel(SpectrumView.RIDGE, RidgeStyle.AETHER))
        assertEquals("FT", SpectrumRidge.chipLabel(SpectrumView.RIDGE, RidgeStyle.FT710))
    }

    @Test
    fun grassKeepsItsTextureAndASignalStandsUp() {
        val span = SpectrumRidge.MIN_SPAN_DB
        assertEquals(0f, SpectrumRidge.shapeDb(0f, span), 0.001f)
        // A few dB of noise is a visible change in height, not squared into a sheet.
        val low = SpectrumRidge.shapeDb(1f, span)
        val mid = SpectrumRidge.shapeDb(4f, span)
        assertTrue(mid > low + 0.04f)
        assertTrue(mid < SpectrumRidge.GRASS_HEIGHT)
        // Past the grass, the level is on its way to the top of the scale.
        assertTrue(SpectrumRidge.shapeDb(span, span) > 0.95f)
        var previous = -1f
        var db = 0f
        while (db <= span) {
            val shaped = SpectrumRidge.shapeDb(db, span)
            assertTrue(shaped + 0.001f >= previous)
            previous = shaped
            db += 1f
        }
    }

    @Test
    fun smoothingFlattensTheFloorAndLeavesAPeakAlone() {
        val src = floatArrayOf(0.04f, 0.08f, 0.90f, 0.05f, 0.04f)
        val dst = FloatArray(src.size)
        SpectrumRidge.smoothSpatial(src, dst, src.size)
        // Above the grass the column is copied. Averaging it with the floor made carriers vanish.
        assertEquals(0.90f, dst[2], 0.001f)
        val wobble = floatArrayOf(0.16f, 0.02f, 0.18f)
        val flat = FloatArray(3)
        SpectrumRidge.smoothSpatial(wobble, flat, 3)
        assertTrue(flat[1] > wobble[1])
        assertTrue(flat[1] < wobble[0])
        // A carrier is not blended down into the previous quiet row.
        assertEquals(0.80f, SpectrumRidge.smoothTemporal(0.8f, 0.05f), 0.001f)
        assertTrue(SpectrumRidge.smoothTemporal(0.10f, 0.02f) in 0.04f..0.09f)
    }

    @Test
    fun fartherRowsAreDarkerAndTheTrapezoidIsDeep() {
        val front = SpectrumRidge.fogArgb(SpectrumRidge.levelArgb(0.5f), age = 0, SpectrumRidge.DEPTH)
        val back = SpectrumRidge.fogArgb(SpectrumRidge.levelArgb(0.5f), age = SpectrumRidge.DEPTH - 1, SpectrumRidge.DEPTH)
        val frontSum = red(front) + green(front) + blue(front)
        val backSum = red(back) + green(back) + blue(back)
        // The far row dims, and it stays about half as bright. Near-black fog hid the lawn.
        assertTrue(backSum < frontSum)
        assertTrue(backSum > frontSum * 0.45f)
        assertTrue(backSum < frontSum * 0.60f)
        // Alpha stays solid. A transparent wall would show the ridge behind it.
        assertEquals(0xFF, (front ushr 24) and 0xFF)
        assertEquals(0xFF, (back ushr 24) and 0xFF)
        // Modest inset, and the oldest baseline near the top, so the vanishing point is above the pane.
        assertTrue(SpectrumRidge.INSET in 0.05f..0.16f)
        assertTrue(SpectrumRidge.RISE >= 0.88f)
        val backBase = SpectrumRidge.projectY(SpectrumRidge.DEPTH - 1, SpectrumRidge.DEPTH, 0f, 100f)
        assertTrue(backBase < 14f)
        assertTrue(SpectrumRidge.AMPLITUDE >= 0.85f)
        assertEquals(64, SpectrumRidge.DEPTH)
    }

    @Test
    fun aCarrierThirtyDbAboveTheFloorReachesMostOfThePane() {
        // One bin, 30 dB above a flat floor, the way the VFO carrier arrives.
        // Default settings: the first range update, the front ridge, no manual gain.
        val bins = 256
        val columns = 64
        val db = ByteArray(bins) { (-110).toByte() }
        db[100] = (-80).toByte()
        val range = RidgeRange()
        val (floor, ceiling) = range.update(db, bins)
        val centre = 14_100_000
        val width = 100_000
        var best = 0f
        for (column in 0 until columns) {
            val peak = SpectrumRidge.columnPeakDb(
                db, bins, column, columns, centre, width, centre, width,
            )
            val level = SpectrumRidge.shapeDb(peak - floor, ceiling - floor)
            if (level > best) best = level
        }
        val height = 400f
        val y = SpectrumRidge.projectY(
            age = 0,
            depth = SpectrumRidge.DEPTH,
            level = best,
            height = height,
            amplitude = SpectrumRidge.amplitudeFor(height),
        )
        assertTrue((height - y) / height >= 0.60f)
        // A column that does not contain the bin stays in the grass.
        val quiet = SpectrumRidge.columnPeakDb(db, bins, 0, columns, centre, width, centre, width)
        assertTrue(SpectrumRidge.shapeDb(quiet - floor, ceiling - floor) < SpectrumRidge.GRASS_HEIGHT + 0.02f)
        assertTrue(best > 0.9f)
    }

    @Test
    fun aFullScopePaneAddsRowsAndRaisesThePeak() {
        // A short combined pane keeps the usual depth. The spectrum-only pane is several
        // times taller: it draws more ridges, and a full-scale peak is taller in pixels,
        // while the peak fraction itself barely moves. The new height is rows, not a blow-up.
        val short = 90f
        val tall = 420f
        assertEquals(SpectrumRidge.DEPTH, SpectrumRidge.rowsForHeight(short))
        val tallRows = SpectrumRidge.rowsForHeight(tall)
        assertTrue(tallRows > SpectrumRidge.DEPTH)
        assertTrue(tallRows <= SpectrumRidge.MAX_DEPTH)
        val shortLift = short * SpectrumRidge.amplitudeFor(short)
        val tallLift = tall * SpectrumRidge.amplitudeFor(tall)
        assertTrue(tallLift > shortLift * 3f)
        assertTrue(SpectrumRidge.amplitudeFor(tall) - SpectrumRidge.amplitudeFor(short) < 0.12f)
        val shortPeak = SpectrumRidge.projectY(0, SpectrumRidge.rowsForHeight(short), 1f, short, amplitude = SpectrumRidge.amplitudeFor(short))
        val tallPeak = SpectrumRidge.projectY(0, tallRows, 1f, tall, amplitude = SpectrumRidge.amplitudeFor(tall))
        assertTrue(tall - tallPeak > (short - shortPeak) * 3f)
    }

    @Test
    fun ringKeepsTheNewestFramesAndDoesNotShareTheCallerBuffer() {
        val ring = SpectrumRidgeRing(depth = 4, bins = 8)
        val quiet = ByteArray(8) { -100 }
        val loud = ByteArray(8) { -100 }
        loud[3] = -20
        ring.push(quiet, centreHz = 1_000, widthHz = 1_000)
        ring.push(loud, centreHz = 2_000, widthHz = 500)
        assertEquals(2, ring.count)
        assertEquals(-20, ring.dbAt(0)[3].toInt())
        assertEquals(2_000, ring.centreAt(0))
        assertEquals(500, ring.widthAt(0))
        assertEquals(8, ring.lengthAt(0))
        assertEquals(-100, ring.dbAt(1)[3].toInt())
        assertEquals(1_000, ring.centreAt(1))
        // The slot is a copy. Changing the array we passed must not change the ridge.
        loud[3] = 0
        assertEquals(-20, ring.dbAt(0)[3].toInt())
        val short = ByteArray(3) { 7 }
        ring.push(short, centreHz = 3, widthHz = 3)
        assertEquals(3, ring.lengthAt(0))
        // Past the live length is not this frame. sampleBin has to be told the length.
        ring.dbAt(0)[7] = 99
        assertNull(WaterfallDisplay.sampleBin(ring.dbAt(0), 7f, bins = ring.lengthAt(0)))
        ring.push(ByteArray(8) { 1 }, centreHz = 4, widthHz = 4)
        ring.push(ByteArray(8) { 2 }, centreHz = 5, widthHz = 5)
        assertEquals(4, ring.count)
        assertEquals(5, ring.centreAt(0))
        assertEquals(4, ring.centreAt(1))
        assertEquals(3, ring.centreAt(2))
        assertEquals(2_000, ring.centreAt(3))
    }

    private fun red(argb: Int): Int = (argb shr 16) and 0xFF

    private fun green(argb: Int): Int = (argb shr 8) and 0xFF

    private fun blue(argb: Int): Int = argb and 0xFF
}
