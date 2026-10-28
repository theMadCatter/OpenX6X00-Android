// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.ptt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import radio.kd0cq.x6100.core.Microphone
import radio.kd0cq.x6100.core.PttClock
import radio.kd0cq.x6100.core.PttLeaseStateMachine
import radio.kd0cq.x6100.core.PttScheduler
import radio.kd0cq.x6100.core.TciTxStateMachine
import radio.kd0cq.x6100.net.PanelClient
import radio.kd0cq.x6100.net.PanelEvent
import radio.kd0cq.x6100.net.TciAudioFrame
import radio.kd0cq.x6100.net.TciClient
import radio.kd0cq.x6100.net.TciEvent

/**
 * The key, wired to the radio: which of the two ways onto the air we use, and everything that has to
 * come off it again.
 *
 * The radio's page has two transmit paths and uses one or the other ([radio_session.js], `Ptt.press`):
 *  - **the radio's microphone**: a Panel lease, `key PTT 1 HOLD` renewed every second until the radio
 *    confirms with `tx 1`, `key PTT 0` to let go. [PttLeaseStateMachine] is that path.
 *  - **our own microphone**: no Panel key at all. `trx:0,true,tci;` on the TCI link keys the radio
 *    with our audio, one `TX_AUDIO` answers each `TX_CHRONO`, and `trx:0,false;` ends it.
 *    [TciTxStateMachine] is that path, and it will not key unless the capture device really runs —
 *    the page's *"the radio's own microphone is not used in its place"*.
 *
 * [usePhoneMicrophone] picks between them. The default is the radio's microphone: it needs no
 * permission, and a phone whose microphone was never granted should not be what stops an operator
 * from keying.
 *
 * Everything that can end a transmission without the operator asking comes to the same two machines:
 * a release, a link dropping, the controls going to someone else, the role changing to one that may
 * not transmit, `err ptt released` from the radio, the radio unkeying us, the app going to the
 * background, and the service dying. Both machines let go on their own when their own watchdogs
 * fire, so no path here depends on the radio noticing first.
 */
class LeasePttController(
    private val panel: PanelClient,
    private val tci: TciClient,
    private val microphone: Microphone,
    private val scheduler: PttScheduler,
    private val clock: PttClock = SystemClockPttClock,
) : PttController {

    /** Where the transmit audio comes from: false for the microphone at the radio, true for ours. */
    @Volatile
    var usePhoneMicrophone: Boolean = false

    private val _keyed = MutableStateFlow(false)
    override val keyed: StateFlow<Boolean> = _keyed.asStateFlow()

    private val _refusal = MutableStateFlow<String?>(null)
    override val refusal: StateFlow<String?> = _refusal.asStateFlow()

    private val _leaseState = MutableStateFlow(PttLeaseStateMachine.State.Idle)

    /** The lease path's state, for the screen that shows "Keying...", "On air", "Unkeying...". */
    val leaseState: StateFlow<PttLeaseStateMachine.State> = _leaseState.asStateFlow()

    private val _txState = MutableStateFlow(TciTxStateMachine.State.Idle)

    /** The audio path's state. */
    val txState: StateFlow<TciTxStateMachine.State> = _txState.asStateFlow()

    /** When we started pressing, for the on-air timer; 0 when we are not. */
    @Volatile
    var keyedSinceMs: Long = 0L
        private set

    private val lease = PttLeaseStateMachine(
        send = { line -> panel.sendChecked(line) },
        clock = clock,
        scheduler = scheduler,
        listener = { state, why ->
            _leaseState.value = state
            publish(why)
        },
    )

    private val tx = TciTxStateMachine(
        send = { line -> tci.sendChecked(line) },
        sendFrame = { frame -> tci.sendFrameChecked(frame) },
        mic = microphone,
        clock = clock,
        scheduler = scheduler,
        listener = { state, why ->
            _txState.value = state
            publish(why)
        },
    )

    /** The key, from either path: what the radio hears as us being on the air. */
    val onAir: Boolean get() = lease.keyed || tx.keyed

    /**
     * The link's own events, so nothing has to remember to feed us. The phases and the two event flows
     * are shared flows, and this is only another listener on them.
     */
    fun watch(scope: CoroutineScope) {
        scope.launch {
            panel.events.collect { onPanelEvent(it) }
        }
        scope.launch {
            tci.events.collect { event -> if (event is TciEvent.Transmitting) tx.onTransmitting(event.on) }
        }
        scope.launch {
            panel.phase.collect { phase ->
                // Nothing on the air, nothing to let go of: and the operator should not be shown a
                // dropped transmission that never started.
                if (!phase.online && lease.keyed) lease.linkLost("the link to the radio dropped")
            }
        }
        scope.launch {
            tci.phase.collect { phase ->
                if (!phase.online && tx.keyed) tx.linkLost("the audio link to the radio dropped")
            }
        }
    }

    override fun press() {
        _refusal.value = null
        if (usePhoneMicrophone) {
            if (!tx.press()) return // the machine said why: no microphone, or no link
        } else if (!lease.press()) {
            return
        }
        keyedSinceMs = clock.nowMs()
        publish(null)
    }

    override fun release() {
        // Both, always: the path that was not used is idle and says nothing, and a path we forgot
        // about is exactly the one that would still be holding.
        lease.release()
        tx.release()
        publish(null)
    }

    override fun failSafe(reason: String) {
        val wasOnAir = onAir
        lease.failSafe(reason)
        tx.failSafe(reason)
        // Only a transmission that really was running has a story to tell. A fail-safe that fires while
        // nothing is keyed is the normal end of a screen, not news the operator has to read.
        publish(if (wasOnAir) reason else null)
    }

    /** The app is going to the background: off the air, and the microphone back to the phone. */
    fun wentToBackground() = failSafe("this app went to the background")

    /** The `TX_CHRONO` pacing, handed over by the audio pipeline that sees every frame. */
    fun onTciFrame(frame: TciAudioFrame) {
        tx.onFrame(frame)
    }

    /** What the radio said on the Panel link that concerns the key. */
    fun onPanelEvent(event: PanelEvent) {
        when (event) {
            is PanelEvent.Transmitting -> lease.radioTx(event.on)

            is PanelEvent.Failure -> {
                // The refusal's own words, or "ptt released: ...": the machine knows which.
                val ours = lease.radioError(event.text)
                if (event.text.startsWith("ptt released") && tx.keyed) {
                    tx.failSafe("the radio ended the transmission: ${event.text}")
                }
                if (ours) publish(lease.lastReason)
            }

            is PanelEvent.ControlHeld ->
                if (lease.keyed && !event.info.mine) lease.controlLost(event.info.holder)

            is PanelEvent.Role ->
                if (!event.role.canOperate && lease.keyed) {
                    lease.failSafe("this session may not transmit any more")
                }

            else -> Unit
        }
    }

    /** Back to a clean slate after the link went down for good: the service's `onDestroy` ends here. */
    fun reset() {
        lease.reset()
        tx.reset()
        keyedSinceMs = 0
        _keyed.value = false
    }

    private fun publish(why: String?) {
        val live = onAir
        _keyed.value = live
        if (!live) keyedSinceMs = 0
        // A reason sticks until the next press: the operator has to be able to read why the button is
        // amber after the transmission is already over.
        if (why != null) _refusal.value = why
    }
}
