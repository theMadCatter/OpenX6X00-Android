// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The QRZ XML API (https://xmldata.qrz.com/xml/current/). The password travels only as
// the query parameter that API requires. Nothing in this file writes it to a log.
// A callsign with no name, grid, or QTH is a limited record: the screen shows the
// fields that arrived and says the rest needs a subscription. An error with no
// callsign is only that error.

package radio.kd0cq.x6100.log

import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

data class QrzCall(
    val call: String,
    val name: String,
    val qth: String,
    val grid: String,
    val lat: Double?,
    val lon: Double?,
    val imageUrl: String?,
    val country: String,
)

data class QrzParse(
    val sessionKey: String?,
    val error: String?,
    val subscribed: Boolean,
    val call: QrzCall?,
) {
    /**
     * True when a callsign came back with none of the paid fields. An error on
     * its own (missing credentials, a dead session, no answer) is not a partial
     * record, so the screen shows that sentence only.
     */
    val limited: Boolean
        get() = call != null && call.name.isBlank() && call.grid.isBlank() && call.qth.isBlank()
}

object QrzApi {
    const val ENDPOINT = "https://xmldata.qrz.com/xml/current/"

    /**
     * Session URL. The password is in the query because that is how this API authenticates.
     * Callers must not log the returned string.
     */
    fun sessionUrl(username: String, password: String): String =
        ENDPOINT + "?username=" + enc(username) + "&password=" + enc(password) + "&agent=OpenX6X00-1.0"

    fun lookupUrl(sessionKey: String, call: String): String =
        ENDPOINT + "?s=" + enc(sessionKey) + "&callsign=" + enc(call.trim().uppercase())

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

object QrzXml {
    fun parse(xml: String): QrzParse {
        if (xml.isBlank()) return QrzParse(null, "empty answer", false, null)
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = runCatching {
            builder.parse(xml.byteInputStream(Charsets.UTF_8))
        }.getOrElse {
            return QrzParse(null, "not XML", false, null)
        }
        val root = doc.documentElement ?: return QrzParse(null, "not XML", false, null)
        val session = child(root, "Session")
        val key = session?.let { text(it, "Key") }?.takeIf { it.isNotBlank() }
        val error = session?.let { text(it, "Error") }?.takeIf { it.isNotBlank() }
        val subscribed = session?.let { text(it, "SubExp") }?.isNotBlank() == true
        val node = child(root, "Callsign")
        val call = node?.let {
            val lat = text(it, "lat")?.toDoubleOrNull()
            val lon = text(it, "lon")?.toDoubleOrNull()
            val fname = text(it, "fname").orEmpty()
            val surname = text(it, "name").orEmpty()
            QrzCall(
                call = text(it, "call").orEmpty(),
                name = listOf(fname, surname).filter { part -> part.isNotBlank() }.joinToString(" "),
                qth = listOfNotNull(text(it, "addr2"), text(it, "state")).filter { part -> part.isNotBlank() }.joinToString(", "),
                grid = text(it, "grid").orEmpty(),
                lat = lat,
                lon = lon,
                imageUrl = text(it, "image")?.takeIf { url -> url.startsWith("https://") },
                country = text(it, "country").orEmpty(),
            )
        }
        return QrzParse(key, error, subscribed, call)
    }

    private fun child(parent: Element, name: String): Element? {
        val nodes = parent.getElementsByTagName(name)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.parentNode == parent) return node
        }
        return null
    }

    private fun text(parent: Element, name: String): String? = child(parent, name)?.textContent?.trim()
}
