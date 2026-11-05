package com.diegonmarcos.superapp.network
import com.diegonmarcos.superapp.ui.SystemInfoPopup
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.battery.SysfsProc
import com.diegonmarcos.superapp.battery.BatteryEstimatePopup

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.ui.KitBubble
import com.diegonmarcos.superapp.ui.KitPopup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wireguard.android.backend.Tunnel
import java.net.NetworkInterface

/**
 * Status-strip network popup. Sections in NetworkSections.ORDER — the
 * strip's own icon order (mobile, WiFi, BT, WG, KDE, ADB, Data, HS, GPS),
 * then Network, a divider between each two (NetworkSections.layout) and a
 * "Settings ›" link at each header (NetworkSections.SETTINGS):
 *   1. Cellular  — the strip's MobileLink state, then each SIM: carrier,
 *                  MCC/MNC, type, signal, data, roaming, voice, Wi-Fi
 *                  calling, preferred network, 5G type (CellularSection).
 *   2. WiFi      — SSID · channel · RSSI · link speed · WiFi RX/TX rate.
 *   3. Bluetooth — adapter state, then each connected device (name,
 *                  profiles, battery) and their count (BtLinks).
 *   4. Mesh      — ON / NO MESH / OFF and why (WgLink), then
 *                  every wg.../tun... interface up (NOT just the app's
 *                  GoBackend tunnel). Catches the official WireGuard
 *                  app's tunnel alongside ours.
 *                  (KDoc trap: never write the literal asterisk-
 *                  slash glob inside a block comment — it
 *                  terminates the doc; see also SysfsProc
 *                  for the same engine fix.)
 *   4b. KDE      — connected / paired KDE Connect devices.
 *   5. ADB       — USB / Wireless debugging, the wireless IP:port, the
 *                  privileged shell channel (libs:shizuku-adb-debug-tools'
 *                  own ChannelReader + ChannelState) and the ADB Shell page.
 *   6. Data      — USB-C cable in a data mode (UsbDataModel): function,
 *                  host/device role, OTG device names, link speed.
 *   7. Hotspot   — Wi-Fi hotspot / USB / Bluetooth tethering (TetherModel);
 *                  SSID, band and clients through the shell channel.
 *   7b. GPS      — location switch, providers, fix, constellations
 *                  (GpsSection); live GNSS only while it is on screen.
 *   8. Network   — DNS servers from the active network's
 *                  LinkProperties + every IPv4 bound on a live
 *                  interface.
 *
 * The bubble scrolls (capped below the screen height) and opens on the
 * tapped icon's own section ([show]'s focus).
 *
 * Same dark-glass bubble visual shape as BatteryEstimatePopup +
 * SystemInfoPopup. Anchored under the tapped icon (Gravity.START so
 * it extends rightward from the leftmost cluster).
 *
 * Down/up rates are derived from TrafficStats deltas (cumulative
 * since boot, sampled on each open). Cached in [lastSample]; first
 * open of a fresh session shows "—" for rate (one delta needs two
 * samples) and a cumulative byte total instead.
 */
object NetworkInfoPopup {

    private data class Sample(val tsMs: Long, val totalRx: Long, val totalTx: Long, val mobileRx: Long, val mobileTx: Long)
    @Volatile private var lastSample: Sample? = null

    // Section keys: the strip passes the tapped icon's key so the bubble opens on that section.
    const val CELLULAR = NetworkSections.CELLULAR; const val WIFI = NetworkSections.WIFI; const val MESH = NetworkSections.MESH
    const val KDE = NetworkSections.KDE; const val BLUETOOTH = NetworkSections.BLUETOOTH; const val ADB = NetworkSections.ADB
    const val DATA = NetworkSections.DATA; const val HOTSPOT = NetworkSections.HOTSPOT; const val GPS = NetworkSections.GPS

    /** Is the phone on the mesh: the WG icon's, dots' and this popup's one truth (see [WgLink]).
     *  Blocking (engine binder). [vpnTransport] only explains an OFF. */
    fun wgLink(ctx: Context, vpnTransport: Boolean = hasTransport(ctx, NetworkCapabilities.TRANSPORT_VPN)): WgLink.Reading {
        val b = WgState.backend(ctx)
        val up = runCatching { b.getState(WgState.tunnel) == Tunnel.State.UP }.getOrDefault(false)
        val stats = if (up) runCatching { b.getStatistics(WgState.tunnel) }.getOrNull() else null
        return wgLinkOf(ctx, up, stats, vpnTransport)
    }

    private fun wgLinkOf(ctx: Context, up: Boolean, stats: com.wireguard.android.backend.Statistics?, vpnTransport: Boolean): WgLink.Reading {
        val hs = runCatching { stats?.peers()?.map { stats.peer(it)?.latestHandshakeEpochMillis() ?: 0L } }.getOrNull().orEmpty()
        val path = if (up) runCatching { MeshTransport.current(ctx)?.path?.label }.getOrNull().orEmpty() else ""
        return WgLink.derive(up, hs, System.currentTimeMillis(), path, vpnTransport)
    }

