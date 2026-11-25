// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Locks the rule that a TCI frequency notice does not erase an err line, and
// that taking control is what clears it.

package radio.kd0cq.x6100.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioFaultTest {

    @Test
    fun aTciFrequencyDoesNotReplaceAnErrLine() {
        var shown: String? = null
        shown = RadioFault.keep(shown, RadioFault.Kind.FAILURE, "view only")
        assertEquals("err view only", shown)
        shown = RadioFault.keep(shown, RadioFault.Kind.TCI_FREQUENCY, "TCI at 14074000 Hz")
        assertEquals("err view only", shown)
        shown = RadioFault.keep(shown, RadioFault.Kind.OTHER, "pairing code shown on the radio for 2 min")
        assertEquals("err view only", shown)
        shown = RadioFault.keep(shown, RadioFault.Kind.FAILURE, "busy")
        assertEquals("err busy", shown)
        shown = RadioFault.keep(shown, RadioFault.Kind.TCI_FREQUENCY, "TCI at 7074000 Hz")
        assertEquals("err busy", shown)
    }

    @Test
    fun takingControlClearsTheErrAndSomeoneElseHoldingItDoesNot() {
        val shown = RadioFault.keep(null, RadioFault.Kind.FAILURE, "err busy")
        assertEquals("err busy", shown)
        assertEquals("err busy", RadioFault.keep(shown, RadioFault.Kind.OTHER, "local"))
        assertNull(RadioFault.keep(shown, RadioFault.Kind.CONTROL_MINE))
    }

    @Test
    fun theFaceKeepsTheErrAndTheGateSentence() {
        val face = RadioFault.show("err view only", "Pair this phone first.", "Pair this phone first.")
        assertEquals("err view only\nPair this phone first.", face)
        val oneLine = RadioFault.show("err busy", "Take control before transmitting.", separator = " · ")
        assertEquals("err busy · Take control before transmitting.", oneLine)
        assertNull(RadioFault.show(null, "  ", null))
    }
}
