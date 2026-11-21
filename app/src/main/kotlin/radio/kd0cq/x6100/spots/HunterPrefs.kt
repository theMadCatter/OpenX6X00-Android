// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The hunter list, one pattern per line. A trailing * is a prefix. Not a secret.

package radio.kd0cq.x6100.spots

import android.content.Context

class HunterPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun watches(): List<String> =
        prefs.getString(LIST, "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun write(lines: String) {
        prefs.edit().putString(LIST, lines.trim()).apply()
    }

    private companion object {
        const val FILE = "spot_hunter"
        const val LIST = "watches"
    }
}
