// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.

package radio.kd0cq.x6100.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Panel's key as `src/remote_server.c` keeps it: `ptt_down()` holds a `key PTT 1 HOLD` for
 * `PTT_LEASE_MS` (3 s) unless the same line comes again, `ptt_lease()` ends it at `PTT_LIMIT_MS`
 * (180 s) or when the socket goes, and tells the client with `err ptt released: ...`. The radio's own
 * page renews once a second (`RENEW_MS`) and waits `KEY_MS` for the `tx 1` that confirms the key
 * (`webserver/src/x6100_webserver/static/js/radio_session.js`).
 *
 * Every test here is the same question in different clothes: after the app decides it is off the air,
 * does anything else go out on the wire?
 */
class PttLeaseStateMachineTest {

    /** The wire, as a test sees it: every line, in order, with a switch to make the socket start failing. */
    private class Wire {
        val lines = mutableListOf<String>()

        /** Sends from this number on fail, as a socket whose peer has gone does. */
        var failFrom = Int.MAX_VALUE

        /** Drop `key PTT 1` after the first one has been accepted: a renewal the socket will not take. */
        var dropRenewals = false

        /** Drop `key PTT 0`, so a test can show the retry that follows a refused unkey. */
        var dropUnkey = false

        /** Drop every down-command, and still accept `key PTT 0`. */
        var dropDown = false

        fun send(line: String): Boolean {
            val down = line.startsWith("key PTT 1")
            if (dropDown && down) return false
            if (dropRenewals && down && lines.any { it.startsWith("key PTT 1") }) return false
            if (dropUnkey && line == "key PTT 0") return false
            if (lines.size + 1 >= failFrom) return false
            lines += line
            return true
        }

        fun keysDown() = lines.filter { it.startsWith("key PTT 1") }
        fun keysUp() = lines.filter { it == "key PTT 0" }
    }

    private val wire = Wire()
    private val clock = ManualClock()
    private val scheduler = ManualScheduler(clock)
    private val states = mutableListOf<Pair<PttLeaseStateMachine.State, String?>>()

    private fun machine(
        renewEveryMs: Long = 1_000,
        hardCapMs: Long = 170_000,
        keyConfirmMs: Long = 1_500,
    ) = PttLeaseStateMachine(
        send = { wire.send(it) },
        clock = clock,
        scheduler = scheduler,
        renewEveryMs = renewEveryMs,
        hardCapMs = hardCapMs,
        keyConfirmMs = keyConfirmMs,
        listener = { state, why -> states += state to why },
    )

    // ---- pressing ----

    @Test
    fun pressAsksForTheKeyWithAHoldNumberOfItsOwn() {
        val m = machine()
        assertTrue(m.press())
        assertEquals(listOf("key PTT 1 1"), wire.lines)
        assertEquals(1L, m.hold)
        assertEquals(PttLeaseStateMachine.State.Requesting, m.currentState)
        assertTrue(m.keyed)
    }

    @Test
    fun holdNumbersOnlyEverGoUp() {
        val m = machine()
        assertTrue(m.press())
        m.radioTx(true)
        m.release()
        assertTrue(m.press())
        assertEquals(listOf("key PTT 1 1", "key PTT 1 2"), wire.keysDown())
        assertEquals(listOf("key PTT 0"), wire.keysUp())
        assertEquals(2L, m.hold)
        assertEquals(3L, m.nextHoldNumber())
    }

    @Test
    fun aHoldNumberIsSpentEvenWhenTheSocketRefusesIt() {
        // The radio may have seen the write before it failed; sending a lower number again would be
        // refused by ptt_down() anyway, which ends holds and never restarts them.
        val m = machine()
        wire.failFrom = 1
        assertFalse(m.press())
        assertFalse(m.keyed)
        assertEquals(2L, m.nextHoldNumber())
    }

    @Test
    fun aSecondPressWhileOneIsHeldIsRefused() {
        val m = machine()
        assertTrue(m.press())
        assertFalse(m.press())
        assertEquals(listOf("key PTT 1 1"), wire.keysDown())
        assertEquals("a press is already held", m.lastReason)
    }

    // ---- the renewals ----

