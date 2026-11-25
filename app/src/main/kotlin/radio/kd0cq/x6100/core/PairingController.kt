// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.core

import radio.kd0cq.x6100.net.ControlInfo
import radio.kd0cq.x6100.net.PanelCommands
import radio.kd0cq.x6100.net.PanelEvent
import radio.kd0cq.x6100.net.PanelRole
import radio.kd0cq.x6100.net.PanelTokenStore
import radio.kd0cq.x6100.net.isPairingCode
import radio.kd0cq.x6100.net.isSessionToken

/**
 * Who holds the radio's controls, as the last `ctl WHO MINE` said.
 *
 * The radio hands its knobs to one client at a time: the operator's own panel (`local`), a TCI client
 * (`tci`), a paired page (`browser NAME`), or no one (`none`) — `ctl_tell()` in `remote_server.c`.
 * `control take` is refused while the radio is on the air, and `allowed()` names the holder when a
 * command needs the controls and someone else has them.
 *
 * The one thing the safety logic needs from this is the moment it moves *away from us*: whoever has
 * the controls can change the frequency, the mode or the app under a transmission, so a client that
 * loses them while keyed gives the key back rather than stay on air beside them.
 */
class ControlHolder {

    /** What a `ctl` line changed, so a listener is only woken when something did. */
    data class Change(val now: ControlInfo, val wasOurs: Boolean, val isOurs: Boolean) {
        val takenFromUs: Boolean get() = wasOurs && !isOurs
        val givenToUs: Boolean get() = !wasOurs && isOurs
        val someoneElse: Boolean get() = !isOurs && !now.isNone
    }

    private val lock = Any()

    @Volatile
    private var info = ControlInfo("none", false)

    val current: ControlInfo get() = info

    /** Whether we hold them. */
    val isOurs: Boolean get() = info.mine

    /** Whether anyone does: `none` means the radio's knobs are free. */
    val isFree: Boolean get() = info.free

    /** `ctl WHO MINE`, kept, and reported when it moved in or out of our hands. */
    fun onControl(incoming: ControlInfo): Change = synchronized(lock) {
        val was = info
        info = incoming
        Change(now = incoming, wasOurs = was.mine, isOurs = incoming.mine)
    }

    /** What a link that went away leaves: we know nothing until the radio says so again. */
    fun reset(): Change = synchronized(lock) {
        val was = info
        info = ControlInfo("none", false)
        Change(now = info, wasOurs = was.mine, isOurs = false)
    }
}

/**
 * Pairing, roles and the radio's controls: the handshake `remote_server.c` answers, in one machine
 * the tests can drive with captured lines.
 *
 * The radio trusts its own network rather than a login, so a new socket is only a viewer. `pair?` puts
 * a six-digit code on the radio's screen for two minutes and answers "pairing code shown on the radio
 * for 2 min"; `pair CODE NAME` answers `paired TOKEN ROLE TX`, and from then on the token — 32 hex
 * characters — is sent as `auth TOKEN` at the start of every connection, which is what makes a
 * reconnect *this* session and not a stranger's. A token the radio no longer knows
 * ("err unknown session: pair this browser again") or a revoked session ("err session revoked") is
 * dropped here at once, because keeping it would only make every later connection a viewer again.
 *
 * What we may do is the `role` line — view, operate or admin — plus whether this session may
 * transmit at all, plus who holds the controls. [Status.canTransmit] is the answer the PTT button
 * needs; it says false for a viewer, for a session the admin refused TX to, and for a pairing we do
 * not have.
 */
