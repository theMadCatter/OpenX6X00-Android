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
// The colours and the row text below are the night values from each mode's
// own LVGL screen (src/js8_window.c, src/ft8_window.c, src/ft8/decode_list.c,
// src/wefax_window.c, src/text_panel.cpp, src/theme.h). JS8's split is not
// the layout of the others. This file still does not open a socket and does
// not key PTT.

package radio.kd0cq.x6100.digital

import org.json.JSONObject
import radio.kd0cq.x6100.log.Qso
import radio.kd0cq.x6100.log.QsoBook
import radio.kd0cq.x6100.net.Ft8Row

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

    /**
     * The radio's checksum code (`chk` in the JSON). Negative is a bad
     * checksum, which the list marks. Zero means the field was absent.
     * It used to be a boolean, and a negative code is non-zero, so a bad
     * checksum was stored as good.
     */
    val checksum: Int,
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
            checksum = o.optInt("chk"),
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
 * The segments are [FccPart97]'s Advanced data table, which is 97.301(c)
 * intersected with 97.305. Phone-only stretches are not data. 60 m is the
 * five 2.8 kHz channels, not a continuous span from 5.3515 to 5.3665 MHz.
 * The live gate the screens call is [FccPart97.gate], which follows the
 * class Setup has stored. This object stays so a test can still ask the
 * Advanced data question on its own.
 *
 * The whole signal has to sit inside one segment. A carrier that starts inside
 * and a tone that ends outside is refused. JS8's dial plus its audio offset is
 * the low edge, and the tone width is added for the high edge.
 */
object UsAdvanced {
    val dataHz: List<LongRange>
        get() = FccPart97.segments(LicenseClass.ADVANCED, Emission.DATA)

    /** True when [lowHz] and [highHz] are inside the same Advanced data segment, both edges included. */
    fun dataCovers(lowHz: Long, highHz: Long): Boolean =
        FccPart97.covers(LicenseClass.ADVANCED, Emission.DATA, lowHz, highHz)

    /**
     * Why a data transmission must not be sent, or null when the dial, the
     * audio offset and the tone width sit inside one segment.
     *
     * A missing offset is not zero. Guessing zero could put the tones outside
     * the segment the operator can see. Turning a transmission off does not
     * call this: Stop, and a CQ that is already on, have to work when the
     * offset is the thing that is wrong.
     */
    fun refuseTransmit(
        holdingControls: Boolean,
        txAllowed: Boolean,
        dialHz: Long?,
        offsetHz: Int?,
        widthHz: Int,
        offsetName: String,
    ): String? {
        if (!holdingControls) return "Take control before transmitting."
        if (!txAllowed) return "This session is not allowed to transmit."
        val dial = dialHz ?: return "The radio has not reported a dial frequency yet."
        val offset = offsetHz ?: return "The radio has not reported the $offsetName yet."
        val low = dial + offset
        val high = low + widthHz
        if (!dataCovers(low, high)) {
            return "That signal ($low–$high Hz) is outside the US Advanced data segments."
        }
        return null
    }
}

/**
 * How the radio's JS8 list draws one message.
 *
 * `js8_window.c` `format_row` writes `hh:mm:ss`, the SNR, the offset, a speed
 * letter when it is not Normal, then the text. `message_bg` picks the row
 * colour in this order: our TX, an alert, a call to us, a CQ, a heartbeat,
 * a group, then the plain panel. The night hexes are the first column of
 * `theme.h`. GhostNet's flash is the alert, because the panel JSON has no
 * separate alert-word flag.
 *
 * The Show button is the radio's: every decode, everything except heartbeats,
 * or only what is to us or to a group. GhostNet is the phone's extra filter
 * on top of that. The radio's Directed view also keeps traffic near the
 * selected offset, using the speed's receive threshold. That threshold is
 * not in the JSON, so this Directed view is the call and group half only.
 */
object Js8Look {
    const val ROW_TX = 0xFF0A1A33.toInt()
    const val ROW_ALERT = 0xFF5B21B6.toInt()
    const val ROW_ALERT_TEXT = 0xFFDDD6FE.toInt()
    const val ROW_TO_ME = 0xFF991B1B.toInt()
    const val ROW_CQ = 0xFF14532D.toInt()
    const val ROW_GROUP = 0xFF1E3A5F.toInt()
    const val ROW_BAR = 0xFF34D399.toInt()
    const val PANEL = 0xFF13161D.toInt()
    const val TEXT = 0xFFF1F5F9.toInt()
    const val MUTE = 0xFF64748B.toInt()

