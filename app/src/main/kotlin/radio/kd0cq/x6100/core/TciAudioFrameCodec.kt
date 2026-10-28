// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.core

import radio.kd0cq.x6100.net.TCI_AUDIO_HEADER_BYTES
import radio.kd0cq.x6100.net.TCI_AUDIO_RATE
import radio.kd0cq.x6100.net.TciAudioFrame
import radio.kd0cq.x6100.net.TciAudioHeader
import radio.kd0cq.x6100.net.TciAudioType
import radio.kd0cq.x6100.net.TciProtocol
import radio.kd0cq.x6100.net.TciSampleType

/**
 * The TCI audio frame codec, on the layout of `audio_hdr_t` in `src/tci_server.c` and the writers and
 * readers of `src/tci_audio.c` / `tci_audio.h`: a 64-byte little-endian header
 *
 * ```
 * u32 receiver, sample_rate, format, codec, crc, length, type, channels, u32 reserved[8]
 * ```
 *
 * and the samples after it. `length` counts the samples of *all* channels (TCI's "real samples"), so
 * a frame holds `length / channels` of each; that is what `handle_tx_audio` checks a client's frame
 * against, and what `TX_CHRONO` asks with.
 *
 * The types are the server's own enum (`AT_IQ`, `AT_RX`, `AT_TX`, `AT_CHRONO`) and the sample types
 * are `tci_audio.h`'s (`TCI_INT16`, `TCI_INT24`, `TCI_INT32`, `TCI_FLOAT32`); this app asks for and
 * sends int16 mono at 24 kHz, which is the rate the firmware captures at (`AUDIO_RATE`), and answers
 * a `TX_CHRONO` of 512 samples with 512 samples of its own, as `tools/tci/tci_client_test.py` does.
 *
 * The header words themselves are [TciProtocol]'s, and the sample conversion is
 * [TciProtocol.decodeFirstChannel]: this object is the frame-shaped front of them, the part that
 * knows which frame is which and what the radio will accept, in plain JVM code the tests can drive.
 */
object TciAudioFrameCodec {

    /** What this app asks the radio for and answers it with: 24 kHz mono int16, 512 samples a frame. */
    const val SAMPLE_RATE = TCI_AUDIO_RATE
    const val CHANNELS = 1
    const val SAMPLES_PER_FRAME = 512

    /** `AUDIO_FRAMES` at 24 kHz: the radio sends one TX_CHRONO per 21.3 ms while keyed with our audio. */
    const val CHRONO_MILLIS = 21.333

    /** The largest frame `handle_tx_audio` reads: `n <= 2048`. */
    const val MAX_SAMPLES = 2048

    /** Bytes one frame holds at the rate we asked for: header + 512 int16. */
    const val FRAME_BYTES = TCI_AUDIO_HEADER_BYTES + SAMPLES_PER_FRAME * 2

    /** A received frame, header and samples together, or null when it is not a frame at all. */
    fun decode(bytes: ByteArray, offset: Int = 0): TciAudioFrame? {
        val header = TciProtocol.decodeAudioHeader(bytes, offset) ?: return null
        if (bytes.size - offset < TCI_AUDIO_HEADER_BYTES) return null
        return TciAudioFrame(header, bytes.copyOfRange(offset + TCI_AUDIO_HEADER_BYTES, bytes.size))
    }

    /** One audio frame out to the radio: the header, then its samples. */
    fun encode(header: TciAudioHeader, payload: ByteArray): ByteArray =
        TciProtocol.encodeAudioHeader(header) + payload

    fun encode(header: TciAudioHeader, samples: ShortArray): ByteArray =
        encode(header, TciProtocol.pcm16Bytes(samples))

    /** The receiver's audio, down to the mono int16 an AudioTrack wants. */
    fun samplesOf(frame: TciAudioFrame): ShortArray = TciProtocol.decodeFirstChannel(frame)

    /** The receiver's audio as little-endian int16 bytes, ready to write to a track. */
    fun pcm16Of(frame: TciAudioFrame): ByteArray = TciProtocol.pcm16Bytes(samplesOf(frame))

    fun isReceiveAudio(header: TciAudioHeader): Boolean = header.type == TciAudioType.RX

