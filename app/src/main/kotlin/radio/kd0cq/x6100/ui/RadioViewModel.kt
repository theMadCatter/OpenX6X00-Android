// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.

package radio.kd0cq.x6100.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import radio.kd0cq.x6100.discovery.FoundRadio
import radio.kd0cq.x6100.discovery.RadioDiscovery
import radio.kd0cq.x6100.digital.Ft8Look
import radio.kd0cq.x6100.digital.GhostNet
import radio.kd0cq.x6100.digital.Js8Protocol
import radio.kd0cq.x6100.digital.Js8Row
import radio.kd0cq.x6100.digital.Js8Status
import radio.kd0cq.x6100.digital.UsAdvanced
import radio.kd0cq.x6100.digital.WeFaxProtocol
import radio.kd0cq.x6100.digital.WeFaxScan
import radio.kd0cq.x6100.digital.WeFaxStatus
import radio.kd0cq.x6100.net.AppWaterfallFrame
import radio.kd0cq.x6100.net.Band
import radio.kd0cq.x6100.net.ControlInfo
import radio.kd0cq.x6100.net.Ft8Row
import radio.kd0cq.x6100.net.Ft8Status
import radio.kd0cq.x6100.net.Hello
import radio.kd0cq.x6100.net.LinkPhase
import radio.kd0cq.x6100.net.LoggedReceipt
import radio.kd0cq.x6100.net.PANEL_PORT
import radio.kd0cq.x6100.net.PanelCommands
import radio.kd0cq.x6100.net.PanelSettings
import radio.kd0cq.x6100.net.PlanSegment
import radio.kd0cq.x6100.net.RadioEndpoint
import radio.kd0cq.x6100.net.RadioLinks
import radio.kd0cq.x6100.net.RadioState
import radio.kd0cq.x6100.net.RoleInfo
import radio.kd0cq.x6100.net.SessionInfo
import radio.kd0cq.x6100.net.SpectrumFrame
import radio.kd0cq.x6100.net.Spot
import radio.kd0cq.x6100.net.TCI_PORT
import radio.kd0cq.x6100.net.TxMeters
import radio.kd0cq.x6100.log.QrzLookup
import radio.kd0cq.x6100.log.QrzParse
import radio.kd0cq.x6100.log.QrzSecretStore
import radio.kd0cq.x6100.log.Qso
import radio.kd0cq.x6100.log.QsoBook
import radio.kd0cq.x6100.log.QsoDatabase
import radio.kd0cq.x6100.log.StationPlace
import radio.kd0cq.x6100.log.toEntity
import radio.kd0cq.x6100.log.toQso
import radio.kd0cq.x6100.service.RadioConnectionService
import radio.kd0cq.x6100.service.RememberedHost
import radio.kd0cq.x6100.service.RememberedHostStore
import radio.kd0cq.x6100.ui.face.RidgeHold
import radio.kd0cq.x6100.ui.face.WaterfallHold
import radio.kd0cq.x6100.service.RadioRigs
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * What the screens show and do: the link's state in, the radio's commands out. The link itself is the
 * app's singleton, owned by [RadioConnectionService] so a screen leaving the screen does not drop it.
 */
class RadioViewModel(application: Application) : AndroidViewModel(application) {

    private val link = RadioLinks.link(application)
    private val rig = RadioRigs.rig(application)
    private val discovery = RadioDiscovery(application)
    private val remembered = RememberedHostStore(application)

    val foundRadios: StateFlow<List<FoundRadio>> = discovery.radios