    /** The palette tops out 30 dB over the noise floor (`WF_MAX_DB`). */
    const val WF_MAX_DB = 30

    enum class Show { ALL, NO_HB, DIRECTED, GHOSTNET }

    enum class Kind { TX, ALERT, TO_ME, CQ, HEARTBEAT, GROUP, PLAIN }

    fun kind(row: Js8Row): Kind = when {
        row.transmitting -> Kind.TX
        GhostNet.isFlash(row) -> Kind.ALERT
        row.toMe -> Kind.TO_ME
        row.cq -> Kind.CQ
        row.heartbeat -> Kind.HEARTBEAT
        row.group -> Kind.GROUP
        else -> Kind.PLAIN
    }

    fun background(kind: Kind): Int = when (kind) {
        Kind.TX -> ROW_TX
        Kind.ALERT -> ROW_ALERT
        Kind.TO_ME -> ROW_TO_ME
        Kind.CQ -> ROW_CQ
        Kind.GROUP -> ROW_GROUP
        Kind.HEARTBEAT, Kind.PLAIN -> PANEL
    }

    fun foreground(kind: Kind): Int = when (kind) {
        Kind.ALERT -> ROW_ALERT_TEXT
        Kind.HEARTBEAT -> MUTE
        else -> TEXT
    }

    /** The radio's row, including the bad-checksum tail and the speed letter. */
    fun line(row: Js8Row): String {
        val hh = row.utc / 10_000
        val mm = row.utc / 100 % 100
        val ss = row.utc % 100
        val letter = Js8Protocol.submodeLetter(row.submode).let { if (it.isEmpty()) "" else " $it" }
        if (row.transmitting) {
            return "%02d:%02d:%02d  TX %4d%s  %s".format(hh, mm, ss, row.freqHz, letter, row.text)
        }
        val tail = when {
            row.partial -> " ..."
            row.checksum < 0 -> "  (bad checksum)"
            else -> ""
        }
        val open = if (row.lowConfidence) "[" else ""
        val close = if (row.lowConfidence) "]" else ""
        return "%02d:%02d:%02d %+3d %4d%s  %s%s%s%s".format(
            hh, mm, ss, row.snr, row.freqHz, letter, open, row.text, close, tail,
        )
    }

    /**
     * The Show button. Our own transmissions always stay, as they do on the radio.
     * A heartbeat that is also to us stays in the No HB view.
     */
    fun visible(rows: List<Js8Row>, show: Show, region: String): List<Js8Row> {
        val filtered = when (show) {
            Show.ALL -> rows
            Show.GHOSTNET -> GhostNet.filter(rows, region)
            Show.NO_HB -> rows.filter { it.transmitting || it.toMe || !it.heartbeat }
            Show.DIRECTED -> rows.filter { row ->
                row.transmitting || row.toMe || (row.group && !row.heartbeat && !row.cq)
            }
        }
        return filtered
    }

    fun showName(show: Show): String = when (show) {
        Show.ALL -> "All"
        Show.NO_HB -> "No HB"
        Show.DIRECTED -> "Directed"
        Show.GHOSTNET -> "GhostNet"
    }

    /**
     * The Show key's two lines.
     *
     * One line, "Show: GhostNet", was wider than the end of the scrolling
     * command row. After a sideways swipe the last letters sat on the right
     * edge and the row clipped them. The name is its own line, the same way
     * the radio stacks a key's title over its value, so the key is as wide
     * as the longer line and that line is a single word.
     */
    fun showButton(show: Show): String = "Show\n${showName(show)}"

