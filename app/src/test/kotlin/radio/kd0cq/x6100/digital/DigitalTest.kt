// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later

package radio.kd0cq.x6100.digital

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import radio.kd0cq.x6100.log.Qso

class DigitalTest {

    @Test
    fun ft8SlotCqAndMyCall() {
        assertEquals(4_000, Ft8List.remainingMs(0, slotMs = 15_000, inMs = 11_000))
        assertEquals(0, Ft8List.remainingMs(0, slotMs = 15_000, inMs = 16_000))
        assertEquals(5_000, Ft8List.remainingMs(10_000, slotMs = 0, inMs = 0))
        assertTrue(Ft8List.isCq("CQ KD0CQ EM37"))
        assertFalse(Ft8List.isCq("KD0CQ W1AW -10"))
        assertTrue(Ft8List.mentionsMe("W1AW KD0CQ EM37", "kd0cq/p"))
        assertFalse(Ft8List.mentionsMe("CQ K1ABC FN42", "KD0CQ"))
        val row = Qso("K1ABC", 14_074_000, "USB", 0, "59", "59", "", "", "", "", 1)
        assertTrue(Ft8List.worked("k1abc", listOf(row)))
        assertFalse(Ft8List.worked("W1AW", listOf(row)))
    }

    @Test
    fun js8CommandsAreTheWordsTheRadioDispatches() {
        assertEquals("js8 cq", Js8Protocol.command("cq"))
        assertEquals("js8 hb", Js8Protocol.command("hb"))
        assertEquals("js8 autocq", Js8Protocol.command("autocq"))
        assertEquals("js8 hbnow", Js8Protocol.command("hbnow"))
        assertEquals("js8 auto", Js8Protocol.command("auto"))
        assertEquals("js8 hold", Js8Protocol.command("hold"))
        assertEquals("js8 speed", Js8Protocol.command("speed"))
        assertEquals("js8 stop", Js8Protocol.command("stop"))
        assertEquals("js8 open", Js8Protocol.command("open"))
        assertEquals("js8 send HELLO THERE", Js8Protocol.send("HELLO THERE"))
        assertEquals("js8 send @ALLCALL?", Js8Protocol.send("@ALLCALL?"))
        assertNull(Js8Protocol.send("   "))
        assertNull(Js8Protocol.send("x".repeat(Js8Protocol.TEXT_MAX + 1)))
        assertEquals("js8 send ${"x".repeat(Js8Protocol.TEXT_MAX)}", Js8Protocol.send("x".repeat(Js8Protocol.TEXT_MAX)))
        assertFalse(Js8Protocol.send("HELLO")!!.startsWith("js8 tx"))
        assertEquals("js8 select KN4ABC", Js8Protocol.select("KN4ABC"))
        assertNull(Js8Protocol.select(" "))
        assertNull(Js8Protocol.select("KN4 ABC"))
        assertEquals("js8 txfreq 800", Js8Protocol.txFreq(800))
        assertEquals("js8 txfreq 0", Js8Protocol.txFreq(-5))
        assertEquals("js8 wf 1", Js8Protocol.waterfall(true))
        assertEquals("js8 wf 0", Js8Protocol.waterfall(false))
        assertEquals("Fast", Js8Protocol.speedName(1))
        assertEquals("Turbo", Js8Protocol.speedName(2))
        assertEquals("Slow", Js8Protocol.speedName(3))
        assertEquals("Normal", Js8Protocol.speedName(0))
        assertEquals("Normal", Js8Protocol.speedName(9))
        assertEquals(160, Js8Protocol.toneWidthHz(2))
        assertNull(Js8Protocol.parse("ft8r {}"))
        assertTrue(Js8Protocol.needsNewerFirmware("unknown command js8"))
        assertFalse(Js8Protocol.needsNewerFirmware("js8 send: an empty message, one too long, or the radio is busy"))
        assertFalse(Js8Protocol.needsNewerFirmware("controlled by the radio's operator: take control first"))
    }

