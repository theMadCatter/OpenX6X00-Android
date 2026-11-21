// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// One QRZ lookup. [fetch] is given the URL and returns the body. The URL of a session
// request contains the password, so [fetch] must not log its argument. A missing
// subscription still returns whatever Callsign fields arrived; [QrzParse.limited] is
// how the screen says the rest was not included.

package radio.kd0cq.x6100.log

class QrzLookup(private val fetch: (String) -> String) {
    private var sessionKey: String? = null

    fun lookup(username: String, password: String, call: String): QrzParse {
        if (username.isBlank() || password.isBlank()) {
            return QrzParse(null, "QRZ username and password are not set", false, null)
        }
        val key = sessionKey ?: openSession(username, password) ?: return lastSession
        val body = runCatching { fetch(QrzApi.lookupUrl(key, call)) }.getOrElse {
            return QrzParse(key, "QRZ did not answer", false, null)
        }
        val parsed = QrzXml.parse(body)
        // A dead session key: forget it so the next lookup signs in again.
        if (parsed.sessionKey == null && parsed.call == null && parsed.error?.contains("session", ignoreCase = true) == true) {
            sessionKey = null
        }
        return parsed
    }

    private var lastSession: QrzParse = QrzParse(null, "QRZ did not answer", false, null)

    private fun openSession(username: String, password: String): String? {
        val body = runCatching { fetch(QrzApi.sessionUrl(username, password)) }.getOrElse {
            lastSession = QrzParse(null, "QRZ did not answer", false, null)
            return null
        }
        val parsed = QrzXml.parse(body)
        lastSession = parsed
        sessionKey = parsed.sessionKey
        return parsed.sessionKey
    }
}
