// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.

package radio.kd0cq.x6100.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import radio.kd0cq.x6100.net.TciAudioFrame
import radio.kd0cq.x6100.net.TciAudioType
import radio.kd0cq.x6100.net.TciProtocol

/**
 * Keying with this app's microphone: `trx:0,true,tci;` starts it and `trx:0,false;` ends it, and between
 * the two the radio sends one `TX_CHRONO` every 21.3 ms (`AUDIO_FRAMES` at 24 kHz) and expects one
 * `TX_AUDIO` frame back. `handle_tx_audio` in `src/tci_server.c` reads our frame, and `tx_session_tick`
 * unkeys us `TX_SILENCE_MS` (500 ms) after the last frame, or `TX_LIMIT_MS` (3 min) after the key.
 *
 * The tests ask the two questions that matter on the air: is every frame one the firmware can read, and
 * does a frame ever reach the radio after the key came off?
 */
class TciTxStateMachineTest {

    /** The microphone, as a test drives it: samples to hand out, and whether it will open at all. */
    private class FakeMic(
        var startResult: Boolean = true,
        var available: Boolean = true,
    ) : Microphone {
        val chunks = ArrayDeque<ShortArray>()
        var starts = 0
        var stops = 0
        var reads = 0
        override var running = false
            private set

        override fun start(): Boolean {
            starts++
            running = startResult && available
            return running
        }

        override fun stop() {
            stops++
            running = false
        }

        /** The capture thread stopping on its own: an error out of `AudioRecord.read`. */
        fun died() {
            available = false
            running = false
        }

        override fun read(count: Int): ShortArray {
            reads++
            if (!running) return ShortArray(0)
            val out = ShortArray(count)
            var taken = 0
            while (taken < count && chunks.isNotEmpty()) {
                val c = chunks.first()
                val n = minOf(count - taken, c.size)
                System.arraycopy(c, 0, out, taken, n)
                taken += n
                chunks.removeFirst()
            }
            return out.copyOf(taken) // what the capture actually delivered, which may be less
        }
    }

    /** The wire: text lines and binary frames, in the order they went out. */
    private class Wire {
        val lines = mutableListOf<String>()
        val frames = mutableListOf<ByteArray>()
        var failFramesFrom = Int.MAX_VALUE
        var failLinesFrom = Int.MAX_VALUE

        /** The key line is refused and `trx:0,false;` is still accepted. */
        var dropKey = false

        /** `trx:0,false;` is refused, so a test can see the retry. */
        var dropUnkey = false

        fun send(line: String): Boolean {
            if (dropKey && line.startsWith("trx:0,true")) return false
            if (dropUnkey && line == "trx:0,false;") return false
            if (lines.size + 1 >= failLinesFrom) return false
            lines += line
            return true
        }

        fun sendFrame(frame: ByteArray): Boolean {
            if (frames.size + 1 >= failFramesFrom) return false
            frames += frame
            return true
        }

        /** The lines that end a transmission, so a test can see the unkey without the key in the way. */
        fun unkeys() = lines.filter { it == "trx:0,false;" }
    }

    private val wire = Wire()
    private val mic = FakeMic()
    private val clock = ManualClock()
    private val scheduler = ManualScheduler(clock)

    private fun machine(silenceMs: Long = 500, txLimitMs: Long = 170_000, keyConfirmMs: Long = 1_000) =
        TciTxStateMachine(
            send = { wire.send(it) },
            sendFrame = { wire.sendFrame(it) },
            mic = mic,
            clock = clock,
            scheduler = scheduler,
            silenceMs = silenceMs,
            txLimitMs = txLimitMs,
            keyConfirmMs = keyConfirmMs,
        )

    /** A pacing frame of the radio's, and the samples it asks us for. */
    private fun chrono(samples: Int = TciAudioFrameCodec.SAMPLES_PER_FRAME): TciAudioFrame =
        TciAudioFrameCodec.decode(TciAudioFrameCodec.chrono(samples = samples))!!

    private fun voice(count: Int, first: Short = 1): ShortArray =
        ShortArray(count) { (first + it).toShort() }

    // ---- keying ----

    @Test
    fun aKeyWithOurAudioStartsWithTheMicrophoneAndNothingElse() {
        val m = machine()
        assertTrue(m.press())
        assertEquals(listOf("trx:0,true,tci;"), wire.lines)
        assertEquals(TciTxStateMachine.State.Starting, m.currentState)
        assertEquals(1, mic.starts)
        assertTrue(m.keyed)
    }