    /** The radio's pacing frame: while keyed with our audio, one per 21.3 ms, each asking for a reply. */
    fun isChrono(header: TciAudioHeader): Boolean = header.type == TciAudioType.CHRONO

    /** Samples of one channel this frame holds: TCI's `length / channels`. */
    fun samplesPerChannel(header: TciAudioHeader): Int {
        val ch = if (header.channels > 0) header.channels else 1
        return header.length / ch
    }

    /**
     * A frame the radio can play, the way `handle_tx_audio` decides it: `type` TX, a length it knows,
     * a sample type with a size, and a payload of exactly `length * bytes` (the shape this app sends)
     * or of twice that (WSJT-X's, whose first half is the audio). Anything else the firmware reads as
     * noise and unkeys on, so a frame we are unsure of is never sent.
     */
    fun isSensibleTxAudio(header: TciAudioHeader, payloadBytes: Int): Boolean {
        if (header.type != TciAudioType.TX) return false
        val n = header.length
        if (n <= 0 || n > MAX_SAMPLES) return false
        val ch = header.channels
        if (ch != 1 && ch != 2) return false
        val bytes = n * header.sampleType.bytesPerSample
        return payloadBytes == bytes || payloadBytes == 2 * bytes
    }

    /** What a `TX_CHRONO` asks for: its rate and how many samples of each channel to answer with. */
    data class ChronoRequest(val receiver: Int, val sampleRate: Int, val channels: Int, val samples: Int)

    /**
     * The pacing frame's ask, or null for a frame that is not one. The answer copies the receiver,
     * rate, type and channel count back, as the knob's client does
     * (`tools/tci/tci_client_test.py`: `struct.pack("<8I", 0, 24000, 0, 0, 0, 512, 2, 1)`).
     */
    fun chronoRequest(frame: TciAudioFrame): ChronoRequest? {
        val h = frame.header
        if (!isChrono(h)) return null
        val ch = if (h.channels > 0) h.channels else CHANNELS
        val n = samplesPerChannel(h)
        if (n <= 0 || n > MAX_SAMPLES) return null
        return ChronoRequest(h.receiver, if (h.sampleRate > 0) h.sampleRate else SAMPLE_RATE, ch, n)
    }

    /** A receiver's frame: what the radio sends after `audio_start:0;`, and the tests feed back in. */
    fun rxAudio(
        samples: ShortArray,
        receiver: Int = 0,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        sampleType: TciSampleType = TciSampleType.Int16,
    ): ByteArray = encode(
        TciAudioHeader(
            receiver = receiver,
            sampleRate = sampleRate,
            sampleType = sampleType,
            codec = 0,
            crc = 0,
            length = samples.size * channels,
            type = TciAudioType.RX,
            channels = channels,
        ),
        samples,
    )

    /** A pacing frame of the radio's: 512 samples at 24 kHz mono unless told otherwise. */
    fun chrono(
        receiver: Int = 0,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        samples: Int = SAMPLES_PER_FRAME,
    ): ByteArray = encode(
        TciAudioHeader(
            receiver = receiver,
            sampleRate = sampleRate,
            sampleType = TciSampleType.Int16,
            codec = 0,
            crc = 0,
            length = samples * channels,
            type = TciAudioType.CHRONO,
            channels = channels,
        ),
        ByteArray(0),
    )

    /**
     * Our microphone on the air: one `TX_AUDIO` frame, or null when the radio would read it as noise.
     * `length` counts every channel's sample, and the header and the payload always agree, which is
     * the one thing that makes the firmware play us instead of ending the transmission: a frame of
     * another shape makes `handle_tx_audio` unkey on the spot.
     */
    fun txAudioOrNull(
        samples: ShortArray,
        receiver: Int = 0,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        sampleType: TciSampleType = TciSampleType.Int16,
    ): ByteArray? {
        if (samples.isEmpty() || channels != 1 && channels != 2) return null
        val header = TciAudioHeader(
            receiver = receiver,
            sampleRate = sampleRate,
            sampleType = sampleType,
            codec = 0,
            crc = 0,
            length = samples.size * channels,
            type = TciAudioType.TX,
            channels = channels,
        )
        return if (isSensibleTxAudio(header, samples.size * channels * sampleType.bytesPerSample)) {
            encode(header, samples)
        } else {
            null
        }
    }

