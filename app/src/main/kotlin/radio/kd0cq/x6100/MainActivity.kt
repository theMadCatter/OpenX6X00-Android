// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import radio.kd0cq.x6100.net.RadioLinks
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
        enableEdgeToEdge()
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

    /** Android 13 wants leaving the connection announced, and the notification needs asking for. */
    private fun askAboutNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

// TODO(audio): RECORD_AUDIO is in the manifest for the microphone that feeds the radio over TCI. Ask
// for it the first time the PTT button is pressed, once PttController can key anything at all.
