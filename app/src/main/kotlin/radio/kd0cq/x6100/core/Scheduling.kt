// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.core

/**
 * The two things the safety logic needs from the world outside it: a clock that only ever moves
 * forward, and somewhere to put a timer. Both are interfaces so the tests can drive time by hand and
 * watch a lease renew, expire and let go without a real second passing.
 *
 * Every time here is monotonic milliseconds, never a wall clock: a phone's clock jumping an hour
 * must not lengthen or cut short a transmission.
 */

/** Monotonic time in milliseconds; `SystemClockPttClock` in the ptt package is the Android one. */
fun interface PttClock {
    fun nowMs(): Long
}

/** A timer that has been asked for and not run yet. Cancelling one twice, or after it ran, is fine. */
fun interface PttTask {
    fun cancel()
}

/** Where a state machine's timers live: a handler on the phone, a manual one in a test. */
interface PttScheduler {

    /** Run [task] once, at or after the absolute monotonic time [atMs]. */
    fun scheduleAt(atMs: Long, task: () -> Unit): PttTask
}

/** A scheduler that runs nothing: for a machine whose timers must never fire while it is steered by hand. */
object NullScheduler : PttScheduler {
    override fun scheduleAt(atMs: Long, task: () -> Unit): PttTask = PttTask { }
}

/** A clock the tests move: [advance] is the only way time passes. */
class ManualClock(startMs: Long = 0L) : PttClock {

    @Volatile
    var nowMs: Long = startMs
        private set

    override fun nowMs(): Long = nowMs

    fun advance(ms: Long) {
        nowMs += ms
    }

    internal fun moveTo(ms: Long) {
        if (ms > nowMs) nowMs = ms
    }
}

/**
 * A scheduler the tests advance themselves. [advance] moves the clock with it and runs what came due,
 * in due order, including a timer a running task asked for; a timer cancelled before its moment never
 * runs. That is the exact question a stuck transmitter turns on: does a renewal asked for a second
 * ago still go out after the lease was let go?
 */
class ManualScheduler(private val clock: ManualClock = ManualClock()) : PttScheduler {

    private class Item(val dueMs: Long, val seq: Long, val task: () -> Unit) {
        var live = true
    }

    private val items = ArrayList<Item>()
    private var seq = 0L

    /** The clock this scheduler moves along, so a test can press, wait and check with one object. */
    fun clock(): PttClock = clock

    /** Timers still waiting to run; for a test that wants to say "nothing is pending". */
    fun pendingCount(): Int = synchronized(items) { items.count { it.live } }

    override fun scheduleAt(atMs: Long, task: () -> Unit): PttTask = synchronized(items) {
        val item = Item(atMs, seq++, task)
        items += item
        PttTask { synchronized(items) { item.live = false } }
    }

    /** Move time on by [ms] and run every timer that comes due, in due order. */
    fun advance(ms: Long) {
        val until = clock.nowMs + ms
        while (true) {
            val next = synchronized(items) {
                val due = items.filter { it.live && it.dueMs <= until }
                    .minWithOrNull(compareBy<Item> { it.dueMs }.thenBy { it.seq })
                if (due != null) items.remove(due)
                due
            } ?: break
            clock.moveTo(next.dueMs)
            if (next.live) next.task()
        }
        clock.moveTo(until)
    }

    /** Forget everything still waiting, as a killed process would. */
    fun clear() = synchronized(items) { items.clear() }
}
