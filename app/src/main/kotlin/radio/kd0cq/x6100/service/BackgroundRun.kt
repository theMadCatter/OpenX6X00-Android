// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// What the phone should do when the radio screen is no longer in front.
// Chris Fink (KD0CQ) asked for the receiver to keep running, on by default.
// No Android types: the activity and the service apply what this decides.

package radio.kd0cq.x6100.service

import kotlinx.coroutines.flow.MutableStateFlow
import radio.kd0cq.x6100.net.LinkPhase
import radio.kd0cq.x6100.ui.face.RadioFace

/**
 * Background running, as a decision the tests can make without a phone.
 *
 * On is the default. The Panel socket, the TCI socket and the receiver stay up
 * when the screen is off or another app is in front. Off is the older path:
 * the touch key is let go with the "went to the background" line, and the
 * service stops, which drops the sockets.
 *
 * A finger on the on-screen PTT is not a hold once the screen is gone. The
 * decision says to release that key. It does not say to announce it when
 * background running is on, so the red banner stays off. The 3 second lease
 * in the key machine is unchanged; the release is the up-command, and the
 * lease is what the radio still does if that write never arrives.
 */
object BackgroundRun {

    /** What [BackgroundPreference] returns when the operator has never touched the switch. */
    const val DEFAULT_ON = true

    const val PREF_FILE = "radio_face"
    const val PREF_KEY = "run_in_background"

    /** How often a meter-only change may repaint the ongoing notification. */
    const val NOTE_INTERVAL_MS = 1_000L

    /**
     * The scope and the waterfall paint while this is true.
     *
     * The activity clears it in onStop and sets it in onStart. The spectrum
     * pane collects frames only while it is true, so a backgrounded phone does
     * not keep building waterfall rows it cannot show.
     */
    val drawScope = MutableStateFlow(true)

    /** A stored switch. A missing value is [DEFAULT_ON], not off. */
    fun enabled(stored: Boolean?): Boolean = stored ?: DEFAULT_ON

    enum class ServiceAction { START, KEEP, STOP }

    /** Which foreground-service kinds the manifest and startForeground should name. */
    enum class Kind { CONNECTED_DEVICE, MEDIA_PLAYBACK }

    /**
     * Connected device the whole time the service is up: that is the radio.
     * Media playback only while receiver audio is actually moving. Android 15
     * stops a mediaPlayback service that is not playing, so the type is not
     * claimed during connect.
     */
    fun serviceKinds(rxPlaying: Boolean): Set<Kind> =
        if (rxPlaying) setOf(Kind.CONNECTED_DEVICE, Kind.MEDIA_PLAYBACK)
        else setOf(Kind.CONNECTED_DEVICE)

    /**
     * Locks while the operator is away, the switch is on, and a socket is up.
     * A phone that is on screen, or a service with nothing to keep, takes neither.
     */
    fun wantLocks(runInBackground: Boolean, connected: Boolean, inBackground: Boolean): Boolean =
        runInBackground && connected && inBackground

    data class Leave(
        /** Panel and TCI stay, and the receiver keeps playing. */
        val keepLink: Boolean,
        /** Let an on-screen key up, with no banner. */
        val releaseTouchPtt: Boolean,
        /** The old fail-safe, banner included when a carrier was actually up. */
        val announceWentToBackground: Boolean,
        /** Partial wake lock and a high-performance Wi-Fi lock. */
        val holdWakeAndWifi: Boolean,
        /** Freeze the scope and tell the radio `screen 0`. */
        val pausePictures: Boolean,
        /** Stop the foreground service, which closes both sockets. */
        val stopService: Boolean,
    )

    /**
     * The activity is leaving the foreground.
     *
     * A configuration change is not leaving: rotation used to take the carrier
     * down and paint the banner on an idle radio. [connected] is either socket
     * online. [touchPttHeld] is the on-screen key; there is no other hold.
     */
    fun onLeave(
        configurationChange: Boolean,
        runInBackground: Boolean,
        connected: Boolean,
        touchPttHeld: Boolean,
    ): Leave {
        if (configurationChange) {
            return Leave(
                keepLink = true,
                releaseTouchPtt = false,
                announceWentToBackground = false,
                holdWakeAndWifi = false,
                pausePictures = false,
                stopService = false,
            )
        }
        if (runInBackground) {
            return Leave(
                keepLink = true,
                releaseTouchPtt = touchPttHeld,
                announceWentToBackground = false,
                holdWakeAndWifi = connected,
                pausePictures = true,
                stopService = false,
            )
        }
        return Leave(
            keepLink = false,
            releaseTouchPtt = false,
            announceWentToBackground = true,
            holdWakeAndWifi = false,
            pausePictures = true,
            stopService = true,
        )
    }

    /**
     * The process was killed and the system started the service again with no intent.
     * Reconnect only when background running is on and we still remember a radio.
     * A Stop from the notification forgets the radio, so this returns [ServiceAction.STOP].
     */
    fun restartAfterDeath(runInBackground: Boolean, savedHost: String?): ServiceAction {
        if (!runInBackground) return ServiceAction.STOP
        if (savedHost.isNullOrBlank()) return ServiceAction.STOP
        return ServiceAction.START
    }

    /**
     * Frequency, mode or phase changes paint at once. The S-meter moves about
     * ten times a second; the shade only needs a fresh caption once a second.
     */
    fun noteDue(nowMs: Long, lastPaintMs: Long, textChanged: Boolean): Boolean {
        if (textChanged) return true
        return nowMs - lastPaintMs >= NOTE_INTERVAL_MS
    }

    /**
     * The ongoing notification. Online with a known dial: frequency, mode and
     * the S-meter caption. Otherwise the same words the shade used before.
     */
    fun statusLine(
        phase: LinkPhase,
        host: String,
        freqHz: Int?,
        modeIndex: Int?,
        meterDb: Double?,
    ): String {
        if (phase == LinkPhase.CONNECTING) return "Connecting to the radio…"
        if (!phase.online) return "Not connected"
        if (freqHz == null) {
            return if (host.isBlank()) "Connected" else "Connected to $host"
        }
        val mode = modeIndex?.let { RadioFace.modeLabel(it) }.orEmpty()
        val meter = meterDb?.let { RadioFace.sMeter(it).caption }.orEmpty()
        return listOf(RadioFace.formatFrequency(freqHz), mode, meter)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }
}