    @Test
    fun theSameLineGoesOutOnceASecondWhileTheKeyIsHeld() {
        val m = machine()
        m.press()
        m.radioTx(true)
        scheduler.advance(3_000)
        // One press and three renewals, all identical: ptt_down() only re-arms on the same number.
        assertEquals(listOf("key PTT 1 1", "key PTT 1 1", "key PTT 1 1", "key PTT 1 1"), wire.keysDown())
        assertEquals(PttLeaseStateMachine.State.Keyed, m.currentState)
    }

    @Test
    fun nothingIsRenewedAfterTheOperatorLetsGo() {
        val m = machine()
        m.press()
        m.radioTx(true)
        scheduler.advance(2_500)
        val beforeRelease = wire.lines.size
        m.release()
        scheduler.advance(10_000)
        assertEquals(listOf("key PTT 0"), wire.lines.subList(beforeRelease, wire.lines.size).toList())
        assertEquals(3, wire.keysDown().size)
        assertFalse(m.keyed)
        assertNull(m.hold)
    }

    @Test
    fun nothingIsRenewedAfterALinkThatDropped() {
        val m = machine()
        m.press()
        m.radioTx(true)
        scheduler.advance(1_000)
        m.linkLost("the link to the radio dropped")
        scheduler.advance(10_000)
        assertEquals(1, wire.keysUp().size)
        assertEquals(2, wire.keysDown().size)
        assertEquals("the link to the radio dropped", m.lastReason)
    }

    @Test
    fun losingTheControlsTakesTheKeyBack() {
        // Someone else holding the controls can move the frequency under a transmission: ctl_tell().
        val m = machine()
        m.press()
        m.radioTx(true)
        m.controlLost("the radio's own panel")
        scheduler.advance(5_000)
        assertEquals(1, wire.keysUp().size)
        assertEquals(1, wire.keysDown().size)
        assertEquals("the radio is controlled by the radio's own panel", m.lastReason)
        assertFalse(m.keyed)
    }

    @Test
    fun aServiceGoingAwayLetsGoAndSaysNothingElse() {
        val m = machine()
        m.press()
        m.radioTx(true)
        m.failSafe("this app went to the background")
        m.reset()
        scheduler.advance(10_000)
        assertEquals(1, wire.keysUp().size)
        assertEquals(PttLeaseStateMachine.State.Idle, m.currentState)
        assertFalse(m.keyed)
    }

    @Test
    fun aRenewalTheSocketWouldNotTakeEndsThePress() {
        // The renewal is the write that fails. The up-command is a different write, and it has to
        // go out: ptt_lease() holds the key for PTT_LEASE_MS after the last renewal it accepted.
        // Stopping the machine without `key PTT 0` left the app Idle while that key was still down.
        val m = machine()
        m.press()
        m.radioTx(true)
        wire.dropRenewals = true
        scheduler.advance(1_000)
        assertFalse(m.keyed)
        assertEquals("the radio is not reached: the renewal was lost", m.lastReason)
        assertEquals(listOf("key PTT 0"), wire.keysUp())
        assertFalse(m.owesUnkey)
        scheduler.advance(10_000)
        // The failed renewal is not retried, and the unkey is not sent a second time once accepted.
        assertEquals(1, wire.keysDown().size)
        assertEquals(1, wire.keysUp().size)
    }

    @Test
    fun aPressTheSocketRefusesIsStillFollowedByKeyUp() {
        // ptt_down() records the hold before it keys. A down-command the phone did not queue can
        // still be one the radio already had. `key PTT 0` releases it, and releases nothing when
        // the down never landed (the key-up branch returns when this client is not holding it).
        wire.dropDown = true
        val m = machine()
        assertFalse(m.press())
        assertEquals(listOf("key PTT 0"), wire.lines)
        assertEquals(2L, m.nextHoldNumber())
        assertFalse(m.keyed)
        assertFalse(m.owesUnkey)
    }

    @Test
    fun aRefusedUnkeyIsRetriedUntilTheSocketTakesIt() {
        val m = machine()
        m.press()
        m.radioTx(true)
        wire.dropRenewals = true
        wire.dropUnkey = true
        scheduler.advance(1_000)
        assertTrue(m.owesUnkey)
        assertTrue(wire.keysUp().isEmpty())
        wire.dropUnkey = false
        scheduler.advance(250) // UNKEY_RETRY_MS
        assertEquals(listOf("key PTT 0"), wire.keysUp())
        assertFalse(m.owesUnkey)
        scheduler.advance(10_000)
        assertEquals(1, wire.keysUp().size)
    }

    // ---- the radio's own words ----

