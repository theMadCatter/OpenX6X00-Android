// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Where the 2D / 3D choice lives. The pairing token already uses SharedPreferences
// (there is no DataStore in this app). This is a second file so a forgotten pairing
// does not also forget the spectrum view.

package radio.kd0cq.x6100.ui.face

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The spectrum view the operator left selected.
 *
 * Chris Fink (KD0CQ): 2D is what [read] returns until the chip on the scope writes
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

    /** Combined spectrum and waterfall until the layout chip says otherwise. */
    fun readLayout(): ScopeLayout = ScopeLayout.fromStored(prefs.getString(KEY_LAYOUT, null))

    fun writeLayout(layout: ScopeLayout) {
        prefs.edit().putString(KEY_LAYOUT, layout.stored).apply()
    }

    private companion object {
        const val FILE = "radio_face"
        const val KEY = "spectrum_view"
        const val KEY_STYLE = "spectrum_style"
        const val KEY_LAYOUT = "spectrum_layout"
    }
}

/**
 * The one spectrum choice both faces read.
 *
 * [load] runs from the activity before the first frame, and again after a
 * configuration change. It applies the three strings in `radio_face`. It does
 * not invent a 2D default when `spectrum_view` is already `3d`. The chip writes
 * through here, so a layout change cannot leave a second copy of the view behind.
 */
object SpectrumChoices {
    private val memory = SpectrumMemory()
    private val _view = MutableStateFlow(memory.view)
    private val _style = MutableStateFlow(memory.style)
    private val _layout = MutableStateFlow(memory.layout)

    val view: StateFlow<SpectrumView> = _view
    val style: StateFlow<RidgeStyle> = _style
    val layout: StateFlow<ScopeLayout> = _layout

    fun load(context: Context) {
        val prefs = SpectrumViewPreference(context)
        memory.applyStored(
            prefs.read().stored,
            prefs.readStyle().stored,
            prefs.readLayout().stored,
        )
        publish()
    }

    fun selectView(context: Context, next: SpectrumView) {
        memory.selectView(next)
        SpectrumViewPreference(context).write(next)
        _view.value = memory.view
    }

    fun selectStyle(context: Context, next: RidgeStyle) {
        memory.selectStyle(next)
        SpectrumViewPreference(context).writeStyle(next)
        _style.value = memory.style
    }

    fun selectLayout(context: Context, next: ScopeLayout) {
        memory.selectLayout(next)
        SpectrumViewPreference(context).writeLayout(next)
        _layout.value = memory.layout
    }

    private fun publish() {
        _view.value = memory.view
        _style.value = memory.style
        _layout.value = memory.layout
    }
}