    /**
     * The status line the radio puts above the waterfall: the switches that
     * can transmit on their own, where we are, the speed, and how many
     * decodes are held. The radio also prints its UTC clock and the time
     * drift. Those two are not in the `js8` JSON, so they are not invented here.
     */
    fun statusLine(status: Js8Status?, count: Int): String {
        if (status == null) return "No JS8 state from the radio yet."
        return buildString {
            if (status.auto) append("AUTO  ")
            if (status.heartbeat) {
                append("HB ")
                append(status.heartbeatMinutes)
                append("m")
                if (status.nextSeconds > 0) {
                    append(" next ")
                    append(status.nextSeconds)
                    append("s")
                }
                append("  ")
            }
            if (status.autoCq) append("auto CQ  ")
            if (status.hold) append("HOLD  ")
            if (status.sending) append("TX  ")
            if (status.where.isNotEmpty()) {
                append(status.where)
                append("  ")
            }
            append(Js8Protocol.speedName(status.speed))
            append("  total ")
            append(count)
            if (status.selectedCall.isNotEmpty()) {
                append("  ")
                append(status.selectedCall)
            }
            if (status.text.isNotEmpty()) {
                append("  ")
                append(status.text)
            }
        }
    }

    /** True when this row is the station the radio has selected. */
    fun selected(row: Js8Row, call: String): Boolean {
        if (call.isEmpty()) return false
        if (!row.transmitting) return row.from.equals(call, ignoreCase = true)
        return row.text.startsWith(call, ignoreCase = true) &&
            (row.text.length == call.length || row.text[call.length] == ' ')
    }
}

/**
 * The FT8 / FT4 / FT2 window is one app. `ft8_window.c` stores the protocol
 * as 0 FT4, 1 FT8, 2 FT2, and any other value counts as FT2. The tone widths
 * are the TX marker widths in that file. The list colours are `decode_list.c`:
 * a call to us is red, our TX is blue, a CQ keeps the panel colour and a
 * green edge, and the other traffic is grey. A slot line carries the time
 * that used to sit in front of every row.
 */
object Ft8Look {
    const val TO_ME = 0xFFEF4444.toInt()

    /** A tile that is calling us uses theme `row_to_me`. The list row uses `ft8_to_me`. */
    const val ROW_TO_ME = 0xFF991B1B.toInt()
    const val TX = 0xFF0082FC.toInt()
    const val CQ_EDGE = 0xFF34D399.toInt()
    const val PANEL = 0xFF13161D.toInt()
    const val ROW = 0xFF1B1F29.toInt()
    const val TEXT = 0xFFF1F5F9.toInt()
    const val MUTE = 0xFF64748B.toInt()
    const val WARN = 0xFFFBBF24.toInt()
    const val ERR = 0xFFF87171.toInt()
    const val CHIP = 0xFF1B1F29.toInt()
    const val LINE = 0xFF94A3B8.toInt()
    const val OK = 0xFF34D399.toInt()

    /**
     * decode_list.c paints every row at opacity 115 so the waterfall under
     * the list stays visible. 255 would hide that waterfall.
     */
    const val LIST_ALPHA = 115f / 255f

    /** ft8_window.c: the calls' strip, then this many pixels of waterfall clear of the list. */
    const val LANE_PX = 26
    const val PEEK_PX = 40

    const val CELL_INFO = 0
    const val CELL_START = 1
    const val CELL_MSG = 2
    const val CELL_CQ = 3
    const val CELL_TO_ME = 4
    const val CELL_TX = 5

    const val WORKED_NO = 0
    const val WORKED_YES = 1
    const val WORKED_SAME = 2

    /** `ftx_protocol_name` via the index in `ft8_window.c`. */
    fun protocolName(protocol: Int): String = when (protocol) {
        0 -> "FT4"
        1 -> "FT8"
        else -> "FT2"
    }

    /** The signal's bandwidth, Hz, for the band-edge check. Same table as the TX marker. */
    fun toneWidthHz(protocol: Int): Int = when (protocol) {
        0 -> 83
        1 -> 50
        else -> 167
    }

    /**
     * "HH:MM:SS" when the row still carries the slot time the radio puts in
     * front of the text. The list then shows that time once, on its own line.
     */
    fun slotTime(text: String): String? {
        if (text.length <= 9) return null
        if (text[2] != ':' || text[5] != ':' || text[8] != ' ') return null
        if (!text[0].isDigit() || !text[1].isDigit()) return null
        return text.take(8)
    }

    fun body(text: String): String = if (slotTime(text) != null) text.drop(9) else text

