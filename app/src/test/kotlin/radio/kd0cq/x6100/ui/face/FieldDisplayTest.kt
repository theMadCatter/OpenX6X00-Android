// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later

package radio.kd0cq.x6100.ui.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldDisplayTest {

    @Test
    fun aDigitStepsByItsPlaceAndThePeakHoldsThenDecays() {
        val hz = 14_074_000
        assertEquals(10_000_000, DigitTune.placeHz(hz, 0))
        assertEquals(1_000, DigitTune.placeHz(hz, 4))
        assertEquals(14_075_000, DigitTune.step(hz, 4, 1))
        assertEquals(14_073_000, DigitTune.step(hz, 4, -1))
        assertEquals(14_074_000, DigitTune.fromKeypad("14.074.000"))
        assertNull(DigitTune.fromKeypad(""))
        assertEquals(0.38f, MeterPeak.hold(0.20f, 0.40f), 0.001f)
        assertTrue(MeterPeak.hold(0.50f, 0.40f) >= 0.50f)
    }
}
