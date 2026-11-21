// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later

package radio.kd0cq.x6100.spots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HunterTest {

    @Test
    fun labelsDropOverlapsAndWatchesMatchPrefixOrAlert() {
        val keep = SpotLabels.keep(
            left = floatArrayOf(0f, 10f, 80f),
            right = floatArrayOf(40f, 50f, 110f),
        )
        assertEquals(listOf(0, 2), keep)
        assertTrue(Hunter.matches("K1ABC", listOf("K1ABC"), radioAlert = false))
        assertTrue(Hunter.matches("JA1ABC", listOf("JA*"), radioAlert = false))
        assertFalse(Hunter.matches("K1ABC", listOf("JA*"), radioAlert = false))
        assertTrue(Hunter.matches("W1AW", emptyList(), radioAlert = true))
        val fresh = Hunter.fresh(setOf(Hunter.key("K1ABC", 14_000_000)), listOf("K1ABC", "W1AW"), listOf(14_000_000, 7_000_000))
        assertEquals(listOf(1), fresh)
        assertFalse(AlertGate.allow(1_000, lastGlobalMs = 900, lastForCallMs = 0))
        assertTrue(AlertGate.allow(40_000, lastGlobalMs = 1_000, lastForCallMs = 0))
        assertFalse(AlertGate.allow(40_000, lastGlobalMs = 1_000, lastForCallMs = 30_000))
    }
}