    /** A tap answers a decode. An info row and our own TX do not. */
    fun canReply(cellType: Int): Boolean =
        cellType == CELL_MSG || cellType == CELL_CQ || cellType == CELL_TO_ME

    fun statusLine(status: radio.kd0cq.x6100.net.Ft8Status?): String {
        if (status == null) return "No FT8 state from the radio yet."
        val name = protocolName(status.protocol)
        return buildString {
            append(if (status.open) name else "$name closed")
            if (status.callingCq) append("  CQ")
            if (status.transmitting) append("  TX Call")
            if (status.sending) append("  sending")
            if (status.autoOn) append("  AUTO")
            if (status.hold) append("  HOLD")
            if (status.hound) append("  HOUND")
            if (status.inQso) append("  QSO")
            append("  TX ")
            append(status.txFreqHz)
            append(" Hz")
            if (status.slotMs > 0) {
                val left = (status.slotMs - status.inMs).coerceAtLeast(0) / 1000
                append("  slot ")
                append(left)
                append("s")
            }
            if (status.nextMessage.isNotEmpty()) {
                append("  next ")
                append(status.nextMessage)
            }
        }
    }

    /**
     * Show: All keeps every row. Show: CQ drops the other stations' QSOs
     * (CELL_RX_MSG). Slot lines, calls to us, CQ and our own TX stay, which
     * is what "leaves the other QSOs out" means for the rows the socket has.
     */
    fun showKeeps(cellType: Int, showAll: Boolean): Boolean = showAll || cellType != CELL_MSG

    /** Their signal as the tile draws it: −24 dB is one bar, −4 dB is five. */
    fun signalBars(snrDb: Int): Int = ((snrDb + 29) / 5).coerceIn(0, 5)

    /**
     * The lane's ink. Yellow is our TX and the tile that is selected. Red is
     * a station calling us. White is a CQ. The rest is the muted text.
     */
    fun laneInk(toMe: Boolean, cq: Boolean, selected: Boolean): Int = when {
        selected -> WARN
        toMe -> ERR
        cq -> TEXT
        else -> MUTE
    }
}

/**
 * One station on the FT8 lane and on the tiles, taken from the newest row
 * for that call. The radio builds the same board from the decoder, not from
 * a second socket.
 */
data class Ft8Heard(
    val call: String,
    val freqHz: Int,
    val snrDb: Int,
    val country: String,
    val distanceKm: Int,
    val cellType: Int,
    val workedType: Int,
    val pota: Int,
    val toMe: Boolean,
    val id: Long,
)

/**
 * Newest row per call, calls to us first, then CQs, then the rest. Within a
 * group the station heard later comes first. Info rows and our own TX have
 * no station on the lane.
 */
fun ft8Heard(rows: List<Ft8Row>, showAll: Boolean): List<Ft8Heard> {
    val byCall = LinkedHashMap<String, Pair<Int, Ft8Heard>>()
    var order = 0
    for (row in rows) {
        if (!Ft8Look.showKeeps(row.cellType, showAll)) continue
        if (row.call.isEmpty()) continue
        if (row.cellType == Ft8Look.CELL_INFO || row.cellType == Ft8Look.CELL_START || row.cellType == Ft8Look.CELL_TX) {
            continue
        }
        byCall[row.call.uppercase()] = order++ to Ft8Heard(
            call = row.call,
            freqHz = row.freqHz,
            snrDb = row.snrDb,
            country = row.country,
            distanceKm = row.distanceKm,
            cellType = row.cellType,
            workedType = row.workedType,
            pota = row.pota,
            toMe = row.toMe || row.cellType == Ft8Look.CELL_TO_ME,
            id = row.id,
        )
    }
    return byCall.values.sortedWith(
        compareByDescending<Pair<Int, Ft8Heard>> { it.second.toMe }
            .thenByDescending { it.second.cellType == Ft8Look.CELL_CQ }
            .thenByDescending { it.first },
    ).map { it.second }
}

/**
 * The five button pages of ft8_window.c, in that file's order. A null slot
 * is a button the radio leaves empty. Only [ft8KeySends] is a panel word.
 * Show and View change this screen. The rest are on the radio and are not
 * dispatched by remote_server.c, so a press must not invent a command.
 */
