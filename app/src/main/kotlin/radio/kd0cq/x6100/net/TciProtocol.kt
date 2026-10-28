// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.

package radio.kd0cq.x6100.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The TCI (thetis/ExpertSDR3) wire format the X6100 firmware speaks: text commands and
 * telemetry of the form "name:arg1,arg2;", and 64-byte-header binary audio frames.
 *
 * Every shape here is read from the reference implementation, `src/tci_server.c` and
 * `src/tci_audio.c` of the OpenX6X00 firmware; nothing is invented.
 */

/** Port the firmware's TCI server listens on (`remote_server.h` / avahi's `_tci._tcp`). */
const val TCI_PORT = 50001

/** The TCI audio frame header is 64 bytes, little endian: `audio_hdr_t`. */
const val TCI_AUDIO_HEADER_BYTES = 64

/** `enum { AT_IQ, AT_RX, AT_TX, AT_CHRONO }` */
enum class TciAudioType(val code: Int) {
    IQ(0),
    RX(1),
    TX(2),
    CHRONO(3),
    ;

    companion object {
        fun fromCode(code: Int): TciAudioType = entries.firstOrNull { it.code == code } ?: RX
    }
}

/** The sample type in the header's `format` word: `tci_audio.c`'s `type_names`. */
enum class TciSampleType(val code: Int, val wireName: String, val bytesPerSample: Int) {
    Int16(0, "int16", 2),
    Int24(1, "int24", 3),
    Int32(2, "int32", 4),
    Float32(3, "float32", 4),
    ;

    companion object {
        fun fromCode(code: Int): TciSampleType = entries.firstOrNull { it.code == code } ?: Int16
        fun fromName(name: String): TciSampleType? = entries.firstOrNull { it.wireName == name }
    }
}

/** `audio_hdr_t`, little endian: receiver, sample_rate, format, codec, crc, length, type, channels, reserved[8]. */
data class TciAudioHeader(
    val receiver: Int,
    val sampleRate: Int,
    val sampleType: TciSampleType,
    val codec: Int,
    val crc: Int,
    /** Samples of *all* channels together, so `length / channels` per channel. */
    val length: Int,
    val type: TciAudioType,
    val channels: Int,
) {
    val frames: Int get() = if (channels > 0) length / channels else 0
}

/** One binary TCI audio frame: header plus its samples. */
data class TciAudioFrame(val header: TciAudioHeader, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is TciAudioFrame && header == other.header && payload.contentEquals(other.payload))

    override fun hashCode(): Int = 31 * header.hashCode() + payload.contentHashCode()
}

/** What the TCI server tells us, in text. */
sealed class TciEvent {
    data class Device(val name: String) : TciEvent()
    data class Protocol(val name: String, val version: String) : TciEvent()
    data class Modulations(val names: List<String>) : TciEvent()

    /** "vfo:0,0,FREQ;" */
    data class Frequency(val hertz: Long) : TciEvent()

    /** "modulation:0,MODE;" */
    data class Mode(val name: String) : TciEvent()

    /** "rx_filter_band:0,LO,HI;", below the carrier negative in lsb/digl/cwr */
    data class Filter(val lowHz: Int, val highHz: Int) : TciEvent()

    data class Agc(val name: String) : TciEvent()
    data class Rit(val enabled: Boolean) : TciEvent()
    data class RitOffset(val hertz: Int) : TciEvent()
    data class Drive(val percent: Int) : TciEvent()

    /** "trx:0,true;" / "trx:0,false;" — on the air or not. */
    data class Transmitting(val on: Boolean) : TciEvent()
    data class AudioFormat(val sampleRate: Int, val sampleType: TciSampleType, val channels: Int, val samples: Int) : TciEvent()

    /** One of the four "audio_samplerate:24000;" style lines, before they are folded. */
    data class AudioSetting(val name: String, val value: String) : TciEvent()
    /** "rx_smeter"/"rx_channel_sensors": one S-meter reading; "tx_sensors": ALC floor in dB, forward
     *  watts twice, SWR and ALC, in the order the radio writes them, without the receiver's index. */
    data class Sensor(val name: String, val values: List<Double>) : TciEvent()

    /** "start;" / "ready;" / "stop;" */
    data class RadioPower(val on: Boolean) : TciEvent()
    data class Echo(val line: String) : TciEvent()
}

/** What the TCI server says. */
object TciProtocol {

