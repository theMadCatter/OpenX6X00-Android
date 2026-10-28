// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.core

import radio.kd0cq.x6100.net.PanelCommands

/**
 * The Panel's PTT as the firmware treats it: a lease, never a latch.
 *
 * `src/remote_server.c` presses the radio's own microphone on `key PTT 1 HOLD` — HOLD a number of the
 * client's choosing for this press — and holds it for `PTT_LEASE_MS` (3 s) unless the very same line
 * comes again, which the radio's own page repeats every second (`RENEW_MS` in `radio_session.js`). It
 * lets go on its own after `PTT_LIMIT_MS` (3 min) whatever happens, and `ptt_down()` refuses a HOLD
 * lower than the last one it saw: *a hold that ended stays ended, only a new number keys again*.
 * `ptt_lease()` then tells the client with `err ptt released: ...`.
 *
 * So this machine
 *  - picks a hold number above every number it has used before and never reuses one,
 *  - renews the same line every [renewEveryMs] while the button is held,
 *  - keeps its own cap of [hardCapMs] (170 s, shorter than the radio's 180 s) so this app is never the
 *    one that has to be told to stop,
 *  - and lets go at once, with `key PTT 0`, on any of: the user releasing, the link going, control
 *    belonging to someone else, `err ptt released`, the app going to the background, the service
 *    dying, or a renewal the socket would not take.
 *
 * Letting go ends the hold: every timer asked for on its behalf is cancelled and its generation
 * retired, so nothing scheduled before the release can send for it afterwards. That is the property
 * the tests are built around, and the reason the machine holds a lock around all of it: releases come
 * from the socket thread, the main thread and a timer thread at once.
 */
