// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
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
import radio.kd0cq.x6100.core.TuneLeaseStateMachine
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
 * Everything that can end a transmission without the operator asking comes to the same machines:
 * a release, a link dropping, the controls going to someone else, the role changing to one that may
 * not transmit, `err ptt released` from the radio, the radio unkeying us, the app going to the
 * background, and the service dying. The machines let go on their own when their own watchdogs
 * fire, so no path here depends on the radio noticing first.
 *
 * Tune is a third lease, `tune 1 HOLD` / `tune 0`. It is not the ATU switch. A fail-safe stops it
 * along with PTT. A finger leaving the PTT button does not, because that finger was not on Tune.
 * [onAuthorityLost] is `ft8 stop` and `js8 stop`. FT8 and JS8 are not this key, so a PTT release
 * does not call it. Losing the transmit flag, or giving control back after this session held it, does.
 */
class LeasePttController(
    private val panel: PanelClient,
    private val tci: TciClient,
    private val microphone: Microphone,
    private val scheduler: PttScheduler,
    private val clock: PttClock = SystemClockPttClock,
    private val onAuthorityLost: () -> Unit = {},
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

    private val _tuning = MutableStateFlow(false)

    /** True while the external-tuner lease this phone started is being renewed. */
    val tuning: StateFlow<Boolean> = _tuning.asStateFlow()

    /**
     * This session has been told `ctl ... 1` at least once on this connection.
     * A later line that is neither us nor our own TCI transmission is control
     * leaving, and that is when a digital engine we may have started is stopped.
     * The first `ctl none` after connect is not that: the radio's own screen
     * may be transmitting, and this phone has not started it.
     */
    private var sawMine = false

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

    private val tune = TuneLeaseStateMachine(
        send = { line -> panel.sendChecked(line) },
        clock = clock,
        scheduler = scheduler,
        listener = { state, why ->
            _tuning.value = state == TuneLeaseStateMachine.State.Tuning
            publish(why)
        },
    )

    /** The key, from either microphone path or Tune: what the radio hears as us being on the air. */
    val onAir: Boolean get() = lease.keyed || tx.keyed || tune.keyed

    /**
     * An up-command is still owed after the machine has already left the press. Service shutdown
     * and a dropped audio link read this so they retry `key PTT 0` / `trx:0,false;` rather than
     * treating Releasing as "already off the air".
     */
    val needsUnkey: Boolean get() = lease.owesUnkey || tx.owesUnkey || tune.owesUnkey

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
                // Both paths. The panel socket is how a phone-mic transmission learns that the
                // local operator took the controls (ctl_tell is a panel line). Losing it while
                // either path is up, or while an unkey is still owed, has to say so on the wire.
                // Keyed is already false once a hold is Releasing, which is why owesUnkey is here:
                // a renewal that failed its up-command used to make this collector do nothing.
                if (!phase.online && (onAir || lease.owesUnkey || tx.owesUnkey || tune.owesUnkey)) {
                    failSafe("the link to the radio dropped")
                }
            }
        }
        scope.launch {
            tci.phase.collect { phase ->
                if (!phase.online && (tx.keyed || tx.owesUnkey)) {
                    tx.linkLost("the audio link to the radio dropped")
                }
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
        // Both microphone paths, always: the path that was not used is idle and says nothing, and a
        // path we forgot about is exactly the one that would still be holding. Tune has its own finger.
        lease.release()
        tx.release()
        publish(null)
    }

    /** A reason the screen should show without a command having been sent. */
    fun report(why: String) {
        _refusal.value = why
    }

    /**
     * Hold Tune. The view model has already required Take control, the transmit
     * flag and the band check. This only starts the lease.
     */
    fun pressTune() = tune.press()

    /** The Tune finger came up. */
    fun releaseTune() = tune.release()

    override fun failSafe(reason: String) {
        val wasOnAir = onAir
        lease.failSafe(reason)
        tx.failSafe(reason)
        tune.failSafe(reason)
        // Only a transmission that really was running has a story to tell. A fail-safe that fires while
        // nothing is keyed is the normal end of a screen, not news the operator has to read.
        publish(if (wasOnAir) reason else null)
    }

    /**
     * Background running is off, and the screen is gone.
     *
     * Off the air, and the microphone back to the phone. The banner is this reason,
     * and only when a carrier was actually up. When background running is on, the
     * activity calls [release] instead, so a pocketed key still goes up and this
     * line is not painted.
     */
    fun wentToBackground() = failSafe("this app went to the background")

    /** The `TX_CHRONO` pacing, handed over by the audio pipeline that sees every frame. */
    fun onTciFrame(frame: TciAudioFrame) {
        tx.onFrame(frame)
    }

    /** What the radio said on the Panel link that concerns the key. */
    fun onPanelEvent(event: PanelEvent) {
        when (event) {
            is PanelEvent.Transmitting -> {
                lease.radioTx(event.on)
                // The panel's `tx 0` is the same carrier the TCI server reports with `trx:0,false`
                // (`state_follow` / the panel's tx line). A dropped TCI text frame must not leave
                // this phone's microphone feeding a session the radio has already ended. Only once
                // pacing has started: a `tx 0` still in the socket buffer from the previous over
                // would otherwise cancel a key that has not been confirmed yet. The confirm and
                // silence timers still end a start the radio never took.
                if (!event.on && tx.currentState == TciTxStateMachine.State.Keyed) {
                    tx.onTransmitting(false)
                }
            }

            is PanelEvent.Failure -> {
                // The refusal's own words, or "ptt released: ...": the machine knows which.
                val ours = lease.radioError(event.text)
                if (event.text.startsWith("ptt released") && tx.keyed) {
                    tx.failSafe("the radio ended the transmission: ${event.text}")
                }
                // An err tune means this tune did not start, or the radio already ended it.
                // noteRadio does not send tune 0. See TuneLeaseStateMachine.
                if (event.text.startsWith("err tune")) tune.noteRadio(event.text)
                if (ours) publish(lease.lastReason)
            }

            is PanelEvent.ControlHeld -> onControl(event.info)

            is PanelEvent.Role ->
                // canOperate is the role word alone. `role operate 0` is a session the admin has
                // refused TX to (`tx_ok` in remote_server.c). Either one ends a live carrier.
                // FT8 and JS8 are not the PTT key, so they are stopped even when the key is up:
                // the engine on the radio would otherwise keep transmitting.
                if (!event.role.canOperate || !event.role.txAllowed) {
                    onAuthorityLost()
                    if (onAir || needsUnkey) failSafe("this session may not transmit any more")
                }

            else -> Unit
        }
    }

    /**
     * Who holds the radio's controls, from `ctl WHO MINE`.
     *
     * `ctl_tell()` in `remote_server.c` sets MINE only when the holder is this panel client
     * (`CTL_BROWSER` and our slot). A transmission keyed with `trx:0,true,tci;` is held as `CTL_TCI`,
     * so the panel line is `ctl tci 0` — mine is 0, and that holder is us, not someone else.
     * `ctl_local_input()` moves the holder to `local` even while the radio is transmitting, and the
     * TCI server does not unkey when that happens (audit B01 is why `trx:0,false` is never refused).
     * The lease path already let go on `!mine`. The phone-mic path used to ignore the line, so the
     * carrier stayed up under the operator at the radio.
     */
    private fun onControl(info: radio.kd0cq.x6100.net.ControlInfo) {
        if (info.mine) sawMine = true
        // Giving control back, or someone else taking it, after we held it.
        // `ctl tci` during our own phone-microphone transmission is still us.
        val lost = sawMine && !info.mine && !info.isTci
        if (lost) {
            onAuthorityLost()
            sawMine = false
            if (tune.keyed || tune.owesUnkey) tune.failSafe("the radio is controlled by ${info.holder}")
        }
        if (lease.keyed && !info.mine) lease.controlLost(info.holder)
        if (tx.keyed && !info.isTci) tx.failSafe("the radio is controlled by ${info.holder}")
    }

    /** Back to a clean slate after the link went down for good: the service's `onDestroy` ends here. */
    fun reset() {
        lease.reset()
        tx.reset()
        tune.reset()
        sawMine = false
        keyedSinceMs = 0
        _keyed.value = false
        _tuning.value = false
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
