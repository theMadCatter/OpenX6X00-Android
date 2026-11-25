// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Before this, the face had a Tuner key that did not send anything. The
// firmware already exposes Tune as its own lease, the same shape as PTT:
// `tune 1 HOLD` starts an external-tuner carrier, the same line once a
// second renews it, and `tune 0` stops it. Without a renewal the radio
// stops it after PTT_LEASE_MS (3 s) and says `err tune stopped`.
//
// This machine sends those lines and no others. A hold number only goes up.
// A renewal of a hold that has ended is ignored by the radio, so after
// release or fail-safe the renewal timer is cancelled and a later tick
// cannot send `tune 1` again. `tune 0` is sent only when this machine
// believes it started the tune. An `err tune` that means the radio never
// started it, or has already stopped it, does not send `tune 0`: on the
// radio, `tune 0` from a client that does not own the tune stops whatever
// tune is running.

package radio.kd0cq.x6100.core

import radio.kd0cq.x6100.net.PanelCommands

/**
 * The external-tuner lease from `tune_cmd` / `tune_lease` in `remote_server.c`.
 */
class TuneLeaseStateMachine(
    /** One text line to the radio. False when the socket would not take it. */
    private val send: (String) -> Boolean,
    private val clock: PttClock,
    private val scheduler: PttScheduler,
    /** The radio's page renews once a second. The lease itself is 3 s. */
    private val renewEveryMs: Long = 1_000,
    private val listener: (State, String?) -> Unit = { _, _ -> },
) {

    enum class State { Idle, Tuning, Releasing }

    private val lock = Any()
    private var state = State.Idle
    private var lastHold = 0L
    private var liveHold: Long? = null
    private var generation = 0L
    private var renewTask: PttTask? = null
    private var unkeyRetry: PttTask? = null
    private var owes = false

    /** True only while a tune this machine started is still being renewed. */
    val keyed: Boolean get() = synchronized(lock) { state == State.Tuning }

    /** A `tune 0` was refused and has not been accepted yet. */
    val owesUnkey: Boolean get() = synchronized(lock) { owes }

    /**
     * Start a tune with a new hold number.
     *
     * False when one is already running, when an unkey is still owed, or
     * when the socket refuses the first line. A refused first line does not
     * arm a renewal: nothing was keyed.
     */
    fun press(): Boolean = synchronized(lock) {
        if (state != State.Idle || owes) return false
        val hold = ++lastHold
        val gen = ++generation
        if (!send(PanelCommands.tune(true, hold))) {
            listener(state, "the radio did not take the tune")
            return false
        }
        liveHold = hold
        owes = false
        state = State.Tuning
        armRenew(hold, gen)
        listener(state, null)
        true
    }

    /** The finger came up. `tune 0`, and no further renewal of this hold. */
    fun release() = synchronized(lock) { letGo(null) }

    /**
     * The link dropped, the screen went away, control was lost, or the
     * service is stopping. Same unkey as [release], with a reason the screen
     * can show when a tune was actually running.
     */
    fun failSafe(reason: String) = synchronized(lock) {
        val running = state == State.Tuning
        letGo(if (running) reason else null)
    }

    /**
     * The radio already refused the tune, or already stopped it.
     *
     * `err tune: ...` is a start that did not happen (`tune_on` stays false).
     * `err tune stopped` is a lease the radio has ended. Sending `tune 0`
     * after either one can stop a tune this client does not own, because the
     * radio treats `tune 0` from a client that is not the owner as a request
     * to stop any tune. The renewals stop here and nothing else is sent.
     */
    fun noteRadio(text: String) = synchronized(lock) {
        if (!text.startsWith("err tune")) return
        cancelTimers()
        generation++
        liveHold = null
        owes = false
        state = State.Idle
        listener(state, text)
    }

    /** After the sockets are gone. Nothing to send. */
    fun reset() = synchronized(lock) {
        cancelTimers()
        generation++
        liveHold = null
        owes = false
        state = State.Idle
        listener(state, null)
    }

    private fun letGo(reason: String?) {
        if (state == State.Idle && !owes) {
            listener(state, null)
            return
        }
        cancelTimers()
        val gen = ++generation
        liveHold = null
        state = State.Releasing
        if (send(PanelCommands.tune(false, 0))) {
            owes = false
            state = State.Idle
            listener(state, reason)
        } else {
            owes = true
            armRetry(gen, UNKEY_RETRIES)
            listener(state, reason)
        }
    }

    private fun armRenew(hold: Long, gen: Long) {
        renewTask?.cancel()
        renewTask = scheduler.scheduleAt(clock.nowMs() + renewEveryMs) {
            synchronized(lock) {
                if (gen != generation || liveHold != hold || state != State.Tuning) return@synchronized
                if (!send(PanelCommands.tune(true, hold))) {
                    letGo("the tune renewal did not reach the radio")
                    return@synchronized
                }
                armRenew(hold, gen)
            }
        }
    }

    /**
     * A `tune 0` the socket refused. Tried again for about as long as the
     * radio's own lease, then given up. A new press is refused while one is
     * still owed, so a retry cannot land on the next tune.
     */
    private fun armRetry(gen: Long, left: Int) {
        if (left <= 0) return
        unkeyRetry = scheduler.scheduleAt(clock.nowMs() + UNKEY_RETRY_MS) {
            synchronized(lock) {
                if (gen != generation || !owes) return@synchronized
                if (send(PanelCommands.tune(false, 0))) {
                    owes = false
                    state = State.Idle
                    listener(state, null)
                } else {
                    armRetry(gen, left - 1)
                }
            }
        }
    }

    private fun cancelTimers() {
        renewTask?.cancel()
        renewTask = null
        unkeyRetry?.cancel()
        unkeyRetry = null
    }

    private companion object {
        const val UNKEY_RETRY_MS = 250L
        const val UNKEY_RETRIES = 12
    }
}
