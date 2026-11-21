// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later

package radio.kd0cq.x6100.digital

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import radio.kd0cq.x6100.log.Qso

class DigitalTest {

    @Test
    fun ft8SlotCqAndMyCall() {
        assertEquals(4_000, Ft8List.remainingMs(0, slotMs = 15_000, inMs = 11_000))
        assertEquals(0, Ft8List.remainingMs(0, slotMs = 15_000, inMs = 16_000))
        assertEquals(5_000, Ft8List.remainingMs(10_000, slotMs = 0, inMs = 0))
        assertTrue(Ft8List.isCq("CQ KD0CQ EM37"))
        assertFalse(Ft8List.isCq("KD0CQ W1AW -10"))
        assertTrue(Ft8List.mentionsMe("W1AW KD0CQ EM37", "kd0cq/p"))
        assertFalse(Ft8List.mentionsMe("CQ K1ABC FN42", "KD0CQ"))
        val row = Qso("K1ABC", 14_074_000, "USB", 0, "59", "59", "", "", "", "", 1)
        assertTrue(Ft8List.worked("k1abc", listOf(row)))
        assertFalse(Ft8List.worked("W1AW", listOf(row)))
    }

    @Test
    fun js8ParserAndCommandsStayOffTheSocketUntilTheFirmwareHasThem() {
        val row = Js8Protocol.parse("""js8r {"id":7,"kind":"activity","text":"KN4ABC: CQ","call":"KN4ABC","snr":-9,"freq":1200,"directed":0}""")
        assertEquals(7L, row?.id)
        assertEquals("KN4ABC", row?.call)
        assertEquals(-9, row?.snr)
        assertFalse(row?.directed == true)
        val directed = Js8Protocol.parse("""js8r {"id":8,"text":"KN4ABC>KD0CQ HELLO","call":"KN4ABC","directed":1}""")
        assertTrue(directed?.directed == true)
        assertNull(Js8Protocol.parse("ft8r {}"))
        assertEquals("js8 cq", Js8Protocol.command("cq"))
        assertEquals("js8 hb", Js8Protocol.command("hb"))
        assertEquals("js8 tx HELLO", Js8Protocol.command("tx", "HELLO"))
        assertEquals("js8 wf 1", Js8Protocol.waterfall(true))
        assertFalse(JS8_IN_FIRMWARE)
    }
}
