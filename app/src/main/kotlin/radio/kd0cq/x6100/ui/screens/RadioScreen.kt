// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The operating screen. Landscape follows the radio's 800×480 main page: status, frequency,
// spectrum, scale, waterfall, F1–F5. Portrait follows the walkie and the phone web panel:
// a side rail, a large frequency, the same spectrum stack, three sliders, and the key.
//
// Every `set` the Panel protocol already accepts is reachable from here. A control the
// protocol does not carry is still on its firmware page, and tapping it says so instead of
// inventing a wire command.

package radio.kd0cq.x6100.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.kd0cq.x6100.net.Band
import radio.kd0cq.x6100.net.PanelCommands
import radio.kd0cq.x6100.net.PanelMode
import radio.kd0cq.x6100.net.PanelSettings
import radio.kd0cq.x6100.net.RadioState
import radio.kd0cq.x6100.ui.LocalWideWindow
import radio.kd0cq.x6100.ui.RadioViewModel
import radio.kd0cq.x6100.ui.face.CompactSlider
import radio.kd0cq.x6100.ui.face.FaceCommand
import radio.kd0cq.x6100.ui.face.FaceGroup
import radio.kd0cq.x6100.ui.face.FaceKey
import radio.kd0cq.x6100.ui.face.FreqGroup
import radio.kd0cq.x6100.ui.face.FrequencyReadout
import radio.kd0cq.x6100.ui.face.GroupStrip
import radio.kd0cq.x6100.ui.face.HoldToTalk
import radio.kd0cq.x6100.ui.face.MicSwitch
import radio.kd0cq.x6100.ui.face.NeedleMeter
import radio.kd0cq.x6100.ui.face.PENDING_FIRMWARE
import radio.kd0cq.x6100.ui.face.Pill
import radio.kd0cq.x6100.ui.face.RadioFace
import radio.kd0cq.x6100.ui.face.SoftkeyBar
import radio.kd0cq.x6100.ui.face.SpectrumStack
import radio.kd0cq.x6100.ui.face.StatusIconRow
import radio.kd0cq.x6100.ui.face.ClockBlock
import radio.kd0cq.x6100.ui.face.UtcClock
import radio.kd0cq.x6100.ui.theme.RfGuru