    @Test
    fun noKeyWithoutAMicrophoneThatReallyRuns() {
        // radio_session.js: "Microphone not ready: no transmission (the radio's own microphone is not
        // used in its place)". The radio's own mic would key a transmission the operator did not ask for.
        val refused = FakeMic(startResult = false)
        val m = TciTxStateMachine(
            send = { wire.send(it) },
            sendFrame = { wire.sendFrame(it) },
            mic = refused,
            clock = clock,
            scheduler = scheduler,
        )
        assertFalse(m.press())
        assertEquals(emptyList<String>(), wire.lines)
        assertFalse(m.keyed)
        assertEquals("the microphone is not ready: no transmission", m.lastReason)
        assertEquals(1, refused.stops) // the device is handed back even when it never opened
    }

    @Test
    fun aKeyTheSocketWouldNotTakeStopsTheMicrophoneAgain() {
        wire.failLinesFrom = 1
        val m = machine()
        assertFalse(m.press())
        assertEquals(1, mic.stops)
        assertFalse(m.keyed)
        assertEquals("the radio is not reached: no transmission", m.lastReason)
    }

    @Test
    fun aSecondKeyWhileOneRunsIsRefused() {
        val m = machine()
        m.press()
        assertFalse(m.press())
        assertEquals(listOf("trx:0,true,tci;"), wire.lines)
        assertEquals(1, mic.starts)
    }

    // ---- the pacing ----

    @Test
    fun eachPacingFrameIsAnsweredByExactlyOneFrameOfAudio() {
        val m = machine()
        m.press()
        repeat(5) {
            mic.chunks += voice(TciAudioFrameCodec.SAMPLES_PER_FRAME)
            assertTrue(m.onFrame(chrono()))
        }
        assertEquals(5, wire.frames.size)
        assertEquals(5L, m.framesSent())
        assertEquals(TciTxStateMachine.State.Keyed, m.currentState)
    }

    @Test
    fun everyFrameWeSendIsOneTheFirmwareCanRead() {
        // handle_tx_audio: AT_TX, n <= 2048, and a payload of exactly n * bytes for the shape we send.
        val m = machine()
        m.press()
        mic.chunks += voice(512)
        m.onFrame(chrono())
        val frame = TciAudioFrameCodec.decode(wire.frames.single())
        assertNotNull(frame)
        assertEquals(TciAudioType.TX, frame!!.header.type)
        assertEquals(512, frame.header.length)
        assertEquals(1, frame.header.channels)
        assertEquals(TciAudioFrameCodec.SAMPLE_RATE, frame.header.sampleRate)
        assertEquals(512 * 2, frame.payload.size)
        assertTrue(TciAudioFrameCodec.isSensibleTxAudio(frame.header, frame.payload.size))
    }

    @Test
    fun theAnswerCopiesWhatThePacingFrameAskedFor() {
        // A radio that asked for 256 samples at another rate gets 256 samples at that rate.
        val m = machine()
        m.press()
        mic.chunks += voice(256)
        m.onFrame(TciAudioFrameCodec.decode(TciAudioFrameCodec.chrono(samples = 256))!!)
        val frame = TciAudioFrameCodec.decode(wire.frames.single())!!
        assertEquals(256, frame.header.length)
        assertEquals(256, frame.payload.size / 2)
    }

    @Test
    fun silenceFillsWhatTheCaptureHasNotDelivered() {
        val m = machine()
        m.press()
        mic.chunks += voice(100)
        m.onFrame(chrono())
        val samples = TciProtocol.decodeFirstChannel(TciAudioFrameCodec.decode(wire.frames.single())!!)
        assertEquals(512, samples.size)
        assertEquals(voice(100).toList(), samples.take(100))
        assertEquals(ShortArray(412).toList(), samples.drop(100))
        assertEquals(1L, m.framesSilent())
    }

    @Test
    fun aReceiveFrameIsNotAnAnswerAndIsNotAnsweredTo() {
        val m = machine()
        m.press()
        val rx = TciAudioFrameCodec.decode(TciAudioFrameCodec.rxAudio(voice(512)))!!
        assertFalse(m.onFrame(rx))
        assertEquals(emptyList<ByteArray>(), wire.frames)
    }

