// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Thresholds for the shade. Battery is a percent. SWR is a ratio, 2.5 by default.

package radio.kd0cq.x6100.service

import android.content.Context

class AlertPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun battery(): Int = prefs.getInt(BAT, RadioAlerts.DEFAULT_BATTERY).coerceIn(1, 100)

    fun swr(): Double = prefs.getFloat(SWR, RadioAlerts.DEFAULT_SWR.toFloat()).toDouble().coerceIn(1.0, 10.0)

    fun writeBattery(percent: Int) {
        prefs.edit().putInt(BAT, percent.coerceIn(1, 100)).apply()
    }

    fun writeSwr(ratio: Double) {
        prefs.edit().putFloat(SWR, ratio.coerceIn(1.0, 10.0).toFloat()).apply()
    }

    private companion object {
        const val FILE = "radio_face"
        const val BAT = "alert_battery"
        const val SWR = "alert_swr"
    }
}
