// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.

package radio.kd0cq.x6100.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Every line here is transcribed from the reference: the greeting `send_greeting()` in
 * `src/tci_server.c` writes, the telemetry from its broadcastf()/client_textf() calls, and the
 * sample scales of `tci_encode`/`tci_decode_first` in `src/tci_audio.c`.
 */
class TciProtocolTest {

    // ---- the greeting ----

    @Test
    fun greetingOpensWithProtocolAndDevice() {
        assertEquals(TciEvent.Protocol("ExpertSDR3", "1.9"), TciProtocol.parse("protocol:ExpertSDR3,1.9;"))
        assertEquals(TciEvent.Device("OpenX6X00"), TciProtocol.parse("device:OpenX6X00;"))
    }

    @Test
    fun greetingEndsWithStartAndReady() {
        // WSJT-X reads a greeting without "start;" as a radio switched off (#17).
        assertEquals(TciEvent.RadioPower(true), TciProtocol.parse("start;"))
        assertEquals(TciEvent.RadioPower(false), TciProtocol.parse("stop;"))
        assertEquals(TciEvent.Echo("ready"), TciProtocol.parse("ready;"))
    }

    @Test
    fun modulationsListKeepsEveryMode() {
        val m = TciProtocol.parse("modulations_list:lsb,usb,digl,digu,cw,cwr,am,nfm,sam,wfm;")
        assertEquals(
            TciEvent.Modulations(listOf("lsb", "usb", "digl", "digu", "cw", "cwr", "am", "nfm", "sam", "wfm")),
            m
        )
    }

    @Test
    fun stateLinesParse() {
        assertEquals(TciEvent.Frequency(14_074_000), TciProtocol.parse("vfo:0,0,14074000;"))
        assertEquals(TciEvent.Mode("usb"), TciProtocol.parse("modulation:0,usb;"))
        assertEquals(TciEvent.Agc("auto"), TciProtocol.parse("agc_mode:0,auto;"))
        assertEquals(TciEvent.Rit(false), TciProtocol.parse("rit_enable:0,false;"))
        assertEquals(TciEvent.RitOffset(-250), TciProtocol.parse("rit_offset:0,-250;"))
        assertEquals(TciEvent.Drive(50), TciProtocol.parse("drive:0,50;"))
        assertEquals(TciEvent.Transmitting(false), TciProtocol.parse("trx:0,false;"))
    }

    @Test
    fun filterLineKeepsTheSignsTheRadioWrites() {
        // filter_line(): "flo,fhi" normally, "-fhi,-flo" when the passband sits below the carrier.
        assertEquals(TciEvent.Filter(0, 2700), TciProtocol.parse("rx_filter_band:0,0,2700;"))
        assertEquals(TciEvent.Filter(-2700, 0), TciProtocol.parse("rx_filter_band:0,-2700,0;"))
    }

    @Test
    fun smeterIsRawDbm() {
        // broadcastf("rx_smeter:0,0,%d;", meter_get_raw_db())
        val s = TciProtocol.parse("rx_smeter:0,0,-47;")
        assertEquals("rx_smeter", (s as TciEvent.Sensor).name)
        assertEquals(listOf(-47.0), s.values)
    }

    @Test
    fun txSensorsKeepTheFirmwareArgumentOrder() {
        // tx_sensors:0, ALC floor dB, forward watts, forward watts again, SWR, ALC.
        val s = TciProtocol.parse("tx_sensors:0,-20.0,12.7,12.7,1.75,4.5;") as TciEvent.Sensor
        assertEquals(listOf(-20.0, 12.7, 12.7, 1.75, 4.5), s.values)
    }

    @Test
    fun audioFormatLinesComeOutAsSettingsUntilFolded() {
        assertEquals(
            TciEvent.AudioSetting("audio_samplerate", "24000"),
            TciProtocol.parse("audio_samplerate:24000;")
        )
        assertEquals(
            TciEvent.AudioSetting("audio_stream_sample_type", "int16"),
            TciProtocol.parse("audio_stream_sample_type:int16;")
        )
    }

    @Test
    fun emptyLinesDropAndUnknownOnesStayVisible() {
        assertNull(TciProtocol.parse(""))
        assertNull(TciProtocol.parse("   ;"))
        assertEquals(TciEvent.Echo("trx_count"), TciProtocol.parse("trx_count:1;"))
    }

    // ---- commands ----

