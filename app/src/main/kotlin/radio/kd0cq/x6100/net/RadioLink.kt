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
import radio.kd0cq.x6100.core.PairingController

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
    val audio: AudioSinkRouter = AudioSinkRouter(),
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

    /** The pairing machine's latest answer, published for the screens. */
    private val _pairing = MutableStateFlow(PairingController.Status())

    /**
     * Pairing, role and controls in one machine, fed every line the radio answers.
     *
     * The client already keeps the token and sends it at each connection; this is what watches what
     * becomes of it. A token the radio has forgotten ("err unknown session") or a session an admin
     * revoked is dropped here the moment the radio says so — otherwise every later connection comes
     * back a viewer, presses a key that is refused, and the operator has no idea why.
     */
    private val pairing = PairingController(
        send = { line -> panel.sendChecked(line) },
        tokens = tokens,
        listener = { status, _ -> _pairing.value = status },
    )

    /** Whether we are paired, what the radio lets us do, and who holds the controls. */
    val pairingState: StateFlow<PairingController.Status> = _pairing.asStateFlow()

    /** Enough to press the key: a pairing, a role that may operate, and TX allowed to this session. */
    val canTransmit: Boolean get() = pairing.status().canTransmit

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

    /**
     * The CW decoder's text, newest last.
     *
     * The scope does not paint this. A line of decode along the bottom of every
     * layout covered the band plan and the last waterfall rows. The lines stay
     * here so a decoder surface can read them; the spectrum layouts do not.
     */
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

    /**
     * The screen is off or another app is in front. [screenFramesWanted] stays as the
     * operator left it, so coming back can ask again. While this is set, a new request
     * is remembered and not sent: the radio should not start JPEGs into a pocket.
     */
    @Volatile
    private var picturesPaused: Boolean = false

    init {
        collectPanel()
        collectFrames()
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
        // A backgrounded phone keeps the wish and does not ask. resumePictures sends it.
        if (picturesPaused) return
        send(PanelCommands.screen(on))
        picture?.let { send(PanelCommands.picture(it)) }
    }

    /**
     * Stop framebuffer JPEGs while nothing is drawing them.
     *
     * `screen 0` is the firmware's off switch (`PanelCommands.screen`). The scope
     * itself is not a radio command: the pane stops collecting. This only tells
     * the radio to stop the mirror. Called again, it does not send a second off.
     */
    fun pausePictures() {
        if (picturesPaused) return
        picturesPaused = true
        // Only while the panel is up. A send with no socket writes "the radio is not reached"
        // into the status line, which is not what leaving the screen means.
        if (screenFramesWanted && _connection.value.panel.online) send(PanelCommands.screen(false))
    }

    /** Ask for the mirror again, if the screen tab still wanted it when we left. */
    fun resumePictures() {
        if (!picturesPaused) return
        picturesPaused = false
        if (screenFramesWanted && _connection.value.panel.online) send(PanelCommands.screen(true))
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
                    is PanelEvent.Spectrum, is PanelEvent.Screen -> Unit
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

    /** Pictures only. Kept off [collectPanel] so a JPEG cannot sit in front of `ctl` or `tx`. */
    private fun collectFrames() {
        scope.launch {
            panel.frames.collect { event ->
                when (event) {
                    // The header's bin count is what arrived. Today panel_thread always sends
                    // WATERFALL_NFFT (1024) int8 bins, when the DSP has a new frame, and skips
                    // one while 16 KB is still queued. There is no command for a larger FFT or
                    // a higher rate. The painter maps these bins onto the pane; it does not
                    // invent bins the socket did not send.
                    is PanelEvent.Spectrum -> _spectrum.value = event.frame
                    is PanelEvent.Screen -> _screen.value = event.frame
                    else -> Unit
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

/**
 * The link's sink, with the sink behind it changeable after the link is built.
 *
 * The link is built before the app's audio is: [RadioLinks] makes it on the first look at the radio,
 * and the [radio.kd0cq.x6100.audio.AndroidAudioPipeline] that owns the phone's audio devices belongs
 * to [radio.kd0cq.x6100.service.RadioRigs], which decides when the microphone and the speaker may be
 * opened at all. So the link holds this, and the rig puts the pipeline behind it. Null means the
 * frames are read and dropped, which is what a preview and a test link do.
 */
class AudioSinkRouter(@Volatile var delegate: AudioSink? = null) : AudioSink {
    override fun receive(frame: TciAudioFrame) {
        delegate?.receive(frame)
    }
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
