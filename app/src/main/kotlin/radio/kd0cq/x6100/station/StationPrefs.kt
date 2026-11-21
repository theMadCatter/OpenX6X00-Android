// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The two Setup toggles, and the last phone fix used for the 100 m gate. Not a secret.

package radio.kd0cq.x6100.station

import android.content.Context
import radio.kd0cq.x6100.log.LatLon

class StationPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun sendLocation(): Boolean = prefs.getBoolean(SEND, false)

    fun syncClock(): Boolean = prefs.getBoolean(CLOCK, false)

    fun myCall(): String = prefs.getString(CALL, "").orEmpty()

    fun writeCall(call: String) { prefs.edit().putString(CALL, call.trim().uppercase()).apply() }

    fun writeSend(on: Boolean) { prefs.edit().putBoolean(SEND, on).apply() }

    fun writeClock(on: Boolean) { prefs.edit().putBoolean(CLOCK, on).apply() }

    fun lastFix(): LatLon? {
        if (!prefs.contains(LAT)) return null
        return LatLon(prefs.getFloat(LAT, 0f).toDouble(), prefs.getFloat(LON, 0f).toDouble())
    }

    fun writeLastFix(point: LatLon) {
        prefs.edit().putFloat(LAT, point.lat.toFloat()).putFloat(LON, point.lon.toFloat()).apply()
    }

    private companion object {
        const val FILE = "station_sync"
        const val SEND = "send_location"
        const val CLOCK = "sync_clock"
        const val CALL = "my_call"
        const val LAT = "last_lat"
        const val LON = "last_lon"
    }
}
