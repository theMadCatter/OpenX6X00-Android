// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later

package radio.kd0cq.x6100.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickKeysTest {

    @Test
    fun quickKeysNeverKeyAndAlertsUseTheThresholds() {
        assertNull(QuickKeys.command("ptt", 0, 8, 0, false, false, false))
        assertNull(QuickKeys.command("PTT", 0, 8, 0, false, false, false))
        assertEquals("set band 1", QuickKeys.command(QuickKeys.BAND_UP, 0, 8, 0, false, false, false))
        assertEquals("set band -1", QuickKeys.command(QuickKeys.BAND_DOWN, 0, 8, 0, false, false, false))
        assertEquals("set mode 1", QuickKeys.command(QuickKeys.MODE, 0, 8, 0, false, false, false))
        assertEquals("knob vol 1", QuickKeys.command(QuickKeys.VOL_UP, 0, 8, 0, false, false, false))
        assertEquals("knob vol -1", QuickKeys.command(QuickKeys.VOL_DOWN, 0, 8, 0, false, false, false))
        assertEquals("set att 1", QuickKeys.command(QuickKeys.ATT, 0, 8, 0, false, false, false))
        assertEquals("set att 0", QuickKeys.command(QuickKeys.ATT, 0, 8, 1, false, false, false))
        assertEquals("set pre 0", QuickKeys.command(QuickKeys.PRE, 0, 8, 0, true, false, false))
        assertEquals("set nb 1", QuickKeys.command(QuickKeys.NB, 0, 8, 0, false, false, false))
        assertEquals("set nr 0", QuickKeys.command(QuickKeys.NR, 0, 8, 0, false, false, true))
        assertTrue(RadioAlerts.lowBattery(15, 20))
        assertFalse(RadioAlerts.lowBattery(21, 20))
        assertFalse(RadioAlerts.lowBattery(null, 20))
        assertFalse(RadioAlerts.lowBattery(-1, 20))
        assertTrue(RadioAlerts.highSwr(2.5, RadioAlerts.DEFAULT_SWR, transmitting = true))
        assertFalse(RadioAlerts.highSwr(2.4, RadioAlerts.DEFAULT_SWR, transmitting = true))
        assertFalse(RadioAlerts.highSwr(9.0, RadioAlerts.DEFAULT_SWR, transmitting = false))
        assertFalse(RadioAlerts.due(1_000, 900))
        assertTrue(RadioAlerts.due(70_000, 1_000))
    }
}
