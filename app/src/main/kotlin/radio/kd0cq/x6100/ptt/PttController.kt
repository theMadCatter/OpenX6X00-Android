// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.ptt

import kotlinx.coroutines.flow.StateFlow

/**
 * The key, as the radio's own page treats it: a press is a lease, not a latch.
 *
 * The panel server keeps a PTT press down for three seconds unless the same hold number is sent again,
 * which the radio's own page does every second, and lets go at three minutes on the air
 * (`remote_server.c`: `ptt_down` and `ptt_lease`). An ended hold stays ended: only a new number keys
 * again, and the radio answers "err ptt released: ...". The TCI server is harsher still: keyed with a
 * client's audio, it unkeys after 500 ms without a frame.
 *
 * So the three methods are all a caller needs, and the difference between them is only who asked:
 *  - [press] picks a hold number above the last one used, sends "key PTT 1 HOLD", and renews it once a
 *    second until [release] sends "key PTT 0";
 *  - [release] is the operator letting go;
 *  - [failSafe] is everything else — the link dropping, the service dying, this app going to the
 *    background — and lets go at once, whatever the link's state, and says in [refusal] why.
 *
 * [LeasePttController] is what the app runs: the Panel lease, or the TCI audio path with this phone's
 * microphone. There is deliberately no do-nothing implementation here any more: a placeholder that
 * silently never keys made it look like the key worked.
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
