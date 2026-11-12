package com.diegonmarcos.superapp.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.SystemClock
import java.net.Inet4Address
import java.net.Inet6Address

/** One RTT (802.11mc / az FTM) ranging result (#1263). */
data class RttResult(val bssid: String, val ok: Boolean, val distanceM: Double, val stdDevM: Double, val rssi: Int, val attempted: Int, val succeeded: Int, val status: String, val at: Long)

/**
 * The Android half of the analyzer (#1263): WifiManager scans (with the throttle result), the connected link through
 * ConnectivityManager (FLAG_INCLUDE_LOCATION_INFO on API 31+ so SSID / BSSID are not redacted), and WifiRttManager ranging.
 * Everything a permission or an API level withholds degrades to an empty value plus a [lastError] the page shows.
 */
class AndroidWifiSource(private val ctx: Context) {
    /** #1666 every location-class call of this source runs only while the Wi-Fi page is attached; each is noted in the app's ledger. */
    private fun note(api: String) = com.diegonmarcos.superapp.perms.LocationUse.note(ctx, SITE, api, com.diegonmarcos.superapp.perms.LocationUse.Trigger.SCREEN)
    companion object { const val SITE = "libs:wifi AndroidWifiSource (Wi-Fi page)" }
    private val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val rtt: WifiRttManager? = if (Build.VERSION.SDK_INT >= 28 && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT))
        ctx.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager else null
    private val lastResults = HashMap<String, ScanResult>()
    @Volatile var lastError: String = ""
        private set

    val supported: Boolean get() = wm != null
    val enabled: Boolean get() = wm?.isWifiEnabled == true
    val rttSupported: Boolean get() = rtt != null
    val rttAvailable: Boolean get() = Build.VERSION.SDK_INT >= 28 && rtt?.isAvailable == true

    /** null below API 30 (the setting exists from Android 10, the getter from 11). */
    val throttleEnabled: Boolean? get() = if (Build.VERSION.SDK_INT >= 30) runCatching { wm?.isScanThrottleEnabled }.getOrNull() else null

    val is6GhzSupported: Boolean get() = Build.VERSION.SDK_INT >= 30 && wm?.is6GHzBandSupported == true
    val is5GhzSupported: Boolean get() = wm?.is5GHzBandSupported == true
    val isStaApConcurrency: Boolean get() = Build.VERSION.SDK_INT >= 30 && wm?.isStaApConcurrencySupported == true
    val wifi7Supported: Boolean get() = Build.VERSION.SDK_INT >= 33 && runCatching { wm?.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE) }.getOrNull() == true

    /** Ask for a scan. false = refused (throttled, Wi-Fi off, or no permission); the cached results still arrive. */
    @Suppress("DEPRECATION")
    fun startScan(): Boolean = runCatching { note("WifiManager.startScan"); wm?.startScan() == true }.onFailure { lastError = it.message ?: "scan refused" }.getOrDefault(false)

    /** Listen for SCAN_RESULTS_AVAILABLE; [onResults] gets whether the results are fresh. Returns the unregister action. */
    fun listen(onResults: (Boolean) -> Unit): () -> Unit {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { onResults(i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) }
        }
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(r, f)
        return { runCatching { ctx.unregisterReceiver(r) } }
    }

    @SuppressLint("MissingPermission")
    fun results(): List<RawScan> {
        note("WifiManager.getScanResults")
        val list = try { wm?.scanResults.orEmpty() } catch (e: SecurityException) { lastError = "scan results need location permission"; emptyList() }
        val bootNow = SystemClock.elapsedRealtime()
        synchronized(lastResults) { list.forEach { lastResults[it.BSSID.orEmpty().lowercase()] = it } }
        return list.map { r -> toRaw(r, bootNow) }
    }

    @Suppress("DEPRECATION")
    private fun toRaw(r: ScanResult, bootNow: Long): RawScan {
        val ssid = if (Build.VERSION.SDK_INT >= 33) r.wifiSsid?.toString()?.let(Ssid::unquote) ?: r.SSID.orEmpty() else r.SSID.orEmpty()
        var mld: String? = null; var linkId = -1; var links = emptyList<MloLink>()
        if (Build.VERSION.SDK_INT >= 33) runCatching {
            mld = r.apMldMacAddress?.toString()
            linkId = r.apMloLinkId
            links = r.affiliatedMloLinks.map { l ->
                val band = when (l.band) { 1 -> Band.B24; 2 -> Band.B5; 8 -> Band.B6; 16 -> Band.B60; else -> Band.Unknown }
                MloLink(l.linkId, l.apMacAddress?.toString().orEmpty(), band, l.channel)
            }
        }
        return RawScan(
            bssid = r.BSSID.orEmpty(), ssid = ssid, freqMhz = r.frequency, centerFreq0 = r.centerFreq0, centerFreq1 = r.centerFreq1,
            widthCode = r.channelWidth, rssi = r.level, capabilities = r.capabilities.orEmpty(),
            ageMs = (bootNow - r.timestamp / 1000).coerceAtLeast(0), stdCode = if (Build.VERSION.SDK_INT >= 30) r.wifiStandard else 0,
            rtt80211mc = r.is80211mcResponder, rtt80211az = Build.VERSION.SDK_INT >= 35 && runCatching { r.is80211azNtbResponder }.getOrDefault(false),
            passpoint = r.isPasspointNetwork, mldMac = mld, mloLinkId = linkId, mloLinks = links, timestampUs = r.timestamp,
        )
    }

    // ---- the connected link ----

    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var wifiNet: Network? = null
    @Volatile private var capsInfo: WifiInfo? = null

    /** Follow the Wi-Fi network; [onChange] fires on any capability / link change. */
    fun watchLink(onChange: () -> Unit) {
        val c = cm ?: return
        if (callback != null) return
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(n: Network, nc: NetworkCapabilities) { wifiNet = n; capsInfo = nc.transportInfo as? WifiInfo; onChange() }
            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) { wifiNet = n; onChange() }
            override fun onLost(n: Network) { if (wifiNet == n) { wifiNet = null; capsInfo = null }; onChange() }
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, nc: NetworkCapabilities) { wifiNet = n; onChange() }
            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) { wifiNet = n; onChange() }
            override fun onLost(n: Network) { if (wifiNet == n) wifiNet = null; onChange() }
        }
        runCatching { c.registerNetworkCallback(req, cb); callback = cb }.onFailure { lastError = it.message ?: "network callback refused" }
    }

    fun stopLink() { callback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }; callback = null }

    val wifiNetwork: Network? get() = wifiNet

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun link(): LinkSnapshot {
        val c = cm; val w = wm ?: return LinkSnapshot(at = System.currentTimeMillis())
        val net = wifiNet
        val nc = net?.let { runCatching { c?.getNetworkCapabilities(it) }.getOrNull() }
        val lp = net?.let { runCatching { c?.getLinkProperties(it) }.getOrNull() }
        val info: WifiInfo? = capsInfo ?: runCatching { note("WifiManager.getConnectionInfo"); w.connectionInfo }.getOrNull()
        val dhcp = runCatching { w.dhcpInfo }.getOrNull()
        val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val v6 = lp?.linkAddresses?.filter { it.address is Inet6Address }?.map { it.toString() }.orEmpty()
        val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
            ?: dhcp?.gateway?.takeIf { it != 0 }?.let { Ipv4.fromLittleEndian(it) }.orEmpty()
        val sec = if (Build.VERSION.SDK_INT >= 31 && info != null) securityName(info.currentSecurityType) else ""
        var mld = ""; var links = emptyList<MloLink>()
        if (Build.VERSION.SDK_INT >= 33 && info != null) runCatching {
            mld = info.apMldMacAddress?.toString().orEmpty()
            links = info.associatedMloLinks.map { l ->
                MloLink(l.linkId, l.apMacAddress?.toString().orEmpty(), when (l.band) { 1 -> Band.B24; 2 -> Band.B5; 8 -> Band.B6; else -> Band.Unknown }, l.channel)
            }
        }
        val ssid = info?.ssid?.let(Ssid::unquote)?.takeIf { it != "<unknown ssid>" }.orEmpty()
        return LinkSnapshot(
            ssid = ssid, bssid = info?.bssid.orEmpty().lowercase().takeIf { it != "02:00:00:00:00:00" }.orEmpty(),
            rssi = info?.rssi ?: -127, freqMhz = info?.frequency ?: 0, linkMbps = info?.linkSpeed ?: -1,
            txMbps = if (Build.VERSION.SDK_INT >= 29) info?.txLinkSpeedMbps ?: -1 else -1,
            rxMbps = if (Build.VERSION.SDK_INT >= 29) info?.rxLinkSpeedMbps ?: -1 else -1,
            maxTxMbps = if (Build.VERSION.SDK_INT >= 30) info?.maxSupportedTxLinkSpeedMbps ?: -1 else -1,
            maxRxMbps = if (Build.VERSION.SDK_INT >= 30) info?.maxSupportedRxLinkSpeedMbps ?: -1 else -1,
            stdCode = if (Build.VERSION.SDK_INT >= 30) info?.wifiStandard ?: 0 else 0, securityType = sec,
            hiddenSsid = info?.hiddenSSID == true, iface = lp?.interfaceName.orEmpty(),
            ipv4 = v4?.address?.hostAddress.orEmpty(), prefix = v4?.prefixLength ?: 0, gateway = gw,
            dns = lp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            dhcpServer = (if (Build.VERSION.SDK_INT >= 30) lp?.dhcpServerAddress?.hostAddress else null)
                ?: dhcp?.serverAddress?.takeIf { it != 0 }?.let { Ipv4.fromLittleEndian(it) }.orEmpty(),
            leaseSeconds = dhcp?.leaseDuration ?: -1, mtu = if (Build.VERSION.SDK_INT >= 29) lp?.mtu ?: 0 else 0,
            ipv6 = v6, domains = lp?.domains.orEmpty(), proxy = lp?.httpProxy?.let { "${it.host}:${it.port}" }.orEmpty(),
            captivePortal = nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            validated = nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
            privateDnsActive = if (Build.VERSION.SDK_INT >= 28) lp?.isPrivateDnsActive == true else false,
            privateDnsServer = if (Build.VERSION.SDK_INT >= 28) lp?.privateDnsServerName.orEmpty() else "",
            downKbps = nc?.linkDownstreamBandwidthKbps ?: 0, upKbps = nc?.linkUpstreamBandwidthKbps ?: 0,
            macRandomization = macRandomization(info), apMldMac = mld, mloLinks = links, at = System.currentTimeMillis(),
        )
    }

    /**
     * Whether this connection uses a randomized MAC. Apps cannot read the per-network privacy setting nor the real MAC (Android 10+
     * returns 02:00:00:00:00:00 for both), so the page says what is knowable: Android 10+ randomizes per network by default, and
     * Android 12+ may re-randomize ("non-persistent"). The setting itself is in Settings > Wi-Fi > the network > Privacy.
     */
    private fun macRandomization(info: WifiInfo?): String = when {
        info == null -> ""
        Build.VERSION.SDK_INT >= 29 -> "per-network randomized by default (Android 10+); apps cannot read the setting"
        else -> "device MAC (randomization is per-network from Android 10)"
    }

    private fun securityName(t: Int): String = when (t) {
        0 -> "Open"; 1 -> "WEP"; 2 -> "WPA/WPA2-Personal"; 3 -> "WPA/WPA2-Enterprise"; 4 -> "WPA3-Personal (SAE)"; 5 -> "WPA3-Enterprise 192"
        6 -> "OWE"; 7 -> "WAPI-PSK"; 8 -> "WAPI-Cert"; 9 -> "WPA3-Enterprise"; 10 -> "OSEN"; 11 -> "Passpoint R1/R2"
        12 -> "Passpoint R3"; 13 -> "DPP"; else -> ""
    }

    // ---- RTT ----

    /** Range to the APs (max 10 per request: RangingRequest.getMaxPeers). [done] gets one result per BSSID. */
    @SuppressLint("MissingPermission")
    fun range(bssids: List<String>, done: (List<RttResult>) -> Unit) {
        val m = rtt
        if (Build.VERSION.SDK_INT < 28 || m == null) { done(emptyList()); lastError = "RTT is not supported on this device"; return }
        val targets = synchronized(lastResults) { bssids.mapNotNull { lastResults[it.lowercase()] } }.filter { it.is80211mcResponder }
        if (targets.isEmpty()) { done(emptyList()); lastError = "no 802.11mc responder among the selected APs (scan first)"; return }
        val req = RangingRequest.Builder().apply { targets.take(RangingRequest.getMaxPeers()).forEach { addAccessPoint(it) } }.build()
        try {
            m.startRanging(req, ctx.mainExecutor, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) { lastError = "ranging failed (code $code)"; done(emptyList()) }
                override fun onRangingResults(results: List<RangingResult>) {
                    val now = System.currentTimeMillis()
                    done(results.map { r ->
                        val ok = r.status == RangingResult.STATUS_SUCCESS
                        RttResult(r.macAddress?.toString().orEmpty().lowercase(), ok, if (ok) r.distanceMm / 1000.0 else 0.0,
                            if (ok) r.distanceStdDevMm / 1000.0 else 0.0, if (ok) r.rssi else 0,
                            if (ok) r.numAttemptedMeasurements else 0, if (ok) r.numSuccessfulMeasurements else 0,
                            if (ok) "ok" else if (r.status == RangingResult.STATUS_RESPONDER_DOES_NOT_SUPPORT_IEEE80211MC) "responder lacks 802.11mc" else "fail", now)
                    })
                }
            })
        } catch (e: SecurityException) { lastError = "RTT needs location (and NEARBY_WIFI_DEVICES on Android 13+)"; done(emptyList()) }
        catch (e: Exception) { lastError = e.message ?: "RTT error"; done(emptyList()) }
    }
}