    @Test
    fun js8RowKeepsItsPlaceWhileTheTextGrows() {
        val first = Js8Protocol.parse("""js8r {"id":4,"utc":100000,"snr":-12,"f":540,"sp":0,"tx":0,"me":0,"cq":1,"hb":0,"grp":0,"lc":0,"part":1,"chk":0,"fr":"W1AW","to":"","g":"FN31","x":"CQ"}""")
        val second = Js8Protocol.parse("""js8r {"id":9,"utc":100100,"snr":-5,"f":1200,"sp":4,"tx":0,"me":0,"cq":0,"hb":1,"grp":0,"lc":0,"part":0,"chk":1,"fr":"K1ABC","to":"@GHOSTNET","g":"","x":"@GHOSTNET SNR -5"}""")
        val grown = Js8Protocol.parse("""js8r {"id":4,"utc":100000,"snr":-12,"f":540,"sp":0,"tx":0,"me":0,"cq":1,"hb":0,"grp":0,"lc":0,"part":0,"chk":1,"fr":"W1AW","to":"","g":"FN31","x":"CQ CQ"}""")
        requireNotNull(first)
        requireNotNull(second)
        requireNotNull(grown)
        val kept = Js8Protocol.upsert(Js8Protocol.upsert(emptyList(), first), second).let { Js8Protocol.upsert(it, grown) }
        assertEquals(listOf(4L, 9L), kept.map { it.id })
        assertEquals("CQ CQ", kept[0].text)
        assertEquals(540, kept[0].freqHz)
        assertEquals("W1AW", kept[0].from)
        assertTrue(kept[0].cq)
        assertEquals("@GHOSTNET SNR -5", kept[1].text)
        assertTrue(kept[1].heartbeat)
        assertEquals("S", Js8Protocol.submodeLetter(kept[1].submode))
    }

    @Test
    fun ghostNetFilterKeepsTheGroupsAndDropsHighHeartbeats() {
        val world = row(1, to = "@GHOSTNET", text = "SNR", freq = 700, heartbeat = false)
        val flash = row(2, to = "@GSTFLASH", text = "WX", freq = 400, heartbeat = false)
        val region = row(3, to = "@GNUSAMO", text = "hello", freq = 600, heartbeat = false)
        val otherRegion = row(4, to = "@GNCAON", text = "hello", freq = 600, heartbeat = false)
        val lowBeat = row(5, to = "@GHOSTNET", text = "HB", freq = 999, heartbeat = true)
        val highBeat = row(6, to = "@GHOSTNET", text = "HB", freq = 1000, heartbeat = true)
        val chatter = row(7, to = "KD0CQ", text = "73", freq = 1500, heartbeat = false)
        val rows = listOf(world, flash, region, otherRegion, lowBeat, highBeat, chatter)

        val shown = GhostNet.filter(rows, GhostNet.DEFAULT_REGION)
        assertEquals(listOf(1L, 2L, 3L, 5L), shown.map { it.id })
        assertTrue(GhostNet.isFlash(flash))
        assertFalse(GhostNet.isFlash(world))
        assertEquals("@GNUSAMO", GhostNet.DEFAULT_REGION)
        assertEquals("@GNUSAMO", GhostNet.groupToken("gnusamo"))
        assertEquals("@GNCAON", GhostNet.groupToken("@gncaon"))
        assertEquals(listOf(1L, 2L, 4L, 5L), GhostNet.filter(rows, "GNCAON").map { it.id })
        assertTrue(shown.none { it.freqHz >= GhostNet.HEARTBEAT_OFFSET_LIMIT_HZ && it.heartbeat })
        assertTrue(GhostNet.heartbeatInWindow(999))
        assertFalse(GhostNet.heartbeatInWindow(1000))
        assertTrue(GhostNet.REMINDER.contains("Thursday 0100Z"))
        assertTrue(GhostNet.REMINDER.contains("7.107 MHz"))
        assertTrue(GhostNet.REMINDER.contains("Wednesday 7 PM Central"))
        assertTrue(GhostNet.SETTINGS_NOTE.contains("web config"))
    }

    @Test
    fun usAdvancedDataSegmentsCoverJs8AndRefuseThePhoneBand() {
        assertTrue(UsAdvanced.dataCovers(7_107_000L, 7_107_050L))
        assertTrue(UsAdvanced.dataCovers(7_107_000L + 500, 7_107_000L + 500 + 50))
        assertTrue(UsAdvanced.dataCovers(14_074_000L, 14_074_050L))
        assertTrue(UsAdvanced.dataCovers(3_575_000L, 3_575_160L))
        assertFalse(UsAdvanced.dataCovers(3_844_000L, 3_844_050L))
        assertFalse(UsAdvanced.dataCovers(3_500_000L, 3_500_050L))
        assertFalse(UsAdvanced.dataCovers(14_150_000L, 14_150_160L))
        assertFalse(UsAdvanced.dataCovers(7_124_900L, 7_125_100L))
        assertTrue(UsAdvanced.dataCovers(50_100_000L, 50_100_160L))
    }

    private fun row(
        id: Long,
        to: String,
        text: String,
        freq: Int,
        heartbeat: Boolean,
    ) = Js8Row(
        id = id,
        utc = 0,
        snr = -10,
        freqHz = freq,
        submode = 0,
        transmitting = false,
        toMe = false,
        cq = false,
        heartbeat = heartbeat,
        group = to.startsWith("@"),
        lowConfidence = false,
        partial = false,
        checksum = true,
        from = "W1AW",
        to = to,
        grid = "",
        text = text,
    )
}