    @Test
    fun aKeyThatIsNeverConfirmedGivesUpAndUnkeys() {
        val m = machine()
        m.press()
        scheduler.advance(1_500)
        assertEquals(listOf("key PTT 0"), wire.keysUp())
        assertNotNull(m.lastReason)
        assertTrue(m.lastReason!!.contains("no tx"))
        scheduler.advance(1_500)
        assertEquals(PttLeaseStateMachine.State.Idle, m.currentState)
    }

    @Test
    fun txZeroFromTheRadioEndsTheRenewals() {
        val m = machine()
        m.press()
        m.radioTx(true)
        scheduler.advance(1_000)
        m.radioTx(false)
        scheduler.advance(10_000)
        assertEquals(2, wire.keysDown().size)
        assertEquals(1, wire.keysUp().size)
        assertEquals("the radio ended the transmission", m.lastReason)
    }

    @Test
    fun errPttReleasedEndsThePressWithoutAskingAgain() {
        // ptt_lease() already let go; a `key PTT 0` would only be noise, and the hold is ended there.
        val m = machine()
        m.press()
        m.radioTx(true)
        assertTrue(m.radioError("ptt released: no renewal from the page"))
        scheduler.advance(10_000)
        assertEquals(emptyList<String>(), wire.keysUp())
        assertEquals(1, wire.keysDown().size)
        assertFalse(m.keyed)
        assertEquals("the radio unkeyed: no renewal from the page", m.lastReason)
    }

    @Test
    fun aRefusalWhileKeyingEndsThePressWithTheRadiosOwnWords() {
        val m = machine()
        m.press()
        assertTrue(m.radioError("controlled by local: use the radio or take control"))
        assertEquals("controlled by local: use the radio or take control", m.lastReason)
        assertEquals(1, wire.keysUp().size)
    }

    @Test
    fun anErrorThatIsNotAboutTheKeyLeavesUsAlone() {
        val m = machine()
        m.press()
        m.radioTx(true)
        assertFalse(m.radioError("unknown command: set nope"))
        assertFalse(m.radioError("err pair this browser again"))
        scheduler.advance(1_000)
        assertEquals(2, wire.keysDown().size)
        assertTrue(m.keyed)
    }

    // ---- our own limit ----

    @Test
    fun ourOwnCapLetsGoBeforeTheRadiosThreeMinutes() {
        // PTT_LIMIT_MS is 180 s; this app stops itself at 170 s so it is never the one to be stopped.
        val m = machine()
        m.press()
        m.radioTx(true)
        scheduler.advance(169_000)
        assertTrue(m.keyed)
        scheduler.advance(1_500)
        assertFalse(m.keyed)
        assertEquals(1, wire.keysUp().size)
        assertEquals("this app's own limit of 170 s on the air", m.lastReason)
        assertEquals(170, wire.keysDown().size) // the press and one renewal each second, then the cap
    }

    // ---- what the screen is told ----

    @Test
    fun theScreenSeesEveryChangeOfState() {
        val m = machine()
        m.press()
        m.radioTx(true)
        m.release()
        scheduler.advance(1_500)
        assertEquals(
            listOf(
                PttLeaseStateMachine.State.Requesting,
                PttLeaseStateMachine.State.Keyed,
                PttLeaseStateMachine.State.Releasing,
                PttLeaseStateMachine.State.Idle,
            ),
            states.map { it.first },
        )
    }

    @Test
    fun pressingAgainWhileTheRadioIsStillUnkeyingIsHonoured() {
        // The wire is ordered, so the radio takes `key PTT 0` first and the new hold after it, which is
        // what its own page does with a fast second touch. What has to hold is that the settle timer of
        // the released hold cannot fire into the new one and call it Idle.
        val m = machine()
        m.press()
        m.radioTx(true)
        m.release()
        assertTrue(m.press())
        m.radioTx(true)
        assertEquals(listOf("key PTT 1 1", "key PTT 0", "key PTT 1 2"), wire.lines)
        assertTrue(m.keyed)
        scheduler.advance(1_500) // the old settle timer's turn: its generation is gone
        assertTrue(m.keyed)
        assertEquals(PttLeaseStateMachine.State.Keyed, m.currentState)
        // Only the new hold's renewal, and only once: the released hold never speaks again.
        assertEquals(listOf("key PTT 1 1", "key PTT 0", "key PTT 1 2", "key PTT 1 2"), wire.lines)
    }
}