enum class Ft8Key {
    PAGE, CQ, TX_CALL, SHOW, VIEW,
    AUTO, HOLD, RETRIES, FORCE_SAVE,
    MODE, FREQ, HOUND, POWER,
    CQ_MOD, TIME_SYNC, LIVE_MAP, TUNE,
    LOG_MAP, FOR_YOU,
}

fun ft8Page(index: Int): List<Ft8Key?> = when (Math.floorMod(index, 5)) {
    0 -> listOf(Ft8Key.PAGE, Ft8Key.CQ, Ft8Key.TX_CALL, Ft8Key.SHOW, Ft8Key.VIEW)
    1 -> listOf(Ft8Key.PAGE, Ft8Key.AUTO, Ft8Key.HOLD, Ft8Key.RETRIES, Ft8Key.FORCE_SAVE)
    2 -> listOf(Ft8Key.PAGE, Ft8Key.MODE, Ft8Key.FREQ, Ft8Key.HOUND, Ft8Key.POWER)
    3 -> listOf(Ft8Key.PAGE, Ft8Key.CQ_MOD, Ft8Key.TIME_SYNC, Ft8Key.LIVE_MAP, Ft8Key.TUNE)
    else -> listOf(Ft8Key.PAGE, Ft8Key.LOG_MAP, Ft8Key.FOR_YOU, null, null)
}

/** The panel words that sit on those pages: cq, txcall, auto, hold, mode. */
fun ft8KeySends(key: Ft8Key): Boolean = when (key) {
    Ft8Key.CQ, Ft8Key.TX_CALL, Ft8Key.AUTO, Ft8Key.HOLD, Ft8Key.MODE -> true
    else -> false
}

/**
 * The TX marker is a band [widthHz] wide starting at the offset. The radio
 * clamps the offset so the band stays inside the filter, and it paints that
 * band at half opacity. Null when the offset is outside the span.
 */
fun finderBand(txHz: Int, widthHz: Int, lowHz: Int, highHz: Int, widthPx: Float): Pair<Float, Float>? {
    val start = waterfallX(txHz, lowHz, highHz, widthPx) ?: return null
    val endHz = (txHz + widthHz).coerceAtMost(highHz)
    val end = waterfallX(endHz, lowHz, highHz, widthPx) ?: return null
    return start to end.coerceAtLeast(start + 1f)
}

/** One row of the FT8 list after the slot time has been lifted onto its own line. */
sealed class Ft8Line {
    data class Slot(val time: String) : Ft8Line()
    data class Decode(val row: radio.kd0cq.x6100.net.Ft8Row, val body: String) : Ft8Line()
}

/** Oldest first, newest last, with a slot line wherever the time changes. */
fun ft8Lines(rows: List<radio.kd0cq.x6100.net.Ft8Row>): List<Ft8Line> {
    val out = ArrayList<Ft8Line>(rows.size + 4)
    var last = ""
    for (row in rows) {
        val time = Ft8Look.slotTime(row.text)
        if (time != null && time != last) {
            out += Ft8Line.Slot(time)
            last = time
        }
        out += Ft8Line.Decode(row, Ft8Look.body(row.text))
    }
    return out
}

/**
 * Hz marks for an app waterfall whose bins run from [lowHz] to [highHz].
 * The radio draws a finder line at the TX offset. These are the labelled
 * ticks under that picture. An empty span has none.
 */
fun waterfallTicks(lowHz: Int, highHz: Int): List<Int> {
    if (highHz <= lowHz) return emptyList()
    val span = highHz - lowHz
    val step = when {
        span > 4_000 -> 1_000
        span > 1_500 -> 500
        else -> 250
    }
    val start = ((lowHz + step - 1) / step) * step
    if (start >= highHz) return emptyList()
    val ticks = ArrayList<Int>()
    var hz = start
    while (hz < highHz) {
        ticks += hz
        hz += step
    }
    return ticks
}

/** Where [hz] sits across the waterfall, or null when it is outside the span. */
fun waterfallX(hz: Int, lowHz: Int, highHz: Int, width: Float): Float? {
    if (highHz <= lowHz || hz < lowHz || hz > highHz || width <= 0f) return null
    return (hz - lowHz).toFloat() / (highHz - lowHz).toFloat() * width
}

