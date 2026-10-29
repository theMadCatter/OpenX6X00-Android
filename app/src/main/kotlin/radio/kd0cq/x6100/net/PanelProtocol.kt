// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.

package radio.kd0cq.x6100.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/**
 * The X6100's Remote/Panel WebSocket protocol: the text commands and answers of
 * `src/remote_server.c`, the spectrum and screen binary frames, and the JSON the radio tells us.
 *
 * Nothing here is invented: every line, key and byte order is read from the firmware source and
 * from how the radio's own page consumes it (`webserver/src/x6100_webserver/views/panel.html`).
 * The whole file is plain Kotlin and JVM-testable, so the unit tests need no Android.
 */

/** The panel server's port; the radio advertises no service for it, so it is a default (`remote_server.h`). */
const val PANEL_PORT = 50002

/** A pairing code is six digits, shown on the radio's screen for two minutes. */
const val PAIRING_CODE_DIGITS = 6

/** The session token the radio answers "paired" with: 32 hex characters (`TOKEN_LEN`). */
const val SESSION_TOKEN_LENGTH = 32

/** 24 kHz: the rate the firmware captures and resamples to, and what AudioPipeline runs at. */
const val TCI_AUDIO_RATE = 24_000

/** A six-digit pairing code, as the radio shows it. */
fun isPairingCode(text: String): Boolean =
    text.length == PAIRING_CODE_DIGITS && text.all { it in '0'..'9' }

/** A session token, as the radio hands it out; anything shorter is the "paired" line's word. */
fun isSessionToken(text: String): Boolean =
    text.length == SESSION_TOKEN_LENGTH && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/** The radio's modes, in the order its `mode_names[]` numbers them; "modes N" says how many this radio has. */
enum class PanelMode(val index: Int, val label: String) {
    Lsb(0, "LSB"),
    DigL(1, "DIGL"),
    Usb(2, "USB"),
    DigU(3, "DIGU"),
    Cw(4, "CW"),
    Cwr(5, "CWR"),
    Am(6, "AM"),
    Fm(7, "NFM"),
    Sam(8, "SAM"),
    Wfm(9, "WFM"),
    ;

    companion object {
        fun fromIndex(index: Int): PanelMode? = entries.firstOrNull { it.index == index }
    }
}

/** The setting names "set NAME VALUE" takes (`remote_server.c`: panel_apply's table). */
object PanelSettings {
    const val FREQ = "freq"
    const val MODE = "mode"
    const val AGC = "agc"
    const val PRE = "pre"
    const val ATT = "att"
    const val NB = "nb"
    const val NR = "nr"
    const val SQL = "sql"
    const val RFG = "rfg"
    const val VOL = "vol"

    /** Tenths of a watt, whatever "state" tells us. */
    const val PWR = "pwr"

    const val STEP = "step"
    const val ZOOM = "zoom"

    /** +1 or -1: the band the radio dials into, not an index. */
    const val BAND = "band"

    /** A toggle, not a position. */
    const val VFO = "vfo"

    const val FILTER = "filter"
    const val ATU = "atu"
    const val LOCK = "lock"
}

/** Commands we send, in the words the radio answers to. */
object PanelCommands {

    /** Watch the radio's own state, spectrum and apps: this is what makes a connection a panel client. */
    fun panel(on: Boolean) = "panel ${if (on) 1 else 0}"

    /**
     * What a new panel socket asks for once it is open.
     *
     * `remote_server.c` sends `role` and `ctl` on the WebSocket handshake, and again for `auth`.
     * `panel 1` sets `panel_fresh` only when this socket was not already subscribed (`on != c->panel`),
     * which is true of every new socket and false of a second `panel 1` on the same one. `panel_fresh`
     * is the resync: modes, bands, the plan, and the whole `state` line, whose `tx` field is the
     * carrier. There is no separate "send state" command. Toggling `panel 0` then `panel 1` on a
     * live socket would ask again, and it is not used here: picture frames no longer share a queue
     * with these lines, so a waterfall does not create a gap to heal.
     */
    fun reconnect(token: String?): List<String> = buildList {
        if (!token.isNullOrEmpty()) add(auth(token))
        add(panel(on = true))
    }

