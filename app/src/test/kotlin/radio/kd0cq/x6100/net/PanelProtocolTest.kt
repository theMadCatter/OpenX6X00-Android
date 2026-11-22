// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel parser, against lines the firmware really writes. Every sample here is spelled out of
 * ~/src/OpenX6X00's remote_server.c: pstate_line, ctl_tell, role_tell, ft8_row_line,
 * ft8_status_line, panel_spots, the sessions line, and the 16- and 8-byte binary headers.
 */
class PanelProtocolTest {

    @Test
    fun helloKeepsAVersionThatHoldsSpaces() {
        // client_textf(c, "hello %s %u", VERSION, boot_id), and VERSION is "OpenX6X00 - v0.3.6"
        val event = PanelProtocol.parseLine("hello OpenX6X00 - v0.3.6 305419896") as PanelEvent.Hello
        assertEquals("OpenX6X00 - v0.3.6", event.version)
        assertEquals(305419896L, event.bootId)
    }

    @Test
    fun sizeKeysModesAndVolmax() {
        assertEquals(PanelEvent.Size(800, 480), PanelProtocol.parseLine("size 800 480"))
        assertEquals(PanelEvent.Modes(12), PanelProtocol.parseLine("modes 12"))
        assertEquals(PanelEvent.VolumeMax(100), PanelProtocol.parseLine("volmax 100"))
        assertEquals(PanelEvent.Pong(7L), PanelProtocol.parseLine("pong 7"))

        val keys = PanelProtocol.parseLine("keys POWER GEN KEY DFN PTT BAND_DOWN BAND_UP") as PanelEvent.Keys
        assertEquals(listOf("POWER", "GEN", "KEY", "DFN", "PTT", "BAND_DOWN", "BAND_UP"), keys.names)
    }

    @Test
    fun stateLineReadsEveryField() {
        val state = """state {"freq":14074000,"mode":3,"flo":-2400,"fhi":200,"agc":1,"pre":1,"att":0,"nb":1,"nr":1,"sql":0,"rfg":63,"vol":40,"pwr":5.0,"step":2,"zoom":3,"vfo":0,"atu":1,"bat":78,"tx":0,"lock":0}"""
        val s = (PanelProtocol.parseLine(state) as PanelEvent.State).state
        assertEquals(14_074_000, s.freqHz)
        assertEquals(PanelMode.DigU, PanelMode.fromIndex(s.mode))
        assertEquals(-2400, s.filterLowHz)
        assertEquals(200, s.filterHighHz)
        assertEquals(1, s.agc)
        assertTrue(s.preOn)
        assertTrue(s.noiseBlankerOn)
        assertTrue(s.noiseReduceOn)
        assertEquals(63, s.rfGain)
        assertEquals(40, s.volume)
        assertEquals(2, s.stepIndex)
        assertTrue(s.atuOn)
        assertEquals(78, s.battery)
        assertFalse(s.transmitting)
        assertFalse(s.freqLocked)
        // A line that does not carry the key tone keeps the defaults.
        assertEquals(0, s.keyToneHz)
        assertFalse(s.cwToneRises)
    }

    @Test
    fun stateLineKeepsTheKeyToneAndWhichSideCwRisesOn() {
        val line = """state {"freq":3844000,"mode":0,"kt":700,"cwrise":1,"flo":300,"fhi":2700}"""
        val s = (PanelProtocol.parseLine(line) as PanelEvent.State).state
        assertEquals(700, s.keyToneHz)
        assertTrue(s.cwToneRises)
        assertEquals(300, s.filterLowHz)
        assertEquals(2700, s.filterHighHz)
        assertEquals(PanelMode.Lsb, PanelMode.fromIndex(s.mode))
    }

