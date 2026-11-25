// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Tune is a lease the face did not send. FT8 and JS8 kept running when the
// phone left. These tests are the unkey question: after the app has decided
// it is off the air, does a later timer key it again, and does a refused
// stop get offered until the socket takes it?

package radio.kd0cq.x6100.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuneAndUnkeyTest {

    @Test
    fun tuneRenewsWhileHeldAndAReleaseStopsItForGood() {
        val clock = ManualClock()
        val scheduler = ManualScheduler(clock)
        val lines = mutableListOf<String>()
        val tune = TuneLeaseStateMachine(
            send = { lines += it; true },
            clock = clock,
            scheduler = scheduler,
        )
        assertTrue(tune.press())
        assertFalse(tune.press())
        scheduler.advance(1_000)
        scheduler.advance(1_000)
        tune.release()
        scheduler.advance(5_000)
        assertEquals(listOf("tune 1 1", "tune 1 1", "tune 1 1", "tune 0"), lines)
        assertFalse(tune.keyed)
        assertFalse(tune.owesUnkey)
    }

    @Test
    fun aFailSafeSendsTune0AndALaterTimerDoesNotRenew() {
        val clock = ManualClock()
        val scheduler = ManualScheduler(clock)
        val lines = mutableListOf<String>()
        val tune = TuneLeaseStateMachine(
            send = { lines += it; true },
            clock = clock,
            scheduler = scheduler,
        )
        tune.press()
        tune.failSafe("the link to the radio dropped")
        scheduler.advance(5_000)
        assertEquals(listOf("tune 1 1", "tune 0"), lines)
        assertFalse(tune.keyed)
    }

    @Test
    fun aRefusedTune0IsRetriedAndAnEndedTuneDoesNotSendTune0() {
        val clock = ManualClock()
        val scheduler = ManualScheduler(clock)
        val lines = mutableListOf<String>()
        var acceptUnkey = false
        val tune = TuneLeaseStateMachine(
            send = { line ->
                if (line == "tune 0" && !acceptUnkey) return@TuneLeaseStateMachine false
                lines += line
                true
            },
            clock = clock,
            scheduler = scheduler,
        )
        tune.press()
        tune.release()
        assertEquals(listOf("tune 1 1"), lines)
        assertTrue(tune.owesUnkey)
        scheduler.advance(250)
        assertTrue(tune.owesUnkey)
        acceptUnkey = true
        scheduler.advance(250)
        assertEquals(listOf("tune 1 1", "tune 0"), lines)
        scheduler.advance(5_000)
        assertEquals(2, lines.size)

        // A radio error must cancel the renewal without tune 0, which would stop someone else's tune.
        val clock2 = ManualClock()
        val scheduler2 = ManualScheduler(clock2)
        val owned = mutableListOf<String>()
        val third = TuneLeaseStateMachine(
            send = { owned += it; true },
            clock = clock2,
            scheduler = scheduler2,
        )
        third.press()
        third.noteRadio("err tune: on the air")
        scheduler2.advance(5_000)
        assertEquals(listOf("tune 1 1"), owned)
        assertFalse(third.keyed)
    }

    @Test
    fun theNextTuneUsesANewHoldNumber() {
        val lines = mutableListOf<String>()
        val tune = TuneLeaseStateMachine(
            send = { lines += it; true },
            clock = ManualClock(),
            scheduler = ManualScheduler(ManualClock()),
        )
        tune.press()
        tune.release()
        tune.press()
        assertEquals(listOf("tune 1 1", "tune 0", "tune 1 2"), lines)
    }

    @Test
    fun digitalStopSendsBothEnginesAndRetriesOnlyARefusedWrite() {
        val clock = ManualClock()
        val scheduler = ManualScheduler(clock)
        val attempts = mutableListOf<String>()
        var open = false
        val unkey = DigitalUnkey(
            send = { line ->
                attempts += line
                open
            },
            clock = clock,
            scheduler = scheduler,
        )
        assertEquals(listOf("ft8 stop", "js8 stop"), unkey.lines)
        unkey.stop()
        assertEquals(listOf("ft8 stop", "js8 stop"), attempts)
        scheduler.advance(250)
        assertEquals(4, attempts.size)
        open = true
        scheduler.advance(250)
        assertEquals(
            listOf("ft8 stop", "js8 stop", "ft8 stop", "js8 stop", "ft8 stop", "js8 stop"),
            attempts,
        )
        scheduler.advance(5_000)
        assertEquals(6, attempts.size)
    }

    @Test
    fun aDigitalStopTheSocketAcceptsIsNotSentAgain() {
        val clock = ManualClock()
        val scheduler = ManualScheduler(clock)
        val attempts = mutableListOf<String>()
        val unkey = DigitalUnkey(
            send = { attempts += it; true },
            clock = clock,
            scheduler = scheduler,
        )
        unkey.stop()
        scheduler.advance(5_000)
        assertEquals(listOf("ft8 stop", "js8 stop"), attempts)
    }
}
