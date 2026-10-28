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
import kotlinx.coroutines.launch
import radio.kd0cq.x6100.discovery.FoundRadio
import radio.kd0cq.x6100.discovery.RadioDiscovery
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
import radio.kd0cq.x6100.service.RadioConnectionService
import radio.kd0cq.x6100.service.RadioRigs

/**
 * What the screens show and do: the link's state in, the radio's commands out. The link itself is the
 * app's singleton, owned by [RadioConnectionService] so a screen leaving the screen does not drop it.
 */
class RadioViewModel(application: Application) : AndroidViewModel(application) {

    private val link = RadioLinks.link(application)
    private val rig = RadioRigs.rig(application)
    private val discovery = RadioDiscovery(application)

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
    val cwLines: StateFlow<List<String>> = link.cwLines
    val receipts: StateFlow<List<LoggedReceipt>> = link.receipts
    val sessions: StateFlow<List<SessionInfo>> = link.sessions
    val spectrum: StateFlow<SpectrumFrame?> = link.spectrum
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

    /** The service owns the sockets, so a screen leaving does not drop the link. */
    fun connectTo(radio: FoundRadio) {
        val endpoint = radio.endpoint()
        _endpoint.value = endpoint
        RadioConnectionService.connect(getApplication(), endpoint)
    }

    /** Reach a radio the resolver does not know, by address. */
    fun connectToHost(host: String, panelPort: Int = PANEL_PORT, tciPort: Int = TCI_PORT) {
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

    fun setRfGain(value: Int) = send(PanelCommands.set(PanelSettings.RFG, value))

    fun setSquelch(value: Int) = send(PanelCommands.set(PanelSettings.SQL, value))

    fun toggle(setting: String, on: Boolean) =
        send(PanelCommands.set(setting, if (on) 1 else 0))

    /** Turn the main tuning knob by N detents. */
    fun tuneBy(detents: Int) = send(PanelCommands.knob("main", detents))

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

    /** The FT8 app's switches, one command each. */
    fun ft8(what: String, arg: String? = null) = send(PanelCommands.ft8(what, arg))

    /** Ask the radio to send its own screen, or to stop. */
    fun wantScreenFrames(on: Boolean) = link.wantScreenFrames(on)

    override fun onCleared() {
        discovery.stop()
        super.onCleared()
    }
}