    @Test
    fun stateWattsKeepTheTenthTheRadioWrote() {
        // "pwr" goes out as "%d.%d", so read it as text: 0.7 as a double times ten is 6.9999999999999996.
        fun watts(pwr: String): Double {
            val line = """state {"freq":14000000,"mode":3,"flo":-2400,"fhi":200,"agc":1,"pre":0,"att":0,"nb":0,"nr":0,"sql":0,"rfg":50,"vol":40,"pwr":$pwr,"step":2,"zoom":3,"vfo":0,"atu":0,"bat":0,"tx":0,"lock":0}"""
            return (PanelProtocol.parseLine(line) as PanelEvent.State).state.powerWatts
        }

        assertEquals(5.0, watts("5.0"), 1e-9)
        assertEquals(0.7, watts("0.7"), 1e-9)
        assertEquals(0.1, watts("0.1"), 1e-9)
        assertEquals(10.0, watts("10.0"), 1e-9)
        assertEquals(4.3, watts("4.3"), 1e-9)
    }

    @Test
    fun metersComeAsTheRadioPrintsThem() {
        assertEquals(PanelEvent.Meter(-93.5), PanelProtocol.parseLine("m -93.5"))

        // snprintf(line, sizeof(line), "t %.1f %.2f %.1f", pwr, swr, alc)
        val tx = (PanelProtocol.parseLine("t 9.8 1.20 0.7") as PanelEvent.TxMeters).meters
        assertEquals(9.8, tx.watts, 1e-9)
        assertEquals(1.20, tx.swr, 1e-9)
        assertEquals(0.7, tx.alc, 1e-9)
    }

    @Test
    fun roleTellsWhatThisConnectionMayDo() {
        // client_textf(c, "role %s %d %s", role_name, tx_ok, the session's name or "-")
        val mine = PanelProtocol.parseLine("role operate 1 My Phone") as PanelEvent.Role
        assertEquals(PanelRole.Operate, mine.role.role)
        assertTrue(mine.role.txAllowed)
        assertTrue(mine.role.canOperate)
        assertFalse(mine.role.canAdmin)
        assertEquals("My Phone", mine.role.sessionName)

        val viewer = PanelProtocol.parseLine("role view 0 -") as PanelEvent.Role
        assertEquals(PanelRole.View, viewer.role.role)
        assertFalse(viewer.role.canOperate)
        assertEquals("", viewer.role.sessionName)
    }

    @Test
    fun pairedKeepsTheTokenAndSaysWhatItGrants() {
        // client_textf(c, "paired %s %s %d", token, role_name, tx_ok); random_hex hands out 32 hex digits
        val event = PanelProtocol.parseLine("paired 0123456789abcdef0123456789abcdef admin 1") as PanelEvent.Paired
        assertEquals("0123456789abcdef0123456789abcdef", event.token)
        assertTrue(isSessionToken(event.token))
        assertEquals(PanelRole.Admin, event.role)
        assertTrue(event.txAllowed)
        assertTrue(event.role.canAdmin)

        // A short token, or one with a letter outside hex, is the radio saying something else.
        assertFalse(isSessionToken("0123456789abcdef0123456789abcde"))
        assertFalse(isSessionToken("0123456789abcdef0123456789abcdeg"))
        assertTrue(isPairingCode("123456"))
        assertFalse(isPairingCode("12345"))
        assertFalse(isPairingCode("12345a"))
    }

    @Test
    fun ctlKeepsTheWholeNameOfTheBrowserHoldingTheControls() {
        // ctl_tell(): "ctl %s %d", where a browser's word is "browser" plus its paired name.
        val ours = PanelProtocol.parseLine("ctl browser My Phone 1") as PanelEvent.ControlHeld
        assertEquals("browser My Phone", ours.info.holder)
        assertTrue(ours.info.mine)
        assertFalse(ours.info.free)
        assertEquals("us", ours.info.label)

        val local = PanelProtocol.parseLine("ctl local 0") as PanelEvent.ControlHeld
        assertTrue(local.info.isLocal)
        assertFalse(local.info.mine)

        val free = PanelProtocol.parseLine("ctl none 0") as PanelEvent.ControlHeld
        assertTrue(free.info.free)
        assertEquals("no one", free.info.label)

        val tci = PanelProtocol.parseLine("ctl tci 0") as PanelEvent.ControlHeld
        assertTrue(tci.info.isTci)
    }

