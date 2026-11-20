// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The radio face is data: these tests pin the formatting, the S-meter scale from smeter.h,
// the band-plan colours from theme.h, the F1–F5 page order from button_bar.cpp, and the rule
// that a control the Panel protocol does not carry is named as pending instead of sent.

package radio.kd0cq.x6100.ui.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import radio.kd0cq.x6100.net.Band
import radio.kd0cq.x6100.net.PanelSettings
import radio.kd0cq.x6100.net.PlanSegment
import radio.kd0cq.x6100.net.RadioState
import radio.kd0cq.x6100.net.Spot
import radio.kd0cq.x6100.net.TxMeters

class RadioFaceTest {

    @Test
    fun frequencyReadsLikeTheRadio() {
        assertEquals("14.074.000", RadioFace.formatFrequency(14_074_000))
        assertEquals("7.074.000", RadioFace.formatFrequency(7_074_000))
        assertEquals("144.300.000", RadioFace.formatFrequency(144_300_000))
        assertEquals("0.500.000", RadioFace.formatFrequency(500_000))
        assertEquals(Triple("14", "074", "000"), RadioFace.frequencyParts(14_074_000))
    }

    @Test
    fun bandNamePrefersTheRadiosList() {
        val named = listOf(Band("20m CW", 14_000_000, 14_070_000), Band("20m", 14_000_000, 14_350_000))
        assertEquals("20m CW", RadioFace.bandLabel(14_050_000, named))
        assertEquals("20m", RadioFace.bandLabel(14_074_000, named))
        assertEquals("20m", RadioFace.bandLabel(14_074_000, emptyList()))
        assertEquals("—", RadioFace.bandLabel(1_000, emptyList()))
    }

    @Test
    fun sMeterUsesTheFirmwareScale() {
        val s7 = RadioFace.sMeter(-87.0)
        assertEquals("S7", s7.caption)
        assertTrue(s7.needle in 0.35f..0.42f)
        assertEquals("S9", RadioFace.sMeter(RadioFace.S9_DB).caption)
        assertEquals("+20", RadioFace.sMeter(-53.0).caption)
        assertEquals(1f, RadioFace.sMeter(RadioFace.S9_40_DB).needle, 0.001f)
        assertEquals(0f, RadioFace.sMeter(RadioFace.S1_DB).needle, 0.001f)
        assertEquals("S1", RadioFace.sMeter(-140.0).caption)
    }

    @Test
    fun txMetersFillAgainstADisplayScale() {
        val view = RadioFace.txMeter(TxMeters(5.0, 1.5, 40.0))
        assertEquals("5.0 W", view.wattsText)
        assertEquals(0.5f, view.watts, 0.001f)
        assertEquals(0.25f, view.swr, 0.001f)
        assertEquals(0.4f, view.alc, 0.001f)
        assertEquals(1f, RadioFace.txMeter(TxMeters(25.0, 9.0, 200.0)).watts, 0.001f)
    }

    @Test
    fun tuningAndZoomStayInsideTheStepsTheRadioAlreadyUses() {
        assertEquals(14_074_100, RadioFace.steppedFrequency(14_074_000, 1, +1))
        assertEquals(14_073_900, RadioFace.steppedFrequency(14_074_000, 1, -1))
        assertEquals(15_074_000, RadioFace.bumpFrequency(14_074_000, FreqGroup.MHZ, +1))
        assertEquals(14_075_000, RadioFace.bumpFrequency(14_074_000, FreqGroup.KHZ, +1))
        assertEquals(14_074_010, RadioFace.bumpFrequency(14_074_000, FreqGroup.HZ, +1))
        assertEquals(0, RadioFace.bumpFrequency(0, FreqGroup.MHZ, -1))
        assertEquals(2, RadioFace.nextZoom(1, narrower = true))
        assertEquals(1, RadioFace.nextZoom(1, narrower = false))
        assertEquals(8, RadioFace.nextZoom(8, narrower = true))
        assertEquals(4, RadioFace.nextZoom(8, narrower = false))
        assertEquals(2, RadioFace.nextStepIndex(1))
        assertEquals(0, RadioFace.nextStepIndex(RadioFace.stepHzTable.lastIndex))
        assertEquals("FAST", RadioFace.agcLabel(2))
        assertEquals(0, RadioFace.nextAgc(3))
        assertEquals("VFO-A", RadioFace.vfoLabel(0))
        assertEquals("VFO-B", RadioFace.vfoLabel(1))
    }

