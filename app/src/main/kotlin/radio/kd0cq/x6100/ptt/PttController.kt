// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.ptt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The key, as the radio's own page treats it: a press is a lease, not a latch.
 *
 * The panel server keeps a PTT press down for three seconds unless the same hold number is sent again,
 * which the radio's own page does every second, and lets go at three minutes on the air
 * (remote_server.c: ptt_down and ptt_lease). An ended hold stays ended: only a new number keys again,
 * and the radio answers "err ptt released: ...". The TCI server is harsher still: keyed with a
 * client's audio, it unkeys after 500 ms without a frame.
 *
 * TODO(ptt): the skeleton stops here on purpose; nothing can key the radio yet. What it will take:
 *  - press(): pick a hold number above the last one used, send "key PTT 1 HOLD", and repeat the same
 *    line once a second while the button is held; a release sends "key PTT 0".
 *  - On TCI, send trx:0,true,tci and answer every TX_CHRONO tick the radio sends with one TX_AUDIO
 *    frame; it unkeys a transmitter that goes silent for 500 ms.
 *  - Fail-safe: the link dropping, the service dying, or this app going to the background must all
 *    unkey at once, and [keyed] must say so before anything else is asked of the radio.
 *
 * Until then [NoOpPttController] never keys, so a stray tap cannot put the radio on the air.
 */
interface PttController {

    /** Whether we hold the key now. */
    val keyed: StateFlow<Boolean>

    /** Who is on the air, in the words the radio used to refuse us. */
    val refusal: StateFlow<String?>

    /** Press, and start renewing the lease with a hold number of our own. */
    fun press()

    /** Release, and stop the renewals. */
    fun release()

    /** Let go for good: the link dropped, the service is stopping, the screen is locked. */
    fun failSafe(reason: String)
}

/** The placeholder the link runs on: it records nothing, renews nothing, and never keys. */
class NoOpPttController : PttController {

    private val held = MutableStateFlow(false)
    private val refused = MutableStateFlow<String?>(null)

    override val keyed: StateFlow<Boolean> = held
    override val refusal: StateFlow<String?> = refused

    override fun press() {
        // TODO(ptt): send "key PTT 1 HOLD" with a fresh hold number and renew it every second
    }

    override fun release() {
        // TODO(ptt): send "key PTT 0" and stop the renewals
    }

    override fun failSafe(reason: String) {
        refused.value = reason
        // TODO(ptt): send the release now, whatever the link's state
    }
}
