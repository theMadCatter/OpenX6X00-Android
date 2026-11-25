// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Shade buttons. Each one is a command the panel protocol already accepts.
// PTT is not one of them: a notification cannot hold the lease, and a bare
// key PTT would leave the carrier up.

package radio.kd0cq.x6100.service

import radio.kd0cq.x6100.net.PanelCommands
import radio.kd0cq.x6100.ui.face.RadioFace

object QuickKeys {
    const val BAND_UP = "band+"
    const val BAND_DOWN = "band-"
    const val MODE = "mode"
    const val VOL_UP = "vol+"
    const val VOL_DOWN = "vol-"
    const val ATT = "att"
    const val PRE = "pre"
    const val NB = "nb"
    const val NR = "nr"

    /**
     * The wire line, or null when the action is PTT or not a quick key.
     * Volume uses the existing `knob vol` encoder. The others are `set`.
     */
    fun command(
        action: String,
        mode: Int,
        modeCount: Int,
        att: Int,
        pre: Boolean,
        nb: Boolean,
        nr: Boolean,
    ): String? {
        if (action.equals("ptt", ignoreCase = true) || RadioFace.isCarrierKey(action)) return null
        return when (action) {
            BAND_UP -> PanelCommands.set("band", 1)
            BAND_DOWN -> PanelCommands.set("band", -1)
            MODE -> PanelCommands.set("mode", RadioFace.nextMode(mode, modeCount))
            VOL_UP -> PanelCommands.knob("vol", 1)
            VOL_DOWN -> PanelCommands.knob("vol", -1)
            ATT -> PanelCommands.set("att", if (att > 0) 0 else 1)
            PRE -> PanelCommands.set("pre", if (pre) 0 else 1)
            NB -> PanelCommands.set("nb", if (nb) 0 else 1)
            NR -> PanelCommands.set("nr", if (nr) 0 else 1)
            else -> null
        }
    }
}

object RadioAlerts {
    const val DEFAULT_BATTERY = 20
    const val DEFAULT_SWR = 2.5
    const val GAP_MS = 60_000L

    fun lowBattery(percent: Int?, threshold: Int): Boolean =
        percent != null && percent in 0..threshold

    /** Only while the radio is on the air. A resting SWR of 1.0 is not an alert. */
    fun highSwr(swr: Double, threshold: Double, transmitting: Boolean): Boolean =
        transmitting && swr >= threshold

    fun due(nowMs: Long, lastMs: Long, gapMs: Long = GAP_MS): Boolean =
        lastMs == 0L || nowMs - lastMs >= gapMs
}
