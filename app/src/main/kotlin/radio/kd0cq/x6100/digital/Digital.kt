// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// FT8 list rules, and the JS8 words the radio actually answers.
//
// Before this, JS8 was a local note. The constant said the firmware had no
// js8 command, the parser read text/freq/call/directed, and a transmit was
// built as "js8 tx", which remote_server.c has never dispatched. A tap could
// not reach the radio's JS8 engine, and a guessed line is not a transmit.
//
// OpenX6X00 v0.3.13 (commit b2c837d, "Remote: the JS8 app from the browser")
// added the words. This file builds those words and reads the JSON the radio
// sends. It does not open a socket and it does not transmit by itself.
// "js8 send TEXT" keeps the spaces: the radio fills the macros. "js8 tx" is
// not a command. The screen asks before Send and CQ, and it does not call
// any transmit word from composition.
//
// FT8 is unchanged. "ft8 reply ID" is still the FT8 app's own transmit, and
// this file still does not key PTT.

package radio.kd0cq.x6100.digital

import org.json.JSONObject
import radio.kd0cq.x6100.log.Qso
import radio.kd0cq.x6100.log.QsoBook

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

/**
 * One JS8 message, as `js8r` carries it.
 *
 * The radio keeps the same [id] while a message is still arriving and the text
 * grows. A later line with that id replaces the row. It does not become a
 * second row. [freqHz] is the audio offset `f`, not a dial frequency.
 * [submode] is the message's own speed bits (0 normal, 1 fast, 2 turbo, 4 slow),
 * which is not the 0..3 index on the state line.
 */
data class Js8Row(
    val id: Long,
    val utc: Int,
    val snr: Int,
    val freqHz: Int,
    val submode: Int,
    val transmitting: Boolean,
    val toMe: Boolean,
    val cq: Boolean,
    val heartbeat: Boolean,
    val group: Boolean,
    val lowConfidence: Boolean,
    val partial: Boolean,
    val checksum: Boolean,
    val from: String,
    val to: String,
    val grid: String,
    val text: String,
)

/**
 * The JS8 app's state, as one `js8` JSON line carries it.
 *
 * [heartbeat] and [auto] are whatever the radio last reported. Nothing here
 * turns them on. [speed] is js8_speed_t: 0 normal, 1 fast, 2 turbo, 3 slow.
 * [txFreqHz] is the transmit offset the next message will use.
 */
data class Js8Status(
    val open: Boolean,
    val busy: Boolean,
    val sending: Boolean,
    val autoCq: Boolean,
    val heartbeat: Boolean,
    val auto: Boolean,
    val hold: Boolean,
    val speed: Int,
    val frame: Int,
    val frames: Int,
    val txFreqHz: Int,
    val selectedHz: Int,
    val heartbeatMinutes: Int,
    val nextSeconds: Int,
    val notes: Int,
    val noteError: Boolean,
    val noteFor: String,
    val note: String,
    val selectedCall: String,
    val where: String,
    val text: String,
)

object Js8Protocol {
    /** The radio's room for one message, TX_TEXT_MAX in js8_window.c. */
    const val TEXT_MAX = 500

    /** The panel replays this many messages when a client subscribes. */
    const val ROWS_KEPT = 64

    /**
     * What an older radio says. handle_text's last branch is
     * "err unknown command js8". A radio that has the command and rejects one
     * word says "err js8 WORD" instead, and that is not this.
     */
    const val NEEDS_FIRMWARE =
        "This radio's JS8 remote needs OpenX6X00 v0.3.13 or newer."

    /** "js8 open", "js8 cq", "js8 speed". No argument. Send, select and txfreq have their own builders. */
    fun command(what: String): String = "js8 $what"

    /**
     * "js8 send TEXT", spaces included, or null when there is nothing to send
     * or the text is past the radio's room. The radio fills macros. This does
     * not build "js8 tx".
     */
    fun send(text: String): String? {
        val body = text.replace('\n', ' ').replace('\r', ' ').trim()
        if (body.isEmpty() || body.length > TEXT_MAX) return null
        return "js8 send $body"
    }

    /** "js8 select CALL". A blank call, or one with a space, is not sent. */
    fun select(call: String): String? {
        val body = call.trim()
        if (body.isEmpty() || body.any { it.isWhitespace() }) return null
        return "js8 select $body"
    }

