// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.audio

import kotlinx.coroutines.flow.StateFlow
import radio.kd0cq.x6100.net.TciAudioFrame

/**
 * The receiver's audio in this app, and our microphone back to the radio.
 *
 * TODO(audio): the skeleton stops here on purpose, and no audio moves yet. What it will take:
 *  - RX: an AudioTrack at the rate the TCI link asked for (24 kHz mono int16 is what the radio
 *    captures at), fed by [onReceive] as its frames arrive; frames come with a 64 byte header, so
 *    TciProtocol.decodeFirstChannel() is the path into PCM.
 *  - TX: an AudioRecord at the same rate. While keyed, the radio sends a TX_CHRONO frame every
 *    21.3 ms (512 samples at 24 kHz) and expects one TX_AUDIO frame ([TciAudioType.TX]) per tick, on
 *    that pace: tci_server.c drops a transmitter that goes silent for TX_SILENCE_MS or keys longer
 *    than TX_LIMIT_MS, and a pace rounded to 21 ms asks for 1.6 % more than the air takes.
 *  - Half-duplex: while keyed the receiver's frames go to the sink only as sidetone, if at all.
 *
 * Until then [NoOpAudioPipeline] swallows the frames, so nothing here can make noise or feed the
 * radio by accident.
 */
interface AudioPipeline {

    /** Whether audio is moving; the controls screen shows it next to the S-meter. */
    val running: StateFlow<Boolean>

    /** Start the track and, for transmit, the recorder. */
    fun start()

    fun stop()

    /** A frame from the TCI link's audio flow. */
    fun onReceive(frame: TciAudioFrame)
}

/** The placeholder the link runs on: it never opens a device, and never feeds the radio. */
class NoOpAudioPipeline : AudioPipeline {

    private val state = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val running: StateFlow<Boolean> = state

    override fun start() {
        // TODO(audio): replace with an AudioTrack at the TCI link's rate
    }

    override fun stop() {
        // TODO(audio): release the track and the recorder
    }

    override fun onReceive(frame: TciAudioFrame) {
        // TODO(audio): play frame; it is dropped here on purpose
    }
}
