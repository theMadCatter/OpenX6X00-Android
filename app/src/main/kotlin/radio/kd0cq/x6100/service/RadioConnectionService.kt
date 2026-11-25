// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
//
// Background running, KD0CQ: while the switch is on (it starts on),
// this service keeps the Panel and TCI sockets and the receiver audio when the
// screen is off. The notification names the frequency, the mode and the S-meter,
// and it offers Stop and Open. A partial wake lock and a high-performance Wi-Fi
// lock are held only then, and only while a socket is actually up.

package radio.kd0cq.x6100.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import radio.kd0cq.x6100.R
import radio.kd0cq.x6100.net.LinkPhase
import radio.kd0cq.x6100.net.PanelMode
import radio.kd0cq.x6100.net.PANEL_PORT
import radio.kd0cq.x6100.net.RadioEndpoint
import radio.kd0cq.x6100.net.RadioLink
import radio.kd0cq.x6100.net.RadioLinks
import radio.kd0cq.x6100.net.TCI_PORT

/**
 * The connection's owner: a foreground service the user can see and stop.
 *
 * The type is `connectedDevice` for the radio, and `mediaPlayback` as well while
 * receiver audio is playing. Transmit audio is not this service's job. An
 * on-screen key is released by the activity before the phone is in a pocket,
 * and this service never opens the microphone on its own.
 */
class RadioConnectionService : LifecycleService() {

    private val link: RadioLink get() = RadioLinks.link(this)
    private val rig: Rig get() = RadioRigs.rig(this)
    private val prefs by lazy { BackgroundPreference(this) }
    private val locks by lazy { RadioLocks(this) }

    @Volatile
    private var inBackground = false