    /** "js8 txfreq HZ". The offset is audio Hz, never below zero. */
    fun txFreq(hz: Int): String = "js8 txfreq ${hz.coerceAtLeast(0)}"

    /** "js8 wf 1" or "js8 wf 0". Watching. The radio does not ask for a role. */
    fun waterfall(on: Boolean): String = "js8 wf ${if (on) 1 else 0}"

    /** The speed button's label. An index the radio does not use is shown as Normal. */
    fun speedName(speed: Int): String = when (speed) {
        1 -> "Fast"
        2 -> "Turbo"
        3 -> "Slow"
        else -> "Normal"
    }

    /**
     * How wide the eight tones are, in Hz, for the speed the radio is on.
     * The browser client uses 50, 80, 160 and 25. The band-edge check uses the
     * widest unknown as Normal, which is the radio's own default.
     */
    fun toneWidthHz(speed: Int): Int = when (speed) {
        1 -> 80
        2 -> 160
        3 -> 25
        else -> 50
    }

    /** A letter for the message's submode bits. Normal has none. */
    fun submodeLetter(submode: Int): String = when (submode) {
        1 -> "F"
        2 -> "T"
        4 -> "S"
        else -> ""
    }

    /**
     * True when the radio does not know the js8 verb at all.
     * "js8 send: ..." means the verb is there and this particular send was refused.
     */
    fun needsNewerFirmware(answer: String?): Boolean {
        val text = answer?.trim()?.lowercase() ?: return false
        return text == "unknown command js8" || text.startsWith("unknown command js8 ")
    }

    /** "js8r {...}", or null when the line is some other verb or the JSON will not parse. */
    fun parse(line: String): Js8Row? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("js8r")) return null
        val json = trimmed.removePrefix("js8r").trim()
        if (!json.startsWith("{")) return null
        return parseRow(json)
    }

    /** The object after "js8r ". The wire names are the ones js8_msg_line writes. */
    fun parseRow(json: String): Js8Row? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return Js8Row(
            id = o.optLong("id"),
            utc = o.optInt("utc"),
            snr = o.optInt("snr"),
            freqHz = o.optInt("f"),
            submode = o.optInt("sp"),
            transmitting = o.on("tx"),
            toMe = o.on("me"),
            cq = o.on("cq"),
            heartbeat = o.on("hb"),
            group = o.on("grp"),
            lowConfidence = o.on("lc"),
            partial = o.on("part"),
            checksum = o.on("chk"),
            from = o.optString("fr"),
            to = o.optString("to"),
            grid = o.optString("g"),
            text = o.optString("x"),
        )
    }

    /** "js8 {...}", or null when the line is not that state. */
    fun parseState(line: String): Js8Status? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("js8")) return null
        if (trimmed.startsWith("js8r")) return null
        val json = trimmed.removePrefix("js8").trim()
        if (!json.startsWith("{")) return null
        return parseStatus(json)
    }

    /** The object after "js8 ". The wire names are the ones js8_status_line writes. */
    fun parseStatus(json: String): Js8Status? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return Js8Status(
            open = o.on("open"),
            busy = o.on("busy"),
            sending = o.on("sending"),
            autoCq = o.on("acq"),
            heartbeat = o.on("hb"),
            auto = o.on("auto"),
            hold = o.on("hold"),
            speed = o.optInt("speed"),
            frame = o.optInt("frame"),
            frames = o.optInt("frames"),
            txFreqHz = o.optInt("txf"),
            selectedHz = o.optInt("selhz"),
            heartbeatMinutes = o.optInt("hbmin"),
            nextSeconds = o.optInt("next"),
            notes = o.optInt("notes"),
            noteError = o.on("noteerr"),
            noteFor = o.optString("notefor"),
            note = o.optString("note"),
            selectedCall = o.optString("sel"),
            where = o.optString("where"),
            text = o.optString("text"),
        )
    }

    /**
     * Put [row] where its id already is, or on the end when the id is new.
     * A message that is still arriving keeps its place and grows. Past
     * [ROWS_KEPT], the oldest rows drop, which is the radio's own ring.
     */
    fun upsert(rows: List<Js8Row>, row: Js8Row, keep: Int = ROWS_KEPT): List<Js8Row> {
        val at = rows.indexOfFirst { it.id == row.id }
        val next = if (at < 0) {
            rows + row
        } else {
            rows.toMutableList().also { it[at] = row }
        }
        return if (next.size <= keep) next else next.takeLast(keep)
    }
}

