// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A waterfall must not be able to push `ctl` or `tx` out of the queue.
 *
 * The old panel client put text and binary frames in one 512-slot `DROP_OLDEST` flow. These tests
 * post far more frames than that, with the control lines in among them, and require every control
 * line to come out in order. The picture lane is only required to still be holding the latest frame.
 */
class PanelEventBusTest {

    @Test
    fun aSpectrumFloodDoesNotDropControlOrTx() = runBlocking {
        val bus = PanelEventBus(this)
        try {
            val gen = bus.openConnection()
            val got = mutableListOf<String>()
            val collecting = launch {
                bus.events.collect { event ->
                    when (event) {
                        is PanelEvent.ControlHeld -> got += "ctl ${event.info.holder}"
                        is PanelEvent.Transmitting -> got += "tx ${if (event.on) 1 else 0}"
                        is PanelEvent.Role -> got += "role ${event.role.role.wire} ${if (event.role.txAllowed) 1 else 0}"
                        is PanelEvent.Failure -> got += "err ${event.text}"
                        is PanelEvent.Pong -> got += "pong ${event.n}"
                        is PanelEvent.Spectrum, is PanelEvent.Screen -> got += "frame"
                        else -> Unit
                    }
                }
            }
            yield()
            bus.post(gen, PanelEvent.ControlHeld(ControlInfo("local", mine = false)))
            repeat(TEXT_LINES) { n ->
                bus.post(gen, PanelEvent.Pong(n.toLong()))
                repeat(FRAMES_PER_LINE) { k ->
                    val seq = n * FRAMES_PER_LINE + k
                    bus.post(gen, spectrum(seq.toLong()))
                    if (k == 0) bus.post(gen, screen(seq.toLong()))
                }
            }
            bus.post(gen, PanelEvent.Transmitting(on = false))
            bus.post(gen, PanelEvent.Role(RoleInfo(PanelRole.Operate, txAllowed = false, sessionName = "kd0cq")))
            bus.post(gen, PanelEvent.Failure("ptt released: no renewal from the page"))

            withTimeout(2_000) {
                while (got.lastOrNull()?.startsWith("err ") != true) yield()
            }
            assertEquals("ctl local", got.first())
            assertEquals("tx 0", got[got.size - 3])
            assertEquals("role operate 0", got[got.size - 2])
            assertEquals("err ptt released: no renewal from the page", got.last())
            assertEquals(TEXT_LINES, got.count { it.startsWith("pong ") })
            assertEquals((0 until TEXT_LINES).map { "pong $it" }, got.filter { it.startsWith("pong ") })
            assertTrue(got.none { it == "frame" })
            assertEquals(0L, bus.textRejected)

            val latest = withTimeout(2_000) { bus.frames.first() }
            val frame = latest as PanelEvent.Spectrum
            assertEquals((TEXT_LINES * FRAMES_PER_LINE - 1).toLong(), frame.frame.sequence)
            collecting.cancel()
        } finally {
            bus.close()
        }
    }

    @Test
    fun controlLinesSurviveWhenNobodyIsCollectingYet() = runBlocking {
        // No subscriber, then more frames than the old 512-slot buffer, then the subscriber starts.
        // The text lane has to have kept ctl and tx 0 anyway.
        val bus = PanelEventBus(this)
        try {
            val gen = bus.openConnection()
            bus.post(gen, PanelEvent.ControlHeld(ControlInfo("browser other", mine = false)))
            repeat(2_000) { bus.post(gen, spectrum(it.toLong())) }
            bus.post(gen, PanelEvent.Transmitting(on = false))
            val got = mutableListOf<String>()
            val collecting = launch {
                bus.events.collect { event ->
                    when (event) {
                        is PanelEvent.ControlHeld -> got += event.info.holder
                        is PanelEvent.Transmitting -> got += "tx ${if (event.on) 1 else 0}"
                        else -> Unit
                    }
                }
            }
            withTimeout(2_000) {
                while (got.size < 2) yield()
            }
            assertEquals(listOf("browser other", "tx 0"), got)
            collecting.cancel()
        } finally {
            bus.close()
        }
    }

    @Test
    fun aRetiredSocketCannotDeliverTxAfterTheNextOneOpens() = runBlocking {
        val bus = PanelEventBus(this)
        try {
            val first = bus.openConnection()
            bus.post(first, PanelEvent.Transmitting(on = false))
            bus.post(first, PanelEvent.ControlHeld(ControlInfo("local", mine = false)))
            bus.closeConnection(first)
            val second = bus.openConnection()
            // A last frame from the socket we just closed, arriving after the next one exists.
            bus.post(first, PanelEvent.Transmitting(on = false))
            bus.post(second, PanelEvent.ControlHeld(ControlInfo("tci", mine = false)))
            val got = CompletableDeferred<ControlInfo>()
            val collecting = launch {
                bus.events.collect { event ->
                    if (event is PanelEvent.ControlHeld && !got.isCompleted) got.complete(event.info)
                    if (event is PanelEvent.Transmitting) got.completeExceptionally(
                        AssertionError("tx from the retired socket was delivered"),
                    )
                }
            }
            val info = withTimeout(2_000) { got.await() }
            assertEquals("tci", info.holder)
            assertEquals(0L, bus.textRejected)
            collecting.cancel()
        } finally {
            bus.close()
        }
    }

    @Test
    fun reconnectAsksForAuthAndAFreshPanelSubscription() {
        // Handshake already sent role and ctl. panel 1 is what sets panel_fresh on a new socket.
        assertEquals(listOf("panel 1"), PanelCommands.reconnect(null))
        assertEquals(listOf("panel 1"), PanelCommands.reconnect(""))
        assertEquals(listOf("auth tok", "panel 1"), PanelCommands.reconnect("tok"))
    }

    private fun spectrum(sequence: Long) =
        PanelEvent.Spectrum(SpectrumFrame(bins = 1, centreHz = 0, widthHz = 0, sequence = sequence, db = byteArrayOf(0)))

    private fun screen(sequence: Long) =
        PanelEvent.Screen(ScreenFrame(width = 1, height = 1, sequence = sequence, jpeg = byteArrayOf(0)))

    private companion object {
        /** More text than the old shared buffer held, so a drop would fail this. */
        const val TEXT_LINES = 600

        /** Frames between those lines. 600 × 20 is well past a 512-slot queue. */
        const val FRAMES_PER_LINE = 20
    }
}