/**
 * The WeFax app, as the panel sends it.
 *
 * `wefax {...}` is the state. A picture row is binary: u16 0xFFFE, u16 width,
 * u32 row number, then one grey byte per pixel. There is no transmit command
 * in the set the panel dispatches. Open, close, auto, cont, save, clear,
 * plan and unplan change the app and need the operate role plus control.
 * `wefax rows 1` only asks for the picture.
 */
data class WeFaxStatus(
    val open: Boolean,
    val auto: Boolean,
    val fax: Boolean,
    val transmitting: Boolean,
    val txPercent: Int,
    val generation: Long,
    val rows: Long,
    val freqHz: Int,
    val saves: Long,
    val station: String,
    val saved: String,
    val plan: Boolean,
    val next: String,
)

/** One grey scan line. [grey] is the pixels the radio sent, left to right. */
class WeFaxScan(val number: Long, val grey: ByteArray)

object WeFaxProtocol {
    const val ROWS_KEPT = 480

    val words: Set<String> = setOf("open", "close", "auto", "cont", "save", "clear", "plan", "unplan")

    fun command(word: String): String = "wefax $word"

    fun rows(on: Boolean): String = if (on) "wefax rows 1" else "wefax rows 0"

    fun parse(json: String): WeFaxStatus? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return WeFaxStatus(
            open = o.on("open"),
            auto = o.on("auto"),
            fax = o.on("fax"),
            transmitting = o.on("tx"),
            txPercent = o.optInt("txp"),
            generation = o.optLong("gen"),
            rows = o.optLong("rows"),
            freqHz = o.optInt("freq"),
            saves = o.optLong("saves"),
            station = o.optString("station"),
            saved = o.optString("saved"),
            plan = o.on("plan"),
            next = o.optString("next"),
        )
    }

    /**
     * The app frame's left label: the station, then the frequency in kHz.
     * wefax_window.c writes "%s   %.1f kHz". No station yet is the word WeFax.
     */
    fun frameLeft(status: WeFaxStatus?): String {
        if (status == null) return "WeFax"
        val name = status.station.ifEmpty { "WeFax" }
        if (status.freqHz == 0) return name
        return "$name   ${"%.1f".format(status.freqHz / 1000.0)} kHz"
    }

    /**
     * The frame's right label. Transmit replaces the receiver state. Auto
     * Plan adds the next slot the JSON already carries.
     */
    fun frameRight(status: WeFaxStatus?): String {
        if (status == null) return "RX Auto"
        if (status.transmitting) return "TX ${status.txPercent}%"
        val rx = if (status.auto) "RX Auto" else "RX Cont"
        // header_update prints "%.5s" of plan_next, which is "HH:MM station kHz".
        if (status.plan && status.next.isNotEmpty()) {
            val time = if (status.next.length >= 5 && status.next[2] == ':') status.next.take(5) else status.next
            return "$rx   Plan $time"
        }
        return rx
    }

    /** The Plan button. Off is "Auto / Plan". On names the next slot's time. */
    fun planButton(status: WeFaxStatus?): String {
        if (status?.plan != true) return "Auto\nPlan"
        val next = status.next
        val time = if (next.length >= 5 && next[2] == ':') next.take(5) else "--:--"
        return "Plan\nnext $time"
    }

    /**
     * Auto, no rows yet, not transmitting: the words the radio puts over an
     * empty chart. Null when a picture is already coming in.
     */
    fun idleLines(status: WeFaxStatus?, hasRows: Boolean): List<String>? {
        if (status == null || !status.auto || hasRows || status.transmitting) return null
        if (!status.plan) {
            return listOf(
                "Auto Plan is off",
                "a fax starts by itself with its start tone",
                "Auto Plan (F2 of page 1) takes the day's charts by itself",
            )
        }
        if (status.next.isEmpty()) {
            return listOf("No chart planned", "a fax starts by itself with its start tone", "")
        }
        return listOf(status.next, status.station, "")
    }

    /**
     * What the status line on the radio's frame says, from the fields the
     * JSON actually carries. The picture itself is the grey rows.
     */
    fun statusLine(status: WeFaxStatus?): String {
        if (status == null) return "No WeFax state from the radio yet."
        return buildString {
            append(if (status.open) "WeFax open" else "WeFax closed")
            if (status.auto) append("  Auto")
            if (status.fax) append("  fax")
            if (status.transmitting) {
                append("  TX ")
                append(status.txPercent)
                append("%")
            }
            if (status.plan) append("  Plan")
            if (status.station.isNotEmpty()) {
                append("  ")
                append(status.station)
            }
            if (status.freqHz != 0) {
                append("  ")
                append(status.freqHz)
                append(" Hz")
            }
            append("  ")
            append(status.rows)
            append(" rows")
            if (status.next.isNotEmpty()) {
                append("  next ")
                append(status.next)
            }
            if (status.saved.isNotEmpty()) {
                append("  saved ")
                append(status.saved)
            }
        }
    }
}