    @Test
    fun spanHitTestingReachesEverySpotNotOnlyTheNewest() {
        val span = RadioFace.spanOf(14_074_000, 100_000)
        assertEquals(14_024_000, span.leftHz)
        assertEquals(14_124_000, span.rightHz)
        assertEquals(14_074_000, RadioFace.hzAtFraction(0.5f, span))
        val older = spot(14_080_000, "OLDER")
        val newer = spot(14_080_400, "NEWER")
        val far = spot(14_100_000, "FAR")
        val hit = RadioFace.nearestSpot(14_080_200, listOf(newer, older, far), RadioFace.slopHz(span))
        assertEquals("NEWER", hit?.call)
        assertEquals("FAR", RadioFace.nearestSpot(14_100_000, listOf(newer, older, far), 500)?.call)
        assertNull(RadioFace.nearestSpot(14_050_000, listOf(newer), 400))
        assertEquals(newer, RadioFace.nearestSpot(14_080_200, listOf(newer, older), 400))
    }

    @Test
    fun waterfallKeepsTheNewestFrameAtTheTopAndDropsTheOldest() {
        val history = SpectrumHistory(2)
        history.push(byteArrayOf(1), 1, 10)
        history.push(byteArrayOf(2), 1, 10)
        history.push(byteArrayOf(3), 1, 10)
        val rows = history.snapshot()
        assertEquals(2, rows.size)
        assertEquals(3, rows[0].db[0].toInt())
        assertEquals(2, rows[1].db[0].toInt())
        assertEquals(0xFF000814.toInt(), RadioFace.waterfallArgb(-128))
        assertEquals(0xFFFFFFFF.toInt(), RadioFace.waterfallArgb(0))
        assertTrue((RadioFace.waterfallArgb(-40) ushr 16 and 0xFF) > 0x20)
    }

    @Test
    fun waterfallWindowLiftsNoiseOffTheBlackStop() {
        // A quiet 20 m band: almost every bin near −108, one carrier at −70. The full −128..0
        // scale paints both in the same navy. The window has to open at least the minimum span,
        // and the noise sample has to leave the floor colour.
        val row = ByteArray(200) { (-108).toByte() }
        row[0] = (-100).toByte()
        row[1] = (-96).toByte()
        row[2] = (-70).toByte()
        val (floor, ceiling) = RadioFace.waterfallWindow(listOf(row))
        assertTrue(ceiling - floor >= RadioFace.WATERFALL_MIN_SPAN_DB)
        val noise = RadioFace.waterfallArgb(-104, floor, ceiling)
        val signal = RadioFace.waterfallArgb(-70, floor, ceiling)
        assertTrue(noise != signal)
        assertTrue(noise != RadioFace.waterfallArgb(floor, floor, ceiling))
    }

    @Test
    fun landscapeScopeGivesTheWaterfallAboutThreeQuarters() {
        // 240 px stands in for a phone-landscape scope after the header and the key row.
        // The waterfall has to be about three quarters of that pane, and the trace still
        // tall enough that the Zoom chip sits above the frequency ticks.
        val split = RadioFace.scopeSplit(heightPx = 240f, scalePx = 16f, planPx = 14f, waterfallShare = 0.73f, zoomGuardPx = 24f)
        val share = split.waterfallPx / 240f
        assertTrue(share in 0.68f..0.76f)
        assertTrue(split.spectrumPx >= 24f)
        val portrait = RadioFace.scopeSplit(300f, 16f, 14f, waterfallShare = 0f, zoomGuardPx = 24f)
        assertEquals(108f, portrait.spectrumPx, 0.5f)
    }

