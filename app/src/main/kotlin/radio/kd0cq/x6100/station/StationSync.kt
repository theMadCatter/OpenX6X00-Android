// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Phone location and the radio clock.
//
// remote_server.c's command list (read for this pass) ends at key, knob, set, ft8, log and logq.
// There is no "pos" and no "clk". A client that sends either is answered "err unknown command".
// The words below are the ones Setup will send once the firmware accepts them. Until then
// [firmwareAccepts] is false and nothing is written to the socket. The grid shown in the
// header is the phone's or the one typed in Setup, not a grid the radio reported.

package radio.kd0cq.x6100.station

import java.util.Locale
import kotlin.math.roundToInt
import radio.kd0cq.x6100.log.Geo
import radio.kd0cq.x6100.log.LatLon

/** False until remote_server.c grows a pos command and a clk command. */
const val FIRMWARE_ACCEPTS_POS_AND_CLK = false

data class PhoneFix(val lat: Double, val lon: Double, val accuracyM: Float?)

enum class SyncChoice { SEND, SKIP, PENDING }

object PositionSync {
    const val MOVE_METRES = 100.0

    /** "pos LAT LON" or "pos LAT LON ACC". Five decimal places is about a metre. */
    fun command(fix: PhoneFix): String {
        val lat = String.format(Locale.US, "%.5f", fix.lat)
        val lon = String.format(Locale.US, "%.5f", fix.lon)
        val acc = fix.accuracyM?.roundToInt()
        return if (acc != null && acc >= 0) "pos $lat $lon $acc" else "pos $lat $lon"
    }

    /**
     * Send on the first fix, and again only after the station has moved [MOVE_METRES].
     * A firmware that does not accept the command never sends, even when the toggle is on.
     */
    fun choose(enabled: Boolean, firmware: Boolean, last: LatLon?, now: LatLon): SyncChoice {
        if (!enabled) return SyncChoice.SKIP
        if (!firmware) return SyncChoice.PENDING
        if (last == null) return SyncChoice.SEND
        val metres = Geo.distanceKm(last, now) * 1000.0
        return if (metres > MOVE_METRES) SyncChoice.SEND else SyncChoice.SKIP
    }
}

object ClockSync {
    fun query(): String = "clk?"

    /** Unix seconds. The radio's answer is not defined in the current server. */
    fun set(epochSeconds: Long): String = "clk $epochSeconds"

    fun choose(enabled: Boolean, firmware: Boolean): SyncChoice {
        if (!enabled) return SyncChoice.SKIP
        if (!firmware) return SyncChoice.PENDING
        return SyncChoice.SEND
    }
}
