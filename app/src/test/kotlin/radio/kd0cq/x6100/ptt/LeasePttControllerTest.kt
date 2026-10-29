// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.ptt

import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import radio.kd0cq.x6100.core.ManualClock
import radio.kd0cq.x6100.core.ManualScheduler
import radio.kd0cq.x6100.core.Microphone
import radio.kd0cq.x6100.core.TciAudioFrameCodec
import radio.kd0cq.x6100.core.TciTxStateMachine
import radio.kd0cq.x6100.net.ControlInfo
import radio.kd0cq.x6100.net.LinkPhase
import radio.kd0cq.x6100.net.PanelClient
import radio.kd0cq.x6100.net.PanelEvent
import radio.kd0cq.x6100.net.PanelRole
import radio.kd0cq.x6100.net.RadioEndpoint
import radio.kd0cq.x6100.net.RoleInfo
import radio.kd0cq.x6100.net.TciAudioFrame
import radio.kd0cq.x6100.net.TciClient
import radio.kd0cq.x6100.net.TciEvent

/**
 * The glue between the two transmit machines and the lines the radio actually sends.
 *
 * The machines already know how to unkey themselves. These tests are about the decisions that live
 * only here: which panel line ends which path, and a dropped panel socket ending a phone-mic
 * transmission that the panel was the only way to hear `ctl local` on.
 *
 * Before the wiring change, `onPanelEvent` called `lease.controlLost` and ignored the TCI machine,
 * so `ctl local 0` (ctl_local_input in remote_server.c, which moves the holder even while the radio
 * is transmitting) left `trx:0,true,tci;` keyed. `ctl tci 0` is that same phone-mic session — mine is
 * 0 because ctl_tell sets MINE only for this panel slot — and must not unkey it.
 */
class LeasePttControllerTest {

    /** Lines the panel socket accepted, in order. */
    private class FakePanel : PanelClient {
        val lines = mutableListOf<String>()
        override val events = MutableSharedFlow<PanelEvent>(extraBufferCapacity = 16)
        override val phase = MutableStateFlow(LinkPhase.PAIRED)
        override val lastError = MutableStateFlow<String?>(null)
        override val token: String? = null

        override fun connect(endpoint: RadioEndpoint) = Unit
        override fun disconnect() = Unit
        override fun send(command: String) {
            sendChecked(command)
        }

        override fun sendChecked(command: String): Boolean {
            lines += command
            return true
        }

        override fun askForPairingCode() = Unit
        override fun pair(code: String, name: String) = Unit
        override fun close() = Unit
    }

    /** Lines and frames the TCI socket accepted. */
    private class FakeTci : TciClient {
        val lines = mutableListOf<String>()
        val frames = mutableListOf<ByteArray>()
        override val events = MutableSharedFlow<TciEvent>(extraBufferCapacity = 16)
        override val audio = MutableSharedFlow<TciAudioFrame>(extraBufferCapacity = 16)
        override val phase = MutableStateFlow(LinkPhase.CONNECTED)
        override val lastError = MutableStateFlow<String?>(null)

        override fun connect(endpoint: RadioEndpoint) = Unit
        override fun disconnect() = Unit
        override fun send(command: String) {
            sendChecked(command)
        }

        override fun sendChecked(command: String): Boolean {
            lines += command
            return true
        }

        override fun sendFrame(frame: ByteArray) {
            sendFrameChecked(frame)
        }

        override fun sendFrameChecked(frame: ByteArray): Boolean {
            frames += frame
            return true
        }

        override fun close() = Unit

        fun unkeys() = lines.filter { it == "trx:0,false;" }
    }

    /** A capture device that is open and has one buffer of voice ready. */
    private class ReadyMic : Microphone {
        override var running: Boolean = false
            private set
        var stops = 0

        override fun start(): Boolean {
            running = true
            return true
        }

        override fun stop() {
            running = false
            stops++
        }

        override fun read(count: Int): ShortArray = ShortArray(count) { 1 }
    }

    private val panel = FakePanel()
    private val tci = FakeTci()
    private val mic = ReadyMic()
    private val clock = ManualClock()
    private val scheduler = ManualScheduler(clock)

    private fun controller() = LeasePttController(panel, tci, mic, scheduler, clock)

    /** One TX_CHRONO, the frame that moves a phone-mic session from Starting to Keyed. */
    private fun chrono(): TciAudioFrame =
        TciAudioFrameCodec.decode(TciAudioFrameCodec.chrono())!!

    private fun keyPhone(c: LeasePttController) {
        c.usePhoneMicrophone = true
        c.press()
        assertTrue(c.onAir)
        c.onTciFrame(chrono())
        assertEquals(TciTxStateMachine.State.Keyed, c.txState.value)
    }

    @Test
    fun localControlUnkeysAPhoneMicrophone() {
        // ctl_local_input() forces CTL_LOCAL even while transmitting. The TCI server does not unkey
        // on that line (audit B01 is why trx:0,false is never refused). This app has to.
        val c = controller()
        keyPhone(c)
        c.onPanelEvent(PanelEvent.ControlHeld(ControlInfo("local", mine = false)))
        assertEquals(listOf("trx:0,false;"), tci.unkeys())
        assertFalse(c.onAir)
        assertEquals(1, mic.stops)
        assertTrue(panel.lines.isEmpty())
    }