    override fun onCreate() {
        super.onCreate()
        alive = true
        makeChannel()
        // Built here, not on the first press: the key has to be watching the link before the operator
        // touches anything, and the audio has to be open when the radio's frames start arriving.
        RadioRigs.rig(this)
        watchState()
        watchSpots()
        watchAlerts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promised first: a startForegroundService() that never reaches startForeground() is a crash.
        val rxPlaying = runCatching { rig.audio.running.value }.getOrDefault(false)
        val notification = buildNotification(link.connection.value.panel, statusText())
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType(rxPlaying))
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.forgetEndpoint()
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_BACKGROUND -> {
                inBackground = true
                applyLocks()
                return START_STICKY
            }
            ACTION_FOREGROUND -> {
                inBackground = false
                locks.drop()
                return START_STICKY
            }
            ACTION_QUICK -> {
                val which = intent.getStringExtra(EXTRA_WHICH).orEmpty()
                val radio = link.state.value
                val line = QuickKeys.command(
                    action = which,
                    mode = radio?.mode ?: 0,
                    modeCount = PanelMode.entries.size,
                    att = radio?.att ?: 0,
                    pre = radio?.preOn == true,
                    nb = radio?.noiseBlankerOn == true,
                    nr = radio?.noiseReduceOn == true,
                )
                if (line != null) link.send(line)
                return START_STICKY
            }
        }
        if (intent == null) {
            val saved = prefs.readEndpoint()
            return when (BackgroundRun.restartAfterDeath(prefs.read(), saved?.host)) {
                BackgroundRun.ServiceAction.START -> {
                    saved?.let { link.connect(it) }
                    START_STICKY
                }
                // The switch is off, or Stop forgot the radio. Do not sit in the shade with no link.
                BackgroundRun.ServiceAction.STOP -> {
                    stopEverything()
                    START_NOT_STICKY
                }
                BackgroundRun.ServiceAction.KEEP -> START_STICKY
            }
        }
        endpointOf(intent)?.let { endpoint ->
            prefs.remember(endpoint)
            link.connect(endpoint)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        inBackground = false
        locks.drop()
        // Fail-safe first: a link going down must never leave the radio on the air.
        rig.shutdown("the radio service stopped")
        link.disconnect()
        super.onDestroy()
    }

    /**
     * The task swiped away.
     *
     * With background running on, the activity has already let a touch key up.
     * The receiver stays. With it off, this is the same close as Stop: the key,
     * the audio and both sockets.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs.read()) {
            rig.stopDigitalEngines()
            rig.ptt.releaseTune()
            rig.ptt.release()
        } else {
            prefs.forgetEndpoint()
            rig.shutdown("the app was closed")
            link.disconnect()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun stopEverything() {
        inBackground = false
        locks.drop()
        rig.shutdown("stopped by you")
        link.disconnect()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * A matching spot, including while the screen is off. The foreground service is already
     * running, so this does not start a second one. The gate stops a busy cluster buzzing.
     */
    private fun watchSpots() {
        lifecycleScope.launch {
            var seen = emptySet<String>()
            var primed = false
            var lastGlobal = 0L
            val lastCall = HashMap<String, Long>()
            link.spots.collect { spots ->
                val watches = radio.kd0cq.x6100.spots.HunterPrefs(this@RadioConnectionService).watches()
                val calls = spots.map { it.call }
                val freqs = spots.map { it.freqHz }
                val fresh = radio.kd0cq.x6100.spots.Hunter.fresh(seen, calls, freqs)
                seen = spots.map { radio.kd0cq.x6100.spots.Hunter.key(it.call, it.freqHz) }.toSet()
                // The first list is what the radio already held. Alert on the next arrival.
                if (!primed) {
                    primed = true
                    return@collect
                }
                val now = SystemClock.elapsedRealtime()
                for (index in fresh) {
                    val spot = spots[index]
                    if (!radio.kd0cq.x6100.spots.Hunter.matches(spot.call, watches, spot.alert)) continue
                    val callKey = radio.kd0cq.x6100.log.QsoBook.baseCall(spot.call)
                    if (!radio.kd0cq.x6100.spots.AlertGate.allow(now, lastGlobal, lastCall[callKey] ?: 0L)) continue
                    lastGlobal = now
                    lastCall[callKey] = now
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val channel = NotificationChannel(HUNTER_CHANNEL, "Spot hunter", NotificationManager.IMPORTANCE_DEFAULT)
                    manager.createNotificationChannel(channel)
                    val open = PendingIntent.getActivity(
                        this@RadioConnectionService,
                        3,
                        Intent(this@RadioConnectionService, radio.kd0cq.x6100.MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    val note = NotificationCompat.Builder(this@RadioConnectionService, HUNTER_CHANNEL)
                        .setSmallIcon(R.drawable.ic_radio)
                        .setContentTitle(spot.call)
                        .setContentText("${spot.freqHz / 1000} kHz  ${spot.comment}")
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build()
                    manager.notify(HUNTER_ID, note)
                    break
                }
            }
        }
    }

    /** Battery from `bat`, SWR from the `t` meter, and only while that meter is a transmit. */
    private fun watchAlerts() {
        lifecycleScope.launch {
            var lastBattery = 0L
            var lastSwr = 0L
            combine(link.state, link.txMeters) { state, tx -> state to tx }.collect { (state, tx) ->
                val prefs = AlertPrefs(this@RadioConnectionService)
                val now = SystemClock.elapsedRealtime()
                if (RadioAlerts.lowBattery(state?.battery, prefs.battery()) && RadioAlerts.due(now, lastBattery)) {
                    lastBattery = now
                    postAlert("Battery ${state?.battery}%", "Below the ${prefs.battery()}% mark")
                }
                if (RadioAlerts.highSwr(tx.swr, prefs.swr(), state?.transmitting == true) && RadioAlerts.due(now, lastSwr)) {
                    lastSwr = now
                    postAlert("High SWR", String.format("%.1f:1", tx.swr))
                }
            }
        }
    }

    private fun postAlert(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(ALERT_CHANNEL, "Radio alerts", NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(channel)
        val note = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager.notify(ALERT_ID, note)
    }

    /** Wake and Wi-Fi only for the case [BackgroundRun.wantLocks] names. */
    private fun applyLocks() {
        if (BackgroundRun.wantLocks(prefs.read(), connected(), inBackground)) locks.hold()
        else locks.drop()
    }

    private fun connected(): Boolean {
        val now = link.connection.value
        return now.panel.online || now.tci.online
    }

    private fun watchState() {
        lifecycleScope.launch {
            var lastLine = ""
            var lastPaint = 0L
            var lastType = -1
            combine(
                link.connection,
                link.state,
                link.meterDb,
                rig.audio.running,
            ) { connection, state, meter, rx ->
                Quad(connection.panel, connection.tci, state?.freqHz, state?.mode, meter, rx)
            }.collect { snap ->
                val phase = displayPhase(snap.panel, snap.tci)
                val line = BackgroundRun.statusLine(
                    phase,
                    link.endpoint?.host.orEmpty(),
                    snap.freqHz,
                    snap.mode,
                    snap.meterDb,
                )
                val type = foregroundType(snap.rxPlaying)
                if (type != lastType) {
                    lastType = type
                    ServiceCompat.startForeground(
                        this@RadioConnectionService,
                        NOTIFICATION_ID,
                        buildNotification(phase, line),
                        type,
                    )
                }
                val now = SystemClock.elapsedRealtime()
                if (BackgroundRun.noteDue(now, lastPaint, line != lastLine)) {
                    lastLine = line
                    lastPaint = now
                    show(phase, line)
                }
                if (inBackground) applyLocks()
            }
        }
    }

    private fun statusText(): String = BackgroundRun.statusLine(
        link.connection.value.panel,
        link.endpoint?.host.orEmpty(),
        link.state.value?.freqHz,
        link.state.value?.mode,
        link.meterDb.value,
    )

    private fun endpointOf(intent: Intent?): RadioEndpoint? {
        val host = intent?.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() } ?: return null
        return RadioEndpoint(
            name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifEmpty { host },
            host = host,
            panelPort = intent.getIntExtra(EXTRA_PANEL_PORT, PANEL_PORT),
            tciPort = intent.getIntExtra(EXTRA_TCI_PORT, TCI_PORT),
        )
    }

    private fun show(phase: LinkPhase, line: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(phase, line))
    }

    private fun buildNotification(phase: LinkPhase, line: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, radio.kd0cq.x6100.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RadioConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val shown = line.ifBlank { fallback(phase) }
        val quick = RemoteViews(packageName, R.layout.notif_quick)
        quick.setTextViewText(R.id.quick_line, shown)
        quick.setOnClickPendingIntent(R.id.quick_band_down, quickIntent(QuickKeys.BAND_DOWN, 10))
        quick.setOnClickPendingIntent(R.id.quick_band_up, quickIntent(QuickKeys.BAND_UP, 11))
        quick.setOnClickPendingIntent(R.id.quick_mode, quickIntent(QuickKeys.MODE, 12))
        quick.setOnClickPendingIntent(R.id.quick_vol_down, quickIntent(QuickKeys.VOL_DOWN, 13))
        quick.setOnClickPendingIntent(R.id.quick_vol_up, quickIntent(QuickKeys.VOL_UP, 14))
        quick.setOnClickPendingIntent(R.id.quick_att, quickIntent(QuickKeys.ATT, 15))
        quick.setOnClickPendingIntent(R.id.quick_pre, quickIntent(QuickKeys.PRE, 16))
        quick.setOnClickPendingIntent(R.id.quick_nb, quickIntent(QuickKeys.NB, 17))
        quick.setOnClickPendingIntent(R.id.quick_nr, quickIntent(QuickKeys.NR, 18))
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(shown)
            .setCustomContentView(quick)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(phase != LinkPhase.STOPPED)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_open), open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** A shade button. The extra is a quick-key name, never PTT. */
    private fun quickIntent(which: String, request: Int): PendingIntent =
        PendingIntent.getService(
            this,
            request,
            Intent(this, RadioConnectionService::class.java)
                .setAction(ACTION_QUICK)
                .putExtra(EXTRA_WHICH, which),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun fallback(phase: LinkPhase): String = when (phase) {
        LinkPhase.CONNECTING -> getString(R.string.notif_connecting)
        LinkPhase.CONNECTED, LinkPhase.PAIRING, LinkPhase.PAIRED ->
            getString(R.string.notif_connected, link.endpoint?.host ?: "")
        else -> getString(R.string.notif_disconnected)
    }

    private fun makeChannel() {
        val channel = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        // The frequency is the point of the shade. Secret hid it on the lock screen.
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    /**
     * Below Android 10 the platform has no type argument. From 10 up the radio is a
     * connected device, and a playing receiver adds media playback. Both are named
     * on the service in the manifest, which Android 14 requires.
     */
    private fun foregroundType(rxPlaying: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val kinds = BackgroundRun.serviceKinds(rxPlaying)
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (BackgroundRun.Kind.MEDIA_PLAYBACK in kinds) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        return type
    }

    private data class Quad(
        val panel: LinkPhase,
        val tci: LinkPhase,
        val freqHz: Int?,
        val mode: Int?,
        val meterDb: Double,
        val rxPlaying: Boolean,
    )

    companion object {
        private const val CHANNEL = "radio-link"
        private const val HUNTER_CHANNEL = "spot-hunter"
        private const val HUNTER_ID = 0x6101
        private const val ALERT_CHANNEL = "radio-alerts"
        private const val ALERT_ID = 0x6102
        private const val NOTIFICATION_ID = 0x6100

        const val ACTION_STOP = "radio.kd0cq.x6100.action.STOP"
        private const val ACTION_QUICK = "radio.kd0cq.x6100.action.QUICK"
        private const val EXTRA_WHICH = "which"
        private const val ACTION_BACKGROUND = "radio.kd0cq.x6100.action.BACKGROUND"
        private const val ACTION_FOREGROUND = "radio.kd0cq.x6100.action.FOREGROUND"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_PANEL_PORT = "panel_port"
        private const val EXTRA_TCI_PORT = "tci_port"

        @Volatile
        var alive: Boolean = false
            private set

        /** Start the link with this radio: the service holds the sockets from here. */
        fun connect(context: Context, endpoint: RadioEndpoint) {
            val intent = Intent(context, RadioConnectionService::class.java)
                .putExtra(EXTRA_HOST, endpoint.host)
                .putExtra(EXTRA_NAME, endpoint.name)
                .putExtra(EXTRA_PANEL_PORT, endpoint.panelPort)
                .putExtra(EXTRA_TCI_PORT, endpoint.tciPort)
            context.startForegroundService(intent)
        }

        /** Keep the link where it is: the service came back, the radio it knows is the radio to keep. */
        fun resume(context: Context) {
            context.startForegroundService(Intent(context, RadioConnectionService::class.java))
        }

        fun stop(context: Context) {
            BackgroundPreference(context).forgetEndpoint()
            signal(context, ACTION_STOP)
        }

        /** The activity left the screen. Ignored when the service is not up: no empty notification. */
        fun enteredBackground(context: Context) = signal(context, ACTION_BACKGROUND)

        /** The activity is back. The locks come off; the sockets stay. */
        fun enteredForeground(context: Context) = signal(context, ACTION_FOREGROUND)

        /**
         * A command to a service that is already in the foreground.
         *
         * startForegroundService from onStop is the moment Android 12 treats the app as
         * backgrounded, and it can refuse a new foreground start. The service is already
         * up, so a plain start is a message to it, not a promotion. A refusal must not
         * crash the activity: the key has already been let go.
         */
        private fun signal(context: Context, action: String) {
            if (!alive) return
            val intent = Intent(context, RadioConnectionService::class.java).setAction(action)
            runCatching { context.startService(intent) }
        }
    }
}

/** Panel if it is up, otherwise TCI, and Connecting if either side is still dialing. */
private fun displayPhase(panel: LinkPhase, tci: LinkPhase): LinkPhase = when {
    panel == LinkPhase.CONNECTING || tci == LinkPhase.CONNECTING -> LinkPhase.CONNECTING
    panel.online -> panel
    tci.online -> tci
    else -> panel
}

/**
 * Partial wake lock and a high-performance Wi-Fi lock.
 *
 * High performance is what keeps the radio's stream from being deferred while
 * the screen is off. Both are reference-counted off, so a second hold does not
 * need a matching pile of releases. [drop] runs when the screen is back, the
 * sockets are down, or the service is stopping.
 */
private class RadioLocks(context: Context) {
    private val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openx6x00:rx")
        .apply { setReferenceCounted(false) }

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is the lock the receiver needs with the screen off.
    private val wifi = runCatching {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "openx6x00:rx")
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    fun hold() {
        if (!wake.isHeld) wake.acquire()
        if (wifi != null && !wifi.isHeld) wifi.acquire()
    }

    fun drop() {
        if (wake.isHeld) wake.release()
        if (wifi != null && wifi.isHeld) wifi.release()
    }
}
