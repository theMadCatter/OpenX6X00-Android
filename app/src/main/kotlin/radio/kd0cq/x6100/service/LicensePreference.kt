// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The band check has to survive a restart, and it has to start as "no class,
// check on". A missing value is that default, not Advanced and not off.
// The same preference file as the background switch. These keys are their own.

package radio.kd0cq.x6100.service

import android.content.Context
import radio.kd0cq.x6100.digital.BandChoice
import radio.kd0cq.x6100.digital.LicenseClass

/**
 * The operator's country table and class, as Setup left them.
 *
 * Country is the United States. There is no other table in this build.
 * Turning the check off is stored here too. The warning for that switch
 * is [radio.kd0cq.x6100.digital.FccPart97.CHECK_OFF_WARNING], shown by Setup,
 * not stored.
 */
class LicensePreference(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        BackgroundRun.PREF_FILE,
        Context.MODE_PRIVATE,
    )

    fun read(): BandChoice = BandChoice(
        check = if (prefs.contains(KEY_CHECK)) prefs.getBoolean(KEY_CHECK, true) else true,
        license = LicenseClass.fromWire(prefs.getString(KEY_CLASS, "")),
    )

    fun write(choice: BandChoice) {
        prefs.edit()
            .putBoolean(KEY_CHECK, choice.check)
            .putString(KEY_CLASS, choice.license?.wire ?: "")
            .apply()
    }

    private companion object {
        const val KEY_CHECK = "license_check"
        const val KEY_CLASS = "license_class"
    }
}
