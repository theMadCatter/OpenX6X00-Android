// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// A QSO as this app keeps it, the ADIF line it exports, and "worked before".
// The radio's own log command has no time field (remote_server.c: "log CALL|RSTS|RSTR|NAME|QTH|GRID|COUNTRY"),
// so the time lives only in this copy. The radio still gets the same text its CW keyboard writes.

package radio.kd0cq.x6100.log

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class Qso(
    val call: String,
    val freqHz: Int,
    val mode: String,
    val timeUtcMs: Long,
    val rstSent: String,
    val rstRcvd: String,
    val name: String,
    val qth: String,
    val grid: String,
    val country: String,
    val radioLogId: Long,
)

object QsoBook {
    /** The same call, ignoring case and a trailing portable suffix (/P, /M, /QRP). */
    fun workedBefore(call: String, rows: List<Qso>): Boolean {
        val want = baseCall(call)
        if (want.isEmpty()) return false
        return rows.any { baseCall(it.call) == want }
    }

    fun baseCall(call: String): String {
        val head = call.trim().uppercase().substringBefore('/')
        return head.filter { it.isLetterOrDigit() }
    }

    /**
     * The panel line. Blank fields are kept in place so the radio's column order stays
     * CALL|RSTS|RSTR|NAME|QTH|GRID|COUNTRY. A blank in the middle is still a column.
     */
    fun radioText(qso: Qso): String = listOf(
        qso.call.trim().uppercase(),
        qso.rstSent.trim(),
        qso.rstRcvd.trim(),
        qso.name.trim(),
        qso.qth.trim(),
        qso.grid.trim().uppercase(),
        qso.country.trim(),
    ).joinToString("|")

    /** One ADIF file. Dates are UTC. A field that is blank is left out, which ADIF allows. */
    fun adif(rows: List<Qso>): String {
        val head = "OpenX6X00 Android log\n<ADIF_VER:5>3.1.4\n<EOH>\n"
        return head + rows.joinToString(separator = "") { row -> record(row) }
    }

    private fun record(qso: Qso): String {
        val instant = Instant.ofEpochMilli(qso.timeUtcMs).atOffset(ZoneOffset.UTC)
        val date = DateTimeFormatter.ofPattern("yyyyMMdd").format(instant)
        val time = DateTimeFormatter.ofPattern("HHmmss").format(instant)
        val mhz = qso.freqHz / 1_000_000.0
        return buildString {
            field("CALL", qso.call.uppercase())
            field("QSO_DATE", date)
            field("TIME_ON", time)
            field("FREQ", String.format(java.util.Locale.US, "%.6f", mhz))
            field("MODE", qso.mode)
            field("RST_SENT", qso.rstSent)
            field("RST_RCVD", qso.rstRcvd)
            field("NAME", qso.name)
            field("QTH", qso.qth)
            field("GRIDSQUARE", qso.grid.uppercase())
            field("COUNTRY", qso.country)
            append("<EOR>\n")
        }
    }

    private fun StringBuilder.field(name: String, value: String) {
        val text = value.trim()
        if (text.isEmpty()) return
        append('<').append(name).append(':').append(text.length).append('>').append(text).append(' ')
    }
}
