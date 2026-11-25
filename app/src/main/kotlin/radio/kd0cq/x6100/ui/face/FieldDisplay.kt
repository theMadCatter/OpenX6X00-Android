// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Field reading. Sunlight is a high-contrast face. A frequency digit has a place value, so
// stepping the selected digit does not need the radio to understand per-digit entry: the
// result is still `set freq`. The meter style is paint only. Peak hold decays in the app.

package radio.kd0cq.x6100.ui.face

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

enum class MeterStyle { ARC, NEEDLE, LED, LAMP }

object FaceChoices {
    val sunlight = MutableStateFlow(false)
    val meter = MutableStateFlow(MeterStyle.ARC)

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sunlight.value = prefs.getBoolean(SUN, false)
        meter.value = MeterStyle.entries.getOrElse(prefs.getInt(METER, 0)) { MeterStyle.ARC }
    }

    fun writeSun(context: Context, on: Boolean) {
        sunlight.value = on
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(SUN, on).apply()
    }

    fun writeMeter(context: Context, style: MeterStyle) {
        meter.value = style
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(METER, style.ordinal).apply()
    }

    private const val FILE = "radio_face"
    private const val SUN = "sunlight"
    private const val METER = "meter_style"
}

object DigitTune {
    /** The place, in hertz, of one digit in [RadioFace.formatFrequency]. Index 0 is the leftmost. */
    fun placeHz(freqHz: Int, index: Int): Int {
        val count = RadioFace.formatFrequency(freqHz).count { it.isDigit() }
        val fromRight = count - 1 - index
        if (fromRight < 0) return 1
        var place = 1
        repeat(fromRight) { place *= 10 }
        return place
    }

    fun step(freqHz: Int, index: Int, delta: Int): Int {
        val next = freqHz.toLong() + delta.toLong() * placeHz(freqHz, index)
        return RadioFace.clampFrequency(next)
    }

    /** Direct entry. Digits only; a dot is ignored. Empty is not a frequency. */
    fun fromKeypad(text: String): Int? {
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return digits.toLongOrNull()?.let { RadioFace.clampFrequency(it) }
    }
}

object MeterPeak {
    fun hold(current: Float, peak: Float, decay: Float = 0.02f): Float =
        if (current >= peak) current else (peak - decay).coerceAtLeast(current)
}

/** Volume up/down steps the selected frequency digit while the operating screen has one. */
object DigitKeys {
    var onStep: ((Int) -> Boolean)? = null
}