    @Test
    fun bandsAndPlanComeOnceAsTheRadioSendsThem() {
        val bandsLine = """bands [{"n":"160m","a":1800000,"b":2000000},{"n":"80m","a":3500000,"b":4000000}]"""
        val bands = (PanelProtocol.parseLine(bandsLine) as PanelEvent.Bands).bands
        assertEquals(2, bands.size)
        assertEquals("160m", bands[0].name)
        assertEquals(1_800_000, bands[0].startHz)
        assertEquals(4_000_000, bands[1].stopHz)

        val plan = PanelProtocol.parseLine("plan [[1810000,1810999,1],[1810000,2000000,0]]") as PanelEvent.Plan
        assertEquals(2, plan.segments.size)
        assertEquals(PlanSegment(1_810_000, 1_810_999, 1), plan.segments[0])
    }

    @Test
    fun spotsUseTheRadiosOwnKeysAndComeNewestFirst() {
        // panel_spots(): {"f","c","m","a","s","k","al"}
        val spotsLine = """spots [{"f":14074000,"c":"KD0CQ","m":"DATA","a":2,"s":"K1TTT","k":"73","al":0},{"f":7074000,"c":"N0CALL","m":"PHONE","a":61,"s":"W1AW","k":"","al":1}]"""
        val spots = (PanelProtocol.parseLine(spotsLine) as PanelEvent.Spots).spots
        assertEquals(2, spots.size)
        assertEquals(14_074_000, spots[0].freqHz)
        assertEquals("KD0CQ", spots[0].call)
        assertEquals("DATA", spots[0].modeClass)
        assertEquals(2, spots[0].ageMin)
        assertEquals("K1TTT", spots[0].spotter)
        assertEquals("73", spots[0].comment)
        assertFalse(spots[0].alert)
        assertTrue(spots[1].alert)
        assertEquals("", spots[1].comment)
    }

    @Test
    fun ft8RowCarriesEveryKeyFt8RowLineWrites() {
        val rowLine = """ft8r {"id":7,"t":1,"x":"CQ KD0CQ FN78","c":"KD0CQ","g":"FN78","f":535700,"snr":-12,"me":1,"w":0,"d":1234,"p":0,"odd":0,"k":"UNITED STATES"}"""
        val row = (PanelProtocol.parseLine(rowLine) as PanelEvent.DecodedFt8Row).row
        assertEquals(7L, row.id)
        assertEquals(1, row.cellType)
        assertEquals("CQ KD0CQ FN78", row.text)
        assertEquals("KD0CQ", row.call)
        assertEquals("FN78", row.grid)
        assertEquals(535_700, row.freqHz)
        assertEquals(-12, row.snrDb)
        assertTrue(row.toMe)
        assertEquals(1234, row.distanceKm)
        assertFalse(row.odd)
        assertEquals("UNITED STATES", row.country)
    }

    @Test
    fun ft8StatusIsTheAppsOwnState() {
        val statusLine = """ft8 {"open":1,"proto":2,"cq":1,"tx":0,"auto":1,"hold":0,"sending":0,"txf":1800,"next":"CQ","in":12345,"slot":15000,"rep":0,"qso":0,"hound":0,"nocall":0,"logged":"","logged_s":0}"""
        val s = (PanelProtocol.parseLine(statusLine) as PanelEvent.Ft8).status
        assertTrue(s.open)
        assertEquals(2, s.protocol)
        assertTrue(s.callingCq)
        assertFalse(s.transmitting)
        assertTrue(s.autoOn)
        assertEquals(1800, s.txFreqHz)
        assertEquals("CQ", s.nextMessage)
        assertEquals(12_345, s.inMs)
        assertFalse(s.inQso)
        assertEquals("", s.logged)
    }