    /** The radio's screen as JPEG frames; the waterfall page turns them off to save the bandwidth. */
    fun screen(on: Boolean) = "screen ${if (on) 1 else 0}"

    /** How the radio renders screen frames for us: JPEG quality, and half size for `fast`. */
    enum class Picture(val word: String) { SHARP("sharp"), BALANCED("balanced"), FAST("fast") }

    fun picture(kind: Picture) = "picture ${kind.word}"

    /** Answers `pong N` with the same number. The radio closes a client that has said nothing for 30
     *  seconds, so send one every few seconds while connected. */
    fun ping(n: Long) = "ping $n"

    fun pairAsk() = "pair?"

    /** The code from the radio's screen and the name we give this pairing. */
    fun pair(code: String, name: String) = "pair $code ${name.replace(' ', '_')}"

    fun auth(token: String) = "auth $token"

    /** Forget our own pairing; the radio then closes this socket. */
    fun unpair() = "unpair"

    /** "control take" is refused while the radio is on the air; "control release" gives it back. */
    fun control(take: Boolean) = "control ${if (take) "take" else "release"}"

    /** The admin's list of paired sessions. */
    fun sessions() = "sessions"

    fun revoke(sessionIndex: Int) = "revoke $sessionIndex"

    /** "set NAME VALUE [VALUE2]"; only "filter" takes the second value. */
    fun set(name: String, value: Long, value2: Long? = null): String =
        if (value2 != null) "set $name $value $value2" else "set $name $value"

    fun set(name: String, value: Int, value2: Int? = null): String =
        set(name, value.toLong(), value2?.toLong())

    /** Hold a key down or let it up; PTT is a lease, named for this press and repeated every second. */
    fun key(name: String, down: Boolean, lease: String? = null): String =
        if (lease != null) "key $name ${if (down) 1 else 0} $lease" else "key $name ${if (down) 1 else 0}"

    /** Turn a knob by N detents, negative the other way. The names are in panel.html's remoteKnob. */
    fun knob(name: String, detents: Int) = "knob $name $detents"

    /** The FT8 app: open, close, cq, stop, auto, hold, mode, txfreq, reply, txcall. */
    fun ft8(what: String, arg: String? = null): String = if (arg != null) "ft8 $what $arg" else "ft8 $what"

    /** "log CALL|RSTS|RSTR|NAME|QTH|GRID|COUNTRY" — the radio's CW keyboard's own text. */
    fun log(text: String) = "log $text"

    /** "logq ID TEXT": the same, with a number of our own so a lost answer can be asked for. */
    fun logq(id: Long, text: String) = "logq $id $text"

    /** "logq? ID TEXT" asks what became of a QSO we sent before a drop; it is never logged twice. */
    fun logqQuery(id: Long, text: String) = "logq? $id $text"
}

/** The radio's state, as one "state {...}" line carries it. */
data class RadioState(
    val freqHz: Int,
    /** The index into [PanelMode]; the radio tells a number, never a name. */
    val mode: Int,
    /** The filter's passband, Hz from the carrier: negative below it. */
    val filterLowHz: Int,
    val filterHighHz: Int,
    /** 0 off, 1 slow, 2 fast, 3 auto. */
    val agc: Int,
    val preOn: Boolean,
    /** The attenuator's index. */
    val att: Int,
    val noiseBlankerOn: Boolean,
    val noiseReduceOn: Boolean,
    val squelch: Int,
    val rfGain: Int,
    /** Tenths of a dBm's worth of speaker volume; the maximum comes with "volmax N". */
    val volume: Int,
    /** "state" tells whole watts and a tenth, so watts as a number. */
    val powerWatts: Double,
    /** The tuning step's index into the radio's own list. */
    val stepIndex: Int,
    val zoom: Int,
    /** Which VFO the screen is on; "set vfo" toggles it. */
    val vfo: Int,
    val atuOn: Boolean,
    val battery: Int,
    val transmitting: Boolean,
    val freqLocked: Boolean,
) {
    val modeLabel: String get() = PanelMode.fromIndex(mode)?.label ?: "mode $mode"
    val attenuatorOn: Boolean get() = att > 0
    val powerTenths: Int get() = (powerWatts * 10).toInt()
}

