// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Before this, leaving the screen, losing the link on purpose, or losing the
// right to transmit sent `key PTT 0` and `trx:0,false` and did not tell the
// radio's own FT8 or JS8 engine to stop. Those engines keep transmitting
// after the phone is gone. `ft8 stop` and `js8 stop` are the words the
// firmware already answers. There is no WeFax transmit word on the panel,
// so there is nothing to send for it.
//
// A stop the socket refuses is offered again, the same idea as a refused
// `key PTT 0`. A stop the socket accepts is not sent again by the retry.

package radio.kd0cq.x6100.core

import radio.kd0cq.x6100.net.PanelCommands

/**
 * `ft8 stop` and `js8 stop`, with a retry when the write does not land.
 */
class DigitalUnkey(
    private val send: (String) -> Boolean,
    private val clock: PttClock,
    private val scheduler: PttScheduler,
    private val retryMs: Long = 250,
    private val retries: Int = 12,
) {

    /** The lines, in the order [stop] offers them. Tests lock this list. */
    val lines: List<String> = listOf(
        PanelCommands.ft8("stop"),
        PanelCommands.js8("stop"),
    )

    /**
     * Ask the radio to stop both digital engines.
     *
     * Safe when neither is transmitting: the radio accepts the word and the
     * engine that is not running has nothing to end. Called when the screen
     * goes away, the service stops, control is given back, or the session
     * loses the right to transmit. Not called for a finger coming off PTT.
     */
    fun stop() {
        lines.forEach { offer(it, retries) }
    }

    private fun offer(line: String, left: Int) {
        if (send(line)) return
        if (left <= 0) return
        scheduler.scheduleAt(clock.nowMs() + retryMs) { offer(line, left - 1) }
    }
}