    @Test
    fun stateCommandsAreSpelledAsTheServerParsesThem() {
        // tci_handle_cmd: vfo, modulation, rx_filter_band, agc_mode, rit_enable, rit_offset, drive, trx.
        assertEquals("vfo:0,0,14074000;", TciCommands.frequency(14_074_000))
        assertEquals("modulation:0,usb;", TciCommands.mode("usb"))
        assertEquals("rx_filter_band:0,-2700,0;", TciCommands.filter(-2700, 0))
        assertEquals("agc_mode:0,fast;", TciCommands.agc("fast"))
        assertEquals("rit_enable:0,true;", TciCommands.rit(true))
        assertEquals("rit_offset:0,-250;", TciCommands.ritOffset(-250))
        assertEquals("drive:0,50;", TciCommands.drive(50))
        assertEquals("drive:0,100;", TciCommands.drive(150)) // clamped: the server reads a percent
    }

    @Test
    fun keyAndUnkeyFollowTheLeaseRules() {
        // "trx:0,true;" holds the PTT; ",tci" lets TCI audio time the key; unkeying our own
        // transmission is never refused, keying while another holds control is echoed false.
        assertEquals("trx:0,true;", TciCommands.key())
        assertEquals("trx:0,true,tci;", TciCommands.key(withAudio = true))
        assertEquals("trx:0,false;", TciCommands.unkey())
        assertEquals(TciEvent.Transmitting(false), TciProtocol.parse(TciCommands.unkey()))
    }

    @Test
    fun audioFormatCommandIsFourLinesTheServerSplitsOnSemicolons() {
        assertEquals(
            "audio_samplerate:24000;audio_stream_sample_type:int16;audio_stream_channels:1;audio_stream_samples:512;",
            TciCommands.audioFormat(24_000, TciSampleType.Int16, 1, 512)
        )
        assertEquals("audio_start:0;", TciCommands.audioStart())
        assertEquals("audio_stop:0;", TciCommands.audioStop())
    }

    @Test
    fun sensorsAndKeepaliveMatchTheHandlers() {
        // rx_sensors_enable and tx_sensors_enable are the only names the sensor handler knows;
        // each send also holds the client's 60 s idle timer open.
        assertEquals(
            "rx_sensors_enable:true;tx_sensors_enable:true;",
            TciCommands.sensors(on = true)
        )
        assertEquals("rx_sensors_enable:true;", TciCommands.keepalive())
    }

    @Test
    fun everyCommandRoundTripsThroughTheParser() {
        assertEquals(TciEvent.Frequency(7_074_000), TciProtocol.parse(TciCommands.frequency(7_074_000)))
        assertEquals(TciEvent.Mode("cw"), TciProtocol.parse(TciCommands.mode("cw")))
        assertEquals(TciEvent.Filter(300, 2400), TciProtocol.parse(TciCommands.filter(300, 2400)))
        assertEquals(TciEvent.Drive(35), TciProtocol.parse(TciCommands.drive(35)))
        assertEquals(TciEvent.Transmitting(true), TciProtocol.parse(TciCommands.key()))
    }

    // ---- binary audio ----

    @Test
    fun audioHeaderMatchesAudioHdrTLayout() {
        val header = TciAudioHeader(
            receiver = 0,
            sampleRate = 24_000,
            sampleType = TciSampleType.Int24,
            codec = 0,
            crc = 0,
            length = 1024,
            type = TciAudioType.RX,
            channels = 2,
        )
        val bytes = TciProtocol.encodeAudioHeader(header)
        assertEquals(64, bytes.size) // sizeof(audio_hdr_t): 16 little-endian uint32s
        assertEquals(header, TciProtocol.decodeAudioHeader(bytes))

        // uint32_t receiver, sample_rate, format, codec, crc, length, type, channels, reserved[8]
        assertEquals(0, words(bytes)[0])
        assertEquals(24_000, words(bytes)[1])
        assertEquals(TciSampleType.Int24.code, words(bytes)[2])
        assertEquals(1024, words(bytes)[5])
        assertEquals(TciAudioType.RX.code, words(bytes)[6])
        assertEquals(2, words(bytes)[7])
        for (i in 8 until 16) assertEquals("reserved[$i]", 0, words(bytes)[i])
    }

    @Test
    fun lengthCountsEveryChannelSoFramesDivide() {
        val header = TciAudioHeader(0, 24_000, TciSampleType.Int16, 0, 0, 1024, TciAudioType.RX, 2)
        assertEquals(512, header.frames)
    }

