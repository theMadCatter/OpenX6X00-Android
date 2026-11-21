// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The background switch, and the radio to dial again if the process is killed.
// Same preference file as the spectrum view. Forgetting a pairing uses a
// different file, so it does not turn background running off.

package radio.kd0cq.x6100.service

import android.content.Context
import radio.kd0cq.x6100.net.PANEL_PORT
import radio.kd0cq.x6100.net.RadioEndpoint
import radio.kd0cq.x6100.net.TCI_PORT

/**
 * Whether the receiver stays up with the screen off, and which radio that was.
 *
 * The switch defaults on. The host is written with [commit] rather than apply:
 * a process the system kills a moment later still has to know where to reconnect.
 * Stop, and turning the link off because background running is off, forget the host.
 */
class BackgroundPreference(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        BackgroundRun.PREF_FILE,
        Context.MODE_PRIVATE,
    )

    fun read(): Boolean = BackgroundRun.enabled(
        if (prefs.contains(BackgroundRun.PREF_KEY)) prefs.getBoolean(BackgroundRun.PREF_KEY, true) else null,
    )

    fun write(on: Boolean) {
        prefs.edit().putBoolean(BackgroundRun.PREF_KEY, on).apply()
    }

    fun readEndpoint(): RadioEndpoint? {
        val host = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: return null
        val name = prefs.getString(KEY_NAME, null).orEmpty().ifBlank { host }
        return RadioEndpoint(
            name = name,
            host = host,
            panelPort = prefs.getInt(KEY_PANEL, PANEL_PORT),
            tciPort = prefs.getInt(KEY_TCI, TCI_PORT),
        )
    }

    fun remember(endpoint: RadioEndpoint) {
        prefs.edit()
            .putString(KEY_HOST, endpoint.host)
            .putString(KEY_NAME, endpoint.name)
            .putInt(KEY_PANEL, endpoint.panelPort)
            .putInt(KEY_TCI, endpoint.tciPort)
            .commit()
    }

    fun forgetEndpoint() {
        prefs.edit()
            .remove(KEY_HOST)
            .remove(KEY_NAME)
            .remove(KEY_PANEL)
            .remove(KEY_TCI)
            .commit()
    }

    private companion object {
        const val KEY_HOST = "bg_host"
        const val KEY_NAME = "bg_name"
        const val KEY_PANEL = "bg_panel"
        const val KEY_TCI = "bg_tci"
    }
}
