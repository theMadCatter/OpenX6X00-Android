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
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import radio.kd0cq.x6100.net.RadioLinks
import radio.kd0cq.x6100.service.RadioRigs
import radio.kd0cq.x6100.ui.AppNav
import radio.kd0cq.x6100.ui.RadioViewModel
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
        askAboutNotifications()
        setContent {
            OpenX6X00Theme {
                AppNav(viewModel)
            }
        }
    }

    /**
     * Off the air when the window goes away.
     *
     * A phone locked in a pocket, a call coming in, another app taking the screen: in all of them the
     * operator is no longer looking at the PTT, and a transmitter nobody is watching is the failure
     * this app is not allowed to have. The link stays up — the notification says so, and the receiver
     * keeps playing — only the key and the microphone let go.
     *
     * `onStop` and not `onPause`, because a split-screen or a floating window still has an operator
     * looking at the radio, and the microphone is put back for them to use.
     */
    override fun onStop() {
        RadioRigs.rig(application).ptt.wentToBackground()
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
