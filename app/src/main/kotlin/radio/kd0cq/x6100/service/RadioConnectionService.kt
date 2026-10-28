// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import radio.kd0cq.x6100.R
import radio.kd0cq.x6100.net.LinkPhase
import radio.kd0cq.x6100.net.PANEL_PORT
import radio.kd0cq.x6100.net.RadioEndpoint
import radio.kd0cq.x6100.net.RadioLink
import radio.kd0cq.x6100.net.RadioLinks
import radio.kd0cq.x6100.net.TCI_PORT

/**
 * The connection's owner: a foreground service the user can see and stop, so the panel and TCI
 * sockets stay up while the app is in the background, and let go of the radio when it is stopped.
 */
class RadioConnectionService : LifecycleService() {

    private val link: RadioLink get() = RadioLinks.link(this)
    private val rig: Rig get() = RadioRigs.rig(this)

    override fun onCreate() {
        super.onCreate()
        makeChannel()
        // Built here, not on the first press: the key has to be watching the link before the operator
        // touches anything, and the audio has to be open when the radio's frames start arriving.
        RadioRigs.rig(this)
        lifecycleScope.launch {
            link.connection.map { it.panel }.collectLatest { show(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promised first: a startForegroundService() that never reaches startForeground() is a crash.
        val notification = buildNotification(link.connection.value.panel)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        endpointOf(intent)?.let { link.connect(it) }
        return START_STICKY
    }

    override fun onDestroy() {
        // Fail-safe first: a link going down must never leave the radio on the air.
        rig.shutdown("the radio service stopped")
        link.disconnect()
        super.onDestroy()
    }

    /**
     * The task swiped away. The service may be started again by a sticky restart, and a phone that
     * restarted it has no operator behind it: so the key is let go here, on the way out, rather than
     * left to whatever the restart does or does not do.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        rig.shutdown("the app was closed")
        super.onTaskRemoved(rootIntent)
    }

    private fun stopEverything() {
        rig.shutdown("stopped by you")
        link.disconnect()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun endpointOf(intent: Intent?): RadioEndpoint? {
        val host = intent?.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() } ?: return null
        return RadioEndpoint(
            name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifEmpty { host },
            host = host,
            panelPort = intent.getIntExtra(EXTRA_PANEL_PORT, PANEL_PORT),
            tciPort = intent.getIntExtra(EXTRA_TCI_PORT, TCI_PORT),
        )
    }

    private fun show(phase: LinkPhase) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(phase))
    }

    private fun buildNotification(phase: LinkPhase): Notification {
        val text = when (phase) {
            LinkPhase.CONNECTING -> getString(R.string.notif_connecting)
            LinkPhase.CONNECTED, LinkPhase.PAIRING -> getString(R.string.notif_connected, link.endpoint?.host ?: "")
            LinkPhase.PAIRED -> getString(R.string.notif_connected, link.endpoint?.host ?: "")
            LinkPhase.LOST -> getString(R.string.notif_disconnected)
            else -> getString(R.string.notif_disconnected)
        }
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
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun makeChannel() {
        val channel = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL = "radio-link"
        private const val NOTIFICATION_ID = 0x6100

        const val ACTION_STOP = "radio.kd0cq.x6100.action.STOP"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_PANEL_PORT = "panel_port"
        private const val EXTRA_TCI_PORT = "tci_port"

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
            context.startForegroundService(Intent(context, RadioConnectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
