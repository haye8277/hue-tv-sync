package com.kneejar.tvhue.hue

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object BridgeDiscovery {

    /** Returns the bridge's IPv4 address, or null if it couldn't be found. */
    suspend fun find(context: Context): String? =
        findOnNetwork(context) ?: findViaCloud()

    private suspend fun findOnNetwork(context: Context): String? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        var listener: NsdManager.DiscoveryListener? = null
        try {
            return withTimeoutOrNull(6_000) {
                suspendCancellableCoroutine { cont ->
                    val resolving = AtomicBoolean(false)
                    val l = object : NsdManager.DiscoveryListener {
                        override fun onServiceFound(info: NsdServiceInfo) {
                            if (!resolving.compareAndSet(false, true)) return
                            @Suppress("DEPRECATION")
                            nsd.resolveService(info, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                    resolving.set(false)
                                }

                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    val host = info.host
                                    if (host is Inet4Address && cont.isActive) {
                                        cont.resume(host.hostAddress)
                                    } else {
                                        resolving.set(false)
                                    }
                                }
                            })
                        }

                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onDiscoveryStarted(serviceType: String) {}
                        override fun onDiscoveryStopped(serviceType: String) {}
                        override fun onServiceLost(info: NsdServiceInfo) {}
                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    }
                    listener = l
                    nsd.discoverServices("_hue._tcp", NsdManager.PROTOCOL_DNS_SD, l)
                }
            }
        } finally {
            listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        }
    }

    private suspend fun findViaCloud(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("https://discovery.meethue.com/").openConnection().apply {
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            val bridges = JSONArray(conn.getInputStream().bufferedReader().use { it.readText() })
            if (bridges.length() == 0) null
            else bridges.getJSONObject(0).optString("internalipaddress").ifBlank { null }
        }.getOrNull()
    }
}
