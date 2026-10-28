// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.

package radio.kd0cq.x6100.net

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One connection to one radio: where it came from, and where its two servers answered. */
data class RadioConnection(
    val endpoint: RadioEndpoint? = null,
    val panel: LinkPhase = LinkPhase.IDLE,
    val tci: LinkPhase = LinkPhase.IDLE,
) {
    val paired: Boolean get() = panel == LinkPhase.PAIRED
    val watching: Boolean get() = panel.online
}

/**
 * The one place that talks to the radio: [panel] for state, control, pairing, spectrum and the apps,
 * [tci] for the receiver's audio and the key. A screen never writes a socket of its own; it reads the
 * flows here and calls [send], and [RadioConnectionService] owns the lifetime.
 *
 * The radio trusts its own network rather than a login, so a new connection is only a viewer until it
 * pairs ("pair CODE NAME", answered "paired TOKEN ROLE TX"). The token is kept and sent as "auth TOKEN"
 * at every connection after that.
 */
class RadioLink(
    val panel: PanelClient,
    val tci: TciClient,
    val audio: AudioSink = NoOpAudioSink,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val tokens: PanelTokenStore = MemoryPanelTokenStore(),
) {

    private val _connection = MutableStateFlow(RadioConnection())
    val connection: StateFlow<RadioConnection> = _connection.asStateFlow()

    private val _state = MutableStateFlow<RadioState?>(null)

    /** The radio's state as "state {...}" tells it, or null before the first one. */
    val state: StateFlow<RadioState?> = _state.asStateFlow()

    private val _meters = MutableStateFlow(0.0)

    /** The S-meter in dB, ten a second while we watch the radio. */
    val meterDb: StateFlow<Double> = _meters.asStateFlow()

    private val _txMeters = MutableStateFlow(TxMeters(0.0, 1.0, 0.0))
    val txMeters: StateFlow<TxMeters> = _txMeters.asStateFlow()

    private val _role = MutableStateFlow(RoleInfo(PanelRole.View, false, ""))
    val role: StateFlow<RoleInfo> = _role.asStateFlow()

    private val _control = MutableStateFlow(ControlInfo("none", false))

    /** Who holds the radio's controls: none, the radio itself, a TCI client or a named browser. */
    val control: StateFlow<ControlInfo> = _control.asStateFlow()

    private val _bands = MutableStateFlow<List<Band>>(emptyList())
    val bands: StateFlow<List<Band>> = _bands.asStateFlow()

    private val _plan = MutableStateFlow<List<PlanSegment>>(emptyList())
    val plan: StateFlow<List<PlanSegment>> = _plan.asStateFlow()

    private val _spots = MutableStateFlow<List<Spot>>(emptyList())
    val spots: StateFlow<List<Spot>> = _spots.asStateFlow()

    private val _ft8Rows = MutableStateFlow<List<Ft8Row>>(emptyList())

    /** Decoded FT8 rows, newest last, as the radio reported them; the last 48 the radio kept. */
    val ft8Rows: StateFlow<List<Ft8Row>> = _ft8Rows.asStateFlow()

    private val _ft8Status = MutableStateFlow<Ft8Status?>(null)
    val ft8Status: StateFlow<Ft8Status?> = _ft8Status.asStateFlow()

    private val _cwLines = MutableStateFlow<List<String>>(emptyList())

    /** The CW decoder's text, newest last. */
    val cwLines: StateFlow<List<String>> = _cwLines.asStateFlow()

    private val _receipts = MutableStateFlow<List<LoggedReceipt>>(emptyList())

    /** What became of the QSOs we sent to the log, newest last. */
    val receipts: StateFlow<List<LoggedReceipt>> = _receipts.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())

    /** The paired sessions, for an admin's screen. */
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    private val _spectrum = MutableStateFlow<SpectrumFrame?>(null)
    val spectrum: StateFlow<SpectrumFrame?> = _spectrum.asStateFlow()

    private val _screen = MutableStateFlow<ScreenFrame?>(null)

    /** The radio's own screen as JPEG, while it is asked for. */
    val screen: StateFlow<ScreenFrame?> = _screen.asStateFlow()

    private val _hello = MutableStateFlow<Hello?>(null)

    /** What the radio said about itself at this connection. */
    val hello: StateFlow<Hello?> = _hello.asStateFlow()

    private val _size = MutableStateFlow<ScreenSize?>(null)

    /** The radio's screen size, so a picture of it can be drawn at the right scale. */
    val screenSize: StateFlow<ScreenSize?> = _size.asStateFlow()

    private val _keyNames = MutableStateFlow<List<String>>(emptyList())

    /** The names "key NAME 1|0" answers to on this radio. */
    val keyNames: StateFlow<List<String>> = _keyNames.asStateFlow()

    private val _modeCount = MutableStateFlow(PanelMode.entries.size)

    /** How many modes this radio has; "modes N" tells us. */
    val modeCount: StateFlow<Int> = _modeCount.asStateFlow()

    private val _volumeMax = MutableStateFlow(100)

    /** The loudest "set vol" this radio takes ("volmax N"). */
    val volumeMax: StateFlow<Int> = _volumeMax.asStateFlow()

    private val _lastAnswer = MutableStateFlow<String?>(null)

    /** The last word the radio gave us, error or not, for the UI's status line. */
    val lastAnswer: StateFlow<String?> = _lastAnswer.asStateFlow()

    val lastError: StateFlow<String?> = panel.lastError

    val endpoint: RadioEndpoint? get() = _connection.value.endpoint

    /** Whether we hold a token this radio answered "paired" with. */
    val hasToken: Boolean get() = tokens.token != null

    /** Whether the radio's screen frames are wanted; off, because the spectrum is what we draw. */
    @Volatile
    var screenFramesWanted: Boolean = false

    init {
        collectPanel()
        collectTci()
        watchPhases()
    }

    /** For a link that should use this app's preferences: called once by the service or the app. */
    fun withTokenStore(store: PanelTokenStore) = RadioLink(panel, tci, audio, scope, store)

    fun connect(endpoint: RadioEndpoint) {
        _connection.value = _connection.value.copy(endpoint = endpoint)
        panel.connect(endpoint)
        tci.connect(endpoint)
    }

    fun disconnect() {
        panel.disconnect()
        tci.disconnect()
        _connection.value = RadioConnection()
    }

    /** One text line to the radio, as [PanelCommands] builds them. */
    fun send(line: String) = panel.send(line)

    /** Ask the radio to show a six-digit code, then [pair] with what the operator reads. */
    fun askForPairingCode() = panel.askForPairingCode()

    fun pair(code: String, name: String) = panel.pair(code, name)

    /** Forget our own pairing on the radio and drop the token here. */
    fun forgetPairing() {
        if (hasToken) send(PanelCommands.unpair())
        tokens.clear()
    }

    /** Ask for a QSO receipt we may have lost in a drop; the radio never logs a retry twice. */
    fun askAboutLog(id: Long, text: String) = send(PanelCommands.logqQuery(id, text))

    /** Turn the radio's screen frames on or off for us, and tell it which picture it should send. */
    fun wantScreenFrames(on: Boolean, picture: PanelCommands.Picture? = null) {
        screenFramesWanted = on
        send(PanelCommands.screen(on))
        picture?.let { send(PanelCommands.picture(it)) }
    }

    private fun collectPanel() {
        scope.launch {
            panel.events.collect { event ->
                when (event) {
                    is PanelEvent.Hello -> _hello.value = Hello(event.version, event.bootId)
                    is PanelEvent.Size -> _size.value = ScreenSize(event.width, event.height)
                    is PanelEvent.Keys -> _keyNames.value = event.names
                    is PanelEvent.Modes -> _modeCount.value = event.count
                    is PanelEvent.VolumeMax -> _volumeMax.value = event.value
                    is PanelEvent.State -> _state.value = event.state
                    is PanelEvent.Meter -> _meters.value = event.db
                    is PanelEvent.TxMeters -> _txMeters.value = event.meters
                    is PanelEvent.Role -> _role.value = event.role
                    is PanelEvent.ControlHeld -> _control.value = event.info
                    is PanelEvent.Transmitting -> _state.value?.let { state ->
                        _state.value = state.copy(transmitting = event.on)
                    }
                    is PanelEvent.Bands -> _bands.value = event.bands
                    is PanelEvent.Plan -> _plan.value = event.segments
                    is PanelEvent.Spots -> _spots.value = event.spots
                    is PanelEvent.DecodedFt8Row -> _ft8Rows.value = (_ft8Rows.value + event.row).takeLast(FT8_ROWS_KEPT)
                    is PanelEvent.Ft8 -> _ft8Status.value = event.status
                    is PanelEvent.Cw -> _cwLines.value = (_cwLines.value + event.text).takeLast(CW_LINES_KEPT)
                    is PanelEvent.Logged -> _receipts.value = (_receipts.value + event.receipt).takeLast(RECEIPTS_KEPT)
                    is PanelEvent.Sessions -> _sessions.value = event.sessions
                    is PanelEvent.Spectrum -> _spectrum.value = event.frame
                    is PanelEvent.Screen -> _screen.value = event.frame
                    is PanelEvent.Paired -> {
                        _lastAnswer.value = "paired as ${event.role.wire}"
                        _role.value = RoleInfo(event.role, event.txAllowed, _role.value.sessionName)
                    }

                    is PanelEvent.Failure -> _lastAnswer.value = event.text
                    is PanelEvent.Notice -> _lastAnswer.value = event.text
                    is PanelEvent.Pong -> Unit
                }
            }
        }
    }

    private fun collectTci() {
        scope.launch {
            tci.events.collect { event ->
                when (event) {
                    is TciEvent.Frequency -> _lastAnswer.value = "TCI at ${event.hertz} Hz"
                    else -> Unit
                }
            }
        }
        scope.launch {
            tci.audio.collect { frame -> audio.receive(frame) }
        }
    }

    private fun watchPhases() {
        scope.launch {
            panel.phase.collect { phase ->
                _connection.value = _connection.value.copy(panel = phase)
                if (!phase.online) _state.value = null
            }
        }
        scope.launch { tci.phase.collect { phase -> _connection.value = _connection.value.copy(tci = phase) } }
    }

    private companion object {
        const val FT8_ROWS_KEPT = 48
        const val CW_LINES_KEPT = 64
        const val RECEIPTS_KEPT = 20
    }
}

/** What the radio's TCI audio goes into: the app's speakers, an FT8 modem, or nothing at all. */
interface AudioSink {
    fun receive(frame: TciAudioFrame)
}

/** Until [AudioPipeline] is wired, the receiver's audio is read and dropped. */
object NoOpAudioSink : AudioSink {
    override fun receive(frame: TciAudioFrame) = Unit
}

/** The link the app uses. The service starts it; the screens read it. */
object RadioLinks {
    /** Built once, with the app's own preferences as its token store. */
    @Volatile
    private var built: RadioLink? = null

    fun link(context: Context? = null): RadioLink =
        built ?: synchronized(this) {
            built ?: run {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val http = OkHttpPanelClient.defaultHttp()
                val tokens = context?.let { PreferencesPanelTokenStore(it) } ?: MemoryPanelTokenStore()
                val link = RadioLink(
                    panel = OkHttpPanelClient(scope, tokens, http),
                    tci = OkHttpTciClient(scope, http),
                    tokens = tokens,
                    scope = scope,
                )
                built = link
                link
            }
        }

    /** For the tests and previews: a link that holds nothing back and reaches nothing. */
    fun replaceWith(link: RadioLink) {
        built = link
    }
}
