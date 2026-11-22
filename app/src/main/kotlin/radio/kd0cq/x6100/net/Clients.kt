// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.net

import java.io.Closeable

/** A radio we can reach: where it answered discovery, and the two ports its servers listen on. */
data class RadioEndpoint(
    val name: String,
    val host: String,
    val panelPort: Int = PANEL_PORT,
    val tciPort: Int = TCI_PORT,
) {
    val panelUrl: String get() = "ws://$host:$panelPort/"
    val tciUrl: String get() = "ws://$host:$tciPort/"

    companion object {
        /** The radio's own mDNS name, when the LAN's resolver knows it. */
        const val DEFAULT_HOST = "x6100.local"

        fun default(name: String = "OpenX6X00", host: String = DEFAULT_HOST) = RadioEndpoint(name, host)
    }
}

/** Where the panel link stands, for the connect screen and the notification. */
enum class LinkPhase {
    IDLE,
    CONNECTING,
    CONNECTED,
    PAIRING,
    PAIRED,
    LOST,
    STOPPED,
    ;

    val online: Boolean get() = this == CONNECTED || this == PAIRING || this == PAIRED
}

/** The panel WebSocket: the radio's state, the spectrum, the apps, pairing and control. */
interface PanelClient : Closeable {

    /**
     * Text from the radio, in the order it arrived: `ctl`, `tx`, `role`, `err`, `state`, and the
     * rest of the panel's lines. This flow does not carry spectrum or screen frames, and it does
     * not drop a line to make room for one.
     */
    val events: kotlinx.coroutines.flow.Flow<PanelEvent>

    /**
     * Spectrum and screen frames only, conflated to the latest. A collector that is behind misses
     * intermediate pictures and still has the newest one. Missing a frame here never takes a
     * control or PTT line with it.
     */
    val frames: kotlinx.coroutines.flow.Flow<PanelEvent>
        get() = kotlinx.coroutines.flow.emptyFlow()

    /**
     * The JS8 and FT8 app waterfall, also conflated to the latest. It is not on [frames]:
     * those two pictures would replace each other, and a spectrum flood would hide every
     * JS8 line. It is not on [events] either, so a row of bins cannot sit in front of `ctl`.
     */
    val appFrames: kotlinx.coroutines.flow.Flow<PanelEvent>
        get() = kotlinx.coroutines.flow.emptyFlow()

    /**
     * WeFax scan lines. Not on [frames] and not on [events]: a chart is a
     * stream of rows, and one of them must not sit in front of `ctl`.
     */
    val faxFrames: kotlinx.coroutines.flow.Flow<PanelEvent>
        get() = kotlinx.coroutines.flow.emptyFlow()

    /** The link's phase, and the last word about why it is not up. */
    val phase: kotlinx.coroutines.flow.StateFlow<LinkPhase>
    val lastError: kotlinx.coroutines.flow.StateFlow<String?>

    /** Keep trying this radio until [disconnect], the way radio_link.js reconnects. */
    fun connect(endpoint: RadioEndpoint)

    fun disconnect()

    /** One command line to the radio; dropped with a word into [lastError] when the link is down. */
    fun send(command: String)

    /**
     * The same line, and whether the socket took it. A PTT renewal has to know: a renewal that never
     * left the phone is a lease about to end, and the honest answer then is to stop pressing, not to
     * keep pressing into the dark.
     */
    fun sendChecked(command: String): Boolean

    /** Put a six-digit code on the radio's screen, and answer it with [pair]. */
    fun askForPairingCode()

    fun pair(code: String, name: String)

    /** The token the radio handed us last, so a reconnect can [PanelCommands.auth] with it. */
    val token: String?
}

/** The TCI WebSocket: the receiver's audio, tuning, and the key. */
interface TciClient : Closeable {

    val events: kotlinx.coroutines.flow.Flow<TciEvent>

    /** The receiver's audio, as it comes from the radio. */
    val audio: kotlinx.coroutines.flow.Flow<TciAudioFrame>

    val phase: kotlinx.coroutines.flow.StateFlow<LinkPhase>
    val lastError: kotlinx.coroutines.flow.StateFlow<String?>

    fun connect(endpoint: RadioEndpoint)

    fun disconnect()

    fun send(command: String)

    /** [send], and whether the socket took it: the key and the unkey both ask before acting on it. */
    fun sendChecked(command: String): Boolean

    /** A binary frame: our microphone's TX_AUDIO while keyed. TX_CHRONO ticks come the other way. */
    fun sendFrame(frame: ByteArray)

    /** [sendFrame], and whether the socket took it. A frame it would not take is a silent transmitter,
     *  and the radio unkeys a silent one; saying so is what lets us stop first. */
    fun sendFrameChecked(frame: ByteArray): Boolean
}

/** How long to wait before trying again, in the way the browser page does: a short wait that grows. */
fun reconnectDelayMs(attempt: Int, capMs: Long = 10_000): Long {
    if (attempt <= 0) return 500
    val ms = 500L shl attempt.coerceAtMost(14)
    return ms.coerceAtMost(capMs)
}