    @Test
    fun anotherBrowserUnkeysAPhoneMicrophone() {
        val c = controller()
        keyPhone(c)
        c.onPanelEvent(PanelEvent.ControlHeld(ControlInfo("browser other", mine = false)))
        assertEquals(listOf("trx:0,false;"), tci.unkeys())
        assertFalse(c.onAir)
    }

    @Test
    fun ourOwnTciHolderDoesNotUnkeyThePhoneMicrophone() {
        // ctl_tell: MINE is 1 only when the holder is this panel slot. A transmission keyed with
        // trx:0,true,tci; is reported as "ctl tci 0". Treating mine=0 as "someone else" unkeyed
        // every phone-mic transmission the moment the radio named its holder.
        val c = controller()
        keyPhone(c)
        c.onPanelEvent(PanelEvent.ControlHeld(ControlInfo("tci", mine = false)))
        assertTrue(tci.unkeys().isEmpty())
        assertTrue(c.onAir)
        assertEquals(TciTxStateMachine.State.Keyed, c.txState.value)
    }

    @Test
    fun losingThePanelLeaseStillSendsKeyUp() {
        val c = controller()
        c.press()
        assertTrue(c.onAir)
        c.onPanelEvent(PanelEvent.Transmitting(on = true))
        c.onPanelEvent(PanelEvent.ControlHeld(ControlInfo("browser other", mine = false)))
        assertEquals(listOf("key PTT 0"), panel.lines.filter { it == "key PTT 0" })
        assertFalse(c.onAir)
        assertTrue(tci.lines.isEmpty())
    }

    @Test
    fun aRoleThatMayNotTransmitUnkeysWhicheverPathIsUp() {
        // "role operate 0 name": canOperate is true, tx_ok is not. ptt_down refuses the lease with
        // "err this session may not transmit", but a session already on the air with trx:0,true,tci;
        // is not asked again. The role line is the notice.
        val c = controller()
        keyPhone(c)
        c.onPanelEvent(
            PanelEvent.Role(RoleInfo(PanelRole.Operate, txAllowed = false, sessionName = "kd0cq")),
        )
        assertEquals(listOf("trx:0,false;"), tci.unkeys())
        assertFalse(c.onAir)

        val lease = controller()
        lease.press()
        assertTrue(lease.onAir)
        lease.onPanelEvent(PanelEvent.Transmitting(on = true))
        lease.onPanelEvent(
            PanelEvent.Role(RoleInfo(PanelRole.View, txAllowed = false, sessionName = "kd0cq")),
        )
        assertEquals(listOf("key PTT 0"), panel.lines.filter { it == "key PTT 0" })
        assertFalse(lease.onAir)
    }

    @Test
    fun panelTxZeroStopsAKeyedPhoneMicAndNotOneStillStarting() {
        // A tx 0 still sitting in the socket from the previous over must not cancel a key whose
        // first TX_CHRONO has not arrived. Once pacing has started, tx 0 is the carrier going down
        // (state_follow), and the microphone has to stop with it.
        val c = controller()
        c.usePhoneMicrophone = true
        c.press()
        assertTrue(c.onAir)
        assertEquals(TciTxStateMachine.State.Starting, c.txState.value)
        c.onPanelEvent(PanelEvent.Transmitting(on = false))
        assertTrue(c.onAir)
        assertTrue(tci.unkeys().isEmpty())

        c.onTciFrame(chrono())
        c.onPanelEvent(PanelEvent.Transmitting(on = false))
        assertEquals(listOf("trx:0,false;"), tci.unkeys())
        assertFalse(c.onAir)
        assertEquals(1, mic.stops)
    }

    @Test
    fun theRadioSayingPttReleasedStopsThePhoneMicrophoneToo() {
        val c = controller()
        keyPhone(c)
        c.onPanelEvent(PanelEvent.Failure("ptt released: no renewal from the page"))
        assertEquals(listOf("trx:0,false;"), tci.unkeys())
        assertFalse(c.onAir)
    }

    @Test
    fun aPanelSocketThatDropsUnkeysThePhoneMicrophone() = runBlocking {
        // The collector is the only place a phone-mic transmission hears that the panel link, which
        // carries ctl_tell, has gone. Keyed on the TCI machine alone used to be invisible to it.
        val c = controller()
        keyPhone(c)
        c.watch(this)
        yield()
        panel.phase.value = LinkPhase.LOST
        yield()
        assertEquals(listOf("trx:0,false;"), tci.unkeys())
        assertFalse(c.onAir)
        coroutineContext.cancelChildren()
    }

    @Test
    fun anAudioSocketThatDropsStillSendsTheUnkey() = runBlocking {
        val c = controller()
        keyPhone(c)
        c.watch(this)
        yield()
        tci.phase.value = LinkPhase.LOST
        yield()
        assertEquals(listOf("trx:0,false;"), tci.unkeys())
        assertFalse(c.onAir)
        coroutineContext.cancelChildren()
    }
}