@Composable
fun RadioScreen(viewModel: RadioViewModel, onNavigate: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    val meter by viewModel.meter.collectAsState()
    val tx by viewModel.txMeters.collectAsState()
    val bands by viewModel.bands.collectAsState()
    val plan by viewModel.plan.collectAsState()
    val spots by viewModel.spots.collectAsState()
    // The spectrum is collected inside the scope pane. Collecting it here recomposed the keys,
    // the header and PTT on every FFT frame. The CW decoder text is not collected here either:
    // painting it on the scope covered the waterfall. RadioLink still keeps the lines.
    val role by viewModel.role.collectAsState()
    val control by viewModel.control.collectAsState()
    val keyed by viewModel.pttKeyed.collectAsState()
    val refusal by viewModel.pttRefusal.collectAsState()
    val audioProblem by viewModel.audioProblem.collectAsState()
    val volumeMax by viewModel.volumeMax.collectAsState()
    val modeCount by viewModel.modes.collectAsState()
    val audio by viewModel.audioRunning.collectAsState()

    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    var freqGroupOrdinal by rememberSaveable { mutableIntStateOf(FreqGroup.KHZ.ordinal) }
    val freqGroup = FreqGroup.entries[freqGroupOrdinal.coerceIn(0, FreqGroup.entries.lastIndex)]
    var pending by remember { mutableStateOf<String?>(null) }
    var pickingModes by remember { mutableStateOf(false) }
    var pickingBands by remember { mutableStateOf(false) }
    var micRefused by remember { mutableStateOf(false) }

    val canOperate = role.canOperate
    val mayKey = canOperate && role.txAllowed
    val page = RadioFace.page(pageIndex)
    val radio = state

    fun sendIfAllowed(block: () -> Unit) {
        if (canOperate) block()
    }

    fun onKey(key: FaceKey, direction: Int) {
        when (val command = RadioFace.commandFor(key, radio, volumeMax, direction)) {
            FaceCommand.NextPage -> pageIndex = RadioFace.nextPageIndex(pageIndex)
            FaceCommand.PreviousPage -> pageIndex = RadioFace.previousPageIndex(pageIndex)
            is FaceCommand.SetInt -> sendIfAllowed { viewModel.send(PanelCommands.set(command.setting, command.value)) }
            is FaceCommand.SetFilter -> sendIfAllowed { viewModel.setFilter(command.lowHz, command.highHz) }
            FaceCommand.PickMode -> pickingModes = true
            FaceCommand.PickBand -> pickingBands = true
            is FaceCommand.Navigate -> onNavigate(command.route)
            is FaceCommand.Pending -> pending = command.feature
        }
    }

    // The shell measured the window and published it. Measuring this pane again disagreed
    // while a rotation was still changing the size: the landscape deck was squeezed under
    // the band plan, and the portrait bottom bar and PTT were not composed yet.
    val landscape = LocalWideWindow.current
    Box(Modifier.fillMaxSize().background(RfGuru.bg)) {
        if (landscape) {
            // Phone landscape is only ~360–412 dp tall. PTT sits beside the keys, and the
            // header is one band, so the scope can keep most of that height for the waterfall.
            LandscapeOperating(
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp),
                viewModel = viewModel,
                state = radio,
                meter = meter,
                tx = tx,
                bands = bands,
                plan = plan,
                spots = spots,
                canOperate = canOperate,
                mayKey = mayKey,
                controlMine = control.mine,
                controlFree = control.free,
                controlTci = control.isTci,
                keyed = keyed,
                audio = audio,
                micRefused = micRefused,
                onMicRefused = { micRefused = it },
                note = refusal ?: audioProblem,
                freqGroup = freqGroup,
                page = page,
                onFreqGroup = { freqGroupOrdinal = it.ordinal },
                onKey = ::onKey,
                onGroup = { pageIndex = RadioFace.groupStartIndex(it) },
                onPlaceholder = { pending = it },
                onPair = { onNavigate("pairing") },
            )
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) {
                PortraitOperating(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    viewModel = viewModel,
                    state = radio,
                    meter = meter,
                    tx = tx,
                    bands = bands,
                    plan = plan,
                    spots = spots,
                    canOperate = canOperate,
                    volumeMax = volumeMax,
                    controlMine = control.mine,
                    controlFree = control.free,
                    controlTci = control.isTci,
                    keyed = keyed,
                    audio = audio,
                    freqGroup = freqGroup,
                    page = page,
                    onFreqGroup = { freqGroupOrdinal = it.ordinal },
                    onKey = ::onKey,
                    onGroup = { pageIndex = RadioFace.groupStartIndex(it) },
                    onPlaceholder = { pending = it },
                    onPair = { onNavigate("pairing") },
                )
                TransmitRow(
                    viewModel = viewModel,
                    mayKey = mayKey,
                    canOperate = canOperate,
                    keyed = keyed,
                    micRefused = micRefused,
                    onMicRefused = { micRefused = it },
                    note = refusal ?: audioProblem,
                )
            }
        }
    }

    pending?.let { feature ->
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = RfGuru.bar,
            titleContentColor = RfGuru.gold,
            textContentColor = RfGuru.text,
            title = { Text(feature) },
            text = { Text(PENDING_FIRMWARE) },
            confirmButton = {
                TextButton(onClick = { pending = null }) { Text("Close", color = RfGuru.gold) }
            },
        )
    }
    if (pickingModes) {
        ModeDialog(
            modeCount = modeCount,
            current = radio?.mode,
            canOperate = canOperate,
            onMode = { index ->
                sendIfAllowed { viewModel.setMode(index) }
                pickingModes = false
            },
            onFt8 = {
                pickingModes = false
                onNavigate("ft8")
            },
            onJs8 = {
                pickingModes = false
                onNavigate("js8")
            },
            onCw = {
                pickingModes = false
                onNavigate("cw")
            },
            onWeFax = {
                pickingModes = false
                onNavigate("wefax")
            },
            onPending = { pending = it },
            onDismiss = { pickingModes = false },
        )
    }
    if (pickingBands) {
        BandDialog(
            bands = bands,
            canOperate = canOperate,
            onTune = { hz ->
                sendIfAllowed { viewModel.setFrequency(hz) }
                pickingBands = false
            },
            onDismiss = { pickingBands = false },
        )
    }
}

