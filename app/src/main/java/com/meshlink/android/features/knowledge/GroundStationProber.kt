package com.MeshLink.android.features.knowledge

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Finds the Ground Station on the local network.
 *
 * Deliberately does **not** rely on mDNS alone. Plenty of consumer routers block multicast or enable
 * client isolation, and hackathon/venue Wi-Fi is among the worst for this, so a service that exists
 * is often undiscoverable. Instead this sweeps the local /24 for an open port and then verifies each
 * responder through `/health` — slower, but it works where mDNS silently doesn't.
 *
 * A manually configured address always wins, so a demo never depends on discovery succeeding.
 */
class GroundStationProber(
    private val context: Context,
    private val client: GroundStationSearch,
) {

    companion object {
        private const val TAG = "GroundStationProber"
        private const val PREFS = "akasha_knowledge"
        private const val KEY_MANUAL_URL = "manual_ground_station_url"
        private const val KEY_LAST_URL = "last_ground_station_url"

        private const val PORT = 8000

        /** Per-host TCP connect budget. Short, because a sweep multiplies it by 254. */
        private const val CONNECT_TIMEOUT_MS = 180

        /** Hosts probed concurrently. Enough to sweep a /24 in ~2s without exhausting sockets. */
        private const val PARALLELISM = 32
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** User-supplied address, e.g. `http://192.168.1.20:8000`. Takes priority over discovery. */
    var manualUrl: String?
        get() = prefs.getString(KEY_MANUAL_URL, null)
        set(value) {
            prefs.edit().putString(KEY_MANUAL_URL, value?.trim()?.ifBlank { null }).apply()
        }

    private var lastKnownUrl: String?
        get() = prefs.getString(KEY_LAST_URL, null)
        set(value) { prefs.edit().putString(KEY_LAST_URL, value).apply() }

    /**
     * Try, in order: a manual address, the address that worked last time, then a subnet sweep.
     *
     * Returns the health state of whatever answered, or null. Ordering matters for speed — the
     * common case (same network as last time) costs one request, not a sweep.
     */
    suspend fun discover(): HealthState? {
        manualUrl?.let { url ->
            client.checkHealth(url)?.let {
                client.baseUrl = url
                Log.i(TAG, "Using manually configured station $url")
                return it
            }
            Log.w(TAG, "Manual station $url did not respond")
        }

        lastKnownUrl?.let { url ->
            client.checkHealth(url)?.let {
                client.baseUrl = url
                return it
            }
        }

        val found = sweepLocalSubnet()
        if (found != null) {
            client.baseUrl = found.groundStationUrl
            lastKnownUrl = found.groundStationUrl
            Log.i(TAG, "Discovered station at ${found.groundStationUrl} (backend=${found.backendName})")
        }
        return found
    }

    /**
     * Scan this device's /24 for anything listening on [PORT], then confirm via `/health`.
     *
     * A plain TCP connect is used as the cheap filter because it fails fast on empty addresses;
     * only the handful that accept a connection are asked to identify themselves.
     */
    private suspend fun sweepLocalSubnet(): HealthState? = withContext(Dispatchers.IO) {
        val prefix = localSubnetPrefix() ?: run {
            Log.d(TAG, "No usable IPv4 address; skipping sweep")
            return@withContext null
        }

        Log.d(TAG, "Sweeping $prefix.0/24 on port $PORT")

        for (chunkStart in 1..254 step PARALLELISM) {
            val candidates = (chunkStart until minOf(chunkStart + PARALLELISM, 255)).map { "$prefix.$it" }

            val open = coroutineScope {
                candidates.map { host ->
                    async { if (isPortOpen(host)) host else null }
                }.awaitAll().filterNotNull()
            }

            for (host in open) {
                val url = "http://$host:$PORT"
                client.checkHealth(url)?.let { return@withContext it }
            }
        }
        null
    }

    private fun isPortOpen(host: String): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
            true
        }
    } catch (e: Exception) {
        false
    }

    /**
     * First three octets of this device's LAN address, or null when it has no LAN.
     *
     * Restricted to RFC1918 ranges, and Wi-Fi-like interfaces are preferred. Without that filter this
     * picked up `192.0.0.4` — the synthetic address Android assigns its CLAT interface on IPv6-only
     * mobile data — and spent every poll sweeping `192.0.0.0/24`, a reserved range that cannot contain
     * a Ground Station. A phone on mobile data has no local network, and saying so is more useful than
     * scanning a fictional one.
     */
    private fun localSubnetPrefix(): String? {
        // Enumerate interfaces rather than using the deprecated WifiInfo.ipAddress, which returns a
        // little-endian int and breaks on IPv6-only links.
        return try {
            val candidates = java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                // Wi-Fi first; tethering and USB interfaces are valid fallbacks.
                .sortedByDescending { it.name.startsWith("wlan") }

            for (nif in candidates) {
                for (addr in nif.inetAddresses) {
                    if (addr !is java.net.Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    if (isPrivateIpv4(ip)) return ip.substringBeforeLast('.')
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine local subnet: ${e.message}")
            null
        }
    }

    /** True only for RFC1918 space — the ranges a Ground Station on a local network can occupy. */
    private fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val (a, b) = parts
        return when {
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            else -> false
        }
    }

    /** True when this device has a working Wi-Fi route, used to decide whether to advertise as gateway. */
    fun isOnWifi(): Boolean = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.isWifiEnabled == true && localSubnetPrefix() != null
    } catch (e: Exception) {
        false
    }
}
