package com.diegonmarcos.superapp.network

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.diegonmarcos.superapp.bgpolicy.BgHost
import com.diegonmarcos.superapp.perms.AdbMdns
import com.diegonmarcos.superapp.system.AdbReadiness
import com.diegonmarcos.superapp.system.WirelessDebuggingSwitch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The network popup's ADB section. The channel facts come from the owner's broker (AdbReadiness over libs:shell-client), the
 * switch from WirelessDebuggingSwitch, the port from AdbMdns; nothing here
 * re-derives the channel's status. The slow reads (the channel probe, the mDNS port lookup) fill
 * their line in after the bubble is up.
 */
internal object AdbSection {

    data class Snap(
        val usb: Boolean,
        val wireless: Boolean,
        /** Wireless debugging exists from Android 11 (API 30). */
        val wirelessSupported: Boolean,
        val wifiIp: String?,
        /** "host:port" of the embedded adb session when it is connected and kept the port. */
        val sessionEndpoint: String?,
    )

    fun read(ctx: Context): Snap {
        val app = ctx.applicationContext
        return Snap(
            usb = runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false),
            wireless = runCatching { WirelessDebuggingSwitch.isOn(app) }.getOrDefault(false),
            wirelessSupported = Build.VERSION.SDK_INT >= 30,
            wifiIp = wifiIpv4(),
            sessionEndpoint = null,   // the owner's connected endpoint is read off the main thread in Render
        )
    }

    @Composable
    fun Render(ctx: Context, s: Snap) {
        val app = ctx.applicationContext
        PopValue("USB debugging: ${onOff(s.usb)}")
        PopValue("Wireless debugging: " + if (s.wirelessSupported) onOff(s.wireless) else "— (Android 11+)")
        // #1682 P3: the owner's connected endpoint comes with the broker's live status (a provider call: off the main thread).
        val ep by produceState<Pair<Boolean, String?>>(false to null) {
            value = true to withContext(Dispatchers.IO) {
                runCatching {
                    com.diegonmarcos.superapp.shellclient.ShellClient.status(app).adbDetail.trim()
                        .takeIf { Regex("""\d{1,3}(\.\d{1,3}){3}:\d+""").matches(it) }
                }.getOrNull()
            }
        }
        if (s.wireless && ep.first) {
            val known = ep.second?.takeIf { it.contains(':') && !it.startsWith("127.") }
            // The connect port rotates on every toggle and is only advertised over mDNS, which is how
            // the lib's own connect finds it: same lookup, our own address only, given up after 4 s.
            var line by remember { mutableStateOf("Wireless: " + (known ?: "${s.wifiIp ?: "—"}:… (looking up the port)")) }
            if (known == null) DisposableEffect(Unit) {
                var found = false
                val mdns = runCatching {
                    AdbMdns(app, AdbMdns.TLS_CONNECT) { host, port ->
                        found = true; line = "Wireless: ${host.hostAddress}:$port"
                    }
                }.getOrNull()
                mdns?.start()
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                val giveUp = Runnable {
                    mdns?.stop()
                    if (!found) line = "Wireless: ${s.wifiIp ?: "—"}:— (port not advertised)"
                }
                main.postDelayed(giveUp, 4_000)
                onDispose { main.removeCallbacks(giveUp); mdns?.stop() }
            }
            PopValue(line)
        }
        val chan by produceState("Shell channel: checking…") {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val r = AdbReadiness.check(app)
                    if (r.usable) "Shell channel: connected (${r.via})" else "Shell channel: off" + (if (r.why.isBlank()) "" else " - ${r.why}")
                }.getOrElse { "Shell channel: — (could not read)" }
            }
        }
        PopValue(chan)
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    /** This phone's own Wi-Fi IPv4 (the address Wireless debugging listens on). */
    fun wifiIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()
}

/**
 * The network popup's Hotspot section. Which tethering is on is public (sticky broadcasts, see
 * TetherModel); the SSID, band and clients are not, so they come from the privileged shell channel
 * when one is up and read "needs the ADB Shell channel" otherwise (the row under them opens it).
 */
internal object HotspotSection {

