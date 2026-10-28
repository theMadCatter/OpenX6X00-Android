// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.core

import radio.kd0cq.x6100.net.TciAudioFrame
import radio.kd0cq.x6100.net.TciCommands

/**
 * Our microphone, seen from the transmit side.
 *
 * The rule this interface exists to make enforceable is the radio's page's own
 * (`radio_session.js`): *"Microphone not ready: no transmission (the radio's own microphone is not
 * used in its place)"*. A key pressed with nothing behind it would either put the radio's own
 * microphone on the air when the operator meant theirs, or key a transmitter that goes silent —
 * and `tci_server.c` ends a silent TCI transmission in 500 ms anyway. So the machine asks the
 * microphone to start, and does not key unless it says it is really capturing.
 */
interface Microphone {

    /** Open the capture device. False when it is refused, busy, or not there. */
    fun start(): Boolean

    /** Hand the device back. Safe to call twice, and while not running. */
    fun stop()

    /** Whether samples are actually coming. */
    val running: Boolean

    /**
     * Up to [count] samples of the microphone, oldest first; fewer when the capture has not caught
     * up. Never null and never longer than asked, and it never waits: a stalled microphone must be
     * seen as a stall, not hold the key down while a thread sleeps.
     */
    fun read(count: Int): ShortArray
}

/**
 * The TCI audio transmission: our microphone on the air, and every way back off it.
 *
 * Keying with our own audio is the TCI server's, not the Panel's: `trx:0,true,tci;` starts a
 * transmission whose audio comes from us, `trx:0,false;` ends it (`tci_server.c`, and the radio's
 * page's `txStart()`/`txStop()`). Once it is running the radio sends one `TX_CHRONO` every 21.3 ms
 * — 512 samples at 24 kHz — and watches for our answer: `handle_tx_audio` reads our frame and
 * `tx_session_tick` unkeys us 500 ms after the last one, or 3 min after the key, whichever comes
 * first. A frame of a shape it cannot read is worse than none: it ends the transmission on the spot,
 * which is why [TciAudioFrameCodec.isSensibleTxAudio] stands in front of every byte we send.
 *
 * The fail-safes, all of which stop the frames and send the unkey:
 *  - we release, or the operator does;
 *  - `trx:0,false;` from the radio, whatever ended it;
 *  - the pacing stopping, which means the link is gone and the radio has unkeyed us already;
 *  - the microphone dying or being taken away mid-transmission, or running without delivering samples;
 *  - our own [txLimitMs], set under the radio's 3 min, so this app stops itself before the radio does;
 *  - a key that never took: the firmware gives a TCI session 1 s, and so do we;
 *  - a frame the socket would not take.
 *
 * A frame is never sent outside a live session. Every release retires a generation, and the chrono
 * handler only ever sends for the live one, so a frame that was already on its way when the key came
 * off cannot reach the radio afterwards.
 */
