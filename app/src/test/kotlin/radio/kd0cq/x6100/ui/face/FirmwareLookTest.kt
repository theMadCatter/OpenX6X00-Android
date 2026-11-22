// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The radio's spectrum smoothing, peak hold, and colour window. The numbers are the
// ones in dsp.cpp, dsp_math.h, and spectrum_view.c on OpenX6X00 main 69f9502.

package radio.kd0cq.x6100.ui.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FirmwareLookTest {

    @Test
    fun aCarrierDoesNotLiftTheNoiseFloor() {
        val bins = 64
        val db = ByteArray(bins) { (-100).toByte() }
        db[10] = (-20).toByte()
        val floor = FirmwareLook.noiseFloorDb(db, bins, FloatArray(16))
        assertTrue(abs(floor - (-100f)) < 0.2f)
        val (min, max) = FirmwareLook.colourWindow(floor)
        assertEquals(-105f, min, 0.2f)
        assertEquals(-57f, max, 0.2f)
        // auto_levels() will not put the floor above S8.
        val clamped = FirmwareLook.colourWindow(-40f)
        assertEquals(FirmwareLook.S8, clamped.first, 0.01f)
        assertEquals(FirmwareLook.S8 + FirmwareLook.COLOUR_RANGE, clamped.second, 0.01f)
    }

    @Test
    fun theTraceRisesFastAndFallsSlowAndThePeakHolds() {
        val trace = SpectrumTrace()
        val db = ByteArray(4) { (-80).toByte() }
        trace.accept(db, 4, centreHz = 0, widthHz = 1000, nowMs = 0L, transmitting = false)
        db[0] = (-40).toByte()
        trace.accept(db, 4, 0, 1000, 100L, false)
        // −80 plus 0.6 of the 40 dB rise.
        assertEquals(-56f, trace.levelAt(0f)!!, 0.01f)
        assertEquals(-56f, trace.peakAt(0f)!!, 0.01f)
        db[0] = (-80).toByte()
        trace.accept(db, 4, 0, 1000, 200L, false)
        // 0.2 of the way back toward −80.
        assertEquals(-60.8f, trace.levelAt(0f)!!, 0.01f)
        assertEquals(-56f, trace.peakAt(0f)!!, 0.01f)
        trace.accept(db, 4, 0, 1000, 200L + FirmwareLook.PEAK_HOLD_MS + 1, false)
        assertEquals(-56f - FirmwareLook.PEAK_SINK_DB, trace.peakAt(0f)!!, 0.01f)
    }

    @Test
    fun transmitFreezesThePeaks() {
        val trace = SpectrumTrace()
        val db = ByteArray(4) { (-80).toByte() }
        trace.accept(db, 4, 0, 1000, 0L, transmitting = false)
        db[0] = (-20).toByte()
        trace.accept(db, 4, 0, 1000, 100L, transmitting = true)
        assertEquals(-80f, trace.peakAt(0f)!!, 0.01f)
        assertTrue(trace.levelAt(0f)!! > -80f)
    }

    @Test
    fun aTuneSlidesTheCarrierWithTheView() {
        // 8 bins across 800 Hz, so a bin is 100 Hz. Tuning up 200 Hz is two bins.
        // spectrum_move and shift_peaks agree on that positive step.
        val n = 8
        val db = ByteArray(n) { (-100).toByte() }
        db[4] = (-40).toByte()
        val trace = SpectrumTrace()
        trace.accept(db, n, centreHz = 1000, widthHz = 800, nowMs = 0L, transmitting = false)
        db[4] = (-100).toByte()
        db[2] = (-40).toByte()
        trace.accept(db, n, centreHz = 1200, widthHz = 800, nowMs = 66L, transmitting = false)
        assertEquals(-40f, trace.levelAt(2f)!!, 0.05f)
        assertEquals(-40f, trace.peakAt(2f)!!, 0.05f)
    }

    @Test
    fun theCeilingSitsOnTheTopOfThePane() {
        assertEquals(0f, FirmwareLook.traceY(-57f, -105f, -57f, 200f), 0.01f)
        assertEquals(200f, FirmwareLook.traceY(-105f, -105f, -57f, 200f), 0.01f)
        assertEquals(0f, FirmwareLook.traceY(0f, -105f, -57f, 200f), 0.01f)
    }

    @Test
    fun theFloorTrackerTakesABandChangeWhole() {
        val leveler = WaterfallLeveler()
        val quiet = ByteArray(64) { (-100).toByte() }
        val first = leveler.update(quiet, centreHz = 14_100_000, widthHz = 100_000)
        assertEquals(-105, first.first)
        assertEquals(-57, first.second)
        val loud = ByteArray(64) { (-60).toByte() }
        // Inside the span: one frame of the 0.15 s follow, not the whole jump.
        val eased = leveler.update(loud, centreHz = 14_100_200, widthHz = 100_000)
        assertTrue(eased.first in (first.first + 1) until -65)
        // Another band: the tracker resets and the new floor is taken as it is.
        // auto_levels() will not start the colours above S8, so a −60 dB floor
        // (minus the 5 dB margin) is held at −79 and the ramp runs 48 dB from there.
        val jumped = leveler.update(loud, centreHz = 7_100_000, widthHz = 100_000)
        assertEquals(FirmwareLook.S8.toInt(), jumped.first)
        assertEquals(FirmwareLook.S8.toInt() + FirmwareLook.COLOUR_RANGE.toInt(), jumped.second)
    }
}