/** "t <W> <SWR> <ALC>", the TX meters, only while the radio is on the air. */
data class TxMeters(val watts: Double, val swr: Double, val alc: Double)

/** One entry of "bands [...]": a named start and stop, Hz. */
data class Band(val name: String, val startHz: Int, val stopHz: Int)

/** One segment of "plan [...]": start, stop, Hz and the plan's own usage type. */
data class PlanSegment(val startHz: Int, val stopHz: Int, val type: Int)

/** One entry of "spots [...]": the cluster spots the radio holds, newest first. */
data class Spot(
    val freqHz: Int,

    /** The spotted station. */
    val call: String,

    /** The mode class the radio sorted it into. */
    val modeClass: String,

    /** Minutes since the spot was posted. */
    val ageMin: Int,
    val spotter: String,
    val comment: String,
    val alert: Boolean,
)

/** One decoded FT8 row, from "ft8r {...}". */
data class Ft8Row(
    /** This connection's id for the row; "ft8 reply ID" answers it. */
    val id: Long,

    /** The decoder's own cell type. */
    val cellType: Int,

    /** What was decoded. */
    val text: String,
    val call: String,
    val grid: String,

    /** The audio frequency the signal sits at, Hz above the VFO. */
    val freqHz: Int,
    val snrDb: Int,

    /** Whether the row is a call to us. */
    val toMe: Boolean,

    /** What the radio has done with this station, as the app's own worked state. */
    val workedType: Int,

    /** Kilometres, from our grid; 0 when unknown. */
    val distanceKm: Int,
    val pota: Int,
    val odd: Boolean,
    val country: String,
)

/** The FT8 app's state, from "ft8 {...}". */
data class Ft8Status(
    val open: Boolean,

    /** Which protocol the app is running: the index into its own list. */
    val protocol: Int,
    val callingCq: Boolean,
    val transmitting: Boolean,
    val autoOn: Boolean,
    val hold: Boolean,

    /** Whether the app is in the middle of sending. */
    val sending: Boolean,

    /** Its TX audio offset, Hz. */
    val txFreqHz: Int,

    /** The message it has queued for the next period. */
    val nextMessage: String,

    /** Milliseconds into the period, and the period's length. */
    val inMs: Int,
    val slotMs: Int,
    val repeats: Int,
    val inQso: Boolean,
    val hound: Boolean,
    val noCall: Boolean,
    val logged: String,
    val loggedThisSession: Boolean,
)

/** One paired session, from "sessions [...]" — an admin's screen. */
data class SessionInfo(
    val index: Int,
    val name: String,
    val role: String,
    val txAllowed: Boolean,
    val lastSeenSeconds: Long,
    val ip: String,
    val online: Boolean,
    val mine: Boolean,
)

/** "hello VERSION BOOT" and "size W H": what the radio told us about itself at this connection. */
data class Hello(val version: String, val bootId: Long)

data class ScreenSize(val width: Int, val height: Int)

/** What "role ROLE TX NAME" says we may do at this connection. */
enum class PanelRole(val wire: String) {
    View("view"),
    Operate("operate"),
    Admin("admin"),
    None(""),
    ;

    /** Whether this role may change the radio: everything but a viewer's. */
    val canOperate: Boolean get() = this == Operate || this == Admin

    /** Whether this role may pair, revoke and administer the radio. */
    val canAdmin: Boolean get() = this == Admin

    companion object {
        fun fromWire(wire: String): PanelRole = entries.firstOrNull { it.wire == wire } ?: View
    }
}

data class RoleInfo(val role: PanelRole, val txAllowed: Boolean, val sessionName: String) {
    val canOperate: Boolean get() = role.canOperate
    val canAdmin: Boolean get() = role.canAdmin
}