    /** Where the panel link stands; the screens show this. */
    val phase: StateFlow<LinkPhase> =
        link.connection.map { it.panel }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkPhase.IDLE)

    /** Where the TCI link stands: the audio's own path. */
    val tciPhase: StateFlow<LinkPhase> =
        link.connection.map { it.tci }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkPhase.IDLE)

    /** The last word the radio gave us, "err ..." included. */
    val lastError: StateFlow<String?> = link.lastAnswer

    private val _endpoint = MutableStateFlow<RadioEndpoint?>(null)
    val endpoint: StateFlow<RadioEndpoint?> = _endpoint.asStateFlow()

    val state: StateFlow<RadioState?> = link.state
    val meter: StateFlow<Double> = link.meterDb
    val txMeters: StateFlow<TxMeters> = link.txMeters
    val role: StateFlow<RoleInfo> = link.role
    val control: StateFlow<ControlInfo> = link.control
    val bands: StateFlow<List<Band>> = link.bands
    val plan: StateFlow<List<PlanSegment>> = link.plan
    val spots: StateFlow<List<Spot>> = link.spots
    val ft8Rows: StateFlow<List<Ft8Row>> = link.ft8Rows
    val ft8Status: StateFlow<Ft8Status?> = link.ft8Status
    val js8Rows: StateFlow<List<Js8Row>> = link.js8Rows
    val js8Status: StateFlow<Js8Status?> = link.js8Status
    val appWaterfall: StateFlow<List<AppWaterfallFrame>> = link.appWaterfall
    val wefax: StateFlow<WeFaxStatus?> = link.wefax
    val wefaxRows: StateFlow<List<WeFaxScan>> = link.wefaxRows
    val js8NeedsFirmware: StateFlow<Boolean> = link.js8NeedsFirmware
    val cwLines: StateFlow<List<String>> = link.cwLines
    val receipts: StateFlow<List<LoggedReceipt>> = link.receipts
    val sessions: StateFlow<List<SessionInfo>> = link.sessions
    val spectrum: StateFlow<SpectrumFrame?> = link.spectrum

    /**
     * Baked 3D rows. Owned here so a configuration change keeps them. The pane
     * projects this same ring into whatever size it measured. Nothing in
     * [RidgeHold.accept] allocates a buffer.
     */
    val ridgeHold = RidgeHold()

    /**
     * Raw waterfall lines, one per spectrum frame, up to [radio.kd0cq.x6100.ui.face.WaterfallDisplay.MAX_ROWS].
     * Owned here so a configuration change and a taller layout keep them. The pane
     * paints the newest lines that fit its bitmap and does not resize this ring
     * to that height. [WaterfallHold.accept] copies each line.
     */
    val waterfallHold = WaterfallHold()

    /**
     * Local QSOs. The radio's `logq` is a second write of the same text. The password for
     * QRZ never enters this flow.
     */
    private val qsoDb = QsoDatabase.open(application)
    private val qrzSecrets = QrzSecretStore(application)
    val stationPlace = StationPlace(application)
    private val _stationGrid = MutableStateFlow(stationPlace.grid())
    val stationGrid: StateFlow<String> = _stationGrid.asStateFlow()

    /** The grid the header shows. This does not tell the radio: pos is not in the firmware yet. */
    fun noteStationGrid(grid: String) {
        stationPlace.writeGrid(grid)
        _stationGrid.value = stationPlace.grid()
    }

    /** No logging interceptor: a session URL contains the QRZ password. */
    private val qrzHttp = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    private val qrzLookup = QrzLookup { url ->
        qrzHttp.newCall(Request.Builder().url(url).build()).execute().use { body ->
            body.body?.string().orEmpty()
        }
    }

    val qsos: StateFlow<List<Qso>> = qsoDb.qsos().all()
        .map { rows -> rows.map { it.toQso() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _qrz = MutableStateFlow<QrzParse?>(null)
    val qrzResult: StateFlow<QrzParse?> = _qrz.asStateFlow()
    private val _qrzBusy = MutableStateFlow(false)
    val qrzBusy: StateFlow<Boolean> = _qrzBusy.asStateFlow()

    /** The radio's own framebuffer, while [wantScreenFrames] has asked for it. */
    val screen: StateFlow<radio.kd0cq.x6100.net.ScreenFrame?> = link.screen

    /** Width and height the radio reported for that framebuffer. */
    val screenSize: StateFlow<radio.kd0cq.x6100.net.ScreenSize?> = link.screenSize

    /** Key names this radio accepts on `key NAME`. PTT is not offered through [pressKey]. */
    val keyNames: StateFlow<List<String>> = link.keyNames

    val modes: StateFlow<Int> = link.modeCount
    val volumeMax: StateFlow<Int> = link.volumeMax
    val hello: StateFlow<Hello?> = link.hello

    /** Whether this phone holds a token the radio paired it with. */
    val paired: Boolean get() = link.hasToken

    /** Whether the receiver is playing: the controls screen shows it beside the S-meter. */
    val audioRunning: StateFlow<Boolean> = rig.audio.running

    /** Whether we hold the key, from either path. */
    val pttKeyed: StateFlow<Boolean> = rig.ptt.keyed

    /** Why we are not on the air, in the radio's words or ours. */
    val pttRefusal: StateFlow<String?> = rig.ptt.refusal

    /** Why the audio is not moving, in the phone's words: a refused track, a stopped capture. */
    val audioProblem: StateFlow<String?> = rig.audio.lastError

    /**
     * Whether this phone will let us capture. The screen asks for the grant before switching the
     * transmit audio to the phone's microphone, because the transmit machine refuses without it rather
     * than falling back to the radio's own microphone.
     */
    fun hasMicrophonePermission(): Boolean =
        getApplication<Application>().checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Where the transmit audio comes from: false keys with the microphone at the radio, on a Panel
     * lease; true keys with this phone's microphone, on the TCI link. Switching it never carries a
     * transmission over: the page does the same, because a call half in one path and half in the other
     * is a call nobody understands afterwards.
     */
    var transmitAudioFromPhone: Boolean
        get() = rig.ptt.usePhoneMicrophone
        set(value) {
            if (rig.ptt.usePhoneMicrophone != value) {
                rig.ptt.usePhoneMicrophone = value
                rig.ptt.release()
            }
        }

    /** Press the key. It may refuse, and [pttRefusal] says why; nothing is sent on a guess. */
    fun pressPtt() = rig.ptt.press()

    /** Let go. Safe from anywhere, including a screen that is going away. */
    fun releasePtt() = rig.ptt.release()

    init {
        _endpoint.value = link.endpoint
        viewModelScope.launch { link.connection.collect { _endpoint.value = it.endpoint } }
    }

    /** Look for radios that answer the name or service the firmware advertises. */
    fun search() = discovery.start()

    fun stopSearching() = discovery.stop()

    /**
     * The host the connect field starts from. A process that died, or an install
     * over the existing app, keeps whatever was last entered. Nothing saved yet
     * is still [RadioEndpoint.DEFAULT_HOST].
     */
    fun rememberedHost(): String = RememberedHost.shown(remembered.readHost())

    /** Write the host the operator entered. Disconnect does not call this to clear it. */
    fun rememberHost(host: String) = remembered.writeHost(host)

    /** The GhostNet regional group, or @GNUSAMO until the operator edits it. */
    fun ghostnetGroup(): String {
        val saved = remembered.readGroup()
        return if (saved.isNullOrBlank()) GhostNet.DEFAULT_REGION else GhostNet.groupToken(saved)
    }

    fun rememberGhostnetGroup(group: String) = remembered.writeGroup(GhostNet.groupToken(group))

    /** The service owns the sockets, so a screen leaving does not drop the link. */
    fun connectTo(radio: FoundRadio) {
        val endpoint = radio.endpoint()
        rememberHost(endpoint.host)
        _endpoint.value = endpoint
        RadioConnectionService.connect(getApplication(), endpoint)
    }

    /** Reach a radio the resolver does not know, by address. */
    fun connectToHost(host: String, panelPort: Int = PANEL_PORT, tciPort: Int = TCI_PORT) {
        rememberHost(host)
        val endpoint = RadioEndpoint(name = host, host = host, panelPort = panelPort, tciPort = tciPort)
        _endpoint.value = endpoint
        RadioConnectionService.connect(getApplication(), endpoint)
    }

    fun disconnect() = RadioConnectionService.stop(getApplication())

    fun askForPairingCode() = link.askForPairingCode()

    /** Answer the radio's six-digit code with the name this phone should be paired under. */
    fun pair(code: String, name: String) = link.pair(code, name)

    fun forgetPairing() = link.forgetPairing()

    fun send(command: String) = link.send(command)

    fun setFrequency(freqHz: Int) = send(PanelCommands.set(PanelSettings.FREQ, freqHz))

    fun setMode(index: Int) = send(PanelCommands.set(PanelSettings.MODE, index))

    fun setFilter(lowHz: Int, highHz: Int) =
        send(PanelCommands.set(PanelSettings.FILTER, lowHz, highHz))

    fun setAgc(index: Int) = send(PanelCommands.set(PanelSettings.AGC, index))

    fun setStep(index: Int) = send(PanelCommands.set(PanelSettings.STEP, index))

    fun setVolume(value: Int) = send(PanelCommands.set(PanelSettings.VOL, value))

    /** The radio takes tenths of a watt. */
    fun setPower(tenthsOfWatt: Int) = send(PanelCommands.set(PanelSettings.PWR, tenthsOfWatt))

    fun setZoom(value: Int) = send(PanelCommands.set(PanelSettings.ZOOM, value))

    /** +1 is the next band up, −1 the next band down. The radio does not take a band index here. */
    fun setBand(direction: Int) = send(PanelCommands.set(PanelSettings.BAND, direction))

    /** "set vfo" toggles A and B. The value is ignored by the radio beyond being present; 1 is what the page sends. */
    fun toggleVfo() = send(PanelCommands.set(PanelSettings.VFO, 1))

    fun setRfGain(value: Int) = send(PanelCommands.set(PanelSettings.RFG, value))

    fun setSquelch(value: Int) = send(PanelCommands.set(PanelSettings.SQL, value))

    fun toggle(setting: String, on: Boolean) =
        send(PanelCommands.set(setting, if (on) 1 else 0))

    /** Turn the main tuning knob by N detents. Positive is up in frequency, as the radio's main encoder. */
    fun tuneBy(detents: Int) = turnKnob("main", detents)

    /**
     * `knob main|vol|mfk N`. The names are the three encoders remote_server.c accepts.
     * A name outside that set is still sent: the radio answers `err unknown knob` and nothing moves.
     */
    fun turnKnob(name: String, detents: Int) = send(PanelCommands.knob(name, detents))

    /**
     * A front-panel key, down or up.
     *
     * PTT is refused here. The only key path that may key the transmitter is [pressPtt] / [releasePtt],
     * because that path is the lease (or the TCI session) and it is the path that also sends the unkey.
     * A bare `key PTT 1` from a screen button would leave the carrier up with no machine watching it.
     */
    fun pressKey(name: String, down: Boolean) {
        if (radio.kd0cq.x6100.ui.face.RadioFace.isCarrierKey(name)) return
        send(PanelCommands.key(name, down))
    }

    fun takeControl() = send(PanelCommands.control(take = true))

    fun releaseControl() = send(PanelCommands.control(take = false))

    /** The admin's list of paired sessions. */
    fun askSessions() = send(PanelCommands.sessions())

    fun revoke(sessionIndex: Int) = send(PanelCommands.revoke(sessionIndex))

    /** The spots the radio holds; the radio pushes them as they change, so asking again is enough. */
    fun refreshSpots() = send(PanelCommands.panel(on = true))

    /** A QSO into the radio's day log, in the form its CW keyboard uses. */
    fun logQso(call: String, sent: String, received: String, name: String = "", qth: String = "") {
        val text = listOf(call, sent, received, name, qth).filter { it.isNotBlank() }.joinToString("|")
        send(PanelCommands.log(text))
    }

    /** The same with a number of ours, so a receipt lost in a drop can be asked for again. */
    fun logQsoWithId(id: Long, fields: List<String>) {
        send(PanelCommands.logq(id, fields.filter { it.isNotBlank() }.joinToString("|")))
    }

    /**
     * Ask the radio for the FT8 app waterfall, or stop asking.
     *
     * The radio uses the same client flag for `ft8 wf` and `js8 wf`. Opening
     * this screen clears the shared picture so the previous mode's rows are
     * not drawn as this one. It does not send `ft8 open` and it does not
     * transmit. The radio does not require a role for `ft8 wf`.
     */
    fun ft8Watch(on: Boolean) {
        if (on) link.clearAppWaterfall()
        send(PanelCommands.ft8("wf", if (on) "1" else "0"))
    }

    /**
     * Open, close, stop, hold, mode, or txfreq. Returns false, and sends
     * nothing, until this session may operate and holds control. A transmit
     * word is refused here, including a CQ that would turn the transmitter on.
     */
    fun ft8Operate(line: String): Boolean {
        if (!ft8LineIsOperate(line)) return false
        if (!holdingControls()) return false
        send(line)
        return true
    }

    /**
     * CQ, auto, reply, or TX Call, when [enabling] is the direction that puts
     * the transmitter to work. Turning one of those off (the radio's command
     * is the same word) still needs control, and it does not need the band
     * check: an offset that is already wrong must not trap the transmitter on.
     * Returns the reason nothing was sent, or null when the line went out.
     */
    fun ft8Transmit(line: String, enabling: Boolean): String? {
        if (!ft8LineIsTransmit(line)) return "That is not an FT8 transmit."
        if (!enabling) {
            if (!holdingControls()) return "Take control before this."
            send(line)
            return null
        }
        val reason = ft8TransmitReason()
        if (reason != null) return reason
        send(line)
        return null
    }

    fun ft8TransmitBlocked(): String? = ft8TransmitReason()

    /**
     * Ask the radio for the JS8 waterfall, or stop asking.
     *
     * This is the only JS8 line the screen sends by itself, and only because the
     * screen was opened or left. It does not open the JS8 app and it does not
     * transmit. The radio does not require a role for "js8 wf". Turning it on
     * clears the shared app waterfall first.
     */
    fun js8Watch(on: Boolean) {
        if (on) link.clearAppWaterfall()
        send(Js8Protocol.waterfall(on))
    }

    /**
     * Ask for WeFax scan lines, or stop. This does not open the app and it
     * does not transmit. The radio does not require a role for `wefax rows`.
     */
    fun wefaxWatch(on: Boolean) = send(WeFaxProtocol.rows(on))

    /**
     * Open, close, auto, cont, save, clear, plan, or unplan. None of these is
     * a transmit command in the panel set. Each still needs operate and control,
     * because the radio refuses them otherwise. Returns false when nothing was sent.
     */
    fun wefaxOperate(word: String): Boolean {
        if (word !in WeFaxProtocol.words) return false
        if (!holdingControls()) return false
        send(WeFaxProtocol.command(word))
        return true
    }

    /**
     * A JS8 command that does not transmit: open, close, hold, speed, stop,
     * select, txfreq. Returns false, and sends nothing, until this session may
     * operate and holds control. A transmit word is refused here even then, so
     * a tap that should have gone through [js8Transmit] cannot slip out.
     */
    fun js8Operate(line: String): Boolean {
        if (!js8LineIsOperate(line)) return false
        if (!holdingControls()) return false
        send(line)
        return true
    }

    /**
     * Why a transmit would not be sent, or null when it would.
     * The confirm dialog asks this before it is shown, and [js8Transmit] asks again.
     */
    fun js8TransmitBlocked(): String? = js8TransmitReason()

    /**
     * Send, CQ, auto CQ, heartbeat, heartbeat-now, or auto.
     * Returns the reason nothing was sent, or null when the line went out.
     * The caller has already required a tap, and for Send and CQ a second tap.
     * This does not turn auto or heartbeat on by itself.
     */
    fun js8Transmit(line: String): String? {
        if (!js8LineIsTransmit(line)) return "That is not a JS8 transmit."
        val reason = js8TransmitReason()
        if (reason != null) return reason
        send(line)
        return null
    }

    private fun holdingControls(): Boolean = control.value.mine && role.value.canOperate

    private fun js8TransmitReason(): String? {
        val js8 = js8Status.value
        return UsAdvanced.refuseTransmit(
            holdingControls = holdingControls(),
            txAllowed = role.value.txAllowed,
            dialHz = state.value?.freqHz?.toLong(),
            offsetHz = js8?.txFreqHz,
            widthHz = Js8Protocol.toneWidthHz(js8?.speed ?: 0),
            offsetName = "JS8 transmit offset",
        )
    }

    private fun ft8TransmitReason(): String? {
        val ft8 = ft8Status.value
        return UsAdvanced.refuseTransmit(
            holdingControls = holdingControls(),
            txAllowed = role.value.txAllowed,
            dialHz = state.value?.freqHz?.toLong(),
            offsetHz = ft8?.txFreqHz,
            widthHz = Ft8Look.toneWidthHz(ft8?.protocol ?: 1),
            offsetName = "FT8 transmit offset",
        )
    }

    private fun ft8Word(line: String): String = line.trim().removePrefix("ft8 ").substringBefore(' ')

    private fun ft8LineIsTransmit(line: String): Boolean = ft8Word(line) in FT8_TRANSMIT

    private fun ft8LineIsOperate(line: String): Boolean {
        val word = ft8Word(line)
        return word in FT8_OPERATE && word !in FT8_TRANSMIT
    }

    private fun js8Word(line: String): String = line.trim().removePrefix("js8 ").substringBefore(' ')

    private fun js8LineIsTransmit(line: String): Boolean {
        val word = js8Word(line)
        return word == "send" || word in JS8_TRANSMIT
    }

    private fun js8LineIsOperate(line: String): Boolean {
        val word = js8Word(line)
        return word in JS8_OPERATE && word !in JS8_TRANSMIT
    }

    fun qrzUsername(): String = qrzSecrets.username()

    /** Stored in the keystore. The password is not logged. */
    fun saveQrzCredentials(username: String, password: String) {
        qrzSecrets.write(username, password)
    }

    fun lookupQrz(call: String) {
        val wanted = call.trim()
        if (wanted.isEmpty()) return
        viewModelScope.launch {
            _qrzBusy.value = true
            val parsed = withContext(Dispatchers.IO) {
                qrzLookup.lookup(qrzSecrets.username(), qrzSecrets.password(), wanted)
            }
            _qrz.value = parsed
            _qrzBusy.value = false
        }
    }

    /**
     * Keep a local row and send `logq`. The radio's command has no time column, so the
     * time stays in the local row. A blank password is not part of either write.
     */
    fun saveLoggedQso(qso: Qso) {
        viewModelScope.launch(Dispatchers.IO) { qsoDb.qsos().insert(qso.toEntity()) }
        if (qso.radioLogId != 0L) send(PanelCommands.logq(qso.radioLogId, QsoBook.radioText(qso)))
    }

    fun adifExport(): String = QsoBook.adif(qsos.value)

    /** Ask the radio to send its own screen, or to stop. [picture] picks sharp, balanced or fast JPEG. */
    fun wantScreenFrames(on: Boolean, picture: PanelCommands.Picture? = null) = link.wantScreenFrames(on, picture)

    override fun onCleared() {
        discovery.stop()
        super.onCleared()
    }

    private companion object {
        /** Words that put the JS8 transmitter on the air. auto answers, so it is one of them. */
        val JS8_TRANSMIT = setOf("send", "cq", "autocq", "hbnow", "hb", "auto")

        /** Words that change the app and do not transmit. Waterfall is [js8Watch], not this set. */
        val JS8_OPERATE = setOf("open", "close", "hold", "speed", "stop", "select", "txfreq")

        /** CQ, auto (it answers), a reply, and TX Call. `ft8 mode` is not one of these. */
        val FT8_TRANSMIT = setOf("cq", "auto", "reply", "txcall")

        /** Words that change the FT8 app and do not start a transmission. */
        val FT8_OPERATE = setOf("open", "close", "stop", "hold", "mode", "txfreq")
    }
}