/**
 * GhostNet is a way of using JS8, not a panel command.
 *
 * The radio's JS8 settings, or the web configurator, turn the mode on. That
 * tunes a GhostNet dial and adds @GHOSTNET, @GSTFLASH and one regional group.
 * This app filters the messages it already decoded. It does not invent a
 * command that would switch the mode.
 *
 * The North America net is Thursday 0100Z on a 7.107 MHz dial, which is
 * Wednesday 7 PM Central. Heartbeats for that net stay below a 1000 Hz offset,
 * so the GhostNet list leaves a heartbeat at or above that offset out.
 */
object GhostNet {
    const val WORLD = "@GHOSTNET"
    const val FLASH = "@GSTFLASH"
    const val DEFAULT_REGION = "@GNUSAMO"

    /** Audio Hz. A heartbeat at this offset, or above it, is outside the net's window. */
    const val HEARTBEAT_OFFSET_LIMIT_HZ = 1000

    const val REMINDER =
        "North America net: Thursday 0100Z on a 7.107 MHz dial, which is Wednesday 7 PM Central. " +
            "Keep heartbeats below a 1000 Hz offset."

    const val SETTINGS_NOTE =
        "GhostNet mode is turned on in the radio's JS8 settings or its web config. " +
            "There is no remote command for that switch."

    /** "@GNUSAMO" as typed, or with the @ added. Blank stays blank: no regional group. */
    fun groupToken(raw: String): String {
        val token = raw.trim().uppercase()
        if (token.isEmpty()) return ""
        return if (token.startsWith("@")) token else "@$token"
    }

    /** True when this heartbeat's audio offset is inside the net's window. */
    fun heartbeatInWindow(offsetHz: Int): Boolean = offsetHz in 0 until HEARTBEAT_OFFSET_LIMIT_HZ

    fun mentions(row: Js8Row, group: String): Boolean {
        val token = groupToken(group)
        if (token.isEmpty()) return false
        return row.text.contains(token, ignoreCase = true) ||
            row.to.contains(token, ignoreCase = true) ||
            row.from.contains(token, ignoreCase = true)
    }

    fun isFlash(row: Js8Row): Boolean = mentions(row, FLASH)

    /**
     * @GHOSTNET, @GSTFLASH, and [region]. A heartbeat whose offset is at or
     * above 1000 Hz is left out even when it names a group.
     */
    fun filter(rows: List<Js8Row>, region: String): List<Js8Row> = rows.filter { row ->
        if (row.heartbeat && !heartbeatInWindow(row.freqHz)) return@filter false
        mentions(row, WORLD) || isFlash(row) || mentions(row, region)
    }
}

/**
 * Where a US Advanced control operator may put a data emission, ITU Region 2.
 *
 * 47 CFR 97.301(c) is the Advanced allocation. 97.305 is where RTTY and data
 * are authorized inside it. The two are intersected here. Phone-only stretches
 * are not data: 3.700–4.000 MHz and 14.150–14.350 MHz are absent on purpose.
 * 6 m, 2 m, 1.25 m and 70 cm follow 97.301(a), which every class shares and
 * where data is allowed across the amateur band.
 *
 * The whole signal has to sit inside one segment. A carrier that starts inside
 * and a tone that ends outside is refused. JS8's dial plus its audio offset is
 * the low edge, and the tone width is added for the high edge.
 */
object UsAdvanced {
    val dataHz: List<LongRange> = listOf(
        1_800_000L..2_000_000L,
        3_525_000L..3_600_000L,
        5_351_500L..5_366_500L,
        7_025_000L..7_125_000L,
        10_100_000L..10_150_000L,
        14_025_000L..14_150_000L,
        18_068_000L..18_110_000L,
        21_025_000L..21_200_000L,
        24_890_000L..24_930_000L,
        28_000_000L..28_300_000L,
        50_000_000L..54_000_000L,
        144_000_000L..148_000_000L,
        222_000_000L..225_000_000L,
        420_000_000L..450_000_000L,
    )

    /** True when [lowHz] and [highHz] are inside the same data segment, both edges included. */
    fun dataCovers(lowHz: Long, highHz: Long): Boolean {
        if (highHz < lowHz) return false
        return dataHz.any { band -> lowHz in band && highHz in band }
    }
}

/** The radio's JSON flags are 0 and 1. A real boolean is accepted too. */
private fun JSONObject.on(name: String): Boolean = when (val value = opt(name)) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    else -> false
}