    /** The same frame, for a caller that would rather hear about it than send nothing. */
    fun txAudio(
        samples: ShortArray,
        receiver: Int = 0,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        sampleType: TciSampleType = TciSampleType.Int16,
    ): ByteArray = txAudioOrNull(samples, receiver, sampleRate, channels, sampleType)
        ?: error("a TX_AUDIO frame the radio would read as noise is never sent")
}

/**
 * Samples waiting for the speaker or the radio, in whole chunks, that never blocks the thread that
 * fills it: the receiver's frames arrive on the link and are played by another thread, and the
 * microphone is read by its own while the radio's pacing frames ask on the link's. Neither may wait
 * on the other, so when the queue is full the oldest chunk goes: late audio is worse than none, and
 * for the microphone a stalled capture must not be what holds the key down.
 */
class SampleChunkQueue(private val maxSamples: Int) {

    private val items = ArrayDeque<ShortArray>()
    private val lock = Any()

    /** Samples already taken from the oldest chunk, so a partial read does not lose its tail. */
    private var head = 0
    private var droppedSamples = 0L
    private var droppedChunks = 0L

    /** Chunks held now. */
    fun size(): Int = synchronized(lock) { items.size }

    /** Samples held now, which is how a test sees the jitter buffer fill. */
    fun samples(): Int = synchronized(lock) { items.sumOf { it.size } - head }

    fun isEmpty(): Boolean = synchronized(lock) { samples() <= 0 }

    /** Samples thrown away because the queue was full: the counter the diagnostics screen shows. */
    fun dropped(): Long = synchronized(lock) { droppedSamples }

    /** How many whole chunks that was. */
    fun droppedChunks(): Long = synchronized(lock) { droppedChunks }

    /**
     * Keep [chunk] behind what is already buffered, dropping the oldest chunks until it fits: late
     * audio is worse than none, and for the microphone a stalled capture must not be what holds the
     * key down. False when even an empty queue could not hold it.
     */
    fun offer(chunk: ShortArray): Boolean = synchronized(lock) {
        if (chunk.isEmpty()) return true
        if (chunk.size > capacitySamples()) {
            droppedSamples += chunk.size
            droppedChunks++
            return false
        }
        while (samples() + chunk.size > capacitySamples() && items.isNotEmpty()) {
            val oldest = items.removeFirst()
            droppedSamples += oldest.size - head
            head = 0
            droppedChunks++
        }
        if (samples() + chunk.size > capacitySamples()) {
            droppedSamples += chunk.size
            droppedChunks++
            return false
        }
        items.addLast(chunk)
        true
    }

    /**
     * Up to [count] samples, oldest first, and fewer when the buffer ran dry. A microphone read that
     * came up short has to look like one: silence that is *known* to be silence is what lets the
     * transmit machine count what it is sending to the radio.
     *
     * The whole read is under the lock, so a fill that drops an old chunk cannot cut across it and
     * leave [head] pointing into a different chunk than the samples it copied.
     */
    fun read(count: Int): ShortArray = synchronized(lock) {
        val out = ShortArray(count)
        var taken = 0
        while (taken < count && items.isNotEmpty()) {
            val first = items.first()
            val room = first.size - head
            if (room <= 0) {
                items.removeFirst()
                head = 0
                continue
            }
            val n = minOf(count - taken, room)
            System.arraycopy(first, head, out, taken, n)
            taken += n
            head += n
            if (head >= first.size) {
                items.removeFirst()
                head = 0
            }
        }
        out.copyOf(taken)
    }

    /**
     * Exactly [count] samples, oldest first, zero-filled when the buffer ran dry: the radio expects a
     * whole frame with every pacing tick and the speaker a steady stream, so silence is the honest
     * answer for samples we do not have.
     */
    fun drain(count: Int): ShortArray {
        val got = read(count)
        return if (got.size == count) got else got.copyOf(count)
    }

    fun clear() = synchronized(lock) {
        items.clear()
        head = 0
    }

    /** The room: the queue holds at most this many samples, oldest first. */
    private fun capacitySamples(): Int = maxSamples
}