    fun show(ctx: Context, anchor: View, focus: String? = null) {
        // Pin the BOX's LEFT EDGE to the screen's left edge (matching Battery/SysInfo's right-edge alignment on the other
        // side), whichever strip icon was tapped; it sits flush under the strip.
        KitPopup.show(anchor, KitPopup.Align.EDGE) { close -> NetworkBubble(ctx, focus, close) }
    }

    /** Under a Compose anchor whose window bounds are [rect]. */
    fun show(ctx: Context, host: View, rect: androidx.compose.ui.unit.IntRect, focus: String? = null) {
        KitPopup.show(host, rect, KitPopup.Align.EDGE) { close -> NetworkBubble(ctx, focus, close) }
    }

    /** The bubble itself, split from [show] so it can be drawn without a window. */
    @Composable
    internal fun NetworkBubble(ctx: Context, focus: String?, dismiss: () -> Unit) {
        val dm = ctx.resources.displayMetrics
        val d = dm.density
        val scroll = rememberScrollState()
        val tops = remember { HashMap<String, Int>() }
        val bottoms = remember { HashMap<String, Int>() }
        var viewportPx by remember { mutableIntStateOf(0) }
        // Bumped when a section's position changes, so the visibility below is re-asked (the maps are not state).
        var layoutTick by remember { mutableIntStateOf(0) }

        // Sample now so every rate row reads from the same snapshot.
        val (sample, prev) = remember {
            val s = sampleTraffic()
            val p = lastSample
            lastSample = s
            s to p
        }
        val usb = remember { readUsbState(ctx) }

        // The live GNSS (the only location request the popup makes) runs while the GPS section is on screen.
        val gpsVisible by remember {
            derivedStateOf {
                layoutTick
                val top = tops[GPS]; val bottom = bottoms[GPS]
                top != null && bottom != null && viewportPx > 0 && top < scroll.value + viewportPx && bottom > scroll.value
            }
        }
        // Open on the tapped icon's own section, once the sections have been laid out.
        LaunchedEffect(Unit) {
            withFrameNanos { }
            focus?.let { tops[it] }?.let { scroll.scrollTo((it - (12 * d).toInt()).coerceAtLeast(0)) }
        }

        // Nine sections outgrow a phone screen: the bubble scrolls, capped at ~85% of the screen height. At least half the
        // screen wide so the (wider) mesh rows + data read comfortably.
        KitBubble(
            Modifier.widthIn(min = (dm.widthPixels * 0.5f / d).dp).heightIn(max = (dm.heightPixels * 0.85f / d).dp)
                .onSizeChanged { viewportPx = it.height }.testTag("netpop:bubble"),
            scroll = scroll,
        ) {
            val grant: () -> Unit = { dismiss(); openTarget(ctx, NetworkSections.PERMISSIONS) }
            val openAdbShell: () -> Unit = {
                dismiss()
                // Configs › Network › ADB Shell inside the shell; the lib's own activity anywhere else.
                val shell = ctx as? com.diegonmarcos.superapp.ShellActivity
                if (shell != null) shell.openSectionPage("config", "adb-shell")
                else com.diegonmarcos.superapp.adbdebug.AdbShellLink.open(ctx)
            }

            // A section wrapper that reports where it sits, and a header with its "Settings ›" link
            // (NetworkSections.SETTINGS).
            @Composable
            fun Section(key: String, content: @Composable () -> Unit) {
                Column(Modifier.onGloballyPositioned {
                    val top = it.positionInParent().y.toInt()
                    val bottom = top + it.size.height
                    if (tops[key] != top || bottoms[key] != bottom) { tops[key] = top; bottoms[key] = bottom; layoutTick++ }
                }.testTag("netpop:section:$key")) { content() }
            }
            @Composable
            fun Header(key: String, row: @Composable () -> Unit) {
                val t = NetworkSections.SETTINGS.getValue(key)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    row()
                    Box(Modifier.padding(start = 10.dp)) {
                        PopLink(t.label) { dismiss(); openTarget(ctx, t, MobileProbe.defaultDataSubId()) }
                    }
                }
            }

            for (item in NetworkSections.layout()) when (item) {
                is NetworkSections.Item.Divider -> PopDivider()
                is NetworkSections.Item.Section -> when (item.key) {
                    // ── Cellular
                    CELLULAR -> Section(CELLULAR) {
                        val cellular = remember { hasTransport(ctx, NetworkCapabilities.TRANSPORT_CELLULAR) }
                        Header(CELLULAR) {
                            PopLightRow("Cellular", cellular) {
                                dismiss(); openSettings(ctx,
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                                    else Settings.ACTION_WIRELESS_SETTINGS)
                            }
                        }
                        CellularSection.Render(ctx, cellular,
                            listOf("Rate: " + fmtRate(sample.mobileRx, sample.mobileTx, prev?.mobileRx, prev?.mobileTx, sample.tsMs, prev?.tsMs)),
                            grant)
                    }

                    // ── WiFi
                    WIFI -> Section(WIFI) {
                        Header(WIFI) {
                            PopLightRow("WiFi", remember { wifiEnabled(ctx) }) {
                                dismiss(); openSettings(ctx,
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI
                                    else Settings.ACTION_WIFI_SETTINGS)
                            }
                        }
                        for (row in remember { readWifi(ctx, sample, prev) }) PopValue(row)
                    }

                    // ── Bluetooth — adapter light, then the connected devices (name, profiles, battery) and their
                    //    count: the same BtLinks reading the strip's BT icon + dots are drawn from.
                    BLUETOOTH -> Section(BLUETOOTH) {
                        val bt = remember { BtLinks.read(ctx) }
                        Header(BLUETOOTH) {
                            PopLightRow("Bluetooth", bt.adapterOn) { dismiss(); openSettings(ctx, Settings.ACTION_BLUETOOTH_SETTINGS) }
                        }
                        for (row in BtLinks.lines(bt)) PopValue(row)
                    }

                    // ── Mesh — header carries one status light PER mesh (wg0 + wg-public ride the single Android
                    //    tunnel). Each light pings that mesh's hub (.1 of its allowed-IP range); tap toggles the shared
                    //    tunnel. Below, a labelled block per mesh: endpoint, last talk, key, IP range.
                    MESH -> Section(MESH) {
                        val meshes = remember { meshList(ctx) }
                        Header(MESH) { MeshHeaderRow(ctx, meshes) { dismiss(); toggleMesh(ctx) } }
                        val meshUp = remember { meshUp(ctx) }
                        val stats = remember { if (meshUp) runCatching { WgState.backend(ctx).getStatistics(WgState.tunnel) }.getOrNull() else null }
                        // The same reading the strip's WG icon + dots are drawn from, with its reason spelled out.
                        val link = remember { wgLinkOf(ctx, meshUp, stats, hasTransport(ctx, NetworkCapabilities.TRANSPORT_VPN)) }
                        PopValue("State: " + when (link.state) {
                            WgLink.State.ON -> "ON"; WgLink.State.NO_MESH -> "NO MESH"; WgLink.State.OFF -> "OFF" } + " — " + link.reason)
                        PopValue("Signal ${SignalLevels.label(link.level)}")
                        if (meshes.isEmpty()) {
                            for (row in remember { readMesh(ctx) }) PopValue(row)
                        } else {
                            for (m in meshes) {
                                PopLabel(m.tag)
                                for (row in meshDetail(m, stats)) PopValue(row)
                            }
                        }
                    }

                    // ── KDE Connect — status + connected device count; tap → KDE configs.
                    KDE -> Section(KDE) {
                        val kdeConn = remember { runCatching {
                            com.diegonmarcos.superapp.kdeconnect.KdeConnectManager.connectedIds().size
                        }.getOrDefault(0) }
                        val kdeTotal = remember { runCatching {
                            com.diegonmarcos.superapp.kdeconnect.KdeConnectConfig.get().devices.size
                        }.getOrDefault(0) }
                        Header(KDE) {
                            PopLightRow("KDE Connect", kdeConn > 0) {
                                dismiss(); (ctx as? com.diegonmarcos.superapp.ShellActivity)?.openSectionPage("config", "kde")
                            }
                        }
                        PopValue("$kdeConn / $kdeTotal device(s) connected")
                    }

                    // ── ADB — USB / Wireless debugging + the privileged shell channel.
                    ADB -> Section(ADB) {
                        val adb = remember { AdbSection.read(ctx) }
                        // The light opens the ADB Shell page too: every place that would switch debugging or the
                        // channel hands off to that one page (test-adb-shell-one-place.sh), never to Developer options.
                        Header(ADB) { PopLightRow("ADB", adb.usb || adb.wireless, openAdbShell) }
                        AdbSection.Render(ctx, adb)
                        PopLink("ADB Shell ›", openAdbShell)
                    }

                    // ── Data (USB-C cable in a data mode, or OTG host)
                    DATA -> Section(DATA) {
                        Header(DATA) {
                            PopLightRow("Data", usb.data) { dismiss(); openTarget(ctx, NetworkSections.SETTINGS.getValue(DATA)) }
                        }
                        for (row in remember { readUsb(ctx, usb) }) PopValue(row)
                    }

                    // ── Hotspot / tethering
                    HOTSPOT -> Section(HOTSPOT) {
                        val tether = remember { readTether(ctx, usb) }
                        val tetherSettings = { dismiss(); openTarget(ctx, NetworkSections.SETTINGS.getValue(HOTSPOT)) }
                        Header(HOTSPOT) { PopLightRow("Hotspot", tether.active, tetherSettings) }
                        HotspotSection.Render(ctx, tether)
                        PopButtons("Tethering settings ›" to tetherSettings, "ADB Shell ›" to openAdbShell)
                    }

                    // ── GPS — location switch, providers, fix and satellites; live GNSS only while on screen.
                    GPS -> Section(GPS) {
                        val locationSettings = { dismiss(); openTarget(ctx, NetworkSections.SETTINGS.getValue(GPS)) }
                        Header(GPS) { PopLightRow("GPS", remember { GpsProbe.locationOn(ctx) }, locationSettings) }
                        GpsSection.Render(ctx, gpsVisible, grant, locationSettings)
                    }
                    else -> {}
                }
            }

            // ── 8. Network: the declared table (build.json::home_status_bar.network_table), see NetworkTable
            PopTitle("Network")
            NetworkTable(ctx, grant)
        }
    }

    /**
     * #1523 The declared table. The live meter runs only while this is composed, i.e. while the dropdown is open: it starts here
     * and stops when the dropdown goes. The public IP is looked up once per open (cached 5 min), the counters are recorded
     * once per open, and the speed test runs only on its tap.
     */
    @Composable
    private fun NetworkTable(ctx: Context, onFix: () -> Unit) {
        val decl = remember { com.diegonmarcos.superapp.launcher.StatusBarDecl.declared }
        val table = decl.networkTable ?: return
        val speed = decl.networkSpeed
        val meter = remember { SpeedMeter(ctx, speed?.sampleMs ?: 1_000L) }
        val test = remember { SpeedTest(ctx) }
        DisposableEffect(meter) { meter.start(); onDispose { meter.stop() } }
        val pub by produceState<Pair<PublicIp.Info?, Boolean>>(null to false) {
            if (speed != null) value = withContext(Dispatchers.IO) { PublicIp.get(speed.publicIpUrl, speed.publicIpTtlMs) }.let { it to (it == null) }
        }
        val facts by produceState(emptyMap<String, List<NetValue>>(), pub) {
            value = withContext(Dispatchers.IO) { NetTableReader.read(ctx, pub.first, pub.second) }
        }
        val usage by produceState<Pair<NetUsageSpan?, NetUsageSpan?>?>(null) { value = withContext(Dispatchers.IO) { NetTableReader.usage(ctx) } }
        val base = if (facts.isEmpty()) NetTable.ids(table).associateWith { listOf(NetValue.NA("reading")) } else facts
        val values = base + NetTable.usageRows(usage?.first, usage?.second, if (usage == null) "reading" else NetTableReader.USAGE_WHY) +
            NetTable.speedRows(meter.nowDownMbps, meter.nowUpMbps, usage?.second, meter.peakDownMbps, meter.peakUpMbps) +
            NetTable.testRows(test.runs, System.currentTimeMillis(), test.running, test.phase, test.failure)
        NetTableView(NetTable.build(table, values), test.running, { speed?.let { test.tap(it.test) } }, onFix)
    }

    // ──────────────── Light indicators + radio toggles ────────────────

    /** "Mesh" header carrying one ping dot PER mesh. Each dot starts amber (probing) / green (fresh handshake), then
     *  resolves to green (hub reachable OR fresh handshake) or dim. Whole row taps → [onTap] (toggle tunnel). */
    @Composable
    private fun MeshHeaderRow(ctx: Context, meshes: List<Mesh>, onTap: () -> Unit) {
        Row(Modifier.clickable(onClick = onTap).testTag("netpop:mesh-header"), verticalAlignment = Alignment.CenterVertically) {
            PopTitle("Mesh")
            for (m in meshes) {
                val fresh = remember(m) { peerHandshakeFresh(ctx, m.pubkey) }
                val hub = hubIp(m.allowedIps)
                val colour by produceState(if (fresh) POP_GREEN else if (hub != null) POP_AMBER else POP_DIM, m) {
                    if (hub != null) {
                        val ok = withContext(Dispatchers.IO) { pingHub(hub) }
                        value = if (ok || fresh) POP_GREEN else POP_DIM
                    }
                }
                PopDot(colour, Modifier.padding(start = 8.dp))
            }
        }
    }

    private fun hasTransport(ctx: Context, t: Int): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(t) == true }
    }.getOrDefault(false)

    private fun wifiEnabled(ctx: Context): Boolean = runCatching {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
    }.getOrDefault(false)

    private fun meshUp(ctx: Context): Boolean = runCatching {
        WgState.backend(ctx).getState(WgState.tunnel) == Tunnel.State.UP
    }.getOrDefault(false)

    /** Hub IP of a peer = the `.1` of its first allowed-IP subnet
     *  ("10.1.0.0/24" → "10.1.0.1"). Null if unparseable. */
    private fun hubIp(allowedIps: String): String? {
        val ip = allowedIps.split(",").firstOrNull()?.trim()?.substringBefore('/') ?: return null
        val o = ip.split('.')
        return if (o.size == 4) "${o[0]}.${o[1]}.${o[2]}.1" else null
    }

    /** A mesh = one WG peer. [tag] is the friendly id (wg0 / wg-public). */
    private data class Mesh(
        val tag: String, val name: String, val endpoint: String,
        val allowedIps: String, val pubkey: String)

    /** Per-mesh detail: endpoint, last talk (handshake age), traffic, key, range. */
    private fun meshDetail(m: Mesh, stats: com.wireguard.android.backend.Statistics?): List<String> {
        val rows = mutableListOf<String>()
        if (m.endpoint.isNotBlank()) rows += "Endpoint: ${m.endpoint}"
        val ps = runCatching { stats?.peer(com.wireguard.crypto.Key.fromBase64(m.pubkey)) }.getOrNull()
        val hs = ps?.latestHandshakeEpochMillis() ?: 0L
        rows += "Last Talk: " + if (hs > 0L) "${(System.currentTimeMillis() - hs) / 1000}s ago" else "—"
        if (ps != null) rows += "Traffic: ↓ ${fmtBytes(ps.rxBytes())} · ↑ ${fmtBytes(ps.txBytes())}"
        if (m.pubkey.isNotBlank())   rows += "Public Key: ${m.pubkey}"
        if (m.allowedIps.isNotBlank()) rows += "IP Range: ${m.allowedIps}"
        return rows
    }

    /** Reachability probe for a mesh hub. ICMP (isReachable) is often blocked
     *  over WG, so try a quick TCP connect to a few hub ports first (53 = the
     *  mesh DNS hub, 443/80/22 = common services), then fall back to ICMP. */
    private fun pingHub(hubIp: String): Boolean {
        for (port in intArrayOf(53, 443, 80, 22)) {
            val ok = runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(hubIp, port), 600); true }
            }.getOrDefault(false)
            if (ok) return true
        }
        return runCatching { java.net.InetAddress.getByName(hubIp).isReachable(600) }.getOrDefault(false)
    }

    /** Meshes to display: configured peers (WireGuardPrefs) unioned with the
     *  baked-default meshes (build.json), deduped by subnet — so wg0 + wg-public
     *  always appear even before the second peer is configured. tag/endpoint fall
     *  back to the baked entry when the saved peer lacks them. */
    private fun meshList(ctx: Context): List<Mesh> {
        val baked = parseBakedMeshes()
        val tagBySubnet = baked.associate { subnetKey(it.allowedIps) to it.tag }
        val out = LinkedHashMap<String, Mesh>()
        runCatching { WgState.prefs(ctx).peers() }.getOrDefault(emptyList()).forEach { p ->
            val k = subnetKey(p.allowedIps)
            out[k] = Mesh(tagBySubnet[k] ?: deriveTag(p.allowedIps), p.name, p.endpoint, p.allowedIps, p.publicKey)
        }
        baked.forEach { out.putIfAbsent(subnetKey(it.allowedIps), it) }
        return out.values.toList()
    }

    private fun subnetKey(allowedIps: String) = allowedIps.split(",").firstOrNull()?.trim().orEmpty()
    private fun deriveTag(allowedIps: String): String {
        val net = allowedIps.split(",").firstOrNull()?.trim()?.substringBefore('/').orEmpty()
        return when {
            net.startsWith("10.0.") -> "wg0"
            net.startsWith("10.1.") -> "wg-public"
            else -> "mesh"
        }
    }

    /** The canonical meshes baked from build.json::ui.wireguard_default.peers. */
    private fun parseBakedMeshes(): List<Mesh> = runCatching {
        val json = String(android.util.Base64.decode(
            com.diegonmarcos.superapp.BuildConfig.UI_WG_PEERS_JSON_B64, android.util.Base64.DEFAULT))
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val allowed = o.optString("allowed_ips")
            Mesh(o.optString("mesh").ifBlank { deriveTag(allowed) }, o.optString("name"),
                o.optString("endpoint"), allowed, o.optString("public_key"))
        }
    }.getOrDefault(emptyList())

    /** A mesh peer is "up" when the tunnel is up AND that peer has a recent
     *  WireGuard handshake (< ~3 min). Per-peer signal from GoBackend stats,
     *  matched by the peer's public key. */
    private fun peerHandshakeFresh(ctx: Context, publicKey: String): Boolean = runCatching {
        val backend = WgState.backend(ctx)
        if (backend.getState(WgState.tunnel) != Tunnel.State.UP) false
        else {
            val stats = backend.getStatistics(WgState.tunnel)
            val ps = stats.peer(com.wireguard.crypto.Key.fromBase64(publicKey))
            val hs = ps?.latestHandshakeEpochMillis() ?: 0L
            WgLink.derive(true, listOf(hs), System.currentTimeMillis()).onMesh
        }
    }.getOrDefault(false)

    /** The app owns the WG tunnel, so it can flip it directly. Bringing it UP
     *  needs prior VPN consent (granted via the Mesh page on first connect);
     *  if that's missing setState throws → we point the user there. */
    private fun toggleMesh(ctx: Context) {
        runCatching {
            val b = WgState.backend(ctx)
            if (b.getState(WgState.tunnel) == Tunnel.State.UP) {
                WgState.requestTunnelDown(ctx)
                Toast.makeText(ctx, "Mesh: disconnecting", Toast.LENGTH_SHORT).show()
            } else {
                b.setState(WgState.tunnel, Tunnel.State.UP, WgState.prefs(ctx).toTunnelConfig())
                Toast.makeText(ctx, "Mesh: connecting", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            Toast.makeText(ctx, "Mesh: open the Mesh page to grant VPN access", Toast.LENGTH_LONG).show()
        }
    }

    private fun openSettings(ctx: Context, action: String) {
        runCatching {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(ctx, "No system screen for that toggle", Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────── Cellular ───────────────────────────

    // ─────────────────────────── WiFi ───────────────────────────

    private fun readWifi(ctx: Context, now: Sample, prev: Sample?): List<String> {
        val rows = mutableListOf<String>()
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) { rows += "—"; return rows }
        @Suppress("DEPRECATION")
        val info = runCatching { wm.connectionInfo }.getOrNull()
        if (info == null) { rows += "—"; return rows }
        @Suppress("DEPRECATION")
        val ssid = (info.ssid ?: "<unknown>").trim('"').take(32)
        val rssi = info.rssi
        val speed = info.linkSpeed
        val freq = info.frequency
        val (band, channel) = decodeWifiFreq(freq)
        rows += "SSID: $ssid"
        rows += "Channel: $channel  ($band)"
        rows += "Signal ${SignalLevels.label(SignalLevels.wifi(rssi))} ($rssi dBm) · $speed Mbps"
        // WiFi rate ≈ total − mobile (TrafficStats has no WiFi-specific
        // bucket; ethernet usually 0 on phone so this is accurate).
        val wifiRxNow  = now.totalRx  - now.mobileRx
        val wifiTxNow  = now.totalTx  - now.mobileTx
        val wifiRxPrev = prev?.let { it.totalRx - it.mobileRx }
        val wifiTxPrev = prev?.let { it.totalTx - it.mobileTx }
        rows += "Rate: " + fmtRate(wifiRxNow, wifiTxNow, wifiRxPrev, wifiTxPrev, now.tsMs, prev?.tsMs)
        return rows
    }

    /** WiFi frequency → (band-label, channel-number). Standard 802.11
     *  channel arithmetic: 2.4 GHz starts at 2412 / 5, 5 GHz at 5000 / 5,
     *  6 GHz at 5950 / 5. Out-of-band → "—". */
    private fun decodeWifiFreq(freq: Int): Pair<String, String> = when {
        freq in 2412..2484 -> "2.4 GHz" to ((freq - 2407) / 5).toString()
        freq in 5160..5885 -> "5 GHz"   to ((freq - 5000) / 5).toString()
        freq in 5925..7125 -> "6 GHz"   to ((freq - 5950) / 5).toString()
        freq <= 0          -> "—" to "—"
        else               -> "${freq} MHz" to "—"
    }

    // ─────────────────────────── Mesh ───────────────────────────

    /** Every wg.../tun.../utun... interface that's UP on the kernel side.
     *  Catches BOTH the SuperApp's own GoBackend tunnel AND any
     *  tunnel brought up by another app (official WireGuard /
     *  Tailscale / system-VPN-of-the-day). For the SuperApp's
     *  tracked tunnel we also append RX/TX bytes from the backend
     *  statistics — the kernel doesn't expose per-interface bytes
     *  through java.net.NetworkInterface and /sys/class/net/... is
     *  SELinux-blocked on hardened Samsung. */
    private fun readMesh(ctx: Context): List<String> {
        val rows = mutableListOf<String>()
        val prefs = runCatching { WgState.prefs(ctx) }.getOrNull()
        val backend = runCatching { WgState.backend(ctx) }.getOrNull()
        val up = runCatching { backend?.getState(WgState.tunnel) == Tunnel.State.UP }.getOrDefault(false)
        val stats = if (up) runCatching { backend?.getStatistics(WgState.tunnel) }.getOrNull() else null

        // ── Interface ────────────────────────────────────────────────────────
        rows += "Tunnel: ${prefs?.tunnelName ?: "wg-mesh"} · ${if (up) "UP" else "DOWN"}"
        prefs?.let { p ->
            if (p.interfaceAddress.isNotBlank()) rows += "Addr: ${p.interfaceAddress}"
            val meta = buildList {
                if (p.interfaceMtu.isNotBlank()) add("MTU ${p.interfaceMtu}")
                if (p.interfaceListenPort.isNotBlank()) add("port ${p.interfaceListenPort}")
            }
            if (meta.isNotEmpty()) rows += meta.joinToString(" · ")
            if (p.interfaceDns.isNotBlank()) rows += "DNS: ${p.interfaceDns}"
            val pub = runCatching { p.derivedInterfacePublicKey() }.getOrDefault("")
            if (pub.isNotBlank()) rows += "Pubkey: ${pub.take(16)}…"
        }
        if (stats != null) rows += "Total: ↓ ${fmtBytes(stats.totalRx())} · ↑ ${fmtBytes(stats.totalTx())}"

        // ── Per-peer (each peer = one mesh) ──────────────────────────────────
        val peers = runCatching { prefs?.peers() }.getOrNull() ?: emptyList()
        for (p in peers) {
            rows += "▸ ${p.name}"
            if (p.endpoint.isNotBlank())   rows += "   endpoint ${p.endpoint}"
            if (p.allowedIps.isNotBlank())  rows += "   allowed ${p.allowedIps}"
            if (p.persistentKeepalive.isNotBlank()) rows += "   keepalive ${p.persistentKeepalive}s"
            if (p.publicKey.isNotBlank())   rows += "   key ${p.publicKey.take(16)}…"
            val ps = runCatching {
                stats?.peer(com.wireguard.crypto.Key.fromBase64(p.publicKey))
            }.getOrNull()
            if (ps != null) {
                val hs = ps.latestHandshakeEpochMillis()
                val ago = if (hs > 0L) "${(System.currentTimeMillis() - hs) / 1000}s ago" else "never"
                rows += "   handshake $ago · ↓ ${fmtBytes(ps.rxBytes())} · ↑ ${fmtBytes(ps.txBytes())}"
            } else if (up) rows += "   handshake — (no traffic yet)"
        }

        // System WG/tun interfaces not tracked by our backend (e.g. the official
        // WireGuard app's tunnel), with their bound IPs.
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && (it.name.startsWith("wg") || it.name.startsWith("tun") || it.name.startsWith("utun")) }
                .forEach { nif ->
                    val addrs = nif.inetAddresses.asSequence().filter { !it.isLoopbackAddress }
                        .mapNotNull { it.hostAddress }.joinToString(", ")
                    rows += "iface ${nif.name}: ${if (addrs.isBlank()) "up" else addrs}"
                }
        }

        if (rows.isEmpty()) rows += "No tunnel configured"
        return rows
    }

    // ─────────────────────────── Data (USB) ───────────────────────────

    /** The sticky ACTION_USB_STATE + charger type + attached OTG devices, through UsbDataModel —
     *  the same rule the strip's Data icon lights on. No permission needed (sticky broadcasts;
     *  UsbManager.getDeviceList and device names need none, only opening a device would). */
    private fun readUsbState(ctx: Context): UsbDataModel.State {
        val app = ctx.applicationContext
        val usb = runCatching {
            app.registerReceiver(null, android.content.IntentFilter("android.hardware.usb.action.USB_STATE"))
        }.getOrNull()
        val batt = runCatching {
            app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val extras = UsbDataModel.EXTRA_KEYS.associateWith { usb?.getBooleanExtra(it, false) == true }
        val otg = runCatching {
            (app.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager)?.deviceList?.values?.map { dev ->
                val name = listOfNotNull(dev.manufacturerName, dev.productName).joinToString(" ").ifBlank { dev.deviceName }
                name + " (%04x:%04x)".format(dev.vendorId, dev.productId)
            }
        }.getOrNull() ?: emptyList()
        return UsbDataModel.state(extras, batt?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0, otg)
    }

    /** Data section: headline · mode · role · OTG devices · speed · power · tether NIC.
     *  Charger WATTAGE stays in the battery popup. */
    private fun readUsb(ctx: Context, st: UsbDataModel.State): List<String> {
        val rows = mutableListOf<String>()
        rows += st.headline()
        if (st.connected || st.role == UsbDataModel.Role.HOST) {
            rows += "Mode: " + st.functions.ifEmpty { listOf("none (charge-only)") }.joinToString(", ") +
                if (st.adbOverUsb) " + adb" else ""
            rows += "Role: ${st.roleLabel()}"
        }
        for (dev in st.otgDevices) rows += "  • $dev"
        if (st.data) rows += "Speed: " + (usbSpeed(st.role) ?: "— (not readable on this device)")
        if (st.power != null) rows += "Power: ${st.power}"
        if (st.functions.isNotEmpty()) {
            rows += "Configured: ${if (st.configured) "yes" else "no"}"
            // Tether modes expose a usb/rndis/ncm NIC; surface its IP (per-iface byte counters are
            // SELinux-blocked on hardened Samsung, same wall the Mesh section hits).
            for (s in usbNetStats()) rows += s
        }
        return rows
    }

    /** Negotiated link speed from sysfs, when the device lets an app read it (many do not: SELinux). */
    private fun usbSpeed(role: UsbDataModel.Role): String? = runCatching {
        if (role == UsbDataModel.Role.HOST) {
            java.io.File("/sys/bus/usb/devices").listFiles()?.asSequence()
                ?.filter { !it.name.startsWith("usb") && !it.name.contains(':') }
                ?.mapNotNull { UsbDataModel.hostSpeed(runCatching { java.io.File(it, "speed").readText() }.getOrNull()) }
                ?.firstOrNull()
        } else {
            java.io.File("/sys/class/udc").listFiles()?.asSequence()
                ?.mapNotNull { UsbDataModel.udcSpeed(runCatching { java.io.File(it, "current_speed").readText() }.getOrNull()) }
                ?.firstOrNull()
        }
    }.getOrNull()

    // ─────────────────────────── Hotspot / tethering ───────────────────────────

    /** Sticky TETHER_STATE_CHANGED + WIFI_AP_STATE_CHANGED + the USB tethering function. No permission. */
    private fun readTether(ctx: Context, usb: UsbDataModel.State): TetherModel.State {
        val app = ctx.applicationContext
        val t = runCatching { app.registerReceiver(null, android.content.IntentFilter("android.net.conn.TETHER_STATE_CHANGED")) }.getOrNull()
        val ap = runCatching { app.registerReceiver(null, android.content.IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED")) }.getOrNull()
        return TetherModel.State(
            tethered = runCatching { t?.getStringArrayListExtra("tetherArray") }.getOrNull()?.toList() ?: emptyList(),
            apState = ap?.getIntExtra("wifi_state", -1)?.takeIf { it >= 0 },
            usbFunction = usb.functions.any { it.startsWith("RNDIS") || it.startsWith("NCM") },
        )
    }

    /**
     * The one way a section's link opens its settings: an in-app page through its section/page
     * route (the shell's own navigation, or MainActivity's page: shortcut from anywhere else), or
     * the first Android screen of the target's chain that resolves on this build. Never throws.
     */
    private fun openTarget(ctx: Context, t: NetworkSections.Target, subId: Int = -1) {
        when (t) {
            is NetworkSections.Target.InApp -> openInApp(ctx, t.section, t.page)
            is NetworkSections.Target.System -> {
                val pm = ctx.packageManager
                for (step in t.tries) {
                    val i = intentOf(step) ?: continue
                    if (t.perSim && subId >= 0) i.putExtra(EXTRA_SUB_ID, subId)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (runCatching { i.resolveActivity(pm) }.getOrNull() == null) continue
                    if (runCatching { ctx.startActivity(i) }.isSuccess) return
                }
                Toast.makeText(ctx, "No system screen for that", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Settings.EXTRA_SUB_ID (API 29): the SIM the mobile network page opens on. */
    private const val EXTRA_SUB_ID = "android.provider.extra.SUB_ID"

    private fun intentOf(step: String): Intent? = when {
        step.startsWith("action:") -> Intent(step.removePrefix("action:"))
        step.startsWith("component:") -> step.removePrefix("component:").split('/', limit = 2)
            .takeIf { it.size == 2 }?.let { Intent().setClassName(it[0], it[1]) }
        else -> null
    }

    private fun shellOf(ctx: Context): com.diegonmarcos.superapp.ShellActivity? {
        var c: Context? = ctx
        while (c != null) {
            if (c is com.diegonmarcos.superapp.ShellActivity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        return null
    }

    private fun openInApp(ctx: Context, section: String, page: String) {
        val shell = shellOf(ctx)
        if (shell != null) { runCatching { shell.openSectionPage(section, page) }; return }
        runCatching {
            ctx.startActivity(Intent(ctx, com.diegonmarcos.superapp.MainActivity::class.java)
                .putExtra("shortcut_action", "page:$section/$page")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.onFailure { Toast.makeText(ctx, "Could not open that page", Toast.LENGTH_SHORT).show() }
    }

    /** Live USB-tether interface(s) named rndis / usb / ncm + their IPv4. */
    private fun usbNetStats(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && (it.name.startsWith("rndis") || it.name.startsWith("usb") || it.name.startsWith("ncm")) }
            .map { nif ->
                val ip = nif.inetAddresses.asSequence()
                    .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') != true }?.hostAddress
                if (ip != null) "Tether ${nif.name}: $ip" else "Tether ${nif.name}: up"
            }.toList()
    }.getOrDefault(emptyList())

    private fun readLocalIps(): List<String> = try {
        val out = mutableListOf<String>()
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (nif.isLoopback || !nif.isUp) continue
            val name = nif.name
            for (addr in nif.inetAddresses) {
                val ip = addr.hostAddress ?: continue
                if (ip.contains(":")) continue
                out += "$name  $ip"
            }
        }
        out
    } catch (_: Throwable) { emptyList() }

    // ─────────────────────────── traffic sampling ───────────────────────────

    private fun sampleTraffic(): Sample = Sample(
        tsMs    = SystemClock.elapsedRealtime(),
        totalRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L),
        totalTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L),
        mobileRx = TrafficStats.getMobileRxBytes().coerceAtLeast(0L),
        mobileTx = TrafficStats.getMobileTxBytes().coerceAtLeast(0L),
    )

    /** Format an interface's rate row. Needs ≥1s + ≥1 byte delta on
     *  both sides to compute a meaningful rate; otherwise falls back
     *  to the cumulative byte totals from this sample. */
    private fun fmtRate(rxNow: Long, txNow: Long, rxPrev: Long?, txPrev: Long?, tsNow: Long, tsPrev: Long?): String {
        if (rxPrev != null && txPrev != null && tsPrev != null && tsNow - tsPrev >= 1000L) {
            val dtSec = (tsNow - tsPrev) / 1000.0
            val rxBps = ((rxNow - rxPrev).coerceAtLeast(0L) / dtSec).toLong()
            val txBps = ((txNow - txPrev).coerceAtLeast(0L) / dtSec).toLong()
            return "↓ ${fmtBytes(rxBps)}/s · ↑ ${fmtBytes(txBps)}/s"
        }
        // No prior sample → show cumulative totals so the row carries
        // some information instead of "—".
        return "↓ ${fmtBytes(rxNow)} · ↑ ${fmtBytes(txNow)}  (cumulative)"
    }

    // ─────────────────────────── helpers ───────────────────────────

    private fun fmtBytes(b: Long): String = when {
        b > 1_000_000_000 -> "%.2f GB".format(b / 1e9)
        b > 1_000_000     -> "%.2f MB".format(b / 1e6)
        b > 1_000         -> "%.1f kB".format(b / 1e3)
        else              -> "$b B"
    }
}