class PttLeaseStateMachine(
    /** One text line to the radio; false when the socket would not take it. */
    private val send: (String) -> Boolean,
    private val clock: PttClock,
    private val scheduler: PttScheduler,
    /** The radio's own lease is 3 s; renewing once a second leaves two misses of margin. */
    private val renewEveryMs: Long = 1_000,
    /** Our cap, under the radio's `PTT_LIMIT_MS` of 180 s: we would rather stop than be stopped. */
    private val hardCapMs: Long = 170_000,
    /** The radio's page waits 1.5 s (`KEY_MS`) for "tx 1" and takes that as never having keyed. */
    private val keyConfirmMs: Long = 1_500,
    /** How long "Unkeying..." is shown before the button is ready again. */
    private val releaseSettleMs: Long = 1_500,
    private val listener: (State, String?) -> Unit = { _, _ -> },
) {

    /**
     * Idle: nothing held. Requesting: `key PTT 1 HOLD` is out, "tx 1" has not come. Keyed: the radio
     * said `tx 1` and the lease is being renewed. Releasing: we let go and no renewal will follow.
     */
    enum class State { Idle, Requesting, Keyed, Releasing }

    private val lock = Any()

    private var state = State.Idle
    private var reason: String? = null

    /** The last hold number used; the next press takes one above it, whatever became of this one. */
    private var lastHold = 0L

    /** The hold being renewed now, or null once it has ended. */
    private var liveHold: Long? = null

    /** Retired on every release, so a timer that is already running finds itself out of date. */
    private var generation = 0L

    private var renewTask: PttTask? = null
    private var capTask: PttTask? = null
    private var confirmTask: PttTask? = null
    private var settleTask: PttTask? = null

    /** What the radio's page shows as `down`: we asked for the key and the radio has not taken it back. */
    val keyed: Boolean
        get() = synchronized(lock) { state == State.Requesting || state == State.Keyed }

    val currentState: State get() = synchronized(lock) { state }

    /** The hold being renewed, or null; the UI shows it and the tests watch it. */
    val hold: Long? get() = synchronized(lock) { liveHold }

    /** Why we are not on the air: a refusal, a release, or the radio's own words. */
    val lastReason: String? get() = synchronized(lock) { reason }

    /** The hold number the next press will use; strictly increasing over the whole session. */
    fun nextHoldNumber(): Long = synchronized(lock) { lastHold + 1 }

    /**
     * Press. A new number, `key PTT 1 HOLD`, the renewals started, the cap armed.
     *
     * A press while a hold is live is refused — that is a renewal, and it would take a number the
     * radio has never seen. A press while the radio is still unkeying from the last one is not: the
     * `key PTT 0` is already written, its timers are gone, and the wire keeps the order, so the radio
     * hears unkey then key, which is what the radio's own page does with a fast second touch.
     *
     * @return false when the socket would not take it or a hold is already live; nothing is renewed
     *         and nothing is keyed in that case.
     */
    fun press(): Boolean = synchronized(lock) {
        if (state == State.Requesting || state == State.Keyed) {
            reason = "a press is already held"
            listener(state, reason)
            return false
        }
        val hold = ++lastHold
        if (!send(PanelCommands.key(PTT_KEY, true, hold.toString()))) {
            // The number is spent: the radio may have seen the write before it failed, so the next
            // press takes a higher one and this one is never sent again.
            liveHold = null
            state = State.Idle
            reason = "the radio is not reached: no transmission"
            listener(state, reason)
            return false
        }
        liveHold = hold
        generation++
        state = State.Requesting
        reason = null
        armRenewalLocked(hold, generation)
        armCapLocked(generation)
        val gen = generation
        confirmTask = scheduler.scheduleAt(clock.nowMs() + keyConfirmMs) {
            synchronized(lock) {
                if (gen != generation || state != State.Requesting) return@synchronized
                letGoLocked("the radio did not key: no tx in ${keyConfirmMs}ms", sendRelease = true)
            }
        }
        listener(state, null)
        true
    }

    /** Release, because the thumb came up: `key PTT 0`, and the renewals stop here. */
    fun release() = synchronized(lock) {
        if (state == State.Idle) return
        letGoLocked(null, sendRelease = state == State.Requesting || state == State.Keyed)
    }

    /**
     * Let go for good. Whatever was asked for is cancelled, the radio is told to unkey if it may still
     * be keyed, and the reason is kept for the screen: the link dropped, the service is stopping, the
     * app went away, the microphone died.
     */
    fun failSafe(reason: String) = synchronized(lock) {
        if (state == State.Idle) {
            this.reason = reason
            listener(State.Idle, reason)
            return
        }
        letGoLocked(reason, sendRelease = state == State.Requesting || state == State.Keyed)
    }

    /**
     * The radio's `tx 0|1`, its confirmation that it is or is not on the air. `tx 1` makes a press real
     * and the confirmation timer goes; `tx 0` while we hold the lease means the radio ended it, and
     * every renewal stops with it.
     */
    fun radioTx(on: Boolean) = synchronized(lock) {
        when {
            on && (state == State.Requesting || state == State.Keyed) -> {
                if (state != State.Keyed) {
                    state = State.Keyed
                    reason = null
                    confirmTask = null
                    listener(state, null)
                }
            }

            !on && (state == State.Keyed || state == State.Requesting) ->
                letGoLocked("the radio ended the transmission", sendRelease = true)

            !on && state == State.Releasing -> settleLocked()

            else -> Unit
        }
    }

    /**
     * An `err ...` from the radio: the ones that are about this press end it, as the page's
     * `Ptt.err()` does, and the rest are left for the screen. An `err ptt released` needs no release of
     * our own: the radio has already let go, and its hold is ended there, so the only thing to do is
     * stop asking.
     *
     * @return true when the line was about the PTT.
     */
    fun radioError(text: String): Boolean = synchronized(lock) {
        if (text.startsWith(ERR_PTT_RELEASED)) {
            if (state != State.Idle) {
                // "ptt released: no renewal from the page" — the reason is what follows the colon.
                val why = text.removePrefix(ERR_PTT_RELEASED).removePrefix(":").trim()
                    .ifEmpty { "the radio released the PTT" }
                letGoLocked("the radio unkeyed: $why", sendRelease = false)
            }
            return true
        }
        if (state == State.Requesting && PTT_REFUSALS.any { text.startsWith(it) }) {
            letGoLocked(text, sendRelease = true)
            return true
        }
        false
    }

    /**
     * `ctl WHO MINE` said the controls belong to someone else. A transmission from here is over: the
     * other holder can change the frequency, the mode or the app under us at any moment, so we give the
     * key back rather than stay on air beside them.
     */
    fun controlLost(holder: String) {
        if (!keyed) return
        failSafe("the radio is controlled by $holder")
    }

    /**
     * The link is gone: closed, cancelled, or never there. The radio will let go of a lease it cannot
     * renew within three seconds, but we still try `key PTT 0` first: a socket that closed politely
     * takes it, and a dead one loses nothing.
     */
    fun linkLost(reason: String) = failSafe(reason)

    /**
     * Everything back to Idle, timers gone: what a service's `onDestroy` leaves behind. A hold that is
     * still live is released first, because the one thing reset must never do is leave the radio on the
     * air behind it; the normal order is `failSafe` then `reset`, and by then there is nothing live.
     */
    fun reset() = synchronized(lock) {
        val wasLive = state == State.Requesting || state == State.Keyed
        cancelTimersLocked()
        liveHold = null
        if (wasLive) send(PanelCommands.key(PTT_KEY, false))
        state = State.Idle
        reason = null
        listener(state, null)
    }

    // ---- the timers ----

    private fun armRenewalLocked(hold: Long, gen: Long) {
        renewTask = scheduler.scheduleAt(clock.nowMs() + renewEveryMs) {
            synchronized(lock) {
                // A renewal is for one hold only: once its generation is retired, or its number is no
                // longer the live one, the line is never sent again (the radio would refuse it, and a
                // radio that got it late would key from a press that ended).
                if (gen != generation || liveHold != hold) return@synchronized
                if (state != State.Requesting && state != State.Keyed) return@synchronized
                if (!send(PanelCommands.key(PTT_KEY, true, hold.toString()))) {
                    letGoLocked("the radio is not reached: the renewal was lost", sendRelease = false)
                    return@synchronized
                }
                armRenewalLocked(hold, gen)
            }
        }
    }

    private fun armCapLocked(gen: Long) {
        capTask = scheduler.scheduleAt(clock.nowMs() + hardCapMs) {
            synchronized(lock) {
                if (gen != generation) return@synchronized
                if (state != State.Requesting && state != State.Keyed) return@synchronized
                letGoLocked("this app's own limit of ${hardCapMs / 1000} s on the air", sendRelease = true)
            }
        }
    }

    /** The one path out of a live hold: stop the renewals, retire the generation, tell the radio. */
    private fun letGoLocked(reason: String?, sendRelease: Boolean) {
        val wasLive = state == State.Requesting || state == State.Keyed
        cancelTimersLocked()
        liveHold = null
        if (wasLive) {
            state = State.Releasing
            if (sendRelease) send(PanelCommands.key(PTT_KEY, false))
        } else {
            state = State.Releasing
        }
        this.reason = reason ?: this.reason
        if (state == State.Releasing) {
            val gen = generation
            settleTask = scheduler.scheduleAt(clock.nowMs() + releaseSettleMs) {
                synchronized(lock) {
                    if (gen != generation) return@synchronized
                    if (state == State.Releasing) settleLocked()
                }
            }
        }
        listener(state, this.reason)
    }

    private fun settleLocked() {
        state = State.Idle
        listener(state, reason)
    }

    /** Every timer of this hold, cancelled, and its generation retired so a running one gives up. */
    private fun cancelTimersLocked() {
        renewTask?.cancel()
        capTask?.cancel()
        confirmTask?.cancel()
        settleTask?.cancel()
        renewTask = null
        capTask = null
        confirmTask = null
        settleTask = null
        generation++
    }

    private companion object {
        /** The key the radio's own page holds for the microphone (`BTN_TRIGGER_HAPPY4`). */
        const val PTT_KEY = "PTT"

        /** `err ptt released: no renewal from the page` / `... three minutes on the air`. */
        const val ERR_PTT_RELEASED = "ptt released"

        /**
         * The refusals that mean this press will not key, in the words `remote_server.c` uses (an
         * `err ` prefix is already off). `ptt_down` refuses an empty hold, `allowed` names whoever
         * holds the controls, and a role or a session that may not transmit says so itself.
         *
         * `ptt released` belongs here too even though it arrives in the middle of a hold rather
         * than as an answer to a press: it is the radio saying out loud that it took the key away
         * (its own limit, or no renewal). Ignoring it would leave the app showing `Keyed` while the
         * radio is off the air, which is the one disagreement that must not survive.
         */
        val PTT_REFUSALS = listOf(
            "controlled by",
            "view only",
            "this session may not transmit",
            "too many keys held",
            "busy: the radio is transmitting",
            ERR_PTT_RELEASED,
        )
    }
}