@Composable
private fun LandscapeOperating(
    modifier: Modifier,
    viewModel: RadioViewModel,
    state: RadioState?,
    meter: Double,
    tx: radio.kd0cq.x6100.net.TxMeters,
    bands: List<Band>,
    plan: List<radio.kd0cq.x6100.net.PlanSegment>,
    spots: List<radio.kd0cq.x6100.net.Spot>,
    canOperate: Boolean,
    mayKey: Boolean,
    controlMine: Boolean,
    controlFree: Boolean,
    controlTci: Boolean,
    keyed: Boolean,
    audio: Boolean,
    micRefused: Boolean,
    onMicRefused: (Boolean) -> Unit,
    note: String?,
    freqGroup: FreqGroup,
    page: radio.kd0cq.x6100.ui.face.FacePage,
    onFreqGroup: (FreqGroup) -> Unit,
    onKey: (FaceKey, Int) -> Unit,
    onGroup: (FaceGroup) -> Unit,
    onPlaceholder: (String) -> Unit,
    onPair: () -> Unit,
) {
    // Two stacked full-width rows (status, then the big frequency) plus a 52 dp PTT bar left
    // the waterfall a few dozen pixels tall. One header and one control row give that height
    // back. The scope is the only weighted child. On the S24 that weight was not the height
    // the stack painted: the waterfall stopped, and a dark band sat above "Take control".
    // Measuring the leftover and giving the stack that exact height puts the waterfall on
    // the control row. The trace keeps about a third of it.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LandscapeHeader(
            state, meter, tx, bands, freqGroup, canOperate, audio, viewModel,
            onFreqGroup, onPlaceholder, onPair,
        )
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            SpectrumStack(
                spectrum = viewModel.spectrum,
                state = state,
                plan = plan,
                spots = spots,
                canOperate = canOperate,
                onTuneHz = { hz -> if (canOperate) viewModel.setFrequency(hz) },
                onKnob = { steps -> if (canOperate) viewModel.tuneBy(steps) },
                onZoom = { narrower ->
                    if (canOperate) viewModel.setZoom(RadioFace.nextZoom(state?.zoom ?: 1, narrower))
                },
                // 0.68 leaves the trace 32% of the pane, inside the 30–35% the radio's
                // proportions ask for. The scale and the plan come out of the waterfall.
                waterfallShare = 0.68f,
                ridgeHold = viewModel.ridgeHold,
                waterfallHold = viewModel.waterfallHold,
                modifier = Modifier.height(maxHeight).fillMaxWidth(),
            )
        }
        ControlHint(controlFree, controlMine, controlTci, keyed, state?.transmitting == true, canOperate, viewModel)
        LandscapeDeck(
            viewModel, page, state, mayKey, canOperate, keyed, micRefused, onMicRefused, note, onKey, onGroup,
        )
    }
}

