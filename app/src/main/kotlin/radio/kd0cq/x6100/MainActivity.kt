// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import radio.kd0cq.x6100.net.RadioLinks
import radio.kd0cq.x6100.service.BackgroundPreference
import radio.kd0cq.x6100.service.BackgroundRun
import radio.kd0cq.x6100.service.RadioConnectionService
import radio.kd0cq.x6100.service.RadioRigs
import radio.kd0cq.x6100.ui.AppNav
import radio.kd0cq.x6100.ui.RadioViewModel
import radio.kd0cq.x6100.ui.face.DigitKeys
import radio.kd0cq.x6100.ui.face.FaceChoices
import radio.kd0cq.x6100.ui.theme.OpenX6X00Theme

/**
 * The one activity: it shows the screens and asks nothing of the radio itself. The link belongs to
 * [radio.kd0cq.x6100.service.RadioConnectionService], so closing this window does not drop the
 * connection, and coming back to it finds the same [RadioLink][radio.kd0cq.x6100.net.RadioLink]
 * with the state the radio last told us.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: RadioViewModel by viewModels()

    /** Denied is not fatal: everything works except the notification that says we are holding the radio. */
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // RF.Guru is a night face. Light system bars would draw black glyphs on the black window.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.parseColor("#FF08090C")),
            navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#FF08090C")),
        )
        super.onCreate(savedInstanceState)
        // Build the shared link before any screen reads its flows; the service starts it later.
        RadioLinks.link(application)
        FaceChoices.load(application)
        askAboutNotifications()
        setContent {
            OpenX6X00Theme {
                AppNav(viewModel)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val step = DigitKeys.onStep
        if (step != null && event.action == KeyEvent.ACTION_DOWN) {
            val dir = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> 1
                KeyEvent.KEYCODE_VOLUME_DOWN -> -1
                else -> 0
            }
            if (dir != 0 && step(dir)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        // Drawing again, and the mirror if the screen tab had asked for it. The locks
        // come off in the service. A service that was never started is left alone.
        BackgroundRun.drawScope.value = true
        RadioLinks.link(application).resumePictures()
        RadioConnectionService.enteredForeground(application)
    }

    /**
     * The window is gone: the screen is off, or another app is in front.
     *
     * `onStop` and not `onPause`, because a split-screen or a floating window still has an operator
     * looking at the radio.
     *
     * Background running is on unless the operator turned it off in Setup. On, the link and the
     * receiver stay, the scope stops painting, and a finger that was on PTT is let up with no
     * banner. The microphone stops because that release is the transmit machine's release.
     * Off, this is the older path: the "went to the background" fail-safe, and the service stops.
     *
     * Rotation destroys this activity and creates another. That is not leaving. The rig lives
     * on the application, and treating the gap as backgrounding used to unkey a live carrier
     * and, when nothing was keyed, paint the banner on an idle radio.
     */
    override fun onStop() {
        if (!isChangingConfigurations) {
            val link = RadioLinks.link(application)
            val rig = RadioRigs.rig(application)
            val now = link.connection.value
            val decision = BackgroundRun.onLeave(
                configurationChange = false,
                runInBackground = BackgroundPreference(application).read(),
                connected = now.panel.online || now.tci.online,
                touchPttHeld = rig.ptt.onAir,
            )
            // The banner path is the old fail-safe, and only when background running is off.
            // A touch key while it is on uses release, which sends the up-command and does not
            // publish "this app went to the background". The 3 second lease is still the radio's
            // backstop if that write does not land. Nothing here keys the radio.
            if (decision.announceWentToBackground) rig.ptt.wentToBackground()
            else if (decision.releaseTouchPtt) rig.ptt.release()
            if (decision.pausePictures) {
                BackgroundRun.drawScope.value = false
                link.pausePictures()
            }
            if (decision.stopService) RadioConnectionService.stop(application)
            else RadioConnectionService.enteredBackground(application)
        }
        super.onStop()
    }

    /** Android 13 wants leaving the connection announced, and the notification needs asking for. */
    private fun askAboutNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