    @Test
    fun cwTextArrivesQuotedAndLogReceiptsComeBothWays() {
        // "cw "...""": the text itself is JSON-encoded, so a quote inside it arrives escaped.
        assertEquals(PanelEvent.Cw("CQ CQ KD0CQ"), PanelProtocol.parseLine("""cw "CQ CQ KD0CQ""""))
        assertEquals(PanelEvent.Cw("say \"hi\""), PanelProtocol.parseLine("""cw "say \"hi\"""""))

        val withId = (PanelProtocol.parseLine("logged 12345 ok") as PanelEvent.Logged).receipt
        assertEquals(12345L, withId.id)
        assertTrue(withId.logged)

        val plain = (PanelProtocol.parseLine("logged no") as PanelEvent.Logged).receipt
        assertNull(plain.id)
        assertFalse(plain.logged)

        assertTrue((PanelProtocol.parseLine("logged 9 pending") as PanelEvent.Logged).receipt.stillPending)
        assertTrue((PanelProtocol.parseLine("logged 9 conflict") as PanelEvent.Logged).receipt.let { !it.logged })
    }

    @Test
    fun sessionsListWhoIsPairedAndWhoIsOnline() {
        val sessionsLine = """sessions [{"n":0,"name":"My Phone","role":"admin","tx":1,"last":1764300000,"ip":"192.168.1.50","online":1,"me":1},{"n":1,"name":"Laptop","role":"view","tx":0,"last":1764290000,"ip":"192.168.1.61","online":0,"me":0}]"""
        val sessions = (PanelProtocol.parseLine(sessionsLine) as PanelEvent.Sessions).sessions
        assertEquals(2, sessions.size)
        assertEquals(0, sessions[0].index)
        assertEquals("My Phone", sessions[0].name)
        assertEquals("admin", sessions[0].role)
        assertTrue(sessions[0].txAllowed)
        assertEquals(1764300000L, sessions[0].lastSeenSeconds)
        assertEquals("192.168.1.50", sessions[0].ip)
        assertTrue(sessions[0].online)
        assertTrue(sessions[0].mine)
        assertFalse(sessions[1].online)
        assertFalse(sessions[1].mine)
    }

    @Test
    fun anErrorIsAnErrorAndAnyOtherWordIsNoticeable() {
        val err = PanelProtocol.parseLine("err unknown session: pair this browser again") as PanelEvent.Failure
        assertEquals("unknown session: pair this browser again", err.text)

        val notice = PanelProtocol.parseLine("pairing code shown on the radio for 2 min") as PanelEvent.Notice
        assertEquals("pairing code shown on the radio for 2 min", notice.text)

        assertTrue((PanelProtocol.parseLine("tx 1") as PanelEvent.Transmitting).on)
        assertFalse((PanelProtocol.parseLine("tx 0") as PanelEvent.Transmitting).on)

        assertNull(PanelProtocol.parseLine(""))
        assertNull(PanelProtocol.parseLine("   "))
        assertNull(PanelProtocol.parseLine("tx maybe"))
    }

    @Test
    fun ahalfWrittenLineIsRefusedRatherThanGuessedAt() {
        assertNull(PanelProtocol.parseLine("state {not json"))
        assertNull(PanelProtocol.parseLine("size 800"))
        assertNull(PanelProtocol.parseLine("t 9.8"))
        assertNull(PanelProtocol.parseLine("spots {not an array"))
    }

    /** The waterfall's frame: 16 bytes LE {u16 0xFFFF, u16 bins, u32 centre, u32 width, u32 seq}, then int8 per bin. */
    private fun spectrumBytes(bins: Int, centreHz: Int, widthHz: Int, seq: Int): ByteArray {
        val out = ByteBuffer.allocate(16 + bins).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0xFFFF.toShort())
        out.putShort(bins.toShort())
        out.putInt(centreHz)
        out.putInt(widthHz)
        out.putInt(seq)
        for (i in 0 until bins) out.put((i - bins / 2).toByte())
        return out.array()
    }

    @Test
    fun spectrumHeaderIsLittleEndianWithTheMagicFirst() {
        // 4294967295 as a signed int: the sequence is unsigned on the wire.
        val bytes = spectrumBytes(bins = 200, centreHz = 14_074_000, widthHz = 24_000, seq = -1)
        val frame = PanelProtocol.parseSpectrum(bytes)
        assertNotNull(frame)
        requireNotNull(frame)
        assertEquals(200, frame.bins)
        assertEquals(14_074_000, frame.centreHz)
        assertEquals(24_000, frame.widthHz)
        assertEquals(4_294_967_295L, frame.sequence)
        assertEquals(200, frame.db.size)
        // (-100).toByte(), not -100.toByte(): the unary minus on a Byte gives an Int, which is never
        // equal to the Byte the frame holds.
        assertEquals((-100).toByte(), frame.db[0])
        assertEquals(99.toByte(), frame.db[199])

        // Noise, and a frame short of its bins, are not a spectrum.
        assertNull(PanelProtocol.parseSpectrum(ByteArray(32)))
        assertNull(PanelProtocol.parseSpectrum(ByteArray(8)))
        val short = spectrumBytes(200, 14_000_000, 24_000, 1).copyOfRange(0, 40)
        assertNull(PanelProtocol.parseSpectrum(short))
    }

    @Test
    fun parseBinaryTellsTheTwoFrameKindsApart() {
        assertTrue(PanelProtocol.parseBinary(spectrumBytes(64, 7_074_000, 4_800, 3)) is PanelEvent.Spectrum)

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03, 0x04)
        val screen = ByteBuffer.allocate(8 + jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(800.toShort()).putShort(480.toShort()).putInt(9).put(jpeg).array()
        val event = PanelProtocol.parseBinary(screen) as PanelEvent.Screen
        assertEquals(800, event.frame.width)
        assertEquals(480, event.frame.height)
        assertEquals(9L, event.frame.sequence)
        assertArrayEquals(jpeg, event.frame.jpeg)

        // A header with nothing JPEG after it is not a screen either.
        assertNull(PanelProtocol.parseScreenFrame(ByteArray(20)))
        assertNull(PanelProtocol.parseScreenFrame(ByteArray(8)))
    }

    @Test
    fun commandsAreSpelledHowTheRadioParsesThem() {
        assertEquals("panel 1", PanelCommands.panel(on = true))
        assertEquals("panel 0", PanelCommands.panel(on = false))
        assertEquals("screen 0", PanelCommands.screen(on = false))
        assertEquals("picture balanced", PanelCommands.picture(PanelCommands.Picture.BALANCED))
        assertEquals("ping 12", PanelCommands.ping(12))
        assertEquals("pair?", PanelCommands.pairAsk())
        // sscanf("pair %5s %7s"): the name is one token, so a space in it would end it.
        assertEquals("pair 123456 My_Phone", PanelCommands.pair("123456", "My Phone"))
        assertEquals("auth 0123456789abcdef0123456789abcdef", PanelCommands.auth("0123456789abcdef0123456789abcdef"))
        assertEquals("unpair", PanelCommands.unpair())
        assertEquals("control take", PanelCommands.control(take = true))
        assertEquals("control release", PanelCommands.control(take = false))
        assertEquals("sessions", PanelCommands.sessions())
        assertEquals("revoke 2", PanelCommands.revoke(2))
        assertEquals("set freq 14074000", PanelCommands.set(PanelSettings.FREQ, 14_074_000))
        assertEquals("set pwr 100", PanelCommands.set(PanelSettings.PWR, 100))
        assertEquals("set filter -2400 200", PanelCommands.set(PanelSettings.FILTER, -2400, 200))
        assertEquals("set atu 1", PanelCommands.set(PanelSettings.ATU, 1))
        assertEquals("key PTT 1 17", PanelCommands.key("PTT", down = true, lease = "17"))
        assertEquals("key PTT 0", PanelCommands.key("PTT", down = false))
        assertEquals("knob main -1", PanelCommands.knob("main", -1))
        assertEquals("ft8 txfreq 1800", PanelCommands.ft8("txfreq", "1800"))
        assertEquals("ft8 cq", PanelCommands.ft8("cq"))
        assertEquals("log KD0CQ|599|599", PanelCommands.log("KD0CQ|599|599"))
        assertEquals("logq 7 KD0CQ|599|599", PanelCommands.logq(7, "KD0CQ|599|599"))
        assertEquals("logq? 7 KD0CQ|599|599", PanelCommands.logqQuery(7, "KD0CQ|599|599"))
    }

    @Test
    fun reconnectWaitsALittleLongerEveryTimeAndThenStopsGrowing() {
        assertEquals(500L, reconnectDelayMs(0))
        assertEquals(1000L, reconnectDelayMs(1))
        assertEquals(4000L, reconnectDelayMs(3))
        assertEquals(10_000L, reconnectDelayMs(30))
    }
}