    /** One text message, without its trailing ';'. */
    fun parse(line: String): TciEvent? {
        val body = line.trim().trimEnd(';')
        if (body.isEmpty()) return null
        val name = body.substringBefore(':').trim()
        val args = body.substringAfter(':', "").split(',').map { it.trim() }

        fun arg(i: Int): String? = args.getOrNull(i)?.takeIf { it.isNotEmpty() }
        fun int(i: Int): Long? = arg(i)?.toLongOrNull()
        fun bool(s: String?): Boolean? = when (s?.lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> null
        }
        fun num(s: String?): Double? = s?.toDoubleOrNull()

        return when (name) {
            "protocol" -> TciEvent.Protocol(arg(0) ?: "", arg(1) ?: "")
            "device" -> TciEvent.Device(arg(0) ?: "")
            "modulations_list" -> TciEvent.Modulations(args.filter { it.isNotEmpty() })
            "vfo" -> TciEvent.Frequency(int(2) ?: int(0) ?: 0L)
            "modulation" -> TciEvent.Mode(arg(1) ?: arg(0) ?: "")
            "rx_filter_band" -> TciEvent.Filter((int(1) ?: int(0) ?: 0L).toInt(), (int(2) ?: int(1) ?: 0L).toInt())
            "agc_mode" -> TciEvent.Agc(arg(1) ?: arg(0) ?: "")
            "rit_enable" -> TciEvent.Rit(bool(arg(1)) ?: bool(arg(0)) ?: false)
            "rit_offset" -> TciEvent.RitOffset((int(1) ?: int(0) ?: 0L).toInt())
            "drive" -> TciEvent.Drive((int(1) ?: int(0) ?: 0L).toInt())
            "trx" -> TciEvent.Transmitting(bool(arg(1)) ?: bool(arg(0)) ?: false)
            "rx_smeter", "rx_channel_sensors" -> TciEvent.Sensor(name, listOf(num(arg(2)) ?: num(arg(1)) ?: num(arg(0)) ?: 0.0))
            "tx_sensors" -> TciEvent.Sensor(name, args.drop(1).mapNotNull(::num))
            // The four arrive one at a time; OkHttpTciClient folds them into one AudioFormat.
            "audio_samplerate", "audio_stream_sample_type", "audio_stream_channels", "audio_stream_samples" ->
                TciEvent.AudioSetting(name, arg(0) ?: "")

            "start" -> TciEvent.RadioPower(true)
            "stop" -> TciEvent.RadioPower(false)
            "ready" -> TciEvent.Echo(name)
            else -> TciEvent.Echo(name)
        }
    }

    /** `audio_hdr_t` and the samples after it, ready for a binary WebSocket frame. */
    fun encodeAudioFrame(
        receiver: Int,
        sampleRate: Int,
        sampleType: TciSampleType,
        type: TciAudioType,
        channels: Int,
        samples: Int,
        payload: ByteArray,
    ): ByteArray {
        val header = encodeAudioHeader(
            TciAudioHeader(receiver, sampleRate, sampleType, codec = 0, crc = 0, length = samples, type, channels)
        )
        return header + payload
    }

