// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.net

import android.content.Context

/** Where the pairing token lives: the radio expects it at every connection. */
interface PanelTokenStore {
    var token: String?

    fun clear()
}

/** The app's own preferences: one string, kept while the pairing stands. */
class PreferencesPanelTokenStore(context: Context) : PanelTokenStore {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, value)
            }.apply()
        }

    override fun clear() {
        token = null
    }

    private companion object {
        const val FILE = "radio_link"
        const val KEY_TOKEN = "panel_token"
    }
}

/** For the tests, and for a link that should not remember anything. */
class MemoryPanelTokenStore(override var token: String? = null) : PanelTokenStore {
    override fun clear() {
        token = null
    }
}
