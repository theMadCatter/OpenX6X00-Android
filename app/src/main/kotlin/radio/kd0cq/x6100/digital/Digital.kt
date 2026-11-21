// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// FT8 list rules, and the JS8 words.
//
// remote_server.c accepts "ft8 open|close|cq|stop|auto|hold|mode", "ft8 txfreq HZ",
// "ft8 reply ID" and "ft8 txcall". A tap-to-reply uses "ft8 reply ID", which is the
// app's own transmit. This file does not key PTT.
//
// The same file has no "js8", "js8r" or "js8 wf". Those words are parsed and built
// here so a later firmware can use them. The screen does not send them while
// [JS8_IN_FIRMWARE] is false: the radio would answer "err unknown command", and a
// guessed transmit is not a transmit.

package radio.kd0cq.x6100.digital

import org.json.JSONObject
import radio.kd0cq.x6100.log.Qso
import radio.kd0cq.x6100.log.QsoBook

/** False: remote_server.c does not dispatch js8. */
const val JS8_IN_FIRMWARE = false

object Ft8List {
    /** Milliseconds left in the slot. The radio's own in/slot win. Otherwise a 15 s UTC period. */
    fun remainingMs(nowEpochMs: Long, slotMs: Int, inMs: Int): Int {
        if (slotMs > 0) return (slotMs - inMs).coerceAtLeast(0)
        val into = (nowEpochMs % 15_000L).toInt()
        return 15_000 - into
    }

    fun isCq(text: String): Boolean = text.trim().uppercase().startsWith("CQ ") || text.trim().uppercase() == "CQ"

    /** True when [myCall] appears as a token, ignoring a portable suffix. */
    fun mentionsMe(text: String, myCall: String): Boolean {
        val me = QsoBook.baseCall(myCall)
        if (me.length < 3) return false
        return text.uppercase().split(Regex("[^A-Z0-9/]+")).any { QsoBook.baseCall(it) == me }
    }

    fun worked(call: String, rows: List<Qso>): Boolean = QsoBook.workedBefore(call, rows)
}

data class Js8Row(
    val id: Long,
    val kind: String,
    val text: String,
    val call: String,
    val snr: Int,
    val freqHz: Int,
    val directed: Boolean,
)

object Js8Protocol {
    fun command(what: String, arg: String? = null): String =
        if (arg.isNullOrBlank()) "js8 $what" else "js8 $what $arg"

    fun waterfall(on: Boolean): String = "js8 wf ${if (on) 1 else 0}"

    /** "js8r {json}". Anything else is not a row. */
    fun parse(line: String): Js8Row? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("js8r ")) return null
        val json = trimmed.removePrefix("js8r ").trim()
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val text = o.optString("text")
        return Js8Row(
            id = o.optLong("id"),
            kind = o.optString("kind", "activity"),
            text = text,
            call = o.optString("call"),
            snr = o.optInt("snr"),
            freqHz = o.optInt("freq"),
            directed = o.optInt("directed") != 0 || text.contains(">"),
        )
    }
}
