// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.

package radio.kd0cq.x6100.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import radio.kd0cq.x6100.net.TCI_AUDIO_HEADER_BYTES
import radio.kd0cq.x6100.net.TciAudioHeader
import radio.kd0cq.x6100.net.TciAudioType
import radio.kd0cq.x6100.net.TciProtocol
import radio.kd0cq.x6100.net.TciSampleType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The audio frame, byte for byte, as `src/tci_server.c` writes and reads it: a 64-byte little-endian
 * header of eight words and the samples after them, with `length` counting the samples of every
 * channel together. `handle_tx_audio` is the reason this is tested and not trusted: it takes a frame
 * of another shape as a reason to unkey, so a byte in the wrong word is a transmission that stops.
 *
 * The word numbers below are written out here and not taken from the code under test: a layout test
 * that reads its offsets from the writer proves nothing.
 */
class TciAudioFrameCodecTest {

    /** `audio_hdr_t`'s fields, in words: receiver, sample_rate, format, codec, crc, length, type, channels. */
    private object Words {
        const val RECEIVER = 0
        const val SAMPLE_RATE = 1
        const val FORMAT = 2
        const val CODEC = 3
        const val CRC = 4
        const val LENGTH = 5
        const val TYPE = 6
        const val CHANNELS = 7
        const val RESERVED_FROM = 8
    }

    private fun voice(count: Int, first: Short = 1): ShortArray =
        ShortArray(count) { (first + it).toShort() }

    /** A header of our own making, for the tests that care about the words and not the samples. */
    private fun header(
        length: Int,
        type: TciAudioType = TciAudioType.TX,
        channels: Int = 1,
        sampleType: TciSampleType = TciSampleType.Int16,
    ) = TciAudioHeader(
        receiver = 0,
        sampleRate = TciAudioFrameCodec.SAMPLE_RATE,
        sampleType = sampleType,
        codec = 0,
        crc = 0,
        length = length,
        type = type,
        channels = channels,
    )

    // ---- the header's bytes ----

    @Test
    fun aFrameWeEncodeIsTheFrameWeDecode() {
        val samples = voice(512, first = -3)
        val back = TciAudioFrameCodec.decode(TciAudioFrameCodec.txAudio(samples))
        assertNotNull(back)
        assertEquals(TciAudioType.TX, back!!.header.type)
        assertEquals(TciSampleType.Int16, back.header.sampleType)
        assertEquals(512, back.header.length)
        assertEquals(1, back.header.channels)
        assertEquals(TciAudioFrameCodec.SAMPLE_RATE, back.header.sampleRate)
        assertEquals(samples.toList(), TciProtocol.decodeFirstChannel(back).toList())
    }

