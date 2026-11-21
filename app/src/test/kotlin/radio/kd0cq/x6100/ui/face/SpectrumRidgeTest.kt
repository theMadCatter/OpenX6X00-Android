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
    fun colourRunsFromABlueGreenFloorToARedPeak() {
        val floor = SpectrumRidge.levelArgb(0f)
        val body = SpectrumRidge.levelArgb(0.45f)
        val peak = SpectrumRidge.levelArgb(1f)
        // The floor is a deep teal sheet, darker than the green body. The crest is red.
        assertTrue(red(floor) < 40)
        assertTrue(green(floor) > red(floor))
        assertTrue(blue(floor) > red(floor))
        assertTrue(green(body) > green(floor) + 40)
        assertTrue(red(peak) > 220)
        assertTrue(green(peak) < 80)
        assertTrue(red(peak) > red(body))
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
    fun noiseCompressesToASheetAndASignalStandsUp() {
        assertEquals(0f, SpectrumRidge.shapeLevel(0f), 0.001f)
        // Inside the knee the wander is a small fraction of the sheet, not the raw level.
        assertTrue(SpectrumRidge.shapeLevel(0.2f) < SpectrumRidge.shapeLevel(SpectrumRidge.KNEE))
        assertTrue(SpectrumRidge.shapeLevel(0.2f) < 0.12f)
        val sheet = SpectrumRidge.shapeLevel(SpectrumRidge.KNEE)
        assertTrue(sheet in 0.15f..0.25f)
        assertTrue(SpectrumRidge.shapeLevel(0.8f) > sheet + 0.35f)
        assertTrue(SpectrumRidge.shapeLevel(1f) > 0.95f)
        var previous = -1f
        var raw = 0f
        while (raw <= 1f) {
            val shaped = SpectrumRidge.shapeLevel(raw)
            assertTrue(shaped + 0.001f >= previous)
            previous = shaped
            raw += 0.05f
        }
    }

    @Test
    fun smoothingFlattensTheFloorAndKeepsAPeak() {
        val src = floatArrayOf(0.10f, 0.12f, 0.90f, 0.11f, 0.10f)
        val dst = FloatArray(src.size)
        SpectrumRidge.smoothSpatial(src, dst, src.size)
        assertTrue(dst[2] > 0.7f)
        val wobble = floatArrayOf(0.20f, 0.02f, 0.22f)
        val flat = FloatArray(3)
        SpectrumRidge.smoothSpatial(wobble, flat, 3)
        assertTrue(flat[1] > wobble[1])
        assertTrue(flat[1] < wobble[0])
        // A carrier present in both rows stays. A flicker is pulled down.
        assertTrue(SpectrumRidge.smoothTemporal(0.8f, 0.8f) > 0.75f)
        assertTrue(SpectrumRidge.smoothTemporal(0.8f, 0.05f) < 0.7f)
    }

    @Test
    fun fartherRowsAreDarkerAndTheTrapezoidIsDeep() {
        val front = SpectrumRidge.fogArgb(SpectrumRidge.levelArgb(0.5f), age = 0, SpectrumRidge.DEPTH)
        val back = SpectrumRidge.fogArgb(SpectrumRidge.levelArgb(0.5f), age = SpectrumRidge.DEPTH - 1, SpectrumRidge.DEPTH)
        assertTrue(red(front) + green(front) + blue(front) > red(back) + green(back) + blue(back) + 40)
        // Alpha stays solid. A transparent wall would show the ridge behind it.
        assertEquals(0xFF, (front ushr 24) and 0xFF)
        assertEquals(0xFF, (back ushr 24) and 0xFF)
        assertTrue(SpectrumRidge.INSET >= 0.34f)
        assertTrue(SpectrumRidge.AMPLITUDE >= 0.7f)
        assertEquals(64, SpectrumRidge.DEPTH)
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
