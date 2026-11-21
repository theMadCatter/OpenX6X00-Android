// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Which spot labels fit on the waterfall, and which spots are worth a notification.
// The radio already marks a spot with alert=true. A local watch is an exact call,
// or a prefix when the pattern ends in *. Notifications are rate-limited so a
// busy cluster does not buzz the phone on every frame.

package radio.kd0cq.x6100.spots

import radio.kd0cq.x6100.log.QsoBook

object SpotLabels {
    /**
     * Indexes to draw, newest first. A label whose box overlaps one already kept is dropped.
     * [left] and [right] are the label edges in pixels.
     */
    fun keep(left: FloatArray, right: FloatArray, gap: Float = 4f): List<Int> {
        val kept = ArrayList<Int>(left.size)
        val boxes = ArrayList<Pair<Float, Float>>(left.size)
        for (i in left.indices) {
            val a = left[i]
            val b = right[i]
            if (boxes.any { b + gap > it.first && a < it.second + gap }) continue
            boxes += a to b
            kept += i
        }
        return kept
    }
}

object Hunter {
    fun matches(call: String, watches: List<String>, radioAlert: Boolean): Boolean {
        if (radioAlert) return true
        val base = QsoBook.baseCall(call)
        if (base.isEmpty()) return false
        return watches.any { raw ->
            val pattern = raw.trim().uppercase()
            if (pattern.isEmpty()) return@any false
            if (pattern.endsWith("*")) {
                val prefix = QsoBook.baseCall(pattern.removeSuffix("*"))
                prefix.isNotEmpty() && base.startsWith(prefix)
            } else {
                QsoBook.baseCall(pattern) == base
            }
        }
    }

    fun key(call: String, freqHz: Int): String = "${QsoBook.baseCall(call)}@$freqHz"

    /** Spots that were not in the previous set. The radio sends the whole list each time. */
    fun fresh(previous: Set<String>, calls: List<String>, freqs: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        for (i in calls.indices) {
            if (key(calls[i], freqs[i]) !in previous) out += i
        }
        return out
    }
}

object AlertGate {
    const val GLOBAL_MS = 30_000L
    const val PER_CALL_MS = 600_000L

    fun allow(nowMs: Long, lastGlobalMs: Long, lastForCallMs: Long): Boolean {
        if (lastGlobalMs != 0L && nowMs - lastGlobalMs < GLOBAL_MS) return false
        if (lastForCallMs != 0L && nowMs - lastForCallMs < PER_CALL_MS) return false
        return true
    }
}
