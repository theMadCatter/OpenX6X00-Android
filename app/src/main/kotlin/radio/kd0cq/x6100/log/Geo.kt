// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Maidenhead grids and the great-circle numbers a lookup shows. Written here, from the
// published grid definition and the haversine formula, so the logger does not depend on
// a maps SDK or an API key.

package radio.kd0cq.x6100.log

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the earth, degrees. North and east are positive. */
data class LatLon(val lat: Double, val lon: Double)

object Maidenhead {

    /**
     * Centre of a 4- or 6-character grid. A shorter or odd string is not a grid.
     * The centre is what distance is measured to: the square itself is the uncertainty.
     */
    fun decode(grid: String): LatLon? {
        val g = grid.trim().uppercase()
        if (g.length != 4 && g.length != 6) return null
        if (g[0] !in 'A'..'R' || g[1] !in 'A'..'R') return null
        if (g[2] !in '0'..'9' || g[3] !in '0'..'9') return null
        var lon = (g[0] - 'A') * 20.0 - 180.0
        var lat = (g[1] - 'A') * 10.0 - 90.0
        lon += (g[2] - '0') * 2.0
        lat += (g[3] - '0') * 1.0
        var lonSpan = 2.0
        var latSpan = 1.0
        if (g.length == 6) {
            if (g[4] !in 'A'..'X' || g[5] !in 'A'..'X') return null
            lon += (g[4] - 'A') * (2.0 / 24.0)
            lat += (g[5] - 'A') * (1.0 / 24.0)
            lonSpan = 2.0 / 24.0
            latSpan = 1.0 / 24.0
        }
        return LatLon(lat + latSpan / 2.0, wrapLon(lon + lonSpan / 2.0))
    }

    /** Four or six characters. The field letters stop at R, which is the published alphabet. */
    fun encode(point: LatLon, subsquare: Boolean = true): String {
        var lon = wrapLon(point.lon) + 180.0
        var lat = point.lat.coerceIn(-90.0, 90.0) + 90.0
        val fieldLon = (lon / 20.0).toInt().coerceIn(0, 17)
        val fieldLat = (lat / 10.0).toInt().coerceIn(0, 17)
        lon -= fieldLon * 20.0
        lat -= fieldLat * 10.0
        val sqLon = (lon / 2.0).toInt().coerceIn(0, 9)
        val sqLat = lat.toInt().coerceIn(0, 9)
        val head = "${'A' + fieldLon}${'A' + fieldLat}$sqLon$sqLat"
        if (!subsquare) return head
        lon -= sqLon * 2.0
        lat -= sqLat.toDouble()
        val subLon = (lon / (2.0 / 24.0)).toInt().coerceIn(0, 23)
        val subLat = (lat / (1.0 / 24.0)).toInt().coerceIn(0, 23)
        return head + ('A' + subLon) + ('A' + subLat)
    }

    private fun wrapLon(lon: Double): Double {
        var x = lon
        while (x < -180.0) x += 360.0
        while (x >= 180.0) x -= 360.0
        return x
    }
}

object Geo {
    private const val EARTH_KM = 6371.0

    /** Great-circle distance. The same point is 0, not a rounding scrap. */
    fun distanceKm(from: LatLon, to: LatLon): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLat = Math.toRadians(to.lat - from.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing, degrees clockwise from north, 0..360. */
    fun bearingDeg(from: LatLon, to: LatLon): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg + 360.0) % 360.0
    }

    fun distanceText(km: Double): String =
        if (km < 10) "${(km * 10).roundToInt() / 10.0} km" else "${km.roundToInt()} km"

    fun bearingText(deg: Double): String = "${deg.roundToInt()}°"
}
