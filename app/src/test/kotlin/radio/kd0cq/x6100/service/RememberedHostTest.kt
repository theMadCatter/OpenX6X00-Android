// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later

package radio.kd0cq.x6100.service

import org.junit.Assert.assertEquals
import org.junit.Test
import radio.kd0cq.x6100.net.RadioEndpoint

class RememberedHostTest {

    @Test
    fun theLastHostWinsAndABlankOneFallsBack() {
        assertEquals("192.168.1.56", RememberedHost.shown("192.168.1.56"))
        assertEquals("192.168.1.56", RememberedHost.shown("  192.168.1.56  "))
        assertEquals(RadioEndpoint.DEFAULT_HOST, RememberedHost.shown(null))
        assertEquals(RadioEndpoint.DEFAULT_HOST, RememberedHost.shown("   "))
        assertEquals("x6100.local", RememberedHost.shown(null, RadioEndpoint.DEFAULT_HOST))
    }
}