/** "ctl WHO MINE": who holds the radio's controls, and whether that is us. */
data class ControlInfo(val holder: String, val mine: Boolean) {
    val isNone: Boolean get() = holder == "none"
    val isLocal: Boolean get() = holder == "local"
    val isTci: Boolean get() = holder == "tci"

    /** Free when no one holds it; the radio keeps its own control while on the air. */
    val free: Boolean get() = isNone
    val label: String get() = if (mine) "us" else if (isNone) "no one" else holder
}

/** "logged [ID] ok|no|pending|unknown|conflict": what became of a QSO we sent. */
data class LoggedReceipt(val id: Long?, val result: String) {
    val logged: Boolean get() = result == "ok"
    val stillPending: Boolean get() = result == "pending"
}

/** A spectrum frame: "16 bytes LE {u16 0xFFFF, u16 bins, u32 centre, u32 width, u32 seq}" then int8 dB per bin. */
data class SpectrumFrame(
    val bins: Int,
    val centreHz: Int,
    val widthHz: Int,
    val sequence: Long,
    /** One dB value per bin, exactly as the radio's waterfall FFT left it. */
    val db: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is SpectrumFrame && bins == other.bins && centreHz == other.centreHz &&
                widthHz == other.widthHz && sequence == other.sequence && db.contentEquals(other.db))

    override fun hashCode(): Int = ((((bins * 31 + centreHz) * 31 + widthHz) * 31 + sequence.hashCode()) * 31) + db.contentHashCode()
}

/** A screen frame: "8 bytes LE {u16 w, u16 h, u32 seq}" then a JPEG. */
data class ScreenFrame(val width: Int, val height: Int, val sequence: Long, val jpeg: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ScreenFrame && width == other.width && height == other.height &&
                sequence == other.sequence && jpeg.contentEquals(other.jpeg))

    override fun hashCode(): Int = 31 * (31 * (31 * width + height) + sequence.hashCode()) + jpeg.contentHashCode()
}

/** Everything the radio can tell a panel client. */
sealed class PanelEvent {
    data class Hello(val version: String, val bootId: Long) : PanelEvent()
    data class Size(val width: Int, val height: Int) : PanelEvent()
    data class Keys(val names: List<String>) : PanelEvent()
    data class Modes(val count: Int) : PanelEvent()
    data class VolumeMax(val value: Int) : PanelEvent()
    data class Pong(val n: Long?) : PanelEvent()

    /** "err ..." — a refused command, or a token the radio no longer knows. */
    data class Failure(val text: String) : PanelEvent()
    data class State(val state: RadioState) : PanelEvent()

    /** "m <dB>", the S-meter, ten times a second. */
    data class Meter(val db: Double) : PanelEvent()
    data class TxMeters(val meters: radio.kd0cq.x6100.net.TxMeters) : PanelEvent()
    data class Bands(val bands: List<Band>) : PanelEvent()
    data class Plan(val segments: List<PlanSegment>) : PanelEvent()
    data class Spots(val spots: List<Spot>) : PanelEvent()
    data class Spectrum(val frame: SpectrumFrame) : PanelEvent()
    data class Screen(val frame: ScreenFrame) : PanelEvent()
    data class DecodedFt8Row(val row: Ft8Row) : PanelEvent()
    data class Ft8(val status: Ft8Status) : PanelEvent()

    /** The CW decoder's text, one line per decode. */
    data class Cw(val text: String) : PanelEvent()
    data class Logged(val receipt: LoggedReceipt) : PanelEvent()

    /** "role ROLE TX NAME" — what this connection may do. */
    data class Role(val role: RoleInfo) : PanelEvent()

    /** "paired TOKEN ROLE TX": the token to keep and send at every connection. */
    data class Paired(val token: String, val role: PanelRole, val txAllowed: Boolean) : PanelEvent()

    data class ControlHeld(val info: ControlInfo) : PanelEvent()

    /** "tx 0|1", to every client, when the radio's TX state changes. */
    data class Transmitting(val on: Boolean) : PanelEvent()
    data class Sessions(val sessions: List<SessionInfo>) : PanelEvent()

