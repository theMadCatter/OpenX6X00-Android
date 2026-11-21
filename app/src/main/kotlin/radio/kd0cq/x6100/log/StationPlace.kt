// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Where this station is, for distance and bearing. A grid typed in Setup. Phone GPS
// is a later pass; this file only stores the grid the operator entered. It is not a secret.

package radio.kd0cq.x6100.log

import android.content.Context

class StationPlace(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun grid(): String = prefs.getString(GRID, "").orEmpty()

    fun writeGrid(grid: String) {
        prefs.edit().putString(GRID, grid.trim().uppercase()).apply()
    }

    fun home(): LatLon? = Maidenhead.decode(grid())

    private companion object {
        const val FILE = "station_place"
        const val GRID = "home_grid"
    }
}
