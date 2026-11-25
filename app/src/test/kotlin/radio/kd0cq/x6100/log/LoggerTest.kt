// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later

package radio.kd0cq.x6100.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerTest {

    @Test
    fun qrzXmlReadsACallAndASessionError() {
        val full = QrzXml.parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <QRZDatabase version="1.34">
              <Callsign>
                <call>KD0CQ</call>
                <fname>KD0CQ</fname>
                <name>KD0CQ</name>
                <addr2>Springfield</addr2>
                <state>MO</state>
                <grid>EM37</grid>
                <lat>37.2</lat>
                <lon>-93.3</lon>
                <country>United States</country>
                <image>https://cdn-xml.qrz.com/q/kd0cq/a.jpg</image>
              </Callsign>
              <Session>
                <Key>session-key</Key>
                <SubExp>Wed Jan 1 00:00:00 2030</SubExp>
              </Session>
            </QRZDatabase>
            """.trimIndent(),
        )
        assertEquals("session-key", full.sessionKey)
        assertTrue(full.subscribed)
        assertNull(full.error)
        // The sample used to carry a personal name. The tree keeps the callsign
        // in both QRZ name fields, and the parser still joins the two parts.
        assertEquals("KD0CQ KD0CQ", full.call?.name)
        assertEquals("Springfield, MO", full.call?.qth)
        assertEquals("EM37", full.call?.grid)
        assertEquals(37.2, full.call?.lat!!, 0.001)
        assertEquals("https://cdn-xml.qrz.com/q/kd0cq/a.jpg", full.call?.imageUrl)
        assertFalse(full.limited)

        val denied = QrzXml.parse(
            """
            <QRZDatabase>
              <Session><Error>A subscription is required</Error></Session>
              <Callsign><call>KD0CQ</call></Callsign>
            </QRZDatabase>
            """.trimIndent(),
        )
        assertEquals("A subscription is required", denied.error)
        assertEquals("KD0CQ", denied.call?.call)
        assertTrue(denied.limited)
        assertTrue(denied.call?.name.orEmpty().isEmpty())
    }

    @Test
    fun missingCredentialsIsTheErrorNotALimitedRecord() {
        val result = QrzLookup { error("should not fetch") }.lookup("", "", "KD0CQ")
        assertEquals("QRZ username and password are not set", result.error)
        assertNull(result.call)
        assertFalse(result.limited)
    }

    @Test
    fun sessionUrlCarriesThePasswordAndIsNotStoredInTheParse() {
        val url = QrzApi.sessionUrl("op", "s3cret")
        assertTrue(url.startsWith(QrzApi.ENDPOINT))
        assertTrue(url.contains("username=op"))
        assertTrue(url.contains("password=s3cret"))
        val parsed = QrzXml.parse("<QRZDatabase><Session><Key>k</Key></Session></QRZDatabase>")
        assertFalse(parsed.toString().contains("s3cret"))
    }

    @Test
    fun distanceAndBearing() {
        val home = LatLon(0.0, 0.0)
        val east = LatLon(0.0, 1.0)
        val km = Geo.distanceKm(home, east)
        assertTrue(km in 110.0..112.5)
        assertEquals(0.0, Geo.distanceKm(home, home), 0.001)
        val bearing = Geo.bearingDeg(home, east)
        assertTrue(bearing in 89.0..91.0)
        val north = Geo.bearingDeg(home, LatLon(1.0, 0.0))
        assertTrue(north < 1.0 || north > 359.0)
        val grid = Maidenhead.decode("EM37")
        assertTrue(grid != null)
        val again = Maidenhead.decode(Maidenhead.encode(grid!!))
        assertTrue(kotlin.math.abs(again!!.lat - grid.lat) < 0.05)
        assertTrue(kotlin.math.abs(again.lon - grid.lon) < 0.1)
        assertNull(Maidenhead.decode("nope"))
    }

    @Test
    fun adifAndWorkedBeforeAndTheRadioLine() {
        val qso = Qso(
            call = "kd0cq/p",
            freqHz = 14_074_000,
            mode = "USB",
            timeUtcMs = 1_763_700_000_000L,
            rstSent = "59",
            rstRcvd = "57",
            name = "KD0CQ",
            qth = "Springfield",
            grid = "em37",
            country = "United States",
            radioLogId = 42,
        )
        assertTrue(QsoBook.workedBefore("KD0CQ", listOf(qso)))
        assertTrue(QsoBook.workedBefore("kd0cq/m", listOf(qso)))
        assertFalse(QsoBook.workedBefore("W1AW", listOf(qso)))
        val line = QsoBook.radioText(qso)
        assertEquals("KD0CQ/P|59|57|KD0CQ|Springfield|EM37|United States", line)
        val adif = QsoBook.adif(listOf(qso))
        assertTrue(adif.contains("<EOH>"))
        assertTrue(adif.contains("<CALL:7>KD0CQ/P"))
        assertTrue(adif.contains("<GRIDSQUARE:4>EM37"))
        assertTrue(adif.contains("<FREQ:"))
        assertTrue(adif.contains("<EOR>"))
        assertFalse(adif.contains("password"))
    }
}
