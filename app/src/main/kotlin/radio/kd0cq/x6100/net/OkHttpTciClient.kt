// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

/**
 * The TCI link over a WebSocket: the radio's greeting (`send_greeting()` in `tci_server.c`) tells us
 * what it can do, [TciCommands.audioFormat] and [TciCommands.audioStart] ask it for 24 kHz mono PCM we
 * can play, and its binary frames carry the receiver. A TCI client has no pairing: the radio's
 * /mnt/tci.conf says what it may do.
 */
class OkHttpTciClient(
    private val scope: CoroutineScope,
    private val http: OkHttpClient = OkHttpPanelClient.defaultHttp(),
    private val sampleRate: Int = TCI_AUDIO_RATE,
    private val sampleType: TciSampleType = TciSampleType.Int16,
    private val channels: Int = 1,
    private val samplesPerFrame: Int = 512,
    private val handshakeMs: Long = 8_000,
) : TciClient {

    private val sink = MutableSharedFlow<TciEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val audioSink = MutableSharedFlow<TciAudioFrame>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val linkPhase = MutableStateFlow(LinkPhase.IDLE)
    private val error = MutableStateFlow<String?>(null)

    private var socket: WebSocket? = null
    private var worker: Job? = null

    @Volatile
    private var wanted: RadioEndpoint? = null

    override val events: Flow<TciEvent> = sink.asSharedFlow()
    override val audio: Flow<TciAudioFrame> = audioSink.asSharedFlow()
    override val phase: StateFlow<LinkPhase> = linkPhase.asStateFlow()
    override val lastError: StateFlow<String?> = error.asStateFlow()

    override fun connect(endpoint: RadioEndpoint) {
        synchronized(this) {
            wanted = endpoint
            if (worker?.isActive != true) worker = scope.launch { stayConnected(endpoint) }
        }
    }

    override fun disconnect() {
        synchronized(this) {
            wanted = null
            worker?.cancel()
            worker = null
            socket?.cancel()
            socket = null
            linkPhase.value = LinkPhase.STOPPED
        }
    }

    override fun close() = disconnect()

    override fun send(command: String) {
        if (!sendChecked(command)) error.value = "the radio is not reached: $command"
    }

    /** OkHttp says no when the socket is closed, closing or its queue is full. */
    override fun sendChecked(command: String): Boolean {
        val ws = socket ?: return false
        return runCatching { ws.send(command) }.getOrDefault(false)
    }

    override fun sendFrame(frame: ByteArray) {
        if (!sendFrameChecked(frame)) error.value = "the radio is not reached: audio"
    }

    /** A TX_AUDIO the socket would not take is a transmitter going silent, and the radio unkeys a
     *  silent one 500 ms later: the caller has to hear about it on the first frame, not the fifth. */
    override fun sendFrameChecked(frame: ByteArray): Boolean {
        val ws = socket ?: return false
        return runCatching { ws.send(ByteString.of(*frame)) }.getOrDefault(false)
    }

    private suspend fun stayConnected(endpoint: RadioEndpoint) {
        var attempt = 0
        while (scope.isActive && wanted == endpoint) {
            linkPhase.value = LinkPhase.CONNECTING
            val opened = CompletableDeferred<Boolean>()
            val closed = CompletableDeferred<Unit>()
            val ws = http.newWebSocket(Request.Builder().url(endpoint.tciUrl).build(), Listener(opened, closed))
            socket = ws
            val up = withTimeoutOrNull(handshakeMs) { opened.await() } == true
            if (up && closed.isActive) {
                attempt = 0
                linkPhase.value = LinkPhase.CONNECTED
                // The server greets us first (see TciCommands.hello); we only ask for the audio we want.
                send(TciCommands.hello())
                send(TciCommands.audioFormat(sampleRate, sampleType, channels, samplesPerFrame))
                send(TciCommands.audioStart())
                keepPaced(ws, closed)
                send(TciCommands.audioStop())
            } else {
                ws.cancel()
                error.value = error.value ?: "no answer from ${endpoint.tciUrl}"
            }
            socket = null
            if (wanted != endpoint) break
            linkPhase.value = LinkPhase.LOST
            delay(reconnectDelayMs(attempt++))
        }
        if (wanted == null) linkPhase.value = LinkPhase.STOPPED
    }

    /** The knob pings every 2 s; a client silent for a minute is dropped, so pace the socket. */
    private suspend fun keepPaced(ws: WebSocket, closed: CompletableDeferred<Unit>) = coroutineScope {
        val pacer = launch {
            while (isActive) {
                delay(20_000)
                ws.send(TciCommands.keepalive())
            }
        }
        try {
            closed.await()
        } finally {
            pacer.cancel()
        }
    }

    private inner class Listener(
        private val opened: CompletableDeferred<Boolean>,
        private val closed: CompletableDeferred<Unit>,
    ) : WebSocketListener() {

        /** The four audio-format lines arrive one at a time; the format is known with the fourth. */
        private val formatLines = mutableMapOf<String, String>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            error.value = null
            opened.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            text.split(';').forEach { part ->
                when (val event = TciProtocol.parse(part)) {
                    null -> Unit
                    is TciEvent.AudioSetting -> {
                        formatLines[event.name] = event.value
                        if (formatLines.size == 4) sink.tryEmit(foldedAudioFormat())
                    }
                    else -> sink.tryEmit(event)
                }
            }
        }

        private fun foldedAudioFormat(): TciEvent.AudioFormat = TciEvent.AudioFormat(
            sampleRate = formatLines.getValue("audio_samplerate").toIntOrNull() ?: 0,
            sampleType = TciSampleType.fromName(formatLines.getValue("audio_stream_sample_type")) ?: TciSampleType.Int16,
            channels = formatLines.getValue("audio_stream_channels").toIntOrNull() ?: 1,
            samples = formatLines.getValue("audio_stream_samples").toIntOrNull() ?: 0,
        )

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val raw = bytes.toByteArray()
            val header = TciProtocol.decodeAudioHeader(raw) ?: return
            audioSink.tryEmit(TciAudioFrame(header, raw.copyOfRange(TCI_AUDIO_HEADER_BYTES, raw.size)))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
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
}
