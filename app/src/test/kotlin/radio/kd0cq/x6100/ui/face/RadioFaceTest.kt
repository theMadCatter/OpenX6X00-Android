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
import radio.kd0cq.x6100.net.PanelMode
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
    fun theWindowAspectPicksTheOperatingFace() {
        assertTrue(RadioFace.wideWindow(800f, 360f))
        assertFalse(RadioFace.wideWindow(360f, 800f))
        assertFalse(RadioFace.wideWindow(640f, 640f))
        assertFalse(RadioFace.wideWindow(0f, 800f))
        assertFalse(RadioFace.wideWindow(Float.POSITIVE_INFINITY, 480f))
    }

    @Test
    fun frequencyDigitsFitTheSlotTheyAreGiven() {
        // 14.100.000 is the string the header dropped after "14". The size is chosen from the
        // slot, and the whole string, plus room for the +/− column, has to stay inside that width.
        val text = RadioFace.formatFrequency(14_100_000)
        assertEquals("14.100.000", text)
        assertTrue(text.endsWith(".100.000"))
        assertEquals(Triple("14", "100", "000"), RadioFace.frequencyParts(14_100_000))
        val advance = 0.62f
        val chars = text.length + 1.5f
        val wide = RadioFace.frequencyTextSp(360f, 40f, text)
        assertTrue("wide slot should be at least the old 20 sp gold text, was $wide", wide >= 20f)
        assertTrue(chars * advance * wide <= 360f + 0.05f)
        val tight = RadioFace.frequencyTextSp(100f, 40f, text)
        assertTrue(chars * advance * tight <= 100f + 0.05f)
        assertTrue(tight < wide)
        assertTrue(tight >= 12f)
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
    fun landscapeSpectrumTakesAboutAThirdAndReachesTheBottom() {
        // An S24-class landscape scope. The trace is 30–35% of that pane. The scale
        // and the plan are inside the rest, so the four bands add up to the pane:
        // nothing is left under the waterfall.
        val height = 480f
        val scale = 16f
        val plan = 8f
        val split = RadioFace.scopeSplit(height, scale, plan, waterfallShare = 0.68f, zoomGuardPx = 24f)
        val trace = split.spectrumPx / height
        assertTrue(trace in 0.30f..0.35f)
        assertEquals(height, split.spectrumPx + scale + plan + split.waterfallPx, 0.01f)
        assertTrue(split.waterfallPx > split.spectrumPx)
        assertTrue(split.spectrumPx > 24f)
        // The old 0.73 share subtracted the strips from the trace and left it on the
        // zoom chip. The trace is still inside 30–35% for that call.
        val older = RadioFace.scopeSplit(height, scale, plan, waterfallShare = 0.73f, zoomGuardPx = 24f)
        assertTrue(older.spectrumPx / height in 0.30f..0.35f)
        val portrait = RadioFace.scopeSplit(300f, 16f, 14f, waterfallShare = 0f, zoomGuardPx = 24f)
        assertEquals(108f, portrait.spectrumPx, 0.5f)
        // The 3D ridges take a little of the waterfall's height. The waterfall stays the
        // larger part of the landscape pane, and portrait grows past the 40% trace.
        val ridge = RadioFace.scopeSplit(height, scale, plan, waterfallShare = 0.68f, zoomGuardPx = 24f, ridge = true)
        assertTrue(ridge.spectrumPx > split.spectrumPx)
        assertTrue(ridge.waterfallPx > ridge.spectrumPx)
        val portraitRidge = RadioFace.scopeSplit(300f, 16f, 14f, waterfallShare = 0f, zoomGuardPx = 24f, ridge = true)
        assertTrue(portraitRidge.spectrumPx > portrait.spectrumPx)
    }

    @Test
    fun scopeLayoutGivesTheWholeRoomToOnePane() {
        // 400 px stands in for the scope after the header. The scale and the plan stay
        // outside the room, so spectrum-only is that room and the waterfall is gone.
        val both = RadioFace.scopeSplit(400f, 16f, 14f, waterfallShare = 0.68f, zoomGuardPx = 24f)
        val spectrum = RadioFace.scopeSplit(
            400f, 16f, 14f, waterfallShare = 0.68f, zoomGuardPx = 24f, layout = ScopeLayout.SPECTRUM,
        )
        assertEquals(0f, spectrum.waterfallPx, 0.01f)
        assertEquals(370f, spectrum.spectrumPx, 0.01f)
        assertTrue(spectrum.spectrumPx > both.spectrumPx * 2f)
        val waterfall = RadioFace.scopeSplit(
            400f, 16f, 14f, waterfallShare = 0.68f, zoomGuardPx = 24f, layout = ScopeLayout.WATERFALL,
        )
        assertEquals(0f, waterfall.spectrumPx, 0.01f)
        assertEquals(370f, waterfall.waterfallPx, 0.01f)
        assertEquals(ScopeLayout.BOTH, ScopeLayout.fromStored(null))
        assertEquals(ScopeLayout.BOTH, ScopeLayout.fromStored("later"))
        assertEquals(ScopeLayout.SPECTRUM, ScopeLayout.fromStored("spectrum"))
        assertEquals(ScopeLayout.WATERFALL, ScopeLayout.fromStored("waterfall"))
        assertEquals("S+W", ScopeLayout.BOTH.chip)
        assertEquals(ScopeLayout.SPECTRUM, ScopeLayout.BOTH.next())
        assertEquals(ScopeLayout.WATERFALL, ScopeLayout.SPECTRUM.next())
        assertEquals(ScopeLayout.BOTH, ScopeLayout.WATERFALL.next())
    }

    @Test
    fun spectrumOnlyFillsThePaneInBothAspects() {
        // Landscape scope after the header and the keys. Spectrum-only keeps that
        // room. The waterfall share must not leave a black band under a short trace.
        val wide = RadioFace.scopeSplit(
            240f, 16f, 8f, waterfallShare = 0.68f, zoomGuardPx = 24f,
            ridge = false, layout = ScopeLayout.SPECTRUM,
        )
        assertEquals(0f, wide.waterfallPx, 0.01f)
        assertEquals(216f, wide.spectrumPx, 0.01f)
        val wideBoth = RadioFace.scopeSplit(240f, 16f, 8f, waterfallShare = 0.68f, zoomGuardPx = 24f)
        assertTrue(wide.spectrumPx > wideBoth.spectrumPx * 2f)

        // Portrait scope is the column left after the keys, the meter and the sliders.
        // Spectrum-only uses that column, not the 40% combined split, and not past it.
        val tall = RadioFace.scopeSplit(
            640f, 16f, 8f, waterfallShare = 0f, zoomGuardPx = 24f,
            ridge = false, layout = ScopeLayout.SPECTRUM,
        )
        val tallBoth = RadioFace.scopeSplit(640f, 16f, 8f, waterfallShare = 0f, zoomGuardPx = 24f)
        assertEquals(0f, tall.waterfallPx, 0.01f)
        assertEquals(616f, tall.spectrumPx, 0.01f)
        assertTrue(tall.spectrumPx > tallBoth.spectrumPx * 2f)
        assertTrue(tall.spectrumPx < 640f)
    }

    @Test
    fun theRightFrequencyLabelStaysClearOfTheZoomChip() {
        // Landscape S+W lifts the spectrum to the zoom guard. The old test
        // (spectrum + 2 < guard) was false in that case, so the right-hand label
        // was drawn through the Zoom chip.
        val width = 900f
        val guard = 66f
        val scale = 44f
        val reserve = 180f
        val usable = RadioFace.scaleLabelWidth(width, guard, guard, scale, reserve)
        assertEquals(width - reserve, usable, 0.01f)
        val lefts = RadioFace.placeScaleLabels(
            centersPx = listOf(0f, width),
            widthsPx = listOf(50f, 50f),
            viewWidthPx = usable,
            gapPx = 8f,
        )
        assertNotNull(lefts[1])
        assertTrue(lefts[1]!! + 50f <= width - reserve + 0.1f)
        val tall = RadioFace.scaleLabelWidth(
            width, spectrumPx = 400f, zoomGuardPx = guard, scalePx = scale, zoomReservePx = reserve,
        )
        assertEquals(width, tall, 0.01f)
    }

    @Test
    fun portraitSpectrumOnlyEndsOnTheBandPlanAndWaterfallSeparatesTheChips() {
        // The portrait column above the meter. Spectrum-only, whether the plot is the
        // 2D trace or the 3D field, uses that whole column. The chips stay over the
        // plot, so they do not open a gap under the band plan.
        val tall = 800f
        val scale = 16f
        val plan = 8f
        val chips = 36f
        for (ridge in listOf(false, true)) {
            val bands = RadioFace.scopeBands(
                heightPx = tall,
                chipPx = chips,
                scalePx = scale,
                planPx = plan,
                waterfallShare = 0f,
                zoomGuardPx = 24f,
                ridge = ridge,
                layout = ScopeLayout.SPECTRUM,
            )
            assertEquals(0f, bands.chipPx, 0.01f)
            assertEquals(0f, bands.waterfallPx, 0.01f)
            assertEquals(tall, bands.total(), 0.01f)
            assertEquals(tall, bands.scaleTop + bands.scalePx + bands.planPx, 0.01f)
        }
        // Waterfall-only. The chip row is its own band. The frequency labels start
        // where that row ends, and the waterfall is what remains under the plan.
        val water = RadioFace.scopeBands(
            heightPx = tall,
            chipPx = chips,
            scalePx = scale,
            planPx = plan,
            waterfallShare = 0f,
            zoomGuardPx = 24f,
            layout = ScopeLayout.WATERFALL,
        )
        assertEquals(chips, water.chipPx, 0.01f)
        assertEquals(0f, water.spectrumPx, 0.01f)
        assertEquals(chips, water.scaleTop, 0.01f)
        assertTrue(water.scaleTop >= water.chipPx)
        assertTrue(water.waterfallPx > tall * 0.8f)
        assertEquals(tall, water.total(), 0.01f)
        // Combined portrait still overlays the chips and keeps the waterfall the larger part.
        val both = RadioFace.scopeBands(
            heightPx = tall,
            chipPx = chips,
            scalePx = scale,
            planPx = plan,
            waterfallShare = 0f,
            zoomGuardPx = 24f,
            layout = ScopeLayout.BOTH,
        )
        assertEquals(0f, both.chipPx, 0.01f)
        assertTrue(both.waterfallPx > both.spectrumPx)
        // Zoom lives in the chip row here, so the right-hand label keeps the full strip.
        val full = RadioFace.scaleLabelWidth(
            900f, 0f, 66f, 44f, 180f, ScopeLayout.WATERFALL,
        )
        assertEquals(900f, full, 0.01f)
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
    fun eachModePutsTheFilterOnItsOwnSideOfTheCarrier() {
        // 3.844.000 on 80 m, the cuts the radio sends as positive audio Hz.
        val f = 3_844_000
        val low = 300
        val high = 2700
        // USB and DATA-U sit above the VFO.
        assertEquals(f + low to f + high, RadioFace.passbandHz(f, low, high, PanelMode.Usb.index))
        assertEquals(f + low to f + high, RadioFace.passbandHz(f, low, high, PanelMode.DigU.index))
        // LSB, DATA-L and RTTY sit below it: f - high_cut .. f - low_cut.
        // RTTY is not a mode index. It runs as LSB or DATA-L.
        assertEquals(f - high to f - low, RadioFace.passbandHz(f, low, high, PanelMode.Lsb.index))
        assertEquals(f - high to f - low, RadioFace.passbandHz(f, low, high, PanelMode.DigL.index))
        // CW-R is the lower side even when a key tone is present. Adding the tone
        // would put the high edge above the VFO.
        assertEquals(f - high to f - low, RadioFace.passbandHz(f, low, high, PanelMode.Cwr.index))
        assertEquals(
            f - high to f - low,
            RadioFace.passbandHz(f, low, high, PanelMode.Cwr.index, keyToneHz = 700, cwToneRises = false),
        )
        // CW on the X6100 (cwrise false) is the upper side. The tone does not slide it.
        assertEquals(
            f + low to f + high,
            RadioFace.passbandHz(f, low, high, PanelMode.Cw.index, keyToneHz = 700, cwToneRises = false),
        )
        // CW on the X6200 rises with the dial, the same side as CW-R.
        assertEquals(
            f - high to f - low,
            RadioFace.passbandHz(f, low, high, PanelMode.Cw.index, keyToneHz = 700, cwToneRises = true),
        )
        // AM, NFM, SAM and WFM are symmetric about the carrier. The low cut is not used.
        listOf(PanelMode.Am, PanelMode.Fm, PanelMode.Sam, PanelMode.Wfm).forEach { mode ->
            assertEquals(f - high to f + high, RadioFace.passbandHz(f, low, high, mode.index))
        }
        // The high edge of a lower-sideband filter is the one nearer the carrier,
        // so the shade ends below the VFO marker.
        val (lsbLo, lsbHi) = RadioFace.passbandHz(f, low, high, PanelMode.Lsb.index)
        assertTrue(lsbHi < f)
        assertTrue(lsbLo < lsbHi)
    }

    @Test
    fun meterReadoutSitsAboveEveryScaleMark() {
        // The card on the landscape header is about 168 by 64 px. The readout is one
        // line. Every mark, including "+40" at the right-hand end, starts at or below
        // that line, so "-72" cannot land on it.
        val face = RadioFace.meterFace(cardHeightPx = 64f, readoutPx = 14f)
        assertEquals(14f, face.readoutBottom, 0.01f)
        assertEquals(14f, face.arcTop, 0.01f)
        RadioFace.sMeterMarks().forEach { (mark, _) ->
            val label = RadioFace.dialLabelTopLeft(168f, face.arcHeight, mark, 18f, 10f)
            assertTrue(face.arcTop + label.y >= face.readoutBottom)
        }
        // A tall readout still ends where the arc begins. The marks stay under it.
        val tall = RadioFace.meterFace(64f, 28f)
        val plus40 = RadioFace.dialLabelTopLeft(168f, tall.arcHeight, 1f, 22f, 10f)
        assertTrue(tall.arcTop + plus40.y >= tall.readoutBottom)
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
