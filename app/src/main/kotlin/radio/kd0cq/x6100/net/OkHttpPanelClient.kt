// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * The panel link over a WebSocket, run the way the radio's own page runs it: keep one socket, say who
 * we are with the token from the last pairing, ask for the panel with "panel 1", ping to tell a dead
 * link from a quiet one, and come back after a short wait that grows when the socket goes away
 * (webserver's radio_link.js).
 */
class OkHttpPanelClient(
    private val scope: CoroutineScope,
    private val tokens: PanelTokenStore = MemoryPanelTokenStore(),
    private val http: OkHttpClient = defaultHttp(),
    private val pingEveryMs: Long = 15_000,
    private val handshakeMs: Long = 8_000,
) : PanelClient {

    /**
     * Text and pictures are separate lanes. A spectrum flood used to evict `ctl` and `tx` from the
     * one shared drop-oldest buffer; [PanelEventBus] is that split.
     */
    private val bus = PanelEventBus(scope)
    private val linkPhase = MutableStateFlow(LinkPhase.IDLE)
    private val error = MutableStateFlow<String?>(null)

    private var socket: WebSocket? = null
    private var worker: Job? = null

    @Volatile
    private var wanted: RadioEndpoint? = null

    /** The generation of the socket [stayConnected] has open, so [disconnect] can retire it. */
    @Volatile
    private var connectionGeneration = 0

    private var pingNumber = 0L

    override val events: Flow<PanelEvent> = bus.events
    override val frames: Flow<PanelEvent> = bus.frames
    override val appFrames: Flow<PanelEvent> = bus.appFrames
    override val faxFrames: Flow<PanelEvent> = bus.faxFrames
    override val phase: StateFlow<LinkPhase> = linkPhase.asStateFlow()
    override val lastError: StateFlow<String?> = error.asStateFlow()

    override val token: String? get() = tokens.token

    override fun connect(endpoint: RadioEndpoint) {
        synchronized(this) {
            wanted = endpoint
            if (worker?.isActive != true) worker = scope.launch { stayConnected(endpoint) }
        }
    }

    override fun disconnect() {
        synchronized(this) {
            wanted = null
            // Retire before cancelling the socket, so a last onMessage cannot join the next press.
            bus.closeConnection(connectionGeneration)
            worker?.cancel()
            worker = null
            socket?.cancel()
            socket = null
            linkPhase.value = LinkPhase.STOPPED
        }
    }

    /** The socket only: a shared OkHttpClient outlives us, its threads are not ours to stop. */
    override fun close() = disconnect()

    override fun send(command: String) {
        if (!sendChecked(command)) error.value = "the radio is not reached: $command"
    }

    /** OkHttp says no when the socket is closed, closing or its queue is full — which is all the ways a
     *  renewal can be lost, and the reason a lease machine must hear about it. */
    override fun sendChecked(command: String): Boolean {
        val ws = socket ?: return false
        return runCatching { ws.send(command) }.getOrDefault(false)
    }

    override fun askForPairingCode() {
        linkPhase.value = LinkPhase.PAIRING
        send(PanelCommands.pairAsk())
    }

    override fun pair(code: String, name: String) {
        linkPhase.value = LinkPhase.PAIRING
        send(PanelCommands.pair(code, name))
    }

    /** One socket after another, for as long as the app wants this radio. */
    private suspend fun stayConnected(endpoint: RadioEndpoint) {
        var attempt = 0
        while (scope.isActive && wanted == endpoint) {
            linkPhase.value = LinkPhase.CONNECTING
            val opened = CompletableDeferred<Boolean>()
            val closed = CompletableDeferred<Unit>()
            // One generation per socket. closeConnection retires it so a line still queued at
            // disconnect cannot land on the next press. The handshake of the new socket, and the
            // lines below, are the resync.
            val generation = bus.openConnection()
            connectionGeneration = generation
            val ws = http.newWebSocket(
                Request.Builder().url(endpoint.panelUrl).build(),
                Listener(generation, opened, closed),
            )
            socket = ws
            val up = withTimeoutOrNull(handshakeMs) { opened.await() } == true
            if (up && closed.isActive) {
                attempt = 0
                linkPhase.value = if (tokens.token != null) LinkPhase.PAIRED else LinkPhase.CONNECTED
                // auth (when we have a token) and panel 1. panel_fresh then sends the whole state.
                for (line in PanelCommands.reconnect(tokens.token)) send(line)
                heartbeat(ws, closed)
            } else {
                ws.cancel()
                error.value = error.value ?: "no answer from ${endpoint.panelUrl}"
            }
            socket = null
            bus.closeConnection(generation)
            if (wanted != endpoint) break
            linkPhase.value = LinkPhase.LOST
            delay(reconnectDelayMs(attempt++))
        }
        if (wanted == null) linkPhase.value = LinkPhase.STOPPED
    }

    /** The page's heartbeat: our own number, echoed back, so a silent link is seen as a dead one. */
    private suspend fun heartbeat(ws: WebSocket, closed: CompletableDeferred<Unit>) = coroutineScope {
        val pacer = launch {
            while (isActive) {
                delay(pingEveryMs)
                ws.send(PanelCommands.ping(++pingNumber))
            }
        }
        try {
            closed.await()
        } finally {
            pacer.cancel()
        }
    }

    private inner class Listener(
        private val generation: Int,
        private val opened: CompletableDeferred<Boolean>,
        private val closed: CompletableDeferred<Unit>,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            error.value = null
            opened.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = runCatching { PanelProtocol.parseLine(text) }.getOrNull()
            if (event == null) return
            when (event) {
                is PanelEvent.Paired -> {
                    if (isSessionToken(event.token)) tokens.token = event.token
                    linkPhase.value = LinkPhase.PAIRED
                }

                is PanelEvent.Failure -> {
                    error.value = event.text
                    if (event.text.contains("pair this browser again")) tokens.clear()
                }

                else -> Unit
            }
            bus.post(generation, event)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val raw = bytes.toByteArray()
            val spectrum = PanelProtocol.parseSpectrum(raw)
            if (spectrum != null) {
                bus.post(generation, PanelEvent.Spectrum(spectrum))
                return
            }
            // 0xFFFD before the JPEG check. The app waterfall's first bins can look like FFD8.
            val appWaterfall = PanelProtocol.parseAppWaterfall(raw)
            if (appWaterfall != null) {
                bus.post(generation, PanelEvent.AppWaterfall(appWaterfall))
                return
            }
            // 0xFFFE before the JPEG check, for the same reason as the waterfall.
            val fax = PanelProtocol.parseWeFax(raw)
            if (fax != null) {
                bus.post(generation, PanelEvent.WeFaxRow(fax))
                return
            }
            val screen = PanelProtocol.parseScreenFrame(raw)
            if (screen != null) bus.post(generation, PanelEvent.Screen(screen))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(CLOSE_NORMAL, null)
            closed.complete(Unit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            error.value = t.message ?: "the radio is not reached"
            opened.complete(false)
            closed.complete(Unit)
        }
    }

    companion object {
        private const val CLOSE_NORMAL = 1000

        /** A WebSocket that says nothing of its own: OkHttp's ping keeps the read open. */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}