    /** "pairing code shown on the radio for 2 min" and other words that are not errors. */
    data class Notice(val text: String) : PanelEvent()
}

/** The parser. Every function is pure so the tests can feed it captured lines. */
object PanelProtocol {

    private const val SPECTRUM_MAGIC = 0xFFFF
    private const val SPECTRUM_HEADER = 16
    private const val SCREEN_HEADER = 8

    /** One text message, or null when the radio said something we do not know yet. */
    fun parseLine(line: String): PanelEvent? {
        val text = line.trim()
        if (text.isEmpty()) return null
        val verb = text.substringBefore(' ').substringBefore('{')
        val rest = text.removePrefix(verb).trim()

        return when (verb) {
            "hello" -> parseHello(rest)
            "size" -> parseSize(rest)
            "keys" -> PanelEvent.Keys(rest.split(' ').filter { it.isNotEmpty() })
            "modes" -> PanelEvent.Modes(rest.toIntOrNull() ?: 0)
            "volmax" -> PanelEvent.VolumeMax(rest.toIntOrNull() ?: 0)
            "pong" -> PanelEvent.Pong(rest.toLongOrNull())
            "err" -> PanelEvent.Failure(rest)
            "state" -> parseState(rest)
            "m" -> rest.toDoubleOrNull()?.let { PanelEvent.Meter(it) }
            "t" -> parseTxMeters(rest)
            "bands" -> parseBands(rest)
            "plan" -> parsePlan(rest)
            "spots" -> parseSpots(rest)
            "ft8r" -> parseFt8Row(rest)
            "ft8" -> parseFt8Status(rest)
            "cw" -> parseCw(rest)
            "logged" -> parseLogged(rest)
            "role" -> parseRole(rest)
            "paired" -> parsePaired(rest)
            "ctl" -> parseCtl(rest)
            "tx" -> when (rest) {
                "0" -> PanelEvent.Transmitting(false)
                "1" -> PanelEvent.Transmitting(true)
                else -> null
            }

            "sessions" -> parseSessions(rest)
            else -> if (rest.isEmpty()) null else PanelEvent.Notice("$verb $rest")
        }
    }

    /** "hello OpenX6X00 - v0.3.6 305419896": the version holds spaces, so the boot id is the last word. */
    private fun parseHello(rest: String): PanelEvent {
        val parts = rest.split(' ').filter { it.isNotEmpty() }
        val boot = parts.lastOrNull()?.toLongOrNull() ?: return PanelEvent.Hello(parts.joinToString(" "), 0L)
        return PanelEvent.Hello(parts.dropLast(1).joinToString(" "), boot)
    }

    private fun parseSize(rest: String): PanelEvent? {
        val parts = rest.split(' ').filter { it.isNotEmpty() }
        val w = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val h = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return PanelEvent.Size(w, h)
    }

    /** The state line, read key by key so a firmware that adds one does not break us. */
    private fun parseState(json: String): PanelEvent? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        fun i(name: String) = o.opt(name).let { v -> if (v == null || v == JSONObject.NULL) 0 else (v as? Number)?.toInt() ?: v.toString().toIntOrNull() ?: 0 }
        fun flag(name: String) = i(name) != 0
        // "pwr" is the one decimal the radio writes: "%d.%d" of watts and tenths. Read it as text,
        // because 0.7 as a double times ten is 6.9999999999999999, and a radio at 0.7 W is not at 0.6.
        val pwr = o.optString("pwr", "0")
        val watts = pwr.substringBefore('.').toIntOrNull() ?: 0
        val tenthsOfAWatt = pwr.substringAfter('.', "").firstOrNull()?.let { it - '0' } ?: 0