/** The radio's JSON flags are 0 and 1. A real boolean is accepted too. */
private fun JSONObject.on(name: String): Boolean = when (val value = opt(name)) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    else -> false
}

/**
 * The four button pages of wefax_window.c. A null slot is empty on the radio.
 * Bulletin, the chart browser, frequency presets, align, tilt, night and
 * power are not panel words. Open is not on this bar: the radio opens the
 * app by launching it, and the phone keeps Open off this page.
 */
enum class FaxKey {
    PAGE, PLAN, BULLETIN, CHARTS, CLOSE,
    FREQ, RX, VIEW, CLEAR,
    ALIGN, TILT, SAVE, POWER, NIGHT,
}

fun faxPage(index: Int): List<FaxKey?> = when (Math.floorMod(index, 4)) {
    0 -> listOf(FaxKey.PAGE, FaxKey.PLAN, FaxKey.BULLETIN, FaxKey.CHARTS, FaxKey.CLOSE)
    1 -> listOf(FaxKey.PAGE, FaxKey.FREQ, FaxKey.RX, FaxKey.VIEW, FaxKey.CLEAR)
    2 -> listOf(FaxKey.PAGE, FaxKey.ALIGN, FaxKey.TILT, FaxKey.SAVE, FaxKey.POWER)
    else -> listOf(FaxKey.PAGE, FaxKey.NIGHT, null, null, null)
}

/** close, plan/unplan, auto/cont, clear, save. None of these keys a carrier. */
fun faxKeySends(key: FaxKey): Boolean = when (key) {
    FaxKey.PLAN, FaxKey.CLOSE, FaxKey.RX, FaxKey.CLEAR, FaxKey.SAVE -> true
    else -> false
}

/**
 * How text_panel.cpp colours a finished CW word. A call is gold only once
 * the radio would call it confirmed: beside DE, or the same call twice.
 * Abbreviations are the dim text. An RST is the header gold. The speed
 * line is not here: the socket does not send WPM.
 */
object CwLook {
    const val ACCENT = 0xFFE5A823.toInt()
    const val DIM = 0xFF94A3B8.toInt()
    const val HEAD = 0xFFE5A823.toInt()
    const val TEXT = 0xFFF1F5F9.toInt()

    enum class Kind { PLAIN, ABBR, RST, CALL }

    private val tokens = setOf(
        "CQ", "DE", "K", "KN", "BK", "AR", "SK", "R", "RR", "TU", "TNX", "TKS", "73", "88", "UR", "RST",
        "QTH", "NAME", "NM", "OP", "RIG", "ANT", "PWR", "WX", "PSE", "AGN", "QSL", "QSO", "QRZ", "QRZ?",
        "QRL", "QRL?", "QRM", "QRN", "QSB", "QRP", "QRS", "QRQ", "QSY", "QRT", "GM", "GA", "GE", "GN",
        "FB", "HW", "HW?", "HR", "ES", "BT", "OM", "YL", "FER", "CALL", "CFM", "GL", "GD", "DR", "ABT",
        "CUL", "SRI", "NR", "TEST", "POTA", "SOTA", "UP", "?", "=",
    )

    fun kind(word: String): Kind {
        val w = word.uppercase()
        if (w.length >= 4 && w.first() == '<' && w.last() == '>') {
            if (w == "<ERR>") return Kind.PLAIN
            if (w.substring(1, w.length - 1).all { it in 'A'..'Z' }) return Kind.ABBR
        }
        if (w in tokens) return Kind.ABBR
        if (isRst(w)) return Kind.RST
        if (isCall(w)) return Kind.CALL
        return Kind.PLAIN
    }