    @Test
    fun nothingGoesOutAfterTheOperatorLetsGo() {
        val m = machine()
        m.press()
        repeat(3) {
            mic.chunks += voice(512)
            m.onFrame(chrono())
        }
        m.release()
        assertEquals(listOf("trx:0,false;"), wire.unkeys())
        val sent = wire.frames.size
        assertFalse(m.onFrame(chrono())) // a pacing frame already on its way when the key came off
        assertEquals(sent, wire.frames.size)
        assertEquals(1, mic.stops)
        assertFalse(m.keyed)
    }

    @Test
    fun nothingGoesOutAfterTheRadioTookTheKeyBack() {
        val m = machine()
        m.press()
        mic.chunks += voice(512)
        m.onFrame(chrono())
        m.onTransmitting(false) // trx:0,false; — the silence watchdog, the SWR guard, an unkey
        assertEquals(1, wire.frames.size)
        assertFalse(m.onFrame(chrono()))
        assertEquals(1, wire.frames.size)
        assertFalse(m.keyed)
    }

    // ---- the watchdogs ----

    @Test
    fun weStopWhenTheRadioStopsPacingUs() {
        val m = machine()
        m.press()
        mic.chunks += voice(512)
        m.onFrame(chrono())
        scheduler.advance(600) // no pacing: the link is gone, and the radio unkeyed us at 500 ms
        assertEquals(listOf("trx:0,false;"), wire.unkeys())
        assertEquals("the radio stopped pacing us: the transmission is over", m.lastReason)
        assertEquals(1, mic.stops)
        assertFalse(m.keyed)
    }

    @Test
    fun aKeyThatNeverTakesGivesUpInASecond() {
        // The firmware's own patience with a TCI key that never got audio (tx_session_tick), which is
        // also the page's KEY_MS. The silence gap is held long here so this pins the confirm timer on
        // its own; the next test pins the silence gap.
        val m = machine(silenceMs = 60_000)
        m.press()
        scheduler.advance(999)
        assertTrue(m.keyed)
        scheduler.advance(1)
        assertEquals(listOf("trx:0,false;"), wire.unkeys())
        assertNotNull(m.lastReason)
        assertTrue(m.lastReason!!.contains("no pacing"))
        assertFalse(m.keyed)
        assertEquals(1, mic.stops)
    }

    @Test
    fun aKeyTheRadioStopsPacingIsOverInsideItsSilenceGap() {
        // TX_SILENCE_MS: the radio unkeys us 500 ms after the last frame it heard from us. Watching the
        // same gap is what tells us we are off the air when the link goes quiet without saying so.
        val m = machine()
        m.press()
        scheduler.advance(499)
        assertTrue(m.keyed)
        scheduler.advance(1)
        assertEquals(listOf("trx:0,false;"), wire.unkeys())
        assertEquals("the radio stopped pacing us: the transmission is over", m.lastReason)
        assertFalse(m.keyed)
        assertEquals(1, mic.stops)
    }

    @Test
    fun ourOwnLimitEndsItBeforeTheRadiosThreeMinutes() {
        val m = machine()
        m.press()
        // Fed all the way, the way the radio feeds it — one pacing frame every 21.3 ms — so it is this
        // app's own cap that ends the transmission and not a gap in the link.
        while (clock.nowMs() < 169_500) {
            scheduler.advance(21)
            mic.chunks += voice(TciAudioFrameCodec.SAMPLES_PER_FRAME)
            m.onFrame(chrono())
        }
        assertTrue(m.keyed)
        assertTrue(m.framesSent() > 7_000)
        scheduler.advance(1_000)
        assertFalse(m.keyed)
        assertEquals("this app's own limit of 170 s on the air", m.lastReason)
        assertEquals(1, mic.stops)
    }

    @Test
    fun aMicrophoneThatDiesMidTransmissionEndsIt() {
        val m = machine()
        m.press()
        mic.chunks += voice(512)
        m.onFrame(chrono())
        mic.died() // the capture thread stopped: an error from AudioRecord
        m.onFrame(chrono())
        assertEquals(listOf("trx:0,false;"), wire.unkeys())
        assertEquals("the microphone went away: the transmission is over", m.lastReason)
        assertFalse(m.keyed)
    }

