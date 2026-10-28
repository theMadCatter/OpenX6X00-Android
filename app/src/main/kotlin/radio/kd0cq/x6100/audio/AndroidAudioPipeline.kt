// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import radio.kd0cq.x6100.core.Microphone
import radio.kd0cq.x6100.core.SampleChunkQueue
import radio.kd0cq.x6100.core.TciAudioFrameCodec
import radio.kd0cq.x6100.net.AudioSink
import radio.kd0cq.x6100.net.TciAudioFrame
import radio.kd0cq.x6100.net.TciProtocol

/**
 * The receiver's audio out of the phone's speaker and the microphone back to the radio, on
 * [AudioTrack] and [AudioRecord] at the rate the TCI link asked for: 24 kHz, mono, PCM16, 512 samples
 * a frame — the rate the firmware captures at (`AUDIO_RATE` in `tci_audio.h`) and the frame the
 * radio's own page asks for (`audio_samplerate:24000;...;audio_stream_samples:512`).
 *
 * Two threads, one per direction, and neither waits on the link:
 *  - the player drains the receiver queue in 512-sample blocks into a low-latency `AudioTrack` in
 *    `MODE_STREAM`, where `write()` blocks until the hardware wants the next block. That is the
 *    pacing; no timer is involved, and a gap in what the radio sent plays as silence, which is what
 *    a gap in a receiver is.
 *  - the recorder reads 512-sample blocks from `AudioRecord` and hands them to [microphone]. The
 *    radio asks for them with a `TX_CHRONO` every 21.3 ms and unkeys a transmitter that goes silent
 *    for 500 ms (`TX_SILENCE_MS` in `tci_server.c`), so the recorder must never be blocked by the
 *    socket and the socket must never be blocked by the recorder. The queue between them is bounded:
 *    when it fills the oldest samples go, because late microphone audio is worse than a clipped
 *    syllable.
 *
 * What is deliberately *not* done: no `MODE_IN_COMMUNICATION`, no voice-processing, and the capture
 * source is `UNPROCESSED` (`MIC` before Android 10) rather than `VOICE_COMMUNICATION`. The phone's voice
 * pipeline would echo-cancel, level-ride and band-limit a signal that is going on the air. The cost is
 * written in DESIGN.md: with the speaker up loud and the phone's microphone keyed, the radio hears the
 * phone.
 *
 * It is both the [AudioPipeline] the rig drives and the [AudioSink] the link pours its frames into:
 * the link hands over every audio frame it reads, including the `TX_CHRONO` ticks, and this is the one
 * place that knows which is which and where each belongs.
 */