    @Test
    fun keyNamesPackOntoRowsThatFitTheWidth() {
        val names = listOf("GEN", "KEY", "DFN", "BAND_DOWN", "BAND_UP", "APP", "MSG")
        val rows = RadioFace.packKeyRows(names, widthDp = 180f)
        assertTrue(rows.size >= 2)
        assertTrue(rows.flatten() == names)
        rows.forEach { row ->
            val width = row.sumOf { it.length * 7.5 + 26.0 }
            assertTrue(width <= 180.0 + 26.0) // one chip may be wider than the view on its own
        }
        assertTrue(rows.flatten().contains("MSG"))
        assertTrue(rows.all { line -> line.all { !it.endsWith("MS") || it == "MSG" } })
    }

    @Test
    fun scaleLabelsStayInsideTheViewAndDoNotStack() {
        assertEquals("14.060", RadioFace.scaleLabel(14_060_000))
        val lefts = RadioFace.placeScaleLabels(
            centersPx = listOf(0f, 30f, 200f),
            widthsPx = listOf(40f, 40f, 40f),
            viewWidthPx = 220f,
            gapPx = 4f,
        )
        assertEquals(0f, lefts[0])
        assertNull(lefts[1])
        assertNotNull(lefts[2])
        assertTrue(lefts[2]!! + 40f <= 220f)
    }

    @Test
    fun bandPlanUsesTheRfGuruColours() {
        val span = RadioFace.spanOf(14_074_000, 100_000)
        val slices = RadioFace.planSlices(
            span,
            listOf(
                PlanSegment(14_000_000, 14_070_000, 0),
                PlanSegment(14_070_000, 14_099_000, 1),
                PlanSegment(14_112_000, 14_350_000, 2),
                PlanSegment(13_000_000, 13_100_000, 3),
            ),
        )
        assertEquals(listOf("CW", "DIG", "SSB"), slices.map { it.label })
        assertEquals(0xFF4F8BF5.toInt(), RadioFace.planColor(0))
        assertEquals(0xFFE5A823.toInt(), RadioFace.planColor(1))
        assertEquals(0xFF34D399.toInt(), RadioFace.planColor(2))
        assertEquals(0xFFF87171.toInt(), RadioFace.planColor(3))
        val (low, high) = RadioFace.passbandHz(14_074_000, 300, 2700)
        assertEquals(14_074_300, low)
        assertEquals(14_076_700, high)
    }

    @Test
    fun filterNudgeWillNotCrossItself() {
        assertEquals(250 to 2700, RadioFace.nudgeFilter(300, 2700, AdjustField.FILTER_LOW, -1))
        // A +50 Hz step would put the low edge on the high edge. The pair is left as it is.
        assertEquals(2650 to 2700, RadioFace.nudgeFilter(2650, 2700, AdjustField.FILTER_LOW, +1))
        assertEquals(300 to 350, RadioFace.nudgeFilter(300, 350, AdjustField.FILTER_HIGH, -1))
        val wider = RadioFace.nudgeFilter(300, 2700, AdjustField.FILTER_BW, +1)
        assertTrue(wider.second - wider.first > 2400)
    }