    fun encodeAudioHeader(h: TciAudioHeader): ByteArray {
        require(h.channels > 0) { "TCI audio says nothing with no channels" }
        val out = ByteBuffer.allocate(TCI_AUDIO_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(h.receiver)
        out.putInt(h.sampleRate)
        out.putInt(h.sampleType.code)
        out.putInt(h.codec)
        out.putInt(h.crc)
        out.putInt(h.length)
        out.putInt(h.type.code)
        out.putInt(h.channels)
        repeat(8) { out.putInt(0) }
        return out.array()
    }

    /** The 64-byte header of a received frame, or null when the frame is short of one. */
    fun decodeAudioHeader(bytes: ByteArray, offset: Int = 0): TciAudioHeader? {
        if (bytes.size - offset < TCI_AUDIO_HEADER_BYTES) return null
        val buf = ByteBuffer.wrap(bytes, offset, TCI_AUDIO_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val receiver = buf.int
        val sampleRate = buf.int
        val sampleType = TciSampleType.fromCode(buf.int)
        val codec = buf.int
        val crc = buf.int
        val length = buf.int
        val type = TciAudioType.fromCode(buf.int)
        val channels = buf.int
        return TciAudioHeader(receiver, sampleRate, sampleType, codec, crc, length, type, channels)
    }

    /** A frame's samples, down to mono int16 at 24 kHz; what AudioPipeline wants to hear. */
    fun decodeFirstChannel(frame: TciAudioFrame): ShortArray {
        val h = frame.header
        val ch = if (h.channels > 0) h.channels else 1
        val bytes = h.sampleType.bytesPerSample
        val frames = frame.payload.size / (bytes * ch)
        val out = ShortArray(frames)
        val buf = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            out[i] = when (h.sampleType) {
                // Only the first channel of each frame; the X6100's stream is mono anyway.
                TciSampleType.Int16 -> {
                    val v = buf.short
                    repeat(ch - 1) { buf.short }
                    v
                }

                // 31-bit scale: the top 16 bits are the int16 the radio means.
                TciSampleType.Int32 -> {
                    val v = (buf.int shr 16).toShort()
                    repeat(ch - 1) { buf.int }
                    v
                }

                // ±1.0, the radio's own float scale.
                TciSampleType.Float32 -> {
                    val v = (buf.float * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    repeat(ch - 1) { buf.float }
                    v
                }

                // 23-bit scale, three bytes little endian: drop the low byte, keep the sign.
                TciSampleType.Int24 -> {
                    val v = readInt24(buf)
                    repeat(ch - 1) { repeat(3) { buf.get() } }
                    v
                }
            }
        }
        return out
    }

    /** One 24-bit little-endian sample on the radio's 2^23 scale, moved down to int16's. */
    private fun readInt24(buf: ByteBuffer): Short {
        val lo = buf.get().toInt() and 0xFF
        val mid = buf.get().toInt() and 0xFF
        val hi = buf.get().toInt()          // signed: the top byte carries the sign
        return (((hi shl 16) or (mid shl 8) or lo) shr 8).toShort()
    }

    /** int16 mono samples as the little-endian bytes TCI carries. */
    fun pcm16Bytes(samples: ShortArray): ByteArray {
        val out = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { out.putShort(it) }
        return out.array()
    }
}

/** Commands to the TCI server, spelled as `tci_server.c` parses them. */
object TciCommands {

    /** "vfo:0,0,FREQ;" — the VFO in Hz. */
    fun frequency(hertz: Long) = "vfo:0,0,$hertz;"

    /** "modulation:0,MODE;" with the name from the greeting's modulations_list. */
    fun mode(name: String) = "modulation:0,$name;"

    /** "rx_filter_band:0,LO,HI;" in Hz from the carrier, below it negative. */
    fun filter(lowHz: Int, highHz: Int) = "rx_filter_band:0,$lowHz,$highHz;"

    /** "agc_mode:0,NAME;" — off, slow, fast, auto (med and medium read as auto). */
    fun agc(name: String) = "agc_mode:0,$name;"

    fun rit(enabled: Boolean) = "rit_enable:0,${if (enabled) "true" else "false"};"
    fun ritOffset(hertz: Int) = "rit_offset:0,$hertz;"

    /** "drive:0,N;" — drive, 0..100. */
    fun drive(percent: Int) = "drive:0,${percent.coerceIn(0, 100)};"

    /** "trx:0,true;" keys with the radio's own PTT; with `withAudio` the key is held by TCI audio. */
    fun key(withAudio: Boolean = false) = if (withAudio) "trx:0,true,tci;" else "trx:0,true;"

    /** Ending our own transmission is never refused by the server, whatever happened to control. */
    fun unkey() = "trx:0,false;"

    /** The audio format we want downlink in, four separate lines; the server echoes each. */
    fun audioFormat(sampleRate: Int, sampleType: TciSampleType, channels: Int, samplesPerFrame: Int): String =
        "audio_samplerate:$sampleRate;audio_stream_sample_type:${sampleType.wireName};" +
            "audio_stream_channels:$channels;audio_stream_samples:$samplesPerFrame;"

    fun audioStart() = "audio_start:0;"
    fun audioStop() = "audio_stop:0;"

    /** Sensors are off until asked for; each line the client sends also holds the 60 s idle timer. */
    fun sensors(on: Boolean) = "rx_sensors_enable:${if (on) "true" else "false"};tx_sensors_enable:${if (on) "true" else "false"};"

    fun keepalive() = "rx_sensors_enable:true;"

    /**
     * The server opens the conversation, not the client: `send_greeting()` answers a new connection
     * with `protocol:`, `device:`, `receive_only:`, `trx_count:`, `channels_count:`, `vfo_limits:`,
     * `if_limits:`, `modulations_list:`, the four audio lines, the whole radio state and `start;`,
     * `ready;`. A client that says nothing is a client that hears nothing, so `rx_enable` is the one
     * line worth sending: it is the state the server answers with, and it keeps the socket busy.
     */
    fun hello() = "rx_enable:0,true;"
}