    @Composable
    fun Render(ctx: Context, st: TetherModel.State) {
        PopValue("On: ${st.summary()}")
        PopValue("Wi-Fi hotspot ${dot(st.wifi)} · USB ${dot(st.usb)} · BT ${dot(st.bluetooth)}")
        if (st.tethered.isNotEmpty()) PopValue("Ifaces: ${st.tethered.joinToString(", ")}")
        if (!st.active) return
        val app = ctx.applicationContext
        // (ssid line, clients line), both "checking…" until the shell answers.
        val lines by produceState("SSID: checking…" to "Clients: checking…") {
            value = withContext(Dispatchers.IO) {
                val ch = runCatching { BgHost.shell(app) }.getOrNull()
                if (ch == null) {
                    val need = "— needs the ADB Shell channel"
                    return@withContext "SSID: $need" to "Clients: $need"
                }
                val out = runCatching {
                    ch.exec("dumpsys wifi 2>/dev/null | grep -E 'mApConfig|mConnectedClient|SoftApInfo|mNumAssociatedStations' | head -40; echo $SPLIT; ip neigh show 2>/dev/null")
                }.getOrNull().orEmpty()
                val dump = out.substringBefore(SPLIT)
                val neigh = out.substringAfter(SPLIT, "")
                val ap = TetherModel.parseSoftAp(dump, Build.VERSION.SDK_INT)
                val n = TetherModel.countClients(neigh, st.tethered)
                val count = maxOf(n, ap.clients ?: 0)
                ("SSID: ${ap.ssid ?: "—"}" + (ap.band?.let { " · $it" } ?: "")) to
                    ("Clients: " + if (out.isBlank()) "— (no answer from ${ch.name()})" else "$count")
            }
        }
        if (st.wifi) PopValue(lines.first)
        PopValue(lines.second)
    }

    private const val SPLIT = "--neigh--"
    private fun dot(b: Boolean) = if (b) "on" else "off"
}

/**
 * The network popup's Cellular section: the strip's own reading (MobileLink) spelled out, then
 * every SIM (carrier + MCC/MNC, network, signal, mobile data, data roaming, voice over 5G / LTE,
 * Wi-Fi calling, preferred network, 5G NSA / SA, RSRP / RSRQ / SINR). The public API answers first;
 * the privileged switches (VoLTE / VoNR / Wi-Fi calling, preferred network mode) come through the
 * fleet's shell channel when one is up and read "— needs ADB Shell" otherwise, as Hotspot does.
 */
internal object CellularSection {

    @Composable
    fun Render(ctx: Context, cellularTransport: Boolean, extra: List<String>, onGrant: () -> Unit) {
        val app = ctx.applicationContext
        val inputs = remember {
            runCatching { MobileProbe.read(app, cellularTransport, screen = true) }
                .getOrElse { MobileLink.Inputs(false, emptyList(), MobileLink.NO_SUB, cellularTransport) }
        }
        val phone = remember { MobileProbe.phoneGranted(app) }
        PopValue(MobileLink.stateLine(MobileLink.derive(inputs)))
        val blocks = remember {
            inputs.sims.map { s ->
                val isData = s.subId == inputs.defaultDataSubId && s.subId >= 0
                Block(s, isData, runCatching { MobileProbe.detail(app, s) }.getOrDefault(MobileLink.Detail()))
            }
        }
        val todo = remember { blocks.filter { b -> b.sim.subId >= 0 && (b.d.volte == null || b.d.wfc == null || b.d.vonr == null || b.d.preferredMode == null) } }
        // Each SIM's lines: the public API answers first; the privileged switches fill in after the shell answers.
        val lines by produceState(blocks.map { MobileLink.simLines(it.sim, it.isData, phone, it.d) }) {
            if (todo.isEmpty()) return@produceState
            val fresh = withContext(Dispatchers.IO) {
                val ch = runCatching { BgHost.shell(app) }.getOrNull()
                val out = ch?.let { c -> runCatching { c.exec(MobileProbe.shellCommand(todo.map { it.sim.subId })) }.getOrNull() }
                blocks.map { b ->
                    if (b !in todo) null else {
                        val d2 = if (out.isNullOrBlank()) b.d.copy(needsShell = ch == null) else MobileProbe.withShell(b.d, out, b.sim.subId)
                        MobileLink.simLines(b.sim, b.isData, phone, d2)
                    }
                }
            }
            value = blocks.mapIndexed { i, b -> fresh[i] ?: MobileLink.simLines(b.sim, b.isData, phone, b.d) }
        }
        for (sim in lines) PopGroup { for (l in sim) PopValue(l) }
        if (!phone) {
            PopValue("Phone permission not granted: other SIMs, network types and carrier settings read —")
            PopLink("Grant ›", onGrant)
        }
        for (e in extra) PopValue(e)
    }