class TciTxStateMachine(
    /** One text line to the TCI server; false when the socket would not take it. */
    private val send: (String) -> Boolean,
    /** One binary frame to the TCI server; false when the socket would not take it. */
    private val sendFrame: (ByteArray) -> Boolean,
    private val mic: Microphone,
    private val clock: PttClock,
    private val scheduler: PttScheduler,
    /** `TX_SILENCE_MS`: how long the radio waits for our audio before unkeying; we watch the same gap. */
    private val silenceMs: Long = 500,
    /** Our cap under `TX_LIMIT_MS` of 180 s. */
    private val txLimitMs: Long = 170_000,
    /** The firmware's own patience with a key that never took (`tx_session_tick`: 1 s). */
    private val keyConfirmMs: Long = 1_000,
    /**
     * How long the capture may run without handing us a single sample before we give the key back.
     * `AudioRecord` says it is running and reads zero when another app owns the input or the device
     * went away under it, and the radio does not notice: our silent frames keep it keyed until the 3
     * min limit, so the transmitter sits on the air carrying nothing. The operator's fix is to find
     * out now, not after three minutes.
     */
    private val micStallMs: Long = 500,
    private val listener: (TciTxStateMachine.State, String?) -> Unit = { _, _ -> },
) {

    /** Idle: not keyed and nothing sent. Starting: the key is out, the first pacing frame is not. Keyed: the radio is pacing us. */
    enum class State { Idle, Starting, Keyed }

    private val lock = Any()

    private var state = State.Idle
    private var reason: String? = null

    /** Retired on every release: a chrono that arrives after it is answered with nothing. */
    private var generation = 0L

    private var confirmTask: PttTask? = null
    private var limitTask: PttTask? = null
    private var silenceTask: PttTask? = null

    /** When the last pacing frame came: the gap the watchdog and the radio both count. */
    private var lastChronoMs = 0L

    /** When the microphone last put a sample in our hands, running or not. */
    private var lastSampleMs = 0L

    /** Kept for the screen and the tests. */
    private var chronos = 0L
    private var framesSent = 0L
    private var framesSilent = 0L

    val keyed: Boolean get() = synchronized(lock) { state != State.Idle }

    val currentState: State get() = synchronized(lock) { state }

    val lastReason: String? get() = synchronized(lock) { reason }

    /** Frames of our microphone the radio has heard, and those that were silence. */
    fun framesSent(): Long = synchronized(lock) { framesSent }

    fun framesSilent(): Long = synchronized(lock) { framesSilent }

    /**
     * Press, with our microphone behind it.
     *
     * @return false, and nothing keyed, when a session is already running, when the microphone would
     *         not start, or when the socket would not take the key.
     */
    fun press(): Boolean = synchronized(lock) {
        if (state != State.Idle) {
            reason = "a transmission is already running"
            listener(state, reason)
            return false
        }
        if (!mic.start() || !mic.running) {
            mic.stop()
            reason = "the microphone is not ready: no transmission"
            listener(State.Idle, reason)
            return false
        }
        if (!send(TciCommands.key(withAudio = true))) {
            mic.stop()
            reason = "the radio is not reached: no transmission"
            listener(State.Idle, reason)
            return false
        }
        generation++
        state = State.Starting
        reason = null
        chronos = 0
        framesSent = 0
        framesSilent = 0
        lastChronoMs = clock.nowMs()
        lastSampleMs = clock.nowMs()
        armConfirmLocked(generation)
        armLimitLocked(generation)
        armSilenceLocked(generation)
        listener(state, null)
        true
    }

    /**
     * A frame from the TCI link. Only a `TX_CHRONO` of a live session is answered, and the answer is
     * always a frame the firmware will read as audio: our microphone's samples, silence filling what
     * the capture has not delivered, because the radio wants a steady stream and not a gap.
     *
     * @return true when this was a pacing frame we took care of, whether or not we answered it.
     */
    fun onFrame(frame: TciAudioFrame): Boolean {
        val ask = TciAudioFrameCodec.chronoRequest(frame) ?: return false
        return synchronized(lock) {
            if (state == State.Idle) {
                // Pacing for a session that is not ours, or one that has ended. The radio is talking
                // to a client that is keyed; a frame from us now would be audio after an unkey.
                return false
            }
            val gen = generation
            lastChronoMs = clock.nowMs()
            chronos++
            if (state != State.Keyed) {
                confirmTask?.cancel()
                confirmTask = null
                state = State.Keyed
                reason = null
                listener(state, null)
            }
            if (!mic.running) {
                letGoLocked("the microphone went away: the transmission is over", sendUnkey = true)
                return true
            }
            val got = mic.read(ask.samples)
            if (got.isNotEmpty()) {
                lastSampleMs = clock.nowMs()
            } else if (clock.nowMs() - lastSampleMs >= micStallMs) {
                // Running, and nothing to send: another app owns the input, or the device died without
                // saying so. One more silent frame would only keep the radio on the air carrying
                // nothing, so this is where the transmission ends.
                letGoLocked(
                    "the microphone delivered nothing for ${micStallMs}ms: the transmission is over",
                    sendUnkey = true,
                )
                return true
            }
            if (got.size < ask.samples) framesSilent++
            val payload = zeroFilledTo(got, ask.samples)
            val out = TciAudioFrameCodec.txAudioOrNull(
                samples = payload,
                receiver = ask.receiver,
                sampleRate = if (ask.sampleRate > 0) ask.sampleRate else TciAudioFrameCodec.SAMPLE_RATE,
                channels = TciAudioFrameCodec.CHANNELS,
            )
            if (out == null) {
                // We built a frame the radio would read as noise; it is not sent, and the session ends
                // here rather than waiting for the radio to notice the gap.
                letGoLocked("our own audio frame was not one the radio can read", sendUnkey = true)
                return true
            }
            if (!sendFrame(out)) {
                letGoLocked("the radio is not reached: the audio frame was lost", sendUnkey = false)
                return true
            }
            framesSent++
            armSilenceLocked(gen)
            true
        }
    }

    /**
     * `trx:0,true;` / `trx:0,false;` from the TCI server. The `false` is the radio taking the key back
     * — the silence watchdog, the 3 min limit, the SWR guard, an operator's unkey — and every renewal
     * of audio stops with it.
     */
    fun onTransmitting(on: Boolean) = synchronized(lock) {
        if (!on && state != State.Idle) letGoLocked("the radio ended the transmission", sendUnkey = true)
    }

    /** The operator let go. */
    fun release() = synchronized(lock) {
        if (state == State.Idle) return
        letGoLocked(null, sendUnkey = true)
    }

    /**
     * Let go for good: the link dropped, the app is going away, the service is stopping. The unkey is
     * sent even when the link looks dead — a socket that can still write should say it — and the
     * microphone is handed back either way.
     */
    fun failSafe(reason: String) = synchronized(lock) {
        if (state == State.Idle) {
            mic.stop()
            this.reason = reason
            listener(State.Idle, reason)
            return
        }
        letGoLocked(reason, sendUnkey = true)
    }

    /** The TCI link is gone: the radio will unkey a silent client, and we tell it so anyway. */
    fun linkLost(reason: String) = failSafe(reason)

    /**
     * Back to Idle with the microphone handed back: what a service's `onDestroy` leaves behind. A
     * session still live here is ended with the unkey first — reset must never walk away from a keyed
     * radio — and the normal order of `failSafe` then `reset` means there is nothing live to say it for.
     */
    fun reset() = synchronized(lock) {
        val wasLive = state != State.Idle
        cancelTimersLocked()
        mic.stop()
        if (wasLive) send(TciCommands.unkey())
        state = State.Idle
        reason = null
        listener(state, null)
    }

    // ---- the timers ----

    private fun armConfirmLocked(gen: Long) {
        confirmTask = scheduler.scheduleAt(clock.nowMs() + keyConfirmMs) {
            synchronized(lock) {
                if (gen != generation || state != State.Starting) return@synchronized
                letGoLocked("the radio did not key: no pacing in ${keyConfirmMs}ms", sendUnkey = true)
            }
        }
    }

    private fun armLimitLocked(gen: Long) {
        limitTask = scheduler.scheduleAt(clock.nowMs() + txLimitMs) {
            synchronized(lock) {
                if (gen != generation || state == State.Idle) return@synchronized
                letGoLocked("this app's own limit of ${txLimitMs / 1000} s on the air", sendUnkey = true)
            }
        }
    }

    /** The same gap the radio counts: no pacing frame means the link is gone and we are off the air. */
    private fun armSilenceLocked(gen: Long) {
        silenceTask?.cancel()
        silenceTask = scheduler.scheduleAt(clock.nowMs() + silenceMs) {
            synchronized(lock) {
                if (gen != generation || state == State.Idle) return@synchronized
                if (clock.nowMs() - lastChronoMs < silenceMs) return@synchronized
                letGoLocked("the radio stopped pacing us: the transmission is over", sendUnkey = true)
            }
        }
    }

    private fun letGoLocked(reason: String?, sendUnkey: Boolean) {
        val wasLive = state != State.Idle
        cancelTimersLocked()
        mic.stop()
        state = State.Idle
        this.reason = reason ?: this.reason
        if (wasLive && sendUnkey) send(TciCommands.unkey())
        listener(state, this.reason)
    }

    private fun cancelTimersLocked() {
        confirmTask?.cancel()
        limitTask?.cancel()
        silenceTask?.cancel()
        confirmTask = null
        limitTask = null
        silenceTask = null
        generation++
    }

    /** The samples the radio asked for, silence filling what the capture has not delivered. */
    private fun zeroFilledTo(got: ShortArray, count: Int): ShortArray {
        val n = minOf(got.size, count)
        if (n == count) return got
        val out = ShortArray(count)
        System.arraycopy(got, 0, out, 0, n)
        return out
    }
}