    @Test
    fun theHeaderIsTheFirmwaresEightWordsInOrder() {
        // audio_hdr_t: receiver, sample_rate, format, codec, crc, length, type, channels, then
        // reserved[8]. These are the offsets a C reader of the firmware uses; a field that moves is a
        // radio that reads our length as its type.
        val frame = TciAudioFrameCodec.decode(TciAudioFrameCodec.txAudio(voice(512)))!!
        val bytes = TciAudioFrameCodec.encode(frame.header, frame.payload)
        val words = ByteBuffer.wrap(bytes, 0, TCI_AUDIO_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val word = IntArray(16) { words.int }
        assertEquals(0, word[Words.RECEIVER])
        assertEquals(24_000, word[Words.SAMPLE_RATE])
        assertEquals(TciSampleType.Int16.code, word[Words.FORMAT])
        assertEquals(0, word[Words.CODEC])
        assertEquals(0, word[Words.CRC])
        assertEquals(512, word[Words.LENGTH])
        assertEquals(TciAudioType.TX.code, word[Words.TYPE])
        assertEquals(1, word[Words.CHANNELS])
        assertEquals("the eight reserved words stay zero", 0, word.drop(Words.RESERVED_FROM).sum())
    }

    @Test
    fun lengthCountsTheSamplesOfEveryChannel() {
        // TCI's "real samples": stereo 256 a channel is a length of 512, which is what the firmware
        // divides by `channels` to find where the samples end.
        val left = voice(256)
        val interleaved = ShortArray(512) { if (it % 2 == 0) left[it / 2] else Short.MAX_VALUE }
        val frame = TciAudioFrameCodec.decode(
            TciAudioFrameCodec.encode(header(length = 512, channels = 2), interleaved)
        )!!
        assertEquals(512, frame.header.length)
        assertEquals(256, TciAudioFrameCodec.samplesPerChannel(frame.header))
        // And the first channel is what comes out, which is the only one this app plays.
        assertEquals(left.toList(), TciProtocol.decodeFirstChannel(frame).toList())
    }

    @Test
    fun bytesShorterThanAHeaderAreNotAFrame() {
        assertNull(TciAudioFrameCodec.decode(ByteArray(TCI_AUDIO_HEADER_BYTES - 1)))
        assertNull(TciAudioFrameCodec.decode(ByteArray(0)))
        // A frame at an offset is read from the offset: 64 bytes of anything before it are not enough,
        // and a WebSocket binary message starts with its own header anyway.
        val withPrefix = ByteArray(8) + TciAudioFrameCodec.chrono() + ByteArray(8)
        assertNotNull(TciAudioFrameCodec.decode(withPrefix, offset = 8))
        assertNull(TciAudioFrameCodec.decode(withPrefix, offset = withPrefix.size - 8))
    }

    @Test
    fun aHeaderOfNothingAtAllIsNotSomethingWeAnswer() {
        // All zero words: sample_rate 0, length 0, type AT_IQ, channels 512. The firmware's own readers
        // would make nothing of it either, and the point here is that no part of this app answers it.
        val frame = TciAudioFrameCodec.decode(ByteArray(TCI_AUDIO_HEADER_BYTES))!!
        assertNull(TciAudioFrameCodec.chronoRequest(frame))
        assertFalse(TciAudioFrameCodec.isReceiveAudio(frame.header))
        assertFalse(TciAudioFrameCodec.isSensibleTxAudio(frame.header, 0))
        assertEquals(0, TciAudioFrameCodec.samplesOf(frame).size)
    }

    @Test
    fun aTypeWordWeDoNotKnowReadsAsReceiverAudio() {
        // TciAudioType.fromCode falls back to RX. That is the safe way to be unsure: an unidentified
        // frame is played, never answered, so a firmware that grows a fourth type cannot make this app
        // talk back on a frame that was never meant to be answered.
        val bytes = TciAudioFrameCodec.chrono()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(Words.TYPE * 4, 99)
        val frame = TciAudioFrameCodec.decode(bytes)!!
        assertEquals(TciAudioType.RX, frame.header.type)
        assertNull(TciAudioFrameCodec.chronoRequest(frame))
    }

    // ---- the pacing frame ----

    @Test
    fun aPacingFrameAsksForTheSamplesItCarries() {
        val ask = TciAudioFrameCodec.chronoRequest(TciAudioFrameCodec.decode(TciAudioFrameCodec.chrono())!!)!!
        assertEquals(512, ask.samples)
        assertEquals(TciAudioFrameCodec.SAMPLE_RATE, ask.sampleRate)
        assertEquals(1, ask.channels)
        assertEquals(0, ask.receiver)
    }

    @Test
    fun aPacingFrameOfAnotherRateIsAnsweredAtThatRate() {
        val ask = TciAudioFrameCodec.chronoRequest(
            TciAudioFrameCodec.decode(TciAudioFrameCodec.chrono(receiver = 2, sampleRate = 48_000, samples = 256))!!
        )!!
        assertEquals(256, ask.samples)
        assertEquals(48_000, ask.sampleRate)
        assertEquals(2, ask.receiver)
    }

    @Test
    fun thePacingFramesAskIsBoundedTheWayTheFirmwaresIs() {
        // handle_tx_audio: n must be a length the radio can have asked for; nothing beyond 2048 is answered.
        assertNull(TciAudioFrameCodec.chronoRequest(TciAudioFrameCodec.decode(TciAudioFrameCodec.chrono(samples = 0))!!))
        assertNull(
            TciAudioFrameCodec.chronoRequest(
                TciAudioFrameCodec.decode(TciAudioFrameCodec.chrono(samples = TciAudioFrameCodec.MAX_SAMPLES + 1))!!
            )
        )
        assertNotNull(
            TciAudioFrameCodec.chronoRequest(
                TciAudioFrameCodec.decode(TciAudioFrameCodec.chrono(samples = TciAudioFrameCodec.MAX_SAMPLES))!!
            )
        )
    }

    @Test
    fun onlyAPacingFrameIsARequest() {
        val rx = TciAudioFrameCodec.decode(TciAudioFrameCodec.rxAudio(voice(512)))!!
        assertNull(TciAudioFrameCodec.chronoRequest(rx))
        assertTrue(TciAudioFrameCodec.isReceiveAudio(rx.header))
        assertFalse(TciAudioFrameCodec.isChrono(rx.header))
        val tx = TciAudioFrameCodec.decode(TciAudioFrameCodec.txAudio(voice(512)))!!
        assertFalse(TciAudioFrameCodec.isReceiveAudio(tx.header))
        assertNull(TciAudioFrameCodec.chronoRequest(tx))
    }

    @Test
    fun aPacingFrameIsSixtyFourBytesWithNothingAfterIt() {
        assertEquals(TCI_AUDIO_HEADER_BYTES, TciAudioFrameCodec.chrono().size)
        assertEquals(0, TciAudioFrameCodec.decode(TciAudioFrameCodec.chrono())!!.payload.size)
    }

    @Test
    fun aFrameOfAudioIsTheHeaderAndTheSamples() {
        assertEquals(
            TCI_AUDIO_HEADER_BYTES + TciAudioFrameCodec.SAMPLES_PER_FRAME * 2,
            TciAudioFrameCodec.FRAME_BYTES,
        )
        assertEquals(1024, TciAudioFrameCodec.decode(TciAudioFrameCodec.rxAudio(voice(512)))!!.payload.size)
    }

    // ---- what the radio will accept ----

    @Test
    fun theShapeWeSendIsTheShapeTheFirmwareReads() {
        val samples = voice(512)
        val frame = TciAudioFrameCodec.decode(TciAudioFrameCodec.txAudio(samples))!!
        assertTrue(TciAudioFrameCodec.isSensibleTxAudio(frame.header, frame.payload.size))
        // And WSJT-X's shape, whose payload carries the audio twice, is a frame too.
        assertTrue(TciAudioFrameCodec.isSensibleTxAudio(frame.header, frame.payload.size * 2))
    }

    @Test
    fun aFrameTheRadioWouldReadAsNoiseIsRefused() {
        val bytes = 512 * 2
        assertFalse(TciAudioFrameCodec.isSensibleTxAudio(header(512, type = TciAudioType.RX), bytes))
        assertFalse(TciAudioFrameCodec.isSensibleTxAudio(header(512, type = TciAudioType.CHRONO), bytes))
        assertFalse(TciAudioFrameCodec.isSensibleTxAudio(header(0), bytes)) // an empty frame
        assertFalse(TciAudioFrameCodec.isSensibleTxAudio(header(-1), bytes))
        assertFalse(TciAudioFrameCodec.isSensibleTxAudio(header(TciAudioFrameCodec.MAX_SAMPLES + 1), bytes))
        assertFalse(TciAudioFrameCodec.isSensibleTxAudio(header(512, channels = 3), bytes)) // no such stream
        assertFalse(TciAudioFrameCodec.isSensibleTxAudio(header(512), bytes - 2)) // a payload cut short
        assertFalse(TciAudioFrameCodec.isSensibleTxAudio(header(512), 3 * bytes)) // and not one padded out
    }

    @Test
    fun theLengthAGreaterThanTheRadioKnowsIsNeverBuilt() {
        assertNull(TciAudioFrameCodec.txAudioOrNull(voice(TciAudioFrameCodec.MAX_SAMPLES + 1)))
        assertNull(TciAudioFrameCodec.txAudioOrNull(ShortArray(0)))
        assertNull(TciAudioFrameCodec.txAudioOrNull(voice(512), channels = 4))
        // The loudest answer is the one the radio asked for, and it is still a frame it can read.
        assertNotNull(TciAudioFrameCodec.txAudioOrNull(voice(TciAudioFrameCodec.MAX_SAMPLES)))
    }

    @Test
    fun theVersionThatThrowsIsForACallerThatWouldRatherNotSendNothing() {
        assertThrows(IllegalStateException::class.java) { TciAudioFrameCodec.txAudio(ShortArray(0)) }
        assertNull(TciAudioFrameCodec.txAudioOrNull(ShortArray(0)))
    }

    @Test
    fun everyFrameTheTransmitMachineBuildsPassesTheRadiosTest() {
        // The guard in TciTxStateMachine is this one, so the frames it sends are the frames this says
        // are readable — checked across the lengths the radio's pacing has ever asked for.
        for (n in intArrayOf(1, 2, 256, 512, 1024, 2048)) {
            val frame = TciAudioFrameCodec.decode(TciAudioFrameCodec.txAudio(voice(n)))!!
            assertTrue("n=$n", TciAudioFrameCodec.isSensibleTxAudio(frame.header, frame.payload.size))
            assertEquals(n, TciProtocol.decodeFirstChannel(frame).size)
        }
    }

    // ---- with the skeleton's codec ----

    @Test
    fun theFrameIsTheProtocolsHeaderWithItsSamplesAfter() {
        // One codec, two shapes: this object adds which frame is which, and never repacks a header of
        // its own, so the app cannot hold two ideas of the same 64 bytes.
        val h = header(512, type = TciAudioType.RX)
        val payload = TciProtocol.pcm16Bytes(voice(512))
        assertEquals(
            (TciProtocol.encodeAudioHeader(h) + payload).toList(),
            TciAudioFrameCodec.encode(h, payload).toList(),
        )
        assertEquals(TciProtocol.decodeAudioHeader(TciAudioFrameCodec.chrono()), TciAudioFrameCodec.decode(TciAudioFrameCodec.chrono())?.header)
    }

    @Test
    fun theReceiverFrameGivesTheSpeakerWhatTheRadioSent() {
        val samples = voice(512, first = -100)
        val frame = TciAudioFrameCodec.decode(TciAudioFrameCodec.rxAudio(samples))!!
        assertEquals(samples.toList(), TciAudioFrameCodec.samplesOf(frame).toList())
        assertEquals(
            TciProtocol.pcm16Bytes(samples).toList(),
            TciAudioFrameCodec.pcm16Of(frame).toList(),
        )
    }

    // ---- the queue between the two threads ----

    @Test
    fun chunksComeBackInOrderAndAPartialReadKeepsTheTail() {
        val q = SampleChunkQueue(4_096)
        assertTrue(q.offer(voice(512, first = 1)))
        assertTrue(q.offer(voice(512, first = 1000)))
        assertEquals(1_024, q.samples())
        assertEquals(2, q.size())
        val first = q.read(600)
        assertEquals(600, first.size)
        assertEquals(voice(512, first = 1).toList() + voice(88, first = 1000).toList(), first.toList())
        // The rest of the second chunk, and nothing invented after it.
        assertEquals(voice(424, first = 1088).toList(), q.read(600).toList())
        assertEquals(0, q.samples())
        assertTrue(q.isEmpty())
        assertEquals(0, q.read(600).size)
    }

    @Test
    fun aReadThatRunsDryIsShortAndDrainFillsItWithSilence() {
        val q = SampleChunkQueue(4_096)
        q.offer(voice(100, first = 7))
        assertEquals(100, q.read(512).size) // the microphone that has not caught up, seen as it is
        q.offer(voice(100, first = 7))
        val whole = q.drain(512)
        assertEquals(512, whole.size)
        assertEquals(voice(100, first = 7).toList(), whole.take(100))
        assertEquals(ShortArray(412).toList(), whole.drop(100))
    }

    @Test
    fun aQueueThatFillsLosesTheOldestAndSaysHowMuch() {
        val q = SampleChunkQueue(1_024)
        assertTrue(q.offer(voice(512, first = 1)))
        assertTrue(q.offer(voice(512, first = 1000)))
        assertEquals(0L, q.dropped())
        assertTrue(q.offer(voice(512, first = 2000))) // the first frame is what goes
        assertEquals(2, q.size())
        assertEquals(1_024, q.samples())
        assertEquals(512L, q.dropped())
        assertEquals(1L, q.droppedChunks())
        // And what is left is the newer audio, oldest first: the frame we threw was the one we heard longest ago.
        assertEquals(voice(512, first = 1000).toList(), q.read(512).toList())
        assertEquals(voice(512, first = 2000).toList(), q.read(512).toList())
    }

    @Test
    fun aChunkBiggerThanTheWholeQueueIsNeverKept() {
        val q = SampleChunkQueue(512)
        assertFalse(q.offer(voice(600)))
        assertTrue(q.isEmpty())
        assertEquals(600L, q.dropped())
        assertEquals(1L, q.droppedChunks())
        // The queue is still usable afterwards: a dropped chunk is not a broken queue.
        assertTrue(q.offer(voice(512)))
        assertEquals(512, q.samples())
    }

    @Test
    fun anEmptyChunkFitsAnywhereAndClearTakesTheRest() {
        val q = SampleChunkQueue(512)
        assertTrue(q.offer(ShortArray(0)))
        assertTrue(q.isEmpty())
        q.offer(voice(512))
        q.clear()
        assertTrue(q.isEmpty())
        assertEquals(0, q.size())
        // Nothing was thrown away for the sake of it: clear() is the operator stopping, not a full queue.
        assertEquals(0L, q.dropped())
    }

    @Test
    fun samplesSurviveAReadOfTheSameLengthTwice() {
        // The pacing reads the same count every 21.3 ms; the accounting must not drift, or the TX
        // machine's idea of how much microphone it has sent stops matching the radio's.
        val q = SampleChunkQueue(8_192)
        repeat(10) { q.offer(voice(512, first = (it * 512 + 1).toShort())) }
        val all = ArrayList<Short>()
        repeat(10) { all += q.drain(512).toList() }
        assertEquals(5_120, all.size)
        assertEquals(voice(512, first = 1).toList() + (1..9).flatMap { voice(512, first = (it * 512 + 1).toShort()).toList() }, all)
    }
}