class PairingController(
    /** One text line to the radio; false when the socket would not take it. */
    private val send: (String) -> Boolean,
    private val tokens: PanelTokenStore,
    val control: ControlHolder = ControlHolder(),
    private val listener: (Status, String?) -> Unit = { _, _ -> },
) {

    /** Nothing, a code we are waiting to be typed in, a pairing we asked for, or one we have. */
    enum class State { Unpaired, CodeShown, Paired, Refused }

    /** What we are to the radio and what we may do; the initial one is a stranger on the network. */
    data class Status(
        val state: State = State.Unpaired,
        val role: PanelRole = PanelRole.View,
        val txAllowed: Boolean = false,
        val sessionName: String = "",
        val control: ControlInfo = ControlInfo("none", false),
        val lastWord: String? = null,
    ) {
        val paired: Boolean get() = state == State.Paired

        /** Enough to change the radio: operate or admin, and paired. */
        val canOperate: Boolean get() = paired && role.canOperate

        /** Enough to press the key: able to operate, and a session the admin let transmit. */
        val canTransmit: Boolean get() = canOperate && txAllowed
    }

    private val lock = Any()

    private var state = State.Unpaired
    private var role = PanelRole.View
    private var txAllowed = false
    private var sessionName = ""
    private var lastWord: String? = null

    fun status(): Status = synchronized(lock) {
        Status(state, role, txAllowed, sessionName, control.current, lastWord)
    }

    /** Whether a token is kept, so a connection can be authenticated without asking first. */
    fun hasToken(): Boolean = synchronized(lock) { tokens.token != null }

    /** Ask the radio to show a code. The operator reads it off the radio's screen and types it in. */
    fun askForCode(): Boolean = synchronized(lock) {
        if (!send(PanelCommands.pairAsk())) {
            lastWord = "the radio is not reached"
            listener(status(), lastWord)
            return false
        }
        state = State.CodeShown
        lastWord = null
        listener(status(), null)
        true
    }

    /**
     * The code the operator read and the name this phone should be remembered as. A code that is not
     * six digits is refused here rather than sent: the radio would only answer "err pair CODE NAME",
     * and a typo is not worth a round trip.
     */
    fun pair(code: String, name: String): Boolean = synchronized(lock) {
        if (!isPairingCode(code)) {
            state = State.Refused
            lastWord = "a pairing code is six digits"
            listener(status(), lastWord)
            return false
        }
        val who = name.trim().ifEmpty { "Android" }.replace(' ', '_')
        if (!send(PanelCommands.pair(code, who))) {
            lastWord = "the radio is not reached"
            listener(status(), lastWord)
            return false
        }
        state = State.CodeShown
        lastWord = null
        listener(status(), null)
        true
    }

    /** Say who we are: the token from the last pairing, at every connection after it. */
    fun auth(): Boolean = synchronized(lock) {
        val token = tokens.token
        if (token == null || !isSessionToken(token)) {
            state = State.Unpaired
            lastWord = "this radio is not paired yet"
            listener(status(), lastWord)
            return false
        }
        if (!send(PanelCommands.auth(token))) {
            lastWord = "the radio is not reached"
            listener(status(), lastWord)
            return false
        }
        lastWord = null
        listener(status(), null)
        true
    }

    /** Forget the pairing here and on the radio; the radio closes the socket afterwards. */
    fun forgetPairing() = synchronized(lock) {
        if (tokens.token != null) send(PanelCommands.unpair())
        tokens.clear()
        state = State.Unpaired
        role = PanelRole.View
        txAllowed = false
        sessionName = ""
        control.reset()
        lastWord = "this radio is not paired yet"
        listener(status(), lastWord)
    }

    /** `control take`; refused while the radio is on the air, and the answer says so. */
    fun takeControl(): Boolean = synchronized(lock) {
        if (!send(PanelCommands.control(take = true))) {
            lastWord = "the radio is not reached"
            listener(status(), lastWord)
            return false
        }
        true
    }

    /** `control release`: the knobs back to whoever wants them, or to no one. */
    fun releaseControl(): Boolean = synchronized(lock) {
        send(PanelCommands.control(take = false))
    }

    /** A link that went away: the pairing survives, the role and the controls do not. */
    fun linkLost(reason: String?) = synchronized(lock) {
        val change = control.reset()
        role = PanelRole.View
        txAllowed = false
        sessionName = ""
        if (state == State.Paired) state = State.Unpaired
        lastWord = reason ?: "the radio is not reached"
        listener(status(), lastWord)
        change
    }

    /** A refusal that ended a press, from the PTT machine, so the screen can show one word. */
    fun noteWord(word: String) = synchronized(lock) {
        lastWord = word
        listener(status(), word)
    }

    /**
     * Every line the radio answered, in order. The ones about pairing, role and control are taken here;
     * anything else is left for the caller (the PTT machine reads the same stream for its own `tx` and
     * `err ptt released`).
     *
     * @return what moved, when this line was about the controls.
     */
    fun onEvent(event: PanelEvent): ControlHolder.Change? = synchronized(lock) {
        when (event) {
            is PanelEvent.Paired -> {
                if (!isSessionToken(event.token)) {
                    state = State.Refused
                    lastWord = "the radio answered without a token"
                    listener(status(), lastWord)
                    return null
                }
                tokens.token = event.token
                role = event.role
                txAllowed = event.txAllowed
                state = State.Paired
                lastWord = null
                listener(status(), null)
                null
            }

            is PanelEvent.Role -> {
                role = event.role.role
                txAllowed = event.role.txAllowed
                sessionName = event.role.sessionName
                if (event.role.role != PanelRole.View && tokens.token != null) state = State.Paired
                lastWord = null
                listener(status(), null)
                null
            }

            is PanelEvent.ControlHeld -> {
                val change = control.onControl(event.info)
                listener(status(), null)
                change
            }

            is PanelEvent.Hello -> {
                // A fresh socket: the radio answers `auth` with our role, or leaves us a viewer.
                control.reset().also { listener(status(), null) }
            }

            is PanelEvent.Failure -> when {
                event.text.startsWith("pairing refused") -> {
                    state = State.Refused
                    lastWord = "the radio did not accept that code"
                    listener(status(), lastWord)
                    null
                }

                event.text.contains("pair this browser again") || event.text.startsWith("session revoked") ||
                    event.text == "not paired" -> {
                    tokens.clear()
                    state = State.Unpaired
                    role = PanelRole.View
                    txAllowed = false
                    sessionName = ""
                    lastWord = event.text
                    listener(status(), lastWord)
                    null
                }

                else -> null
            }

            is PanelEvent.Notice -> if (event.text.startsWith("pairing code shown")) {
                state = State.CodeShown
                lastWord = "type the six digits the radio shows"
                listener(status(), lastWord)
                null
            } else {
                null
            }

            else -> null
        }
    }
}
