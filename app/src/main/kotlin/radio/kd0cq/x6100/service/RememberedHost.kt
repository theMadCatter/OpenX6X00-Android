// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The connect field used to start at x6100.local every time the process came
// back. The background service already remembered a host, and then forgot it
// on Stop, so the field and the reconnect path were not the same memory.
//
// This is the last host the operator typed or picked, written with commit so
// a kill a moment later still has it. Stop and Disconnect do not clear it.
// A fresh install with nothing saved still starts at x6100.local. The pairing
// token lives in a different file and is not written here.

package radio.kd0cq.x6100.service

import android.content.Context
import radio.kd0cq.x6100.net.RadioEndpoint

/** What the connect field shows when the process has no saved instance state. */
object RememberedHost {
    fun shown(saved: String?, fallback: String = RadioEndpoint.DEFAULT_HOST): String =
        saved?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}

/**
 * The last host, and the GhostNet regional group, in the same preference file
 * as the spectrum view. Neither key is removed when the link stops.
 */
class RememberedHostStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun readHost(): String? = prefs.getString(KEY_HOST, null)

    fun writeHost(host: String) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        prefs.edit().putString(KEY_HOST, trimmed).commit()
    }

    fun readGroup(): String? = prefs.getString(KEY_GROUP, null)

    fun writeGroup(group: String) {
        prefs.edit().putString(KEY_GROUP, group.trim()).commit()
    }

    private companion object {
        const val FILE = "radio_face"
        const val KEY_HOST = "last_host"
        const val KEY_GROUP = "ghostnet_group"
    }
}