    private class Block(val sim: MobileLink.Sim, val isData: Boolean, val d: MobileLink.Detail)
}

/**
 * The network popup's GPS section: the location switch and mode, the providers, the permission,
 * the current / last fix (source, accuracy, age), the GNSS constellations in view / used with
 * their bands, and whether the receiver is dual-frequency. The live part (GNSS fixes + satellite
 * status) is a [GpsProbe.LiveSession] the popup runs only while this section is on screen.
 */
internal object GpsSection {

    /** Draws the section. [visible] is whether it is on screen: the live session runs only then, and stops when it leaves. */
    @Composable
    fun Render(ctx: Context, visible: Boolean, onGrant: () -> Unit, onSettings: () -> Unit) {
        val app = ctx.applicationContext
        val on = remember { GpsProbe.locationOn(app) }
        val fine = remember { GpsProbe.fineGranted(app) }
        PopValue("Location: " + if (on) "ON · mode ${GpsFix.modeName(GpsProbe.mode(app))}" else "OFF")
        PopValue(GpsProbe.providersLine(app))
        GpsProbe.scanningLine(app)?.let { PopValue(it) }
        if (!fine) {
            PopValue(if (GpsProbe.coarseGranted(app)) "Permission: approximate only — satellites and GNSS fixes need precise location"
                else "Permission: location not granted — fix and satellites read —")
            PopLink("Grant ›", onGrant)
        }
        // Every update of the session bumps the tick, which redraws the live block from the session's own fields.
        var tick by remember { mutableIntStateOf(0) }
        val session = remember { if (fine && on) GpsProbe.LiveSession(app) { tick++ } else null }
        DisposableEffect(session, visible) {
            if (visible) session?.start() else session?.stop()
            onDispose { session?.stop() }
        }
        // #1690 the position is Cloud Nav's: subscribed (fleet IPC) only while the section is on screen.
        DisposableEffect(visible) {
            val sub = if (visible) runCatching {
                com.diegonmarcos.superapp.location.NavLocation.subscribe(app) { a -> GpsProbe.accept(a); tick++ }
            }.getOrNull() else null
            onDispose { runCatching { sub?.close() } }
        }
        tick.let {
            val s = session
            // #1690 Cloud Nav's newest answer (no location-class call here), else this app's cached fix.
            val loc = s?.fix ?: if (visible) GpsProbe.lastKnown(app) else GpsProbe.cached
            (com.diegonmarcos.superapp.location.NavLocation.last as? com.diegonmarcos.superapp.location.NavLocation.Answer.Unavailable)?.let { u ->
                PopValue("Position: ${u.detail}")
                PopLink("Open Cloud Nav ›") { com.diegonmarcos.superapp.location.NavLocation.openOrInstall(ctx) }
            }
            val f = loc?.let { GpsProbe.fixOf(it, usedSats = if (it.provider == "gps") s?.usedSats() else null) }
            val sats = s?.sats.orEmpty()
            PopGroup {
                PopValue("Fix quality ${SignalLevels.label(GpsFix.dots(on, f))}")
                PopValue(GpsFix.fixLine(f))
                for (l in GpsFix.satelliteLines(sats, f, s?.running == true)) PopValue(l)
                for (l in GpsProbe.capabilityLines(app, GpsFix.dualFrequencySeen(sats))) PopValue(l)
            }
        }
        PopLink("Location settings ›", onSettings)
    }
}
