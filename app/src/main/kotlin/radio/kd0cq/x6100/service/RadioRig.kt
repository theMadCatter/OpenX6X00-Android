// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import radio.kd0cq.x6100.audio.AndroidAudioPipeline
import radio.kd0cq.x6100.core.DigitalUnkey
import radio.kd0cq.x6100.net.RadioLink
import radio.kd0cq.x6100.net.RadioLinks
import radio.kd0cq.x6100.net.LinkPhase
import radio.kd0cq.x6100.ptt.HandlerPttScheduler
import radio.kd0cq.x6100.ptt.LeasePttController
import radio.kd0cq.x6100.ptt.SystemClockPttClock

/**
 * The app's one set of moving parts: the link, the audio devices, and the key.
 *
 * Built once, by the first of the screens, the service or the view model to ask. The sockets belong to
 * [RadioLink] and are built with it; the phone's speaker and microphone belong here, because opening
 * them is a decision about the operator's device and not about the protocol, and the microphone in
 * particular is opened only for the seconds a transmission with it is running.
 *
 * Three things are wired here and nowhere else:
 *  - the link's audio frames reach [audio], and its `TX_CHRONO` ticks reach [ptt];
 *  - the receiver starts playing when the TCI link comes up and stops when it goes, so a phone with no
 *    radio to hear does not hold an audio track open;
 *  - [ptt] watches the link's events and phases, so a dropped link or a `tx 0` reaches the key without
 *    anyone remembering to forward them.
 */
class Rig internal constructor(
    val link: RadioLink,
    val audio: AndroidAudioPipeline,
    val ptt: LeasePttController,
    private val digital: DigitalUnkey,
    private val scheduler: HandlerPttScheduler,
) {

    /**
     * Let go of the radio and of the phone's audio, in that order and always.
     *
     * The key first, while the sockets are still open, because that is the only moment the unkey can
     * still reach the radio; the devices after it, because a microphone left open after the service is
     * gone is a light on the phone and a promise broken. The timers go last: a release that has already
     * been asked for has nothing left for them to do.
     */
    /**
     * `ft8 stop` and `js8 stop` while the panel socket can still take them.
     *
     * PTT and Tune are separate leases. A finger leaving PTT does not call this.
     * Leaving the screen, stopping the service, losing the transmit flag, or
     * giving control back does. WeFax has no transmit word on the panel.
     */
    fun stopDigitalEngines() = digital.stop()

    fun shutdown(reason: String) {
        // The digital engines first, then the key and Tune, while the sockets
        // are still open. A stop the socket refuses is retried by [digital].
        stopDigitalEngines()
        ptt.failSafe(reason)
        audio.release()
        scheduler.clear()
        ptt.reset()
    }
}

/** The holder. [RadioLinks] holds the sockets; this holds the parts that touch the phone. */
object RadioRigs {

    @Volatile
    private var built: Rig? = null

    fun rig(context: Context): Rig = built ?: synchronized(this) {
        built ?: build(context.applicationContext).also { built = it }
    }

    private fun build(context: Context): Rig {
        val link = RadioLinks.link(context)
        val audio = AndroidAudioPipeline(
            hasMicrophonePermission = {
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
        )
        link.audio.delegate = audio
        val scheduler = HandlerPttScheduler()
        val digital = DigitalUnkey(
            send = { line -> link.panel.sendChecked(line) },
            clock = SystemClockPttClock,
            scheduler = scheduler,
        )
        val ptt = LeasePttController(
            panel = link.panel,
            tci = link.tci,
            microphone = audio.microphone,
            scheduler = scheduler,
            onAuthorityLost = { digital.stop() },
        )
        audio.onChrono = { frame -> ptt.onTciFrame(frame) }

        // The rig's own watchers. They are never cancelled: a service that stops and starts again must
        // find a key that is still watching the link, and two collectors on two shared flows cost
        // nothing between transmissions.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ptt.watch(scope)
        scope.launch {
            link.tci.phase.collect { phase ->
                when {
                    phase == LinkPhase.CONNECTED -> audio.start()
                    !phase.online -> {
                        // The audio link is gone: whatever was being keyed with it is over.
                        // needsUnkey covers a session that already looks idle here because its
                        // unkey was the write the socket refused.
                        if (ptt.onAir || ptt.needsUnkey) ptt.failSafe("the audio link to the radio dropped")
                        audio.stop()
                    }
                }
            }
        }
        return Rig(link, audio, ptt, digital, scheduler)
    }
}
