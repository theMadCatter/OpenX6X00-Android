// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.ptt

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import radio.kd0cq.x6100.core.PttClock
import radio.kd0cq.x6100.core.PttScheduler
import radio.kd0cq.x6100.core.PttTask

/**
 * The clock the leases run on: [SystemClock.elapsedRealtime], which counts since boot and is not
 * adjusted for the wall clock, a time zone or a network time update. A phone whose clock jumps an hour
 * must neither lengthen a transmission nor cut one short, and `System.currentTimeMillis()` would do
 * either.
 */
object SystemClockPttClock : PttClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * The timers on the phone's main looper.
 *
 * They are the slow timers — a PTT renewal once a second, a cap after minutes, a settle after a
 * second and a half — and each one only writes a line to a socket, which is what the main thread is
 * for. The fast path is not here: a `TX_CHRONO` is answered on the link's own thread, because 21.3 ms
 * is shorter than a frame on a busy UI and a missed tick is what makes the radio unkey us.
 */
class HandlerPttScheduler(
    looper: Looper = Looper.getMainLooper(),
    private val clock: PttClock = SystemClockPttClock,
) : PttScheduler {

    private val handler = Handler(looper)

    override fun scheduleAt(atMs: Long, task: () -> Unit): PttTask {
        val runnable = Runnable { task() }
        val delayMs = (atMs - clock.nowMs()).coerceAtLeast(0L)
        handler.postDelayed(runnable, delayMs)
        return PttTask { handler.removeCallbacks(runnable) }
    }

    /** Anything still waiting, dropped: what a service's `onDestroy` leaves. */
    fun clear() = handler.removeCallbacksAndMessages(null)
}
