// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later

package radio.kd0cq.x6100.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import radio.kd0cq.x6100.net.LinkPhase

/**
 * The background switch and the start/stop decision. No service is started here:
 * the activity applies [BackgroundRun.Leave], and these are the cases it branches on.
 */
class BackgroundRunTest {

    @Test
    fun backgroundRunningIsOnUntilTheOperatorTurnsItOff() {
        assertTrue(BackgroundRun.DEFAULT_ON)
        assertTrue(BackgroundRun.enabled(null))
        assertTrue(BackgroundRun.enabled(true))
        assertFalse(BackgroundRun.enabled(false))
        assertEquals("run_in_background", BackgroundRun.PREF_KEY)
    }

    @Test
    fun leavingTheScreenKeepsTheLinkWhenBackgroundRunningIsOn() {
        val held = BackgroundRun.onLeave(
            configurationChange = false,
            runInBackground = true,
            connected = true,
            touchPttHeld = true,
        )
        assertTrue(held.keepLink)
        assertTrue(held.releaseTouchPtt)
        assertFalse(held.announceWentToBackground)
        assertTrue(held.holdWakeAndWifi)
        assertTrue(held.pausePictures)
        assertFalse(held.stopService)

        val idle = BackgroundRun.onLeave(
            configurationChange = false,
            runInBackground = true,
            connected = true,
            touchPttHeld = false,
        )
        assertFalse(idle.releaseTouchPtt)
        assertFalse(idle.announceWentToBackground)
        assertFalse(idle.stopService)

        // Not connected yet: stay ready to reconnect, but do not hold the radios awake.
        val waiting = BackgroundRun.onLeave(
            configurationChange = false,
            runInBackground = true,
            connected = false,
            touchPttHeld = false,
        )
        assertTrue(waiting.keepLink)
        assertFalse(waiting.holdWakeAndWifi)
        assertFalse(waiting.stopService)
    }

    @Test
    fun turningBackgroundRunningOffStopsTheServiceAndUsesTheOldBanner() {
        val leave = BackgroundRun.onLeave(
            configurationChange = false,
            runInBackground = false,
            connected = true,
            touchPttHeld = true,
        )
        assertFalse(leave.keepLink)
        assertFalse(leave.releaseTouchPtt)
        assertTrue(leave.announceWentToBackground)
        assertFalse(leave.holdWakeAndWifi)
        assertTrue(leave.pausePictures)
        assertTrue(leave.stopService)
    }

    @Test
    fun aRotationIsNotBackgrounding() {
        val leave = BackgroundRun.onLeave(
            configurationChange = true,
            runInBackground = true,
            connected = true,
            touchPttHeld = true,
        )
        assertTrue(leave.keepLink)
        assertFalse(leave.releaseTouchPtt)
        assertFalse(leave.announceWentToBackground)
        assertFalse(leave.holdWakeAndWifi)
        assertFalse(leave.pausePictures)
        assertFalse(leave.stopService)
    }

    @Test
    fun locksAreOnlyHeldWhileAwayAndConnected() {
        assertTrue(BackgroundRun.wantLocks(runInBackground = true, connected = true, inBackground = true))
        assertFalse(BackgroundRun.wantLocks(runInBackground = true, connected = true, inBackground = false))
        assertFalse(BackgroundRun.wantLocks(runInBackground = true, connected = false, inBackground = true))
        assertFalse(BackgroundRun.wantLocks(runInBackground = false, connected = true, inBackground = true))
    }

    @Test
    fun aKilledProcessReconnectsOnlyWhenBackgroundRunningRemembersARadio() {
        assertEquals(
            BackgroundRun.ServiceAction.START,
            BackgroundRun.restartAfterDeath(runInBackground = true, savedHost = "x6100.local"),
        )
        assertEquals(
            BackgroundRun.ServiceAction.STOP,
            BackgroundRun.restartAfterDeath(runInBackground = false, savedHost = "x6100.local"),
        )
        assertEquals(
            BackgroundRun.ServiceAction.STOP,
            BackgroundRun.restartAfterDeath(runInBackground = true, savedHost = null),
        )
        assertEquals(
            BackgroundRun.ServiceAction.STOP,
            BackgroundRun.restartAfterDeath(runInBackground = true, savedHost = "  "),
        )
    }

    @Test
    fun mediaPlaybackIsClaimedOnlyWhileTheReceiverIsPlaying() {
        assertEquals(
            setOf(BackgroundRun.Kind.CONNECTED_DEVICE),
            BackgroundRun.serviceKinds(rxPlaying = false),
        )
        assertEquals(
            setOf(BackgroundRun.Kind.CONNECTED_DEVICE, BackgroundRun.Kind.MEDIA_PLAYBACK),
            BackgroundRun.serviceKinds(rxPlaying = true),
        )
    }

    @Test
    fun theNotificationCarriesTheDialAndDoesNotRepaintEveryMeterTick() {
        assertEquals(
            "14.100.000 USB S9",
            BackgroundRun.statusLine(LinkPhase.PAIRED, "x6100.local", 14_100_000, 2, -73.0),
        )
        assertEquals(
            "Connecting to the radio…",
            BackgroundRun.statusLine(LinkPhase.CONNECTING, "x6100.local", null, null, null),
        )
        assertEquals(
            "Not connected",
            BackgroundRun.statusLine(LinkPhase.STOPPED, "x6100.local", 14_100_000, 2, -73.0),
        )
        assertEquals(
            "Connected to x6100.local",
            BackgroundRun.statusLine(LinkPhase.CONNECTED, "x6100.local", null, null, null),
        )
        assertTrue(BackgroundRun.noteDue(nowMs = 5_000, lastPaintMs = 0, textChanged = true))
        assertFalse(BackgroundRun.noteDue(nowMs = 5_200, lastPaintMs = 5_000, textChanged = false))
        assertTrue(BackgroundRun.noteDue(nowMs = 6_000, lastPaintMs = 5_000, textChanged = false))
    }
}