        return PanelEvent.State(
            RadioState(
                freqHz = i("freq"),
                mode = i("mode"),
                filterLowHz = i("flo"),
                filterHighHz = i("fhi"),
                agc = i("agc"),
                preOn = flag("pre"),
                att = i("att"),
                noiseBlankerOn = flag("nb"),
                noiseReduceOn = flag("nr"),
                squelch = i("sql"),
                rfGain = i("rfg"),
                volume = i("vol"),
                powerWatts = watts + tenthsOfAWatt / 10.0,
                stepIndex = i("step"),
                zoom = i("zoom"),
                vfo = i("vfo"),
                atuOn = flag("atu"),
                battery = i("bat"),
                transmitting = flag("tx"),
                freqLocked = flag("lock"),
            )
        )
    }

    private fun parseTxMeters(rest: String): PanelEvent? {
        val parts = rest.split(' ').filter { it.isNotEmpty() }
        val w = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val swr = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        val alc = parts.getOrNull(2)?.toDoubleOrNull() ?: return null
        return PanelEvent.TxMeters(radio.kd0cq.x6100.net.TxMeters(w, swr, alc))
    }

    private fun parseBands(json: String): PanelEvent? {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
        val bands = ArrayList<Band>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            bands += Band(
                name = o.optString("n"),
                startHz = o.optInt("a"),
                stopHz = o.optInt("b"),
            )
        }
        return PanelEvent.Bands(bands)
    }

    private fun parsePlan(json: String): PanelEvent? {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
        val segments = ArrayList<PlanSegment>(array.length())
        for (i in 0 until array.length()) {
            val seg = array.optJSONArray(i) ?: continue
            if (seg.length() < 3) continue
            segments += PlanSegment(seg.getInt(0), seg.getInt(1), seg.getInt(2))
        }
        return PanelEvent.Plan(segments)
    }

    private fun parseSpots(json: String): PanelEvent? {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
        val spots = ArrayList<Spot>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            spots += Spot(
                freqHz = o.optInt("f"),
                call = o.optString("c"),
                modeClass = o.optString("m"),
                ageMin = o.optInt("a"),
                spotter = o.optString("s"),
                comment = o.optString("k"),
                alert = o.optInt("al") != 0,
            )
        }
        return PanelEvent.Spots(spots)
    }

    private fun parseFt8Row(json: String): PanelEvent? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return PanelEvent.DecodedFt8Row(
            Ft8Row(
                id = o.optLong("id"),
                cellType = o.optInt("t"),
                text = o.optString("x"),
                call = o.optString("c"),
                grid = o.optString("g"),
                freqHz = o.optInt("f"),
                snrDb = o.optInt("snr"),
                toMe = o.optInt("me") != 0,
                workedType = o.optInt("w"),
                distanceKm = o.optInt("d"),
                pota = o.optInt("p"),
                odd = o.optInt("odd") != 0,
                country = o.optString("k"),
            )
        )
    }

    private fun parseFt8Status(json: String): PanelEvent? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        fun flag(name: String) = o.optInt(name) != 0
        return PanelEvent.Ft8(
            Ft8Status(
                open = flag("open"),
                protocol = o.optInt("proto"),
                callingCq = flag("cq"),
                transmitting = flag("tx"),
                autoOn = flag("auto"),
                hold = flag("hold"),
                sending = flag("sending"),
                txFreqHz = o.optInt("txf"),
                nextMessage = o.optString("next"),
                inMs = o.optInt("in"),
                slotMs = o.optInt("slot"),
                repeats = o.optInt("rep"),
                inQso = flag("qso"),
                hound = flag("hound"),
                noCall = flag("nocall"),
                logged = o.optString("logged"),
                loggedThisSession = flag("logged_s"),
            )
        )
    }

    /** `cw "text"`, with the text itself JSON-encoded by the radio. */
    private fun parseCw(rest: String): PanelEvent? {
        val quoted = rest.ifBlank { return null }
        val text = runCatching { JSONObject("{\"v\":$quoted}").getString("v") }.getOrElse { quoted.trim('"') }
        return PanelEvent.Cw(text)
    }

    /** "logged ok", "logged 12345 ok", "logged 12345 conflict". */
    private fun parseLogged(rest: String): PanelEvent? {
        val parts = rest.split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val id = parts.first().toLongOrNull()
        return if (id != null) {
            PanelEvent.Logged(LoggedReceipt(id, parts.getOrNull(1) ?: ""))
        } else {
            PanelEvent.Logged(LoggedReceipt(null, parts.first()))
        }
    }

    /** "role view 0 -", "role operate 1 My Phone". */
    private fun parseRole(rest: String): PanelEvent? {
        val parts = rest.split(' ', limit = 3)
        val role = parts.getOrNull(0)?.let { PanelRole.fromWire(it) } ?: return null
        return PanelEvent.Role(
            RoleInfo(
                role = role,
                txAllowed = parts.getOrNull(1) == "1",
                sessionName = (parts.getOrNull(2) ?: "-").takeIf { it != "-" } ?: "",
            )
        )
    }

    private fun parsePaired(rest: String): PanelEvent? {
        val parts = rest.split(' ').filter { it.isNotEmpty() }
        val token = parts.getOrNull(0)?.takeIf { isSessionToken(it) } ?: return null
        return PanelEvent.Paired(
            token = token,
            role = parts.getOrNull(1)?.let { PanelRole.fromWire(it) } ?: PanelRole.View,
            txAllowed = parts.getOrNull(2) == "1",
        )
    }

    /** "ctl none 0", "ctl local 0", "ctl tci 0", "ctl browser My Phone 1". */
    private fun parseCtl(rest: String): PanelEvent? {
        val parts = rest.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val holder = parts.first()
        // A browser's word is "browser" plus its paired name, which may hold spaces; the last token is
        // the flag that says whether that browser is us.
        val name = if (holder == "browser") parts.subList(1, parts.size - 1).joinToString(" ") else ""
        return PanelEvent.ControlHeld(ControlInfo(holder = if (name.isEmpty()) holder else "$holder $name", mine = parts.last() == "1"))
    }

    private fun parseSessions(json: String): PanelEvent? {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
        val sessions = ArrayList<SessionInfo>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            sessions += SessionInfo(
                index = o.optInt("n"),
                name = o.optString("name"),
                role = o.optString("role"),
                txAllowed = o.optInt("tx") != 0,
                lastSeenSeconds = o.optLong("last"),
                ip = o.optString("ip"),
                online = o.optInt("online") != 0,
                mine = o.optInt("me") != 0,
            )
        }
        return PanelEvent.Sessions(sessions)
    }

    /** A binary frame: the spectrum, or a screen. Null for a frame we cannot make sense of. */
    fun parseBinary(bytes: ByteArray): PanelEvent? =
        parseSpectrum(bytes)?.let { PanelEvent.Spectrum(it) } ?: parseScreenFrame(bytes)?.let { PanelEvent.Screen(it) }

    /** The waterfall's FFT: a 16-byte header with 0xFFFF, bins, centre, width, sequence, then int8 dB. */
    fun parseSpectrum(bytes: ByteArray): SpectrumFrame? {
        if (bytes.size < SPECTRUM_HEADER) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if ((buf.short.toInt() and 0xFFFF) != SPECTRUM_MAGIC) return null
        val bins = buf.short.toInt() and 0xFFFF
        if (bins <= 0 || bytes.size - SPECTRUM_HEADER < bins) return null
        val centre = buf.int
        val width = buf.int
        val seq = buf.int.toLong() and 0xFFFFFFFFL
        val db = ByteArray(bins)
        buf.get(db)
        return SpectrumFrame(bins, centre, width, seq, db)
    }

    /** A screen frame: an 8-byte header with width, height and sequence, then a JPEG. */
    fun parseScreenFrame(bytes: ByteArray): ScreenFrame? {
        if (bytes.size <= SCREEN_HEADER) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = buf.short.toInt() and 0xFFFF
        val h = buf.short.toInt() and 0xFFFF
        val seq = buf.int.toLong() and 0xFFFFFFFFL
        if (w == 0 || h == 0 || w > 4096 || h > 4096) return null
        if (bytes[SCREEN_HEADER] != 0xFF.toByte() || bytes[SCREEN_HEADER + 1] != 0xD8.toByte()) return null
        return ScreenFrame(w, h, seq, bytes.copyOfRange(SCREEN_HEADER, bytes.size))
    }
}
