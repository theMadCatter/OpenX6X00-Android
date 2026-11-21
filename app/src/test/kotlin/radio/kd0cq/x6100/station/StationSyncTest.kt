// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later

package radio.kd0cq.x6100.station

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import radio.kd0cq.x6100.log.LatLon

class StationSyncTest {

    @Test
    fun posAndClkWordsAndTheHundredMetreGate() {
        val fix = PhoneFix(37.2, -93.3, 12f)
        assertEquals("pos 37.20000 -93.30000 12", PositionSync.command(fix))
        assertEquals("pos 37.20000 -93.30000", PositionSync.command(fix.copy(accuracyM = null)))
        assertEquals("clk?", ClockSync.query())
        assertEquals("clk 1700000000", ClockSync.set(1_700_000_000))

        val home = LatLon(37.2, -93.3)
        // About 0.001 degree of latitude is 111 m.
        val moved = LatLon(37.201, -93.3)
        val stayed = LatLon(37.2004, -93.3)
        assertEquals(SyncChoice.SEND, PositionSync.choose(true, true, null, home))
        assertEquals(SyncChoice.SEND, PositionSync.choose(true, true, home, moved))
        assertEquals(SyncChoice.SKIP, PositionSync.choose(true, true, home, stayed))
        assertEquals(SyncChoice.SKIP, PositionSync.choose(false, true, null, home))
        // The current firmware does not accept the command, so a moved station is not sent.
        assertEquals(SyncChoice.PENDING, PositionSync.choose(true, false, null, moved))
        assertEquals(SyncChoice.PENDING, ClockSync.choose(true, false))
        assertEquals(SyncChoice.SEND, ClockSync.choose(true, true))
        assertFalse(FIRMWARE_ACCEPTS_POS_AND_CLK)
    }
}