@Composable
private fun LandscapeHeader(
    state: RadioState?,
    meter: Double,
    tx: radio.kd0cq.x6100.net.TxMeters,
    bands: List<Band>,
    freqGroup: FreqGroup,
    canOperate: Boolean,
    audio: Boolean,
    viewModel: RadioViewModel,
    onFreqGroup: (FreqGroup) -> Unit,
    onPlaceholder: (String) -> Unit,
    onPair: () -> Unit,
) {
    val freq = state?.freqHz ?: 0
    val stationGrid by viewModel.stationGrid.collectAsState()
    Row(
        Modifier.fillMaxWidth().height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoPills(state, canOperate, audio, viewModel, onPlaceholder, onPair, Modifier.weight(1f))
                StatusIconRow(onPlaceholder)
                val battery = state?.battery
                val percent = RadioFace.batteryOnFace(battery)
                if (percent != null) {
                    Text(
                        "$percent%",
                        color = RfGuru.goldText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                } else if (battery == null) {
                    Text(
                        "—%",
                        color = RfGuru.goldText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                UtcClock(fontSize = 11.sp)
                if (stationGrid.isNotBlank()) {
                    Text(stationGrid, color = RfGuru.goldText, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill(RadioFace.bandLabel(freq, bands), on = state != null)
                FrequencyReadout(
                    freqHz = freq,
                    group = freqGroup,
                    onGroup = onFreqGroup,
                    onBump = { dir ->
                        if (canOperate) viewModel.setFrequency(RadioFace.bumpFrequency(freq, freqGroup, dir))
                    },
                    onTune = { hz -> if (canOperate) viewModel.setFrequency(hz) },
                    large = false,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    state?.let { RadioFace.modeLabel(it.mode) } ?: "—",
                    color = RfGuru.gold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                FlagPills(state, canOperate, viewModel, onPlaceholder)
            }
        }
        NeedleMeter(
            db = meter,
            transmitting = state?.transmitting == true,
            tx = tx,
            modifier = Modifier.width(168.dp).fillMaxHeight(),
        )
    }
}

@Composable
private fun LandscapeDeck(
    viewModel: RadioViewModel,
    page: radio.kd0cq.x6100.ui.face.FacePage,
    state: RadioState?,
    mayKey: Boolean,
    canOperate: Boolean,
    keyed: Boolean,
    micRefused: Boolean,
    onMicRefused: (Boolean) -> Unit,
    note: String?,
    onKey: (FaceKey, Int) -> Unit,
    onGroup: (FaceGroup) -> Unit,
) {
    // Same permission rule as the portrait TransmitRow: a refused microphone leaves the
    // radio's own mic selected, and the switch itself stays on canOperate, not on mayKey.
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onMicRefused(!granted)
        if (granted) viewModel.transmitAudioFromPhone = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // All six group names share this slot equally. A narrower scrolling strip clipped
            // DFL and hid MEM. The keys keep the larger share so "TX pwr" and its value fit.
            GroupStrip(page.group, onGroup, Modifier.weight(1.15f), distribute = true)
            SoftkeyBar(
                page,
                state,
                onKey,
                Modifier.weight(1.55f),
                barHeight = 52.dp,
                compact = true,
            )
            HoldToTalk(
                keyed = keyed,
                mayKey = mayKey,
                onPress = { viewModel.pressPtt() },
                onRelease = { viewModel.releasePtt() },
                modifier = Modifier.width(72.dp).height(52.dp),
            )
            MicSwitch(
                checked = viewModel.transmitAudioFromPhone,
                enabled = canOperate && !keyed,
                compact = true,
                onChecked = { want ->
                    if (want && !viewModel.hasMicrophonePermission()) {
                        askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.transmitAudioFromPhone = want
                    }
                },
            )
        }
        if (micRefused) {
            Text(
                "No microphone permission. The key still uses the microphone at the radio.",
                color = RfGuru.red,
                fontSize = 11.sp,
            )
        }
        note?.let { Text(it, color = RfGuru.red, fontSize = 11.sp) }
    }
}

@Composable
private fun PortraitOperating(
    modifier: Modifier,
    viewModel: RadioViewModel,
    state: RadioState?,
    meter: Double,
    tx: radio.kd0cq.x6100.net.TxMeters,
    bands: List<Band>,
    plan: List<radio.kd0cq.x6100.net.PlanSegment>,
    spots: List<radio.kd0cq.x6100.net.Spot>,
    canOperate: Boolean,
    volumeMax: Int,
    controlMine: Boolean,
    controlFree: Boolean,
    controlTci: Boolean,
    keyed: Boolean,
    audio: Boolean,
    freqGroup: FreqGroup,
    page: radio.kd0cq.x6100.ui.face.FacePage,
    onFreqGroup: (FreqGroup) -> Unit,
    onKey: (FaceKey, Int) -> Unit,
    onGroup: (FaceGroup) -> Unit,
    onPlaceholder: (String) -> Unit,
    onPair: () -> Unit,
) {
    // No scroll. A fixed 300 dp scope plus the sliders pushed F1–F5 under the PTT row, so the
    // keys showed as a sliver. The scope takes the space that is left after the keys, and the
    // keys stay on screen on a 1080×2400 phone.
    val stationGrid by viewModel.stationGrid.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            InfoPills(state, canOperate, audio, viewModel, onPlaceholder, onPair, Modifier.weight(1f))
            ClockBlock(state?.battery, onPlaceholder, stationGrid = stationGrid)
        }
        StatusIconRow(onPlaceholder)
        // This is the space above the needle meter. Measuring it, then giving the row that
        // exact height, is what makes the scope grow into it. A wrap-content row left the
        // band plan at the bottom of a short strip and the rest of this space empty, in
        // both 2D and 3D. The frequency strip keeps its own height. The scope takes the rest,
        // so spectrum-only ends on the band plan the way the landscape scope already does.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            Row(
                Modifier.height(maxHeight).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PortraitRail(state, page, canOperate, viewModel, onPage = {
                    val pageKey = page.keys.firstOrNull { it.action is radio.kd0cq.x6100.ui.face.FaceAction.NextPage }
                    if (pageKey != null) onKey(pageKey, +1) else onGroup(page.group)
                })
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FreqStrip(state, bands, freqGroup, canOperate, viewModel, onFreqGroup, onPlaceholder, large = false)
                    SpectrumStack(
                        spectrum = viewModel.spectrum,
                        state = state,
                        plan = plan,
                        spots = spots,
                        canOperate = canOperate,
                        onTuneHz = { hz -> if (canOperate) viewModel.setFrequency(hz) },
                        onKnob = { steps -> if (canOperate) viewModel.tuneBy(steps) },
                        onZoom = { narrower ->
                            if (canOperate) viewModel.setZoom(RadioFace.nextZoom(state?.zoom ?: 1, narrower))
                        },
                        ridgeHold = viewModel.ridgeHold,
                        waterfallHold = viewModel.waterfallHold,
                        modifier = Modifier.fillMaxWidth().weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
        // Step stays on the frequency strip, next to LOCK. A second full-width "step 500" sat
        // between the scope and the meter and pushed the softkeys off the screen.
        NeedleMeter(meter, state?.transmitting == true, tx, Modifier.fillMaxWidth().height(72.dp))
        val volMax = volumeMax.coerceAtLeast(1).toFloat()
        CompactSlider("VOL", "${state?.volume ?: 0}", (state?.volume ?: 0).toFloat(), 0f..volMax, canOperate) {
            viewModel.setVolume(it)
        }
        CompactSlider("SQL", "${state?.squelch ?: 0}", (state?.squelch ?: 0).toFloat(), 0f..100f, canOperate) {
            viewModel.setSquelch(it)
        }
        CompactSlider("PWR", RadioFace.formatWatts(state?.powerWatts ?: 0.0), (state?.powerTenths ?: 0).toFloat(), 0f..100f, canOperate) {
            viewModel.setPower(it)
        }
        ControlHint(controlFree, controlMine, controlTci, keyed, state?.transmitting == true, canOperate, viewModel)
        GroupStrip(page.group, onGroup)
        SoftkeyBar(page, state, onKey, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PortraitRail(
    state: RadioState?,
    page: radio.kd0cq.x6100.ui.face.FacePage,
    canOperate: Boolean,
    viewModel: RadioViewModel,
    onPage: () -> Unit,
) {
    Column(
        Modifier.width(72.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RailButton(page.badge.replace(' ', '\n')) { onPage() }
        RailButton(RadioFace.vfoLabel(state?.vfo ?: 0).replace('-', '\n')) {
            if (canOperate) viewModel.toggleVfo()
        }
        RailButton("Band\n+") { if (canOperate) viewModel.setBand(1) }
        RailButton("Band\n−") { if (canOperate) viewModel.setBand(-1) }
        Text(
            RadioFace.formatWatts(state?.powerWatts ?: 0.0),
            color = RfGuru.goldText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RailButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = text,
        color = RfGuru.text,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = 13.sp,
        modifier = modifier
            .fillMaxWidth()
            .background(RfGuru.button, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

@Composable
private fun InfoPills(
    state: RadioState?,
    canOperate: Boolean,
    audio: Boolean,
    viewModel: RadioViewModel,
    onPlaceholder: (String) -> Unit,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modeCount by viewModel.modes.collectAsState()
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(
            if (state == null) "VFO" else RadioFace.vfoLabel(state.vfo),
            on = true,
            onClick = { if (canOperate) viewModel.toggleVfo() },
        )
        Pill(
            state?.let { RadioFace.modeLabel(it.mode) } ?: "—",
            on = true,
            onClick = { if (canOperate && state != null) viewModel.setMode(RadioFace.nextMode(state.mode, modeCount)) },
        )
        Pill(
            state?.let { RadioFace.agcLabel(it.agc) } ?: "AGC",
            on = state != null,
            onClick = { if (canOperate) viewModel.setAgc(RadioFace.nextAgc(state?.agc ?: 0)) },
        )
        Pill("PRE", on = state?.preOn == true, onClick = { if (canOperate) viewModel.toggle(PanelSettings.PRE, state?.preOn != true) })
        Pill("ATT", on = state?.attenuatorOn == true, onClick = { if (canOperate) viewModel.toggle(PanelSettings.ATT, state?.attenuatorOn != true) })
        Pill("ATU", on = state?.atuOn == true, onClick = { if (canOperate) viewModel.toggle(PanelSettings.ATU, state?.atuOn != true) })
        Pill("SPL", on = false, placeholder = true, onClick = { onPlaceholder("Split / TX shift") })
        if (!canOperate) {
            Pill("Pair", on = true, onClick = onPair)
        }
        if (audio) {
            Text("RX", color = RfGuru.ok, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun FreqStrip(
    state: RadioState?,
    bands: List<Band>,
    freqGroup: FreqGroup,
    canOperate: Boolean,
    viewModel: RadioViewModel,
    onFreqGroup: (FreqGroup) -> Unit,
    onPlaceholder: (String) -> Unit,
    large: Boolean = true,
) {
    val freq = state?.freqHz ?: 0
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(RfGuru.bar, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(RadioFace.bandLabel(freq, bands), on = state != null)
            FrequencyReadout(
                freqHz = freq,
                group = freqGroup,
                onGroup = onFreqGroup,
                onBump = { dir ->
                    if (canOperate) viewModel.setFrequency(RadioFace.bumpFrequency(freq, freqGroup, dir))
                },
                onTune = { hz -> if (canOperate) viewModel.setFrequency(hz) },
                large = large,
                modifier = Modifier.weight(1f),
            )
            Text(
                state?.let { RadioFace.modeLabel(it.mode) } ?: "—",
                color = RfGuru.gold,
                fontFamily = FontFamily.Monospace,
                fontSize = if (large) 16.sp else 13.sp,
            )
            if (large) FlagPills(state, canOperate, viewModel, onPlaceholder)
        }
        if (!large) FlagPills(state, canOperate, viewModel, onPlaceholder)
    }
}

@Composable
private fun FlagPills(
    state: RadioState?,
    canOperate: Boolean,
    viewModel: RadioViewModel,
    onPlaceholder: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("NR", on = state?.noiseReduceOn == true, onClick = {
            if (canOperate) viewModel.toggle(PanelSettings.NR, state?.noiseReduceOn != true)
        })
        Pill("NB", on = state?.noiseBlankerOn == true, onClick = {
            if (canOperate) viewModel.toggle(PanelSettings.NB, state?.noiseBlankerOn != true)
        })
        Pill("DNF", on = false, placeholder = true, onClick = { onPlaceholder("DNF") })
        Pill(
            "LOCK",
            on = state?.freqLocked == true,
            onClick = { if (canOperate) viewModel.toggle(PanelSettings.LOCK, state?.freqLocked != true) },
        )
        Text(
            RadioFace.stepLabel(state?.stepIndex ?: 0),
            color = RfGuru.dim,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable {
                if (canOperate) viewModel.setStep(RadioFace.nextStepIndex(state?.stepIndex ?: 0))
            },
        )
    }
}

@Composable
private fun ControlHint(
    free: Boolean,
    mine: Boolean,
    tci: Boolean,
    keyed: Boolean,
    transmitting: Boolean,
    canOperate: Boolean,
    viewModel: RadioViewModel,
) {
    // Taking or dropping control while the carrier is up is how a second client unkeys someone.
    // The button stays hidden until the key is up. `ctl tci` is this phone's own microphone path.
    if (keyed || transmitting) return
    if (!free && !mine && !tci) {
        TextButton(onClick = { viewModel.takeControl() }, enabled = canOperate) {
            Text("Take control", color = RfGuru.gold)
        }
    } else if (mine) {
        TextButton(onClick = { viewModel.releaseControl() }) {
            Text("Give control back", color = RfGuru.dim)
        }
    }
}

@Composable
private fun TransmitRow(
    viewModel: RadioViewModel,
    mayKey: Boolean,
    canOperate: Boolean,
    keyed: Boolean,
    micRefused: Boolean,
    onMicRefused: (Boolean) -> Unit,
    note: String?,
    pttHeight: androidx.compose.ui.unit.Dp = 64.dp,
) {
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onMicRefused(!granted)
        if (granted) viewModel.transmitAudioFromPhone = true
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldToTalk(
                keyed = keyed,
                mayKey = mayKey,
                onPress = { viewModel.pressPtt() },
                onRelease = { viewModel.releasePtt() },
                modifier = Modifier.weight(1f).height(pttHeight),
            )
            MicSwitch(
                checked = viewModel.transmitAudioFromPhone,
                enabled = canOperate && !keyed,
                onChecked = { want ->
                    if (want && !viewModel.hasMicrophonePermission()) {
                        askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.transmitAudioFromPhone = want
                    }
                },
            )
        }
        if (micRefused) {
            Text(
                "No microphone permission. The key still uses the microphone at the radio.",
                color = RfGuru.red,
                fontSize = 11.sp,
            )
        }
        note?.let { Text(it, color = RfGuru.red, fontSize = 11.sp) }
    }
}

@Composable
private fun ModeDialog(
    modeCount: Int,
    current: Int?,
    canOperate: Boolean,
    onMode: (Int) -> Unit,
    onFt8: () -> Unit,
    onJs8: () -> Unit,
    onCw: () -> Unit,
    onWeFax: () -> Unit,
    onPending: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RfGuru.bar,
        titleContentColor = RfGuru.gold,
        textContentColor = RfGuru.text,
        title = { Text("Modes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PanelMode.entries.take(modeCount.coerceAtLeast(1)).forEach { mode ->
                    TextButton(onClick = { onMode(mode.index) }, enabled = canOperate) {
                        Text(
                            if (current == mode.index) "${mode.label}  ●" else mode.label,
                            color = if (current == mode.index) RfGuru.gold else RfGuru.text,
                        )
                    }
                }
                TextButton(onClick = onFt8) { Text("FT8 / FT4", color = RfGuru.goldText) }
                TextButton(onClick = onJs8) { Text("JS8 / GhostNet", color = RfGuru.goldText) }
                TextButton(onClick = onCw) { Text("CW decoder", color = RfGuru.goldText) }
                TextButton(onClick = onWeFax) { Text("WeFax", color = RfGuru.goldText) }
                Text("Not on the Panel link yet", color = RfGuru.mute, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                RadioFace.pendingDigitalModes.forEach { name ->
                    TextButton(onClick = { onPending(name) }) { Text(name, color = RfGuru.mute) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = RfGuru.gold) } },
    )
}

@Composable
private fun BandDialog(
    bands: List<Band>,
    canOperate: Boolean,
    onTune: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RfGuru.bar,
        titleContentColor = RfGuru.gold,
        textContentColor = RfGuru.text,
        title = { Text("Bands") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (bands.isEmpty()) {
                    Text("The radio has not sent a band list yet.", color = RfGuru.dim)
                }
                bands.forEach { band ->
                    val middle = ((band.startHz.toLong() + band.stopHz.toLong()) / 2L).toInt()
                    TextButton(onClick = { onTune(middle) }, enabled = canOperate) {
                        Text("${band.name}  ${RadioFace.formatFrequency(middle)}", color = RfGuru.text)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = RfGuru.gold) } },
    )
}