    @Test
    fun aMicrophoneThatGoesQuietMidTransmissionEndsIt() {
        // The case `running` does not catch: AudioRecord reads zero while another app owns the input.
        // Our silent frames keep the radio keyed, so nothing but this check gets us off the air.
        val m = machine()
        m.press()
        mic.chunks += voice(512)
        m.onFrame(chrono())
        mic.chunks.clear() // the device is open and delivers nothing
        scheduler.advance(21)
        m.onFrame(chrono())
        assertTrue(m.keyed) // the grace the capture needs to spin up
        // Fed the way the radio feeds it, so it is the microphone that ends this and not the pacing gap.
        var fed = 0
        while (m.keyed && fed < 40) {
            scheduler.advance(21)
            m.onFrame(chrono())
            fed++
        }
        assertFalse(m.keyed)
        assertEquals(listOf("trx:0,false;"), wire.unkeys())
        assertEquals(1, mic.stops)
        assertNotNull(m.lastReason)
        assertTrue(m.lastReason!!.startsWith("the microphone delivered nothing"))
    }

    @Test
    fun aFrameTheSocketWouldNotTakeEndsTheSessionAtOnce() {
        val m = machine()
        m.press()
        wire.failFramesFrom = 1
        mic.chunks += voice(512)
        m.onFrame(chrono())
        assertFalse(m.keyed)
        assertEquals("the radio is not reached: the audio frame was lost", m.lastReason)
        assertEquals(1, mic.stops)
        // The frame is binary and the unkey is text. handle_cmd never refuses trx:0,false from the
        // client that keyed (audit B01). TX_SILENCE_MS is the backstop when this line does not arrive,
        // not a reason to skip it. The refused frame itself is not on the wire.
        assertEquals(listOf("trx:0,false;"), wire.unkeys())
        assertEquals(listOf("trx:0,true,tci;", "trx:0,false;"), wire.lines)
        assertTrue(wire.frames.isEmpty())
        assertFalse(m.owesUnkey)
    }

    @Test
    fun aKeyTheSocketRefusesIsStillFollowedByAnUnkey() {
        wire.dropKey = true
        val m = machine()
        assertFalse(m.press())
        assertEquals(listOf("trx:0,false;"), wire.unkeys())
        assertEquals(1, mic.stops)
        assertFalse(m.keyed)
        assertFalse(m.owesUnkey)
    }

    @Test
    fun aRefusedUnkeyIsRetriedUntilTheSocketTakesIt() {
        val m = machine()
        m.press()
        wire.dropUnkey = true
        m.release()
        assertTrue(m.owesUnkey)
        assertTrue(wire.unkeys().isEmpty())
        wire.dropUnkey = false
        scheduler.advance(100) // UNKEY_RETRY_MS
        assertEquals(listOf("trx:0,false;"), wire.unkeys())
        assertFalse(m.owesUnkey)
        scheduler.advance(5_000)
        assertEquals(1, wire.unkeys().size)
    }

    // ---- fail-safe ----

    @Test
    fun failSafeUnkeysStopsTheMicrophoneAndSilencesEveryTimer() {
        val m = machine()
        m.press()
        mic.chunks += voice(512)
        m.onFrame(chrono())
        m.failSafe("this app went to the background")
        assertEquals(listOf("trx:0,false;"), wire.unkeys())
        assertEquals(1, mic.stops)
        scheduler.advance(10_000)
        assertEquals(1, wire.frames.size)
        assertEquals(1, wire.unkeys().size) // one unkey, and no second thought after it
        assertFalse(m.keyed)
    }

    @Test
    fun aLinkThatDroppedIsStillToldToUnkey() {
        val m = machine()
        m.press()
        m.linkLost("the audio link to the radio dropped")
        assertEquals(listOf("trx:0,false;"), wire.unkeys())
        assertFalse(m.keyed)
        assertEquals(1, mic.stops)
    }

    @Test
    fun resetLeavesNothingPending() {
        val m = machine()
        m.press()
        m.reset()
        scheduler.advance(10_000)
        // reset() never walks away from a keyed radio: the unkey goes out, and after it nothing does.
        assertEquals(listOf("trx:0,false;"), wire.unkeys())
        assertEquals(2, wire.lines.size)
        assertFalse(m.keyed)
        assertEquals(1, mic.stops)
        assertEquals(TciTxStateMachine.State.Idle, m.currentState)
    }
}
