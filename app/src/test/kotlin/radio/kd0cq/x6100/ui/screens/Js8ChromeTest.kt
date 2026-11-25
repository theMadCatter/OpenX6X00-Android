// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The S24 Ultra check of e4662dc, 1080 px wide. These lock the budgets the
// JS8 window and the navigation pad lay out with: Set on the first landscape
// frame, the net reminder above the gesture bar, and a floor when the inset
// reads empty.

package radio.kd0cq.x6100.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Js8ChromeTest {

    @Test
    fun s24LandscapeShowsSetOnTheFirstFrame() {
        val column = Js8Chrome.s24LandscapeColumnDp()
        assertEquals(384f, Js8Chrome.s24ShortSideDp(), 0.01f)
        // 384 dp window, 24 status, 48 navigation, 64 header, 8 padding.
        assertEquals(240f, column, 0.01f)
        assertTrue(Js8Chrome.setVisibleOnFirstFrame(column))
        assertTrue(Js8Chrome.controlCapDp(column) >= Js8Chrome.throughSetDp(takeControl = true))
        // The old 168 dp cap was under a two-row offset stack. One row fits.
        assertTrue(Js8Chrome.throughSetDp(takeControl = true) < 168)
        assertTrue(Js8Chrome.setVisibleOnFirstFrame(Js8Chrome.landscapeColumnDp(360f)))
    }

    @Test
    fun aColumnShorterThanTheOffsetRowDoesNotClaimSetFits() {
        val through = Js8Chrome.throughSetDp(takeControl = true).toFloat()
        assertFalse(Js8Chrome.setVisibleOnFirstFrame(through - 20f))
        assertEquals(0f, Js8Chrome.controlCapDp(0f), 0.01f)
    }

    @Test
    fun thePortraitReminderClearsTheNavigationBar() {
        val window = 2340f / Js8Chrome.S24_DENSITY
        assertEquals(832f, window, 0.01f)
        assertEquals(0, Js8Chrome.PORTRAIT_CONTROL_MAX_DP)
        assertTrue(Js8Chrome.portraitFooterClearsTheBar(window))
        val room = Js8Chrome.portraitContentDp(window) - Js8Chrome.portraitNoteBottomDp()
        assertTrue("reminder would end $room dp past the bar", room >= 0f)
    }

    @Test
    fun anEmptyNavigationInsetStillClearsTheGestureBar() {
        assertEquals(48f, ScreenBars.bottomDp(0f), 0.01f)
        assertEquals(48f, ScreenBars.bottomDp(24f), 0.01f)
        assertEquals(80f, ScreenBars.bottomDp(80f), 0.01f)
    }
}
