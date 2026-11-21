// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Where the 2D / 3D choice lives. The pairing token already uses SharedPreferences
// (there is no DataStore in this app). This is a second file so a forgotten pairing
// does not also forget the spectrum view.

package radio.kd0cq.x6100.ui.face

import android.content.Context

/**
 * The spectrum view the operator left selected.
 *
 * KD0CQ: 2D is what [read] returns until the chip on the scope writes
 * 3D. The write is the same preference the next process reads, so the choice
 * survives leaving the radio screen.
 */
class SpectrumViewPreference(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(): SpectrumView = SpectrumView.fromStored(prefs.getString(KEY, null))

    fun write(view: SpectrumView) {
        prefs.edit().putString(KEY, view.stored).apply()
    }

    /** Aether lawn unless the operator long-pressed the chip for the FT-710 colours. */
    fun readStyle(): RidgeStyle = RidgeStyle.fromStored(prefs.getString(KEY_STYLE, null))

    fun writeStyle(style: RidgeStyle) {
        prefs.edit().putString(KEY_STYLE, style.stored).apply()
    }

    private companion object {
        const val FILE = "radio_face"
        const val KEY = "spectrum_view"
        const val KEY_STYLE = "spectrum_style"
    }
}