    /**
     * True when this call would be coloured. [at] is the index of the word
     * in [text]. The word must be a call, and it must sit against DE or
     * occur at least twice.
     */
    fun confirmed(text: String, word: String): Boolean {
        val w = word.uppercase()
        if (!isCall(w)) return false
        val words = text.uppercase().split(' ', '\n').filter { it.isNotEmpty() }
        val i = words.indexOf(w)
        if (i < 0) return false
        if (i > 0 && words[i - 1] == "DE") return true
        if (i + 1 < words.size && words[i + 1] == "DE") return true
        return words.count { it == w } >= 2
    }

    fun ink(kind: Kind, confirmed: Boolean): Int = when {
        kind == Kind.CALL && confirmed -> ACCENT
        kind == Kind.ABBR -> DIM
        kind == Kind.RST -> HEAD
        else -> TEXT
    }

    /** The last [maxLines] of the decoder, which is all the panel keeps. */
    fun tail(text: String, maxLines: Int): String {
        if (maxLines < 1 || text.isEmpty()) return ""
        val lines = text.split('\n')
        return lines.takeLast(maxLines).joinToString("\n")
    }

    /**
     * Soft-wrap to [columns], then keep the last [maxLines]. A decoder that
     * never sends a newline would otherwise be one line the panel cannot show.
     */
    fun wrappedTail(text: String, maxLines: Int, columns: Int): String {
        if (maxLines < 1 || columns < 1 || text.isEmpty()) return ""
        val wrapped = ArrayList<String>()
        for (raw in text.split('\n')) {
            if (raw.isEmpty()) {
                wrapped += ""
                continue
            }
            var i = 0
            while (i < raw.length) {
                val end = (i + columns).coerceAtMost(raw.length)
                var cut = end
                if (end < raw.length) {
                    val space = raw.lastIndexOf(' ', end - 1)
                    if (space > i) cut = space + 1
                }
                wrapped += raw.substring(i, cut).trimEnd()
                i = if (cut == i) i + 1 else cut
            }
        }
        return wrapped.takeLast(maxLines).joinToString("\n")
    }

    private fun isRst(w: String): Boolean {
        if (w.length != 3 || w[0] !in '1'..'5') return false
        return w.drop(1).all { it == 'N' || it in '1'..'9' }
    }

    /** Prefix, one digit, 1–4 letters, with the same optional /P and prefix forms as cw_parse.c. */
    private fun isCall(w: String): Boolean {
        if (w.length !in 3..14) return false
        val slash = w.indexOf('/')
        if (slash < 0) return core(w)
        val second = w.indexOf('/', slash + 1)
        if (second >= 0) {
            return prefix(w.substring(0, slash)) &&
                core(w.substring(slash + 1, second)) &&
                postfix(w.substring(second + 1))
        }
        val left = w.substring(0, slash)
        val right = w.substring(slash + 1)
        return (postfix(right) && core(left)) || (prefix(left) && core(right))
    }

    private fun core(s: String): Boolean {
        var suf = 0
        while (suf < s.length && s[s.length - 1 - suf] in 'A'..'Z') suf++
        if (suf !in 1..4 || suf + 2 > s.length) return false
        if (!s[s.length - suf - 1].isDigit()) return false
        val pl = s.length - suf - 1
        val p = s.substring(0, pl)
        return (pl == 1 && p[0] in 'A'..'Z') ||
            (pl == 2 && p[0] in 'A'..'Z' && (p[1] in 'A'..'Z' || p[1].isDigit())) ||
            (pl == 2 && p[0].isDigit() && p[1] in 'A'..'Z') ||
            (pl == 3 && p[0].isDigit() && p[1] in 'A'..'Z' && p[2] in 'A'..'Z')
    }

    private fun prefix(s: String): Boolean {
        if (s.length !in 1..4) return false
        if (s.any { it !in 'A'..'Z' && !it.isDigit() }) return false
        return s.any { it in 'A'..'Z' }
    }

    private fun postfix(s: String): Boolean = when (s) {
        "P", "M", "MM", "AM", "QRP" -> true
        else -> s.length == 1 && s[0].isDigit()
    }
}
