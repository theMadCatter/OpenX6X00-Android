// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import radio.kd0cq.x6100.net.PANEL_PORT
import radio.kd0cq.x6100.net.RadioEndpoint
import radio.kd0cq.x6100.net.TCI_PORT

/** A radio seen on the LAN, with the ports its two servers listen on. */
data class FoundRadio(
    val service: String,
    val instance: String,
    val host: String,
    val panelPort: Int = PANEL_PORT,
    val tciPort: Int = TCI_PORT,
) {
    fun endpoint() = RadioEndpoint(instance.ifEmpty { service }, host, panelPort, tciPort)
}

/**
 * Finding the radio with NSD.
 *
 * The radio's avahi file (rootfs/etc/avahi/services/openx6x00.service) advertises three services
 * under x6100.local: _http._tcp on 80, _https._tcp on 443 and _tci._tcp on 50001. It does not
 * advertise the panel server, so a found radio gives us its name and the TCI port, and the panel's
 * 50002 is the default remote_server.h names.
 */
class RadioDiscovery(context: Context) {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val seen = MutableStateFlow<List<FoundRadio>>(emptyList())

    /** Radios answered so far, one per address. */
    val radios: StateFlow<List<FoundRadio>> = seen.asStateFlow()

    private var listening = false
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun start() {
        synchronized(this) {
            if (!listening) {
                listening = true
                SERVICES.forEach { discover(it) }
            }
        }
    }

    fun stop() {
        synchronized(this) {
            if (listening) {
                listening = false
                listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
                listeners.clear()
            }
        }
    }

    private fun discover(type: String) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery of $serviceType failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) = resolve(type, info)

            override fun onServiceLost(info: NsdServiceInfo) {
                seen.value = seen.value.filterNot { it.instance == info.serviceName }
            }
        }
        listeners += listener
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "cannot look for $type: ${it.message}") }
    }

    private fun resolve(type: String, info: NsdServiceInfo) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "cannot resolve ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val address = serviceInfo.host?.hostAddress ?: return
                val announced = serviceInfo.port
                val radio = FoundRadio(
                    service = type,
                    instance = serviceInfo.serviceName,
                    host = address,
                    // The panel server is not advertised, so 50002 stands; what the radio does
                    // advertise is kept, so a port set on the radio is the port we use.
                    panelPort = PANEL_PORT,
                    tciPort = if (type == TCI_SERVICE && announced > 0) announced else TCI_PORT,
                )
                seen.value = (seen.value.filterNot { it.host == address && it.service == type } + radio)
                    .sortedBy { it.host }
            }
        })
    }

    /** A radio typed in by hand, which the connect screen always allows. */
    fun manual(instance: String, host: String, panelPort: Int = PANEL_PORT, tciPort: Int = TCI_PORT) =
        FoundRadio(MANUAL, instance.ifEmpty { host }, host, panelPort, tciPort)

    companion object {
        private const val TAG = "RadioDiscovery"
        private const val HTTP_SERVICE = "_http._tcp."
        private const val TCI_SERVICE = "_tci._tcp."
        private const val MANUAL = "manual"
        private val SERVICES = listOf(HTTP_SERVICE, TCI_SERVICE)
    }
}

/** A host the user typed: a dotted name the resolver may know, or four numbers. */
fun looksLikeHost(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty()) return false
    if (t.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
        return t.split('.').all { (it.toIntOrNull() ?: -1) in 0..255 }
    }
    return t.contains('.') && t.all { it.isLetterOrDigit() || it in ".-_" }
}

/** A port the user typed, or the default when the box is empty. */
fun portOr(text: String, fallback: Int): Int =
    text.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: fallback
