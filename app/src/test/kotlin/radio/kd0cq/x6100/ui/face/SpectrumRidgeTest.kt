// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
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
    fun colourRunsFromGreenNoiseToARedPeak() {
        val floor = SpectrumRidge.levelArgb(0f)
        val grass = SpectrumRidge.levelArgb(0.12f)
        val mid = SpectrumRidge.levelArgb(0.40f)
        val peak = SpectrumRidge.levelArgb(1f)
        // Floor strokes are green (#3ccf5a). Teal was the cold cast against AetherSDR.
        assertTrue(green(floor) > red(floor) + 80)
        assertTrue(green(floor) > blue(floor) + 80)
        assertTrue(green(floor) > 180)
        assertTrue(green(grass) > 200 && red(grass) > 80)
        // The middle is yellow-green into yellow, then the peak is orange-red.
        assertTrue(red(mid) > 150 && green(mid) > 180)
        assertTrue(red(peak) > 200)
        assertTrue(green(peak) < 80)
        assertEquals(0, SpectrumRidge.colorStep(0f))
        assertEquals(SpectrumRidge.COLOR_STEPS - 1, SpectrumRidge.colorStep(1f))
        assertEquals(SpectrumRidge.levelArgb(0f), SpectrumRidge.stepArgb(0))
        val veil = SpectrumRidge.veilArgb(RidgeStyle.AETHER)
        val alpha = (veil ushr 24) and 0xFF
        assertTrue(alpha in 0xD9..0xE6)
        assertTrue(red(veil) < 30 && green(veil) < 40 && blue(veil) < 40)
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
        assertTrue(mid > low + 0.03f)
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
        val wobble = floatArrayOf(0.06f, 0.01f, 0.07f)
        val flat = FloatArray(3)
        SpectrumRidge.smoothSpatial(wobble, flat, 3)
        assertTrue(flat[1] > wobble[1])
        assertTrue(flat[1] < wobble[0])
        // A carrier is not blended down into the previous quiet row.
        assertEquals(0.80f, SpectrumRidge.smoothTemporal(0.8f, 0.05f), 0.001f)
        val blended = SpectrumRidge.smoothTemporal(0.05f, 0.01f)
        assertEquals(0.03f, blended, 0.001f)
        // Noise is the mean of this frame and the three behind it. A carrier in
        // that history is left out, so the floor is not pulled up to the peak.
        val older = floatArrayOf(0.90f, 0.02f, 0.02f)
        assertEquals((0.04f + 0.02f + 0.02f) / 3f, SpectrumRidge.smoothTemporal(0.04f, older, 3), 0.001f)
        assertEquals(0.20f, SpectrumRidge.smoothTemporal(0.20f, older, 3), 0.001f)
    }

    @Test
    fun fartherRowsAreDarkerAndTheTrapezoidIsDeep() {
        val front = SpectrumRidge.fogArgb(SpectrumRidge.levelArgb(0.5f), age = 0, SpectrumRidge.DEPTH)
        val back = SpectrumRidge.fogArgb(SpectrumRidge.levelArgb(0.5f), age = SpectrumRidge.DEPTH - 1, SpectrumRidge.DEPTH)
        val frontSum = red(front) + green(front) + blue(front)
        val backSum = red(back) + green(back) + blue(back)
        // Front stays full. The oldest stroke keeps about a third, so the comb has depth.
        assertTrue(backSum > frontSum * 0.28f)
        assertTrue(backSum < frontSum * 0.42f)
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
    fun aTallPaneKeepsSixtyFourRowsAndOpensTheFrontGaps() {
        // More rows fused into a sheet. Both panes draw the same sixty-four, and the
        // gaps at the front are wider than the gaps at the back.
        assertEquals(SpectrumRidge.DEPTH, SpectrumRidge.rowsForHeight(90f))
        assertEquals(SpectrumRidge.DEPTH, SpectrumRidge.rowsForHeight(420f))
        val depth = SpectrumRidge.DEPTH
        val height = 400f
        val frontGap = SpectrumRidge.projectY(0, depth, 0f, height) -
            SpectrumRidge.projectY(1, depth, 0f, height)
        val backGap = SpectrumRidge.projectY(depth - 2, depth, 0f, height) -
            SpectrumRidge.projectY(depth - 1, depth, 0f, height)
        assertTrue(frontGap > backGap * 1.4f)
        val shortLift = 90f * SpectrumRidge.amplitudeFor(90f)
        val tallLift = 420f * SpectrumRidge.amplitudeFor(420f)
        assertTrue(tallLift > shortLift * 3f)
    }

    @Test
    fun theFloorSitsLowInThePane() {
        // Top of the noise band, front row, default amplitude. The carpet was this
        // sitting near a fifth of the pane. It has to stay a small wiggle.
        val height = 400f
        val level = SpectrumRidge.shapeDb(SpectrumRidge.GRASS_DB, SpectrumRidge.MIN_SPAN_DB)
        val y = SpectrumRidge.projectY(
            age = 0,
            depth = SpectrumRidge.DEPTH,
            level = level,
            height = height,
            amplitude = SpectrumRidge.amplitudeFor(height),
        )
        val fraction = (height - y) / height
        assertTrue(fraction <= 0.15f)
        assertTrue(fraction >= 0.04f)
    }

    @Test
    fun theGrassFieldFillsWideAndTallPanesAndTheCarrierStaysProportional() {
        // Landscape spectrum-only is a wide tall pane. Portrait is narrow and tall.
        // The S+W strip is wide and short. The horizon stays near the top of each,
        // and the carrier's fraction of the pane does not jump between them.
        val panes = listOf(900f to 520f, 360f to 140f, 400f to 780f, 640f to 640f)
        val peaks = ArrayList<Float>(panes.size)
        for ((width, height) in panes) {
            val span = SpectrumRidge.fieldSpanFraction(width, height)
            assertTrue("field $width x $height covers $span", span >= 0.90f)
            val peak = SpectrumRidge.carrierFraction(width, height)
            assertTrue("carrier $width x $height is $peak", peak in 0.58f..1f)
            peaks += peak
        }
        assertTrue(peaks.max() - peaks.min() <= 0.12f)
    }

    @Test
    fun aetherGrassIsADenseFieldOfBlades() {
        val depth = SpectrumRidge.GRASS_ROWS
        assertTrue(depth > SpectrumRidge.DEPTH)
        assertTrue(depth <= SpectrumRidge.MAX_DEPTH)
        val height = 400f
        val curve = SpectrumRidge.GRASS_CURVE
        val frontGap = SpectrumRidge.projectY(0, depth, 0f, height, curve = curve) -
            SpectrumRidge.projectY(1, depth, 0f, height, curve = curve)
        val blade = height * SpectrumRidge.amplitudeFor(height) * SpectrumRidge.GRASS_HEIGHT
        // A noise blade covers several row pitches, so the floor does not show between rows.
        assertTrue(blade > frontGap * 4f)
        val split = SpectrumRidge.bladeSplit(baseY = 100f, tipY = 40f)
        assertTrue(split > 40f && split < 70f)
        val fill = SpectrumRidge.grassFillArgb()
        val alpha = (fill ushr 24) and 0xFF
        assertTrue(alpha in 0x30..0x80)
        assertTrue(green(fill) > red(fill) + 40)
        assertTrue(green(fill) > blue(fill) + 40)
        // Yellow through the middle. Red is the top of the ramp, not a mid-level blade.
        val mid = SpectrumRidge.levelArgb(0.45f)
        assertTrue(red(mid) < green(mid) + 40)
        val peak = SpectrumRidge.levelArgb(1f)
        assertTrue(red(peak) > green(peak) + 80)
        val noise = SpectrumRidge.bladeTexture(0, 0.05f)
        assertEquals(noise, SpectrumRidge.bladeTexture(0, 0.05f), 0.0001f)
        assertEquals(0f, SpectrumRidge.bladeTexture(4, 0.5f), 0.0001f)
        assertTrue(kotlin.math.abs(noise) <= 0.05f * 0.26f)
        val samples = (0 until 12).map { SpectrumRidge.bladeTexture(it, 0.05f) }
        assertTrue(samples.max() - samples.min() > 0.005f)
    }

    @Test
    fun aRowIsFrozenOnceItLeavesTheFront() {
        // The boil was every row being rebuilt from the raw bins on the next frame.
        // The snapshot taken at the front has to be the samples still drawn behind it.
        val ring = SpectrumRidgeRing(depth = 6, bins = 16)
        val centre = 14_100_000
        val width = 48_000
        val floor = -110f
        val ceiling = floor + 40f
        val first = ByteArray(16) { -108 }
        first[8] = -90
        ring.push(first, centre, width, floor, ceiling)
        val frozen = ring.bakedAt(0).copyOf()
        val signal = SpectrumRidge.shapeDb(-90f - floor, ceiling - floor)
        assertEquals(signal, frozen[8], 0.001f)
        assertTrue(signal > SpectrumRidge.GRASS_HEIGHT)
        val second = ByteArray(16) { -104 }
        second[8] = -80
        ring.push(second, centre, width, floor, ceiling)
        for (i in frozen.indices) assertEquals(frozen[i], ring.bakedAt(1)[i], 0.0001f)
        val louder = SpectrumRidge.shapeDb(-80f - floor, ceiling - floor)
        assertEquals(louder, ring.bakedAt(0)[8], 0.001f)
        val rawNoise = SpectrumRidge.shapeDb(-104f - floor, ceiling - floor)
        assertTrue(ring.bakedAt(0)[0] < rawNoise)
        assertTrue(ring.bakedAt(0)[0] > frozen[0])
        // A later frame, and a floor that has moved, still leave the snapshot alone.
        ring.push(ByteArray(16) { -100 }, centre, width, floor - 20f, ceiling)
        for (i in frozen.indices) assertEquals(frozen[i], ring.bakedAt(2)[i], 0.0001f)
        assertTrue(SpectrumRidge.SAMPLES in 384..512)
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