class AndroidAudioPipeline(
    /** False until the operating system has granted RECORD_AUDIO; the microphone is never assumed. */
    private val hasMicrophonePermission: () -> Boolean = { true },
    /** How much receiver audio to hold, in samples. It is also the latency ceiling: a full queue
     *  drops its oldest chunk, so the speaker is never further behind the radio than this. */
    rxBufferSamples: Int = 8 * TciAudioFrameCodec.SAMPLES_PER_FRAME,
    /** The same for the microphone, which is also how far a transmission may lag the operator. */
    txBufferSamples: Int = 4 * TciAudioFrameCodec.SAMPLES_PER_FRAME,
) : AudioPipeline, AudioSink {

    /** The link's frame, straight into [onReceive]. */
    override fun receive(frame: TciAudioFrame) = onReceive(frame)

    private val period = TciAudioFrameCodec.SAMPLES_PER_FRAME

    private val rxQueue = SampleChunkQueue(rxBufferSamples)
    private val txQueue = SampleChunkQueue(txBufferSamples)

    private val state = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = state.asStateFlow()

    private val problem = MutableStateFlow<String?>(null)

    /** Why the audio is not moving, in words the controls screen can show. */
    val lastError: StateFlow<String?> = problem.asStateFlow()

    /** A `TX_CHRONO` from the radio: the pacing that pulls the microphone. The wiring sets it to the
     *  transmit machine's `onFrame`, because the link hands every audio frame here and only this
     *  class is asked for them all, pacing frames included. */
    @Volatile
    var onChrono: ((TciAudioFrame) -> Unit)? = null

    /** The microphone, as the transmit machine sees it. A separate object because an
     *  [AudioPipeline.running] (a flow, and about the speaker) and a microphone that is or is not
     *  capturing are two different facts about two different devices. */
    val microphone: Microphone = object : Microphone {
        override fun start(): Boolean = startCapture()
        override fun stop() = stopCapture()
        override val running: Boolean get() = capturing
        override fun read(count: Int): ShortArray = txQueue.read(count)
    }

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var capturing = false

    private var player: Thread? = null
    private var capturer: Thread? = null

    /** Frames the phone's output buffer holds, as the track reports them: what the operator's ear
     *  waits on, on top of the device's own output latency (`AudioTrack.getLatency()`). Read it after
     *  [start]. On a streaming track this is the room left rather than the capacity, so treat it as an
     *  upper bound on the delay, which is the only use the diagnostics screen has for it. */
    @Volatile
    var trackBufferSamples: Int = 0
        private set

    /** Blocks the hardware asked for and did not get: every one is a click in the receiver. */
    @Volatile
    var underruns: Int = 0
        private set

    /** Frames the radio sent that we threw away rather than play late. */
    fun rxDropped(): Long = rxQueue.dropped()

    fun txDropped(): Long = txQueue.dropped()

    /** The microphone, for the screen: whether the capture device is open. */
    val microphoneRunning: Boolean get() = capturing

    // ---- receiver ----

    override fun start() {
        synchronized(this) {
            if (state.value) return
            val minBytes = AudioTrack.getMinBufferSize(
                TciAudioFrameCodec.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBytes <= 0) {
                problem.value = "this phone cannot play 24 kHz mono PCM (error $minBytes)"
                return
            }
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(TciAudioFrameCodec.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val frames = framesFor(minBytes, 2)
            val newTrack = try {
                @Suppress("MissingPermission") // a playback track asks for no permission
                AudioTrack(
                    attributes,
                    format,
                    frames,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE,
                )
            } catch (e: IllegalArgumentException) {
                problem.value = "the phone refused the audio track: ${e.message}"
                return
            }
            if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
                newTrack.release()
                problem.value = "the phone's audio output would not open"
                return
            }
            track = newTrack
            trackBufferSamples = newTrack.bufferSizeInFrames
            problem.value = null
            state.value = true
            newTrack.play()
            player = thread("x6100-rx") { playLoop() }
        }
    }

    override fun stop() {
        val dead: Thread?
        synchronized(this) {
            if (!state.value) return
            state.value = false
            dead = player
            player = null
            track?.let { t ->
                underruns = t.underrunCount
                runCatching { t.pause() }
                runCatching { t.flush() }
                t.release()
            }
            track = null
            rxQueue.clear()
        }
        dead?.join(WITHIN_STOP_MS)
    }

    /** The link's audio flow: receiver frames to the speaker, pacing frames to the transmit side. */
    override fun onReceive(frame: TciAudioFrame) {
        if (TciAudioFrameCodec.isChrono(frame.header)) {
            onChrono?.invoke(frame)
            return
        }
        if (!state.value || !TciAudioFrameCodec.isReceiveAudio(frame.header)) return
        rxQueue.offer(TciAudioFrameCodec.samplesOf(frame))
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (state.value) {
            val t = track ?: break
            // A whole block, silence filling whatever has not arrived: the hardware wants a steady
            // stream, and the honest silence of a missing block beats a short one that shifts pitch.
            val samples = rxQueue.drain(period)
            val bytes = TciProtocol.pcm16Bytes(samples)
            val written = t.write(bytes, 0, bytes.size)
            if (written < 0) {
                problem.value = "the audio output stopped (error $written)"
                return
            }
        }
    }

    // ---- microphone ----

    /** Open the capture device and start reading it. False, with the reason in [lastError], when the
     *  phone will not give the microphone: a transmission with nothing behind it is never started. */
    private fun startCapture(): Boolean {
        if (capturing) return true
        if (!hasMicrophonePermission()) {
            problem.value = "the microphone permission was not granted"
            return false
        }
        val minBytes = AudioRecord.getMinBufferSize(
            TciAudioFrameCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) {
            problem.value = "this phone cannot capture 24 kHz mono PCM (error $minBytes)"
            return false
        }
        val newRecord = try {
            @Suppress("MissingPermission") // the check above is the grant's, and the UI holds it
            AudioRecord(
                microphoneSource(),
                TciAudioFrameCodec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                framesFor(minBytes, 4),
            )
        } catch (e: RuntimeException) {
            // IllegalArgumentException for a shape the phone will not take, SecurityException for a
            // grant taken away since the check, UnsupportedOperationException for no such input.
            problem.value = "the phone would not give up its microphone: ${e.message}"
            return false
        }
        if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
            newRecord.release()
            problem.value = "the phone's microphone would not open"
            return false
        }
        return try {
            newRecord.startRecording()
            txQueue.clear()
            record = newRecord
            capturing = true
            capturer = thread("x6100-tx") { captureLoop(newRecord) }
            problem.value = null
            true
        } catch (e: IllegalStateException) {
            newRecord.release()
            problem.value = "the microphone would not start recording"
            false
        }
    }

    /**
     * Hand the microphone back to the phone. Called on every release, so the microphone-in-use light
     * goes off the moment we are off the air, and never left open by a transmission that ended in a
     * way nobody noticed.
     */
    private fun stopCapture() {
        val dead: Thread?
        synchronized(this) {
            dead = capturer
            capturer = null
            capturing = false
            record?.let { r ->
                runCatching { r.stop() }
                r.release()
            }
            record = null
            txQueue.clear()
        }
        dead?.join(WITHIN_STOP_MS)
    }

    private fun captureLoop(into: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = ShortArray(period)
        while (capturing) {
            val n = into.read(block, 0, block.size)
            if (n > 0) {
                txQueue.offer(block.copyOf(n))
            } else if (n < 0) {
                problem.value = "the microphone stopped (error $n)"
                capturing = false
                return
            }
        }
    }

    /** Everything down: the receiver's track, the microphone, and both threads. */
    fun release() {
        stop()
        stopCapture()
    }

    /**
     * A buffer size for one of the two devices, in frames. `getMinBufferSize` answers in bytes and both
     * constructors want frames — int16 mono is two bytes a frame — and its answer is only a floor: the
     * receiver runs just above it, because every frame of it is delay the operator hears, and the
     * microphone well above it, because a capture that overruns loses the operator's syllable and the
     * radio unkeys a transmitter that stops answering its pacing frames.
     */
    private fun framesFor(minBytes: Int, multiple: Int): Int =
        ((minBytes * multiple) / 2).coerceAtLeast(period * 4)

    /**
     * The microphone to capture. Android 10's `UNPROCESSED` is the main input with the phone's audio
     * processing switched off; before that the closest thing was plain `MIC`, which on most phones also
     * bypasses the voice pipeline. Never `VOICE_COMMUNICATION`: that one asks the phone to run echo
     * cancellation, automatic gain and a band-pass for speech over a signal that is going on the air.
     */
    private fun microphoneSource(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.MIC

    private inline fun thread(name: String, crossinline body: () -> Unit): Thread =
        Thread { body() }.apply {
            this.name = name
            isDaemon = true
            start()
        }

    private companion object {
        /** Long enough for a blocked `write()` or `read()` to come back, short enough that stopping
         *  the service is not held up by a phone whose audio server stopped answering. */
        const val WITHIN_STOP_MS = 500L
    }
}
