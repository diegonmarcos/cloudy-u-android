package com.diegonmarcos.superapp.network

import android.Manifest
import android.annotation.SuppressLint
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.Calendar

/**
 * #1523 Reads the network table's facts (connection, mobile, IP, VPN, metered, data used) into [NetValue]s by row id.
 * Every value has a source; one that cannot be read becomes n/a with the reason, and "needs location" / "needs Phone
 * permission" carry the Fix-in-Perms flag. Binder reads and the WireGuard engine: call off the main thread.
 */
internal object NetTableReader {
    private fun na(reason: String, fix: Boolean = false) = listOf(NetValue.NA(reason, fix))
    private fun v(text: String) = listOf(NetValue.V(text))

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private class Nets(val cm: ConnectivityManager) {
        val active: Network? = cm.activeNetwork
        val caps: NetworkCapabilities? = active?.let { cm.getNetworkCapabilities(it) }
        private val all: List<Pair<Network, NetworkCapabilities>> =
            cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
        fun with(t: Int) = all.firstOrNull { it.second.hasTransport(t) && it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        /** The network the local addresses belong to: the active one, or what a VPN rides on. */
        val base: Pair<Network, NetworkCapabilities>? =
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
                all.firstOrNull { !it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            else active?.let { a -> caps?.let { a to it } }
        val baseLp: LinkProperties? get() = base?.first?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }
        fun vpn() = all.firstOrNull { it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
    }

    fun read(ctx: Context, pub: PublicIp.Info?, pubFailed: Boolean): Map<String, List<NetValue>> {
        val app = ctx.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = Nets(cm)
        val out = LinkedHashMap<String, List<NetValue>>()
        out += connection(app, n)
        out += mobile(app, n)
        out += ip(app, n, pub, pubFailed)
        out += vpn(app, n, pub, pubFailed)
        out += metered(cm, n)
        return out
    }

    // ── connection ──────────────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun connection(ctx: Context, n: Nets): Map<String, List<NetValue>> {
        val c = n.caps
        val t = mutableListOf<String>()
        if (c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) t += "Wi-Fi"
        if (c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) t += "Mobile"
        if (c?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) t += "Ethernet"
        if (c?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            val under = n.base?.second
            t += "VPN" + (when {
                under?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> " over Wi-Fi"
                under?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> " over Mobile"
                else -> ""
            })
        }
        val m = LinkedHashMap<String, List<NetValue>>()
        m["transport"] = if (t.isEmpty()) na("offline: no active network") else v(t.joinToString(" + "))
        val wifi = n.with(NetworkCapabilities.TRANSPORT_WIFI)
        val notWifi = na("not on Wi-Fi")
        // WifiManager.connectionInfo carries the SSID / BSSID once Location is granted (the capabilities' copy is redacted)
        // #1666 a location access (the popup the user opened asks), noted in the app's ledger.
        if (wifi != null) com.diegonmarcos.superapp.perms.LocationUse.note(ctx, "network.NetTableReader.read (popup Network table)",
            "WifiManager.getConnectionInfo (SSID/BSSID)", com.diegonmarcos.superapp.perms.LocationUse.Trigger.SCREEN)
        val info: WifiInfo? = wifi?.let { runCatching { (ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo }.getOrNull() }
            ?: wifi?.second?.let { runCatching { if (Build.VERSION.SDK_INT >= 29) it.transportInfo as? WifiInfo else null }.getOrNull() }
        if (wifi == null || info == null) {
            for (id in listOf("ssid", "bssid", "band", "channel", "link", "rssi", "signal", "freq", "standard")) m[id] = notWifi
            return m
        }
        val locOk = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val locOn = runCatching { (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).let { if (Build.VERSION.SDK_INT >= 28) it.isLocationEnabled else true } }.getOrDefault(true)
        fun hidden(): List<NetValue> = if (!locOk) na("needs location", fix = true) else if (!locOn) na("location is off") else na("hidden by the system")
        val ssid = info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
        m["ssid"] = if (ssid != null) v(ssid.take(32)) else hidden()
        val bssid = info.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
        m["bssid"] = if (bssid != null) v(bssid) else hidden()
        val f = info.frequency
        val band = NetTable.band(f); val ch = NetTable.channel(f)
        m["band"] = band?.let { v(it) } ?: na("frequency not reported")
        m["channel"] = ch?.let { v(it.toString()) } ?: na("frequency not reported")
        m["freq"] = if (f > 0) v("$f MHz") else na("frequency not reported")
        m["link"] = if (info.linkSpeed > 0) v("${info.linkSpeed} Mbps") else na("link speed not reported")
        val rssi = info.rssi
        val rssiOk = rssi in -126..0
        m["rssi"] = if (rssiOk) v("$rssi dBm") else na("RSSI not reported")
        m["signal"] = if (rssiOk) v("${SignalLevels.label(SignalLevels.wifi(rssi))} (level)") else na("RSSI not reported")
        m["standard"] = if (Build.VERSION.SDK_INT < 30) na("needs Android 11") else NetTable.wifiStandard(info.wifiStandard)?.let { v(it) } ?: na("driver does not report it")
        return m
    }

    // ── mobile ──────────────────────────────────────────────────────────────────────────────

    private fun mobile(ctx: Context, n: Nets): Map<String, List<NetValue>> {
        val ids = listOf("carrier", "nettype", "msignal", "roaming")
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm == null || !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return ids.associateWith { na("no telephony hardware") }
        val inp = runCatching { MobileProbe.withLiveSignal(MobileProbe.read(ctx, n.with(NetworkCapabilities.TRANSPORT_CELLULAR) != null, screen = true)) }.getOrNull()
        val sim = inp?.let { MobileLink.dataSim(it.sims, it.defaultDataSubId) }
        if (sim == null) return ids.associateWith { na(if (inp?.airplane == true) "airplane mode" else "no SIM") }
        val phone = MobileProbe.phoneGranted(ctx)
        val m = LinkedHashMap<String, List<NetValue>>()
        m["carrier"] = (sim.carrier ?: runCatching { tm.networkOperatorName }.getOrNull())?.takeIf { it.isNotBlank() }?.let { v(it) } ?: na("not registered")
        m["nettype"] = if (sim.networkType == 0 && sim.overrideType == 0) na("no network type reported") else v(MobileLink.typeName(sim.networkType, sim.overrideType))
        val nr = sim.networkType == MobileLink.NR || sim.overrideType >= MobileLink.OVR_NR_NSA
        m["msignal"] = when {
            sim.dbm != null -> v("${sim.dbm} dBm · ${NetTable.asu(sim.dbm, nr)} ASU")
            Build.VERSION.SDK_INT < 29 -> na("needs Android 10 for dBm")
            !phone -> na("needs Phone permission", fix = true)
            else -> na("signal not reported yet")
        }
        m["roaming"] = when (sim.dataRoaming) {
            true -> v("on" + if (sim.roaming) " · roaming now" else "")
            false -> v("off" + if (sim.roaming) " · roaming now" else "")
            null -> if (phone) na("not readable on this build") else na("needs Phone permission", fix = true)
        }
        return m
    }

    // ── IP ──────────────────────────────────────────────────────────────────────────────────

    private fun hostOf(a: java.net.InetAddress) = a.hostAddress?.substringBefore('%')

    private fun ip(ctx: Context, n: Nets, pub: PublicIp.Info?, pubFailed: Boolean): Map<String, List<NetValue>> {
        val lp = n.baseLp
        val off = "offline: no network"
        val m = LinkedHashMap<String, List<NetValue>>()
        if (lp == null) {
            for (id in listOf("ipv4", "ipv6", "dns", "gateway")) m[id] = na(off)
        } else {
            val addrs = lp.linkAddresses.map { it.address }
            m["ipv4"] = addrs.filterIsInstance<Inet4Address>().mapNotNull { hostOf(it) }.firstOrNull()?.let { v(it) } ?: na("no IPv4 address")
            val v6 = addrs.filterIsInstance<Inet6Address>().filter { !it.isLoopbackAddress }
            m["ipv6"] = (v6.firstOrNull { !it.isLinkLocalAddress } ?: v6.firstOrNull())?.let { a -> v(hostOf(a).orEmpty() + if (a.isLinkLocalAddress) " (link-local)" else "") } ?: na("no IPv6 on this network")
            val dns = lp.dnsServers.mapNotNull { hostOf(it) }
            m["dns"] = if (dns.isEmpty()) na("none assigned") else v(dns.take(2).joinToString(", ") + if (dns.size > 2) " +${dns.size - 2}" else "")
            val gw = lp.routes.filter { it.isDefaultRoute && it.gateway != null }.mapNotNull { it.gateway?.let { g -> hostOf(g) } }.distinct()
            m["gateway"] = if (gw.isEmpty()) na("no default route") else v(gw.joinToString(", "))
        }
        m["public_ip"] = when {
            pub?.ip != null -> v(pub.ip)
            pubFailed -> na("lookup failed or offline")
            else -> na("looking up")
        }
        m["private_dns"] = privateDns(ctx, lp)
        return m
    }

    private fun privateDns(ctx: Context, lp: LinkProperties?): List<NetValue> {
        val cr = ctx.contentResolver
        val mode = runCatching { Settings.Global.getString(cr, "private_dns_mode") }.getOrNull()
        val host = runCatching { Settings.Global.getString(cr, "private_dns_specifier") }.getOrNull()?.takeIf { it.isNotBlank() }
        val active = if (Build.VERSION.SDK_INT >= 28) lp?.isPrivateDnsActive else null
        return when (mode) {
            "hostname" -> v("hostname" + (host?.let { " $it" } ?: "") + if (active == true) " · active" else "")
            "opportunistic" -> v("automatic" + if (active == true) " · active" else "")
            "off" -> v("off")
            else -> if (active != null) v(if (active) "on" else "off") else na("mode not readable")
        }
    }

    // ── VPN / WireGuard mesh ────────────────────────────────────────────────────────────────

    private fun vpn(ctx: Context, n: Nets, pub: PublicIp.Info?, pubFailed: Boolean): Map<String, List<NetValue>> {
        val m = LinkedHashMap<String, List<NetValue>>()
        val link = runCatching { NetworkInfoPopup.wgLink(ctx, n.vpn() != null) }.getOrNull()
        m["wg"] = if (link == null) na("engine not reachable") else v((if (link.onMesh) "up" else "down") + " · " + link.reason)
        val addr = runCatching { WgState.prefs(ctx).interfaceAddress }.getOrNull()?.takeIf { it.isNotBlank() }
        m["mesh_ip"] = if (addr == null) na("no tunnel configured") else if (link?.onMesh == true) v(addr) else v("$addr (down)")
        val warp = pub?.let { PublicIp.warpText(it) }
        m["warp"] = when {
            warp != null -> v(warp)
            pub != null -> na("lookup reports no WARP state")
            pubFailed -> na("lookup failed or offline")
            else -> na("looking up")
        }
        return m
    }

    // ── metered / validated / captive ───────────────────────────────────────────────────────

    private fun metered(cm: ConnectivityManager, n: Nets): Map<String, List<NetValue>> {
        val c = n.caps ?: return listOf("metered", "validated", "captive").associateWith { na("offline: no active network") }
        return mapOf(
            "metered" to v(NetTable.yesNo(!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))),
            "validated" to v(NetTable.yesNo(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))),
            "captive" to v(if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) "yes (sign-in needed)" else "no"),
        )
    }

    // ── data used ───────────────────────────────────────────────────────────────────────────

    private const val USAGE_STORE = "netusage"
    private const val USAGE_KEY = "snaps"

    /** Today (since local midnight) and the last 24 h. Records a counter snapshot first (this is the "on open" delta). */
    fun usage(ctx: Context, nowMs: Long = System.currentTimeMillis()): Pair<NetUsageSpan?, NetUsageSpan?> {
        val app = ctx.applicationContext
        val sp = app.getSharedPreferences(USAGE_STORE, Context.MODE_PRIVATE)
        val cur = TrafficStats.getTotalRxBytes() to TrafficStats.getTotalTxBytes()
        val mob = TrafficStats.getMobileRxBytes() to TrafficStats.getMobileTxBytes()
        var snaps = NetUsage.decode(sp.getString(USAGE_KEY, "").orEmpty())
        if (cur.first != TrafficStats.UNSUPPORTED.toLong() && cur.second != TrafficStats.UNSUPPORTED.toLong()) {
            snaps = NetUsage.record(snaps, NetSnap(nowMs, cur.first, cur.second, mob.first.coerceAtLeast(0L), mob.second.coerceAtLeast(0L)))
            sp.edit().putString(USAGE_KEY, NetUsage.encode(snaps)).apply()
        }
        val midnight = Calendar.getInstance().apply { timeInMillis = nowMs; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val day = nowMs - SpeedMath.WINDOW_MS
        return (stats(app, midnight, nowMs) ?: NetUsage.span(snaps, midnight, nowMs)) to (stats(app, day, nowMs) ?: NetUsage.span(snaps, day, nowMs))
    }

    /** The system's own accounting, when the Usage access switch lets the app read it (a SecurityException otherwise). */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun stats(app: Context, from: Long, to: Long): NetUsageSpan? = runCatching {
        val nsm = app.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val w = nsm.querySummaryForDevice(ConnectivityManager.TYPE_WIFI, null, from, to)
        val mb = nsm.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, from, to)
        NetUsageSpan(w.rxBytes, w.txBytes, mb.rxBytes, mb.txBytes, to - from)
    }.getOrNull()

    const val USAGE_WHY = "needs a second open to measure"
}