    @Test
    fun softkeyPagesFollowTheFirmwareGroups() {
        assertEquals("VOL 1:2", RadioFace.page(0).badge)
        assertEquals(FaceGroup.GEN, RadioFace.page(0).group)
        assertEquals(1, RadioFace.nextPageIndex(0))
        assertEquals(0, RadioFace.nextPageIndex(4))
        assertEquals(4, RadioFace.previousPageIndex(0))
        assertEquals(5, RadioFace.groupStartIndex(FaceGroup.APP))
        assertEquals(FaceGroup.DFL, RadioFace.page(RadioFace.groupStartIndex(FaceGroup.DFL)).group)
        assertEquals(3, RadioFace.page(RadioFace.groupStartIndex(FaceGroup.DFL)).keys.size)
        val vol = RadioFace.page(0).keys.first { it.id == "vol" }
        val state = sample()
        assertEquals("30", RadioFace.keyLabel(vol, state).value)
        assertEquals(31, (RadioFace.commandFor(vol, state, volumeMax = 55, direction = +1) as FaceCommand.SetInt).value)
        assertEquals(30, (RadioFace.commandFor(vol, state, volumeMax = 30, direction = +1) as FaceCommand.SetInt).value)
        val zoom = RadioFace.page(2).keys.first { it.id == "zoom" }
        assertEquals(2, (RadioFace.commandFor(zoom, state, 100, +1) as FaceCommand.SetInt).value)
        assertEquals(1, (RadioFace.commandFor(zoom, state, 100, -1) as FaceCommand.SetInt).value)
        val rit = RadioFace.page(2).keys.first { it.id == "rit" }
        val pending = RadioFace.commandFor(rit, state, 100, +1)
        assertTrue(pending is FaceCommand.Pending)
        assertFalse(RadioFace.writesTheRadio(pending))
        assertEquals("pending", RadioFace.keyLabel(rit, state).value)
        assertTrue(RadioFace.isCarrierKey("ptt"))
        assertFalse(RadioFace.isCarrierKey("F1"))
    }

    @Test
    fun everyPanelSettingIsEitherASoftkeyOrOnTheStrip() {
        val fromKeys = RadioFace.pages.flatMap { page ->
            page.keys.mapNotNull { key ->
                when (val action = key.action) {
                    is FaceAction.Adjust -> action.setting
                    is FaceAction.Toggle -> action.setting
                    is FaceAction.Zoom -> PanelSettings.ZOOM
                    FaceAction.CycleAgc -> PanelSettings.AGC
                    else -> null
                }
            }
        }.toSet()
        val all = setOf(
            PanelSettings.FREQ, PanelSettings.MODE, PanelSettings.AGC, PanelSettings.PRE, PanelSettings.ATT,
            PanelSettings.NB, PanelSettings.NR, PanelSettings.SQL, PanelSettings.RFG, PanelSettings.VOL,
            PanelSettings.PWR, PanelSettings.STEP, PanelSettings.ZOOM, PanelSettings.BAND, PanelSettings.VFO,
            PanelSettings.FILTER, PanelSettings.ATU, PanelSettings.LOCK,
        )
        assertEquals(all, fromKeys + RadioFace.controlsOutsideTheSoftkeys.toSet())
    }

    @Test
    fun pendingCatalogNamesTheFirmwareMenusThePanelDoesNotCarry() {
        val names = RadioFace.pendingFeatures().map { it.name.lowercase() }
        listOf(
            "split", "rit", "xit", "comp", "if shift", "vox", "monitor", "dnf",
            "nb level", "nr level", "cw keyer speed", "cw keyer tone", "iambic", "qsk", "ratio",
            "zap", "cw peak", "cw trainer", "swr scan", "rx recorder", "field ops", "gps",
            "birdie cal", "memories", "js8", "psk", "rtty", "olivia", "sstv", "wspr",
        ).forEach { needed ->
            assertTrue("missing placeholder for $needed", names.any { needed in it })
        }
        assertTrue(RadioFace.pendingFeatures().all { it.note == PENDING_FIRMWARE })
    }

    private fun sample(): RadioState = RadioState(
        freqHz = 14_074_000,
        mode = 2,
        filterLowHz = 300,
        filterHighHz = 2700,
        agc = 3,
        preOn = true,
        att = 0,
        noiseBlankerOn = false,
        noiseReduceOn = true,
        squelch = 0,
        rfGain = 100,
        volume = 30,
        powerWatts = 5.0,
        stepIndex = 1,
        zoom = 1,
        vfo = 0,
        atuOn = false,
        battery = 61,
        transmitting = false,
        freqLocked = false,
    )

    private fun spot(hz: Int, call: String) = Spot(hz, call, "SSB", 1, "KD0CQ", "", false)
}