    @Test
    fun chronoFramesAreHeaderOnly() {
        // client_chrono() writes just the header: the pace IS the message, every
        // samples-per-frame at the stream rate (512 at 24 kHz = 21.3 ms).
        val raw = TciProtocol.encodeAudioHeader(
            TciAudioHeader(0, 24_000, TciSampleType.Int16, 0, 0, 512, TciAudioType.CHRONO, 1)
        )
        val frame = TciProtocol.decodeAudioHeader(raw)!!
        assertEquals(TciAudioType.CHRONO, frame.type)
        assertEquals(21.3, 512 * 1000.0 / 24_000, 0.1)
    }

    @Test
    fun shortFramesAreNotAudio() {
        assertNull(TciProtocol.decodeAudioHeader(ByteArray(63)))
        assertNull(TciProtocol.decodeAudioHeader(ByteArray(0)))
    }

    @Test
    fun encodeAudioFramePutsSamplesAfterTheHeader() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val frame = TciProtocol.encodeAudioFrame(0, 24_000, TciSampleType.Int16, TciAudioType.TX, 1, 2, payload)
        assertEquals(68, frame.size)
        val header = TciProtocol.decodeAudioHeader(frame)!!
        assertEquals(TciAudioType.TX, header.type)
        assertEquals(2, header.length)
        assertEquals(payload.toList(), frame.copyOfRange(64, 68).toList())
    }

    // ---- sample scales from tci_audio.c ----

    @Test
    fun int16FirstChannelStripsTheOthers() {
        // two int16 channels: (5, 9) then (-2, 7)
        val frame = frame(TciSampleType.Int16, channels = 2, bytes(
            5, 0, 9, 0,
            0xFE, 0xFF, 7, 0,
        ))
        assertEquals(listOf(5, -2), TciProtocol.decodeFirstChannel(frame).map { it.toInt() })
    }

    @Test
    fun int24KeepsItsTopSixteenBits() {
        // tci_decode_first: out = p[1] | p[2] << 8 — the low byte is dropped, the sign stays.
        assertEquals(listOf(-32768), mono(TciSampleType.Int24, 0x00, 0x00, 0x80.toInt()))
        assertEquals(listOf(32767), mono(TciSampleType.Int24, 0xFF, 0xFF, 0x7F))
    }

    @Test
    fun int32KeepsItsTopSixteenBits() {
        // tci_decode_first: out = p[2] | p[3] << 8
        assertEquals(listOf(-32768), mono(TciSampleType.Int32, 0, 0, 0, 0x80.toInt()))
        assertEquals(listOf(32767), mono(TciSampleType.Int32, 0, 0, 0xFF, 0x7F))
    }

    @Test
    fun float32ScalesToTheInt16RangeAndClamps() {
        // tci_decode_first clamps to ±1.0 and multiplies by 32767.
        assertEquals(listOf(32767), mono(TciSampleType.Float32, *floatBytes(1.0f)))
        assertEquals(listOf(-32768), mono(TciSampleType.Float32, *floatBytes(-1.5f)))
        assertEquals(listOf(0), mono(TciSampleType.Float32, *floatBytes(Float.NaN)))
    }

    @Test
    fun pcm16BytesIsLittleEndian() {
        assertEquals(listOf<Byte>(5, 0, 0xFE.toByte(), 0xFF.toByte()), TciProtocol.pcm16Bytes(shortArrayOf(5, -2)).toList())
    }

    @Test
    fun sampleTypesMatchTciAudio() {
        // tci_audio.c: type_names {"int16","int24","int32","float32"} and tci_sample_bytes.
        assertEquals(listOf("int16", "int24", "int32", "float32"), TciSampleType.entries.map { it.wireName })
        assertEquals(listOf(2, 3, 4, 4), TciSampleType.entries.map { it.bytesPerSample })
        assertEquals(TciSampleType.Float32, TciSampleType.fromName("float32"))
        assertEquals(TciSampleType.Int16, TciSampleType.fromCode(99)) // unknown: read as int16
        assertEquals(TciAudioType.CHRONO, TciAudioType.fromCode(3))
    }

    // ---- helpers ----

    private fun frame(type: TciSampleType, channels: Int, payload: ByteArray) = TciAudioFrame(
        TciAudioHeader(0, 24_000, type, 0, 0, payload.size / type.bytesPerSample, TciAudioType.RX, channels),
        payload
    )

    private fun mono(type: TciSampleType, vararg samples: Int) =
        TciProtocol.decodeFirstChannel(frame(type, 1, bytes(*samples))).map { it.toInt() }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun floatBytes(vararg values: Float): IntArray {
        val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buf.putFloat(it) }
        return IntArray(buf.capacity()) { buf.array()[it].toInt() and 0xFF }
    }

    private fun words(b: ByteArray): IntArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(16) { buf.int }
    }
}
