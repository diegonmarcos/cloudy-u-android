package com.diegonmarcos.superapp.network

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge
import com.diegonmarcos.superapp.firewall.FirewallController
import com.diegonmarcos.superapp.settings.ConfigButton
import com.diegonmarcos.superapp.settings.ConfigCaption
import com.diegonmarcos.superapp.settings.ConfigCheckRow
import com.diegonmarcos.superapp.settings.ConfigColumn
import com.diegonmarcos.superapp.settings.ConfigHeader
import com.diegonmarcos.superapp.settings.ConfigRadioRow
import com.diegonmarcos.superapp.settings.ConfigReadout
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * #740 Configs ▸ Setup ▸ Network ▸ DNS. Three blocks, every word of the
 * choices from `build.json::ui.dns` through [FleetDns.decl]:
 *  1. Android Private DNS — a replica of Android's own menu showing what the
 *     phone is set to and what the active network really uses; changed through
 *     the privileged shell channel when armed, else Android's settings open.
 *  2. Fleet DNS — Mirror Android or one declared preset (+ the ordered
 *     fallbacks for Private with fallbacks). Saving re-applies it to a running
 *     Cloud Mesh tunnel, which is the VPN every fleet app resolves through, and
 *     (#751) hands the engine the mesh-down form it carries while the mesh is off.
 *  3. Status — (#794) whether the preset is IN EFFECT: what it promises next
 *     to the DNS servers Android really hands this app, in red when they
 *     differ. When the engine that owns the VPN slot (Cloud-Lib-Net-Wg) has no
 *     VPN consent, one tap asks for it, raises the tunnel and checks again.
 *     Plus the last successful lookup and a Test lookup naming the upstream.
 *     (#1899) Under it, the reachability test by IP ([DnsReach]): the declared
 *     public sites reached at their fixed addresses with no name resolved, on
 *     the normal path and on a socket bound outside the VPN, so a DNS failure
 *     is told from a network failure.
 *  4. Bridges and 5. DNS servers (#794) — every fleet member's path to an
 *     answer with its terminal bridge, and every known server probed now;
 *     both rendered from [DnsOverview.collect], the body of
 *     /api/net/dns/overview.
 *
 * Compose since #773: the page's memory is [DnsModel] (state + the work), the page is the composable below.
 */
class DnsFragment : KitComposeFragment() {

    private var model: DnsModel? = null

    /** #794 The engine's consent activity answers RESULT_OK once Android's VPN dialog is
     *  accepted (at once when consent is already held): hand it the choice and check it took. */
    private var pendingMd = ""
    private val exportMdLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri ?: return@registerForActivityResult
        val text = pendingMd; pendingMd = ""
        val ok = runCatching { requireContext().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("could not write the file") }
        toast(requireContext(), if (ok.isSuccess) "Saved the DNS page as Markdown" else "Export failed: ${ok.exceptionOrNull()?.message}")
    }

    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        model?.onConsentResult(r.resultCode)
    }

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = requireContext()
        val scope = rememberCoroutineScope()
        val m = remember {
            DnsModel(ctx, scope, askConsent = { c ->
                WgState.backend(c).consentIntent()?.let { consentLauncher.launch(it) }
                    ?: toast(c, "Cloud-Lib-Net-Wg is not installed — install it from Store ▸ Cloud Constellation ▸ Libs")
            }, openWirelessSettings = { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) },
                exportMd = { name, text -> pendingMd = text; exportMdLauncher.launch(name) },
                copy = { text ->
                    c3copy(ctx, text)
                })
        }
        model = m
        LaunchedEffect(Unit) { m.refreshAndroid(); m.refreshStatus() }
        DnsPage(m)
    }

    companion object {
        fun newInstance() = DnsFragment()
    }
}

private fun c3copy(ctx: Context, text: String) {
    ctx.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("DNS", text))
}

private fun toast(ctx: Context, msg: String) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }

/** Everything the DNS page remembers while it is on screen, and the work behind each button. */
internal class DnsModel(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val askConsent: (Context) -> Unit,
    private val openWirelessSettings: () -> Unit,
    private val exportMd: (String, String) -> Unit = { _, _ -> },
    private val copy: (String) -> Unit = {},
) {
    val decl get() = FleetDns.decl
    val prefs = FleetDns.Prefs(ctx)

    var androidText by mutableStateOf("Reading…")
    var androidMode by mutableStateOf<String?>(null)
    var hostname by mutableStateOf("")
    var presetId by mutableStateOf(FleetDns.effective(decl, prefs.preset).id)
    var fallbacks by mutableStateOf(prefs.fallbacks)
    var statusText by mutableStateOf("")
    var statusOk by mutableStateOf(true)
    var needsConsent by mutableStateOf(false)
    var results by mutableStateOf("${decl.testPublic} · ${decl.testMesh}")
    var reach by mutableStateOf(DnsReach.planText(decl.reach))
    var reachOk by mutableStateOf(true)
    var bridges by mutableStateOf("Scan to ask every app.")
    var servers by mutableStateOf("Scan to probe them.")

    private fun toast(msg: String) = toast(ctx, msg)

    private fun <T> background(work: () -> T, done: (T) -> Unit) {
        scope.launch { done(withContext(Dispatchers.IO) { work() }) }
    }

    fun onConsentResult(resultCode: Int) {
        if (resultCode != Activity.RESULT_OK) {
            toast("VPN permission refused — the preset stays off without the mesh"); refreshStatus(); return
        }
        statusText = "VPN allowed — starting the DNS tunnel and checking Android's resolver…"
        background({ runCatching { FleetDns.syncAndCheck(ctx, raiseNow = !FirewallController.isEnabled(ctx)) } }) { v ->
            v.fold({ toast(if (it.ok) "In effect: ${it.actual.joinToString(", ")}" else "Still not in effect — see Status") },
                   { toast("Not applied: ${it.message}") })
            refreshStatus()
        }
    }

    fun applyAndroid() {
        val mode = androidMode ?: return toast("Pick a Private DNS mode")
        val host = hostname.trim().ifEmpty { decl.hostnameSuggestions.firstOrNull().orEmpty() }
        background({ runCatching { FleetDns.writeAndroid(ctx, mode, host) } }) { r ->
            when {
                r.isFailure -> toast("Refused: ${r.exceptionOrNull()?.message}")
                r.getOrNull() == null -> {
                    toast("No privileged channel — set it in Android's Network settings")
                    openWirelessSettings()
                }
                else -> { toast("Android Private DNS set"); refreshAndroid() }
            }
        }
    }

    fun refreshAndroid() {
        background({ FleetDns.readAndroid(ctx) }) { a ->
            val m = a.mode ?: "opportunistic"
            if (decl.androidModes.any { it.first == m }) androidMode = m
            if (hostname.isEmpty()) hostname = a.specifier.orEmpty()
            val label = decl.androidModes.firstOrNull { it.first == a.mode }?.second
                ?: if (a.mode == null) "unset or not readable (Android's default is Automatic)" else a.mode
            androidText = buildString {
                append("Android is set to: $label")
                if (a.mode == "hostname") append(" — ${a.specifier}")
                append("\nActive network${if (a.onVpn) " (VPN)" else ""}: DNS ${a.activeServers.joinToString(", ").ifEmpty { "—" }}")
                a.privateDnsActive?.let { append("\nPrivate DNS in use: ${if (it) "yes" else "no"}${a.privateDnsServer?.let { s -> " ($s)" } ?: ""}") }
                if (a.mode == "hostname") append("\nStrict Private DNS takes precedence: every lookup goes to ${a.specifier}, whatever the fleet preset.")
                val enc = FleetDns.effective(decl, prefs.preset).encryption
                if (enc.isNotEmpty() && a.mode == "off") append("\nThe fleet preset is encrypted only when Android's Private DNS is Automatic.")
            }
        }
    }

    fun requestConsent() = askConsent(ctx)

    fun pickPreset(id: String) {
        prefs.preset = id
        presetId = id
        applyToTunnel(); drawFallbacks()
    }

    fun toggleFallback(fid: String, on: Boolean) {
        prefs.fallbacks = prefs.fallbacks.filter { it != fid } + (if (on) listOf(fid) else emptyList())
        applyToTunnel(); drawFallbacks()
    }

    /** The fallback tick list is a function of the stored choice; re-read it after a change. */
    fun drawFallbacks() { fallbacks = prefs.fallbacks; presetId = FleetDns.effective(decl, prefs.preset).id }

    private fun idleLabel(idle: String): String = when {
        idle == "UP" -> "carried by the engine's DNS-only tunnel"
        idle == "STANDBY" -> "ready — the engine raises it when Cloud Mesh goes down"
        idle == "OFF" && !prefs.chosen -> "Android's own DNS — pick a preset here to apply one without the mesh"
        idle == "OFF" -> "Android's own DNS (Mirror)"
        FirewallController.isEnabled(ctx) -> "the firewall holds the one VPN slot ($idle)"
        else -> idle
    }

    /** #794 Promise next to reality, from [FleetDns.live] — the verdict the alert and the API read too. */
    fun refreshStatus() {
        background({ runCatching { FleetDns.live(ctx) } to prefs.lastLookup }) { (r, last) ->
            val p = FleetDns.effective(decl, prefs.preset)
            val live = r.getOrElse { e -> statusText = "Status unreadable: ${e.message}"; return@background }
            val v = live.verdict
            statusText = buildString {
                append("Preset: ${p.label}${if (prefs.chosen) "" else " (default, not chosen here)"}\nCloud Mesh: ${if (live.meshUp) "up" else "down"}\n")
                append("Preset servers in use: ${v.promised.joinToString(" → ").ifEmpty { "Android's own (mirror)" }}\n")
                append("${FleetDns.health.text()}\n")
                append("Android resolves with: ${v.actual.joinToString(", ").ifEmpty { "—" }}${if (live.android.onVpn) " (VPN)" else " (network)"}\n")
                append(if (v.ok) "✓ ${v.why}" else "✗ ${v.why}")
                append("\nWithout the mesh: ${idleLabel(live.idle)}")
                append("\nLast successful lookup: ${last.ifEmpty { "none yet" }}")
            }
            statusOk = v.ok
            needsConsent = v.needsConsent
        }
    }

    /** #794 Sections 4 and 5: one [DnsOverview.collect], rendered as text. */
    fun scan() {
        bridges = "Asking every fleet app…"; servers = "Probing…"
        background({ runCatching { DnsOverview.collect(ctx) } }) { r ->
            r.fold({ o -> bridges = DnsOverview.pathsText(o); servers = DnsOverview.serversText(o) },
                   { e -> bridges = "Scan failed: ${e.message}"; servers = "" })
        }
    }

    /**
     * Re-establish a running tunnel so its VPN carries the new DNS list, and
     * (#751) hand the engine the mesh-down form. It takes the slot at once when
     * the mesh is down — unless the firewall holds it: that one stays, and the
     * engine raises the choice the next time the mesh goes down. (#794) Then
     * check Android took it; missing VPN consent goes straight to the one tap.
     */
    private fun applyToTunnel() {
        statusText = "Applying ${FleetDns.effective(decl, prefs.preset).label}…"
        background({
            runCatching {
                val up = FleetDns.meshUp(ctx)
                if (up) WgState.backend(ctx).setState(WgState.tunnel, Tunnel.State.UP, WgState.prefs(ctx).toTunnelConfig())
                up to FleetDns.syncAndCheck(ctx, raiseNow = !FirewallController.isEnabled(ctx))
            }
        }) { r ->
            r.fold({ (up, v) ->
                when {
                    v.needsConsent -> {
                        toast("${FleetDns.engineLabel(ctx)} needs the VPN permission for this preset")
                        askConsent(ctx)
                    }
                    up && v.ok -> toast("Cloud Mesh re-applied with the new DNS")
                    else -> toast(v.why)
                }
            }, { e -> toast("Not applied: ${e.message}") })
            refreshStatus()
        }
    }

    /** #1899 Every declared target by IP on both paths, held against Android's resolver answering the
     *  test names (the mesh name only while the mesh is up: it cannot resolve without it). */
    fun testReach() {
        reach = "Connecting by IP…"
        background({
            runCatching {
                val names = listOf(decl.testPublic) + (if (FleetDns.meshUp(ctx)) listOf(decl.testMesh) else emptyList())
                DnsReach.run(ctx, decl.reach, names)
            }
        }) { r ->
            r.fold({ reach = DnsReach.text(it); reachOk = it.verdict.ok },
                   { e -> reach = "Reachability test failed: ${e.javaClass.simpleName}: ${e.message}"; reachOk = false })
        }
    }

    /** #1924 Everything on the page, as one text (Status, Test lookup, Test reachability by IP, Bridges, DNS servers). */
    fun reportText(): String = DnsExport.text(
        "Cloud SuperApp DNS @ ${DateFormat.getDateTimeInstance().format(Date())}",
        statusText, results, reach, bridges, servers)

    fun copyAll() { copy(reportText()); toast("Copied the whole DNS page") }

    fun exportMarkdown() {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(Date())
        exportMd("cloud-dns-$stamp.md", DnsExport.markdown(reportText()))
    }

    fun testLookup() {
        results = "Resolving…"
        background({
            val up = FleetDns.meshUp(ctx)
            val fleet = FleetDns.fleetResolvers(ctx)
            listOf(decl.testPublic, decl.testMesh).filter { it.isNotEmpty() }.map { name ->
                val plan = runCatching { FleetDns.queryPlan(decl, name, up, FleetDns.health.healthy, prefs.preset, prefs.fallbacks, prefs.chosen, fleet) }
                // #874 the same lookup the Store's downloader makes: through the bridge.
                val sys = runCatching { FleetDnsBridge.resolve(name).joinToString(", ") { it.hostAddress ?: "" } + " via ${FleetDnsBridge.lastRoute}" }
                    .getOrElse { "FAILED (${it.javaClass.simpleName}: ${it.message})" }
                val routed = plan.fold({ ups ->
                    // #1924 SERVFAIL / REFUSED / timeout is a miss: the next server of the plan is asked
                    if (ups.isEmpty()) null else ups.firstNotNullOfOrNull { s ->
                        runCatching { FleetDns.query(s, name, decl.timeoutMs) }.getOrNull()
                            ?.takeIf { !it.startsWith("rcode") }?.let { "$it via $s" }
                    } ?: "FAILED — no upstream answered (${ups.joinToString(", ")})"
                }, { "FAILED — ${it.message}" })
                Triple(name, sys, routed)
            }
        }) { rows ->
            results = rows.joinToString("\n\n") { (name, sys, routed) ->
                "$name\n  ${FleetDnsBridge.label}: $sys" + (routed?.let { "\n  fleet plan: $it" } ?: "")
            }
            rows.firstOrNull { (_, sys, routed) -> routed?.startsWith("FAILED") == false || (routed == null && !sys.startsWith("FAILED")) }
                ?.let { (name, sys, routed) ->
                    prefs.lastLookup = "$name → ${routed ?: "$sys (bridge)"} at ${DateFormat.getTimeInstance().format(Date())}"
                }
            refreshStatus()
        }
    }
}

@Composable
private fun DnsPage(m: DnsModel) {
    val decl = m.decl
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConfigColumn {
            // ── 1. Android Private DNS ──────────────────────────────────────
            ConfigHeader("Android Private DNS")
            ConfigCaption(m.androidText)
            decl.androidModes.forEach { (modeId, label) ->
                ConfigRadioRow(label, selected = m.androidMode == modeId, tag = modeId) { m.androidMode = modeId }
            }
            OutlinedTextField(value = m.hostname, onValueChange = { m.hostname = it }, singleLine = true,
                placeholder = { Text(decl.hostnameSuggestions.joinToString(" · ")) },
                modifier = Modifier.testTag("dns:host").fillMaxWidth())
            ConfigButton("Apply to Android") { m.applyAndroid() }

            // ── 2. Fleet DNS ────────────────────────────────────────────────
            ConfigHeader("Fleet DNS")
            ConfigCaption("Applied once, at the VPN every fleet app resolves through: the Cloud Mesh tunnel while it is up, and without the mesh a DNS-only tunnel that routes nothing (the fleet resolver is unreachable then, so Private with fallbacks uses its fallbacks alone and Private only fails lookups). Mesh names (${decl.meshZones.joinToString(", ") { "*.$it" }}) go to the fleet resolver whenever the mesh is up.")
            decl.presets.forEach { p ->
                ConfigRadioRow(
                    if (p.available) "${p.label}\n${p.subtitle}" else "${p.label} — unavailable\n${p.unavailableReason}",
                    selected = p.id == m.presetId, enabled = p.available, tag = p.id,
                ) { m.pickPreset(p.id) }
            }
            val current = FleetDns.effective(decl, m.presetId)
            if (current.fallbackAllowed) {
                ConfigCaption("Fallbacks, in the order you tick them: " +
                    m.fallbacks.mapNotNull { decl.preset(it)?.label }.joinToString(" → ").ifEmpty { "none" })
                for (fid in current.fallbackChoices) {
                    val f = decl.preset(fid) ?: continue
                    ConfigCheckRow(if (f.available) f.label else "${f.label} — unavailable", checked = fid in m.fallbacks,
                        enabled = f.available, tag = fid) { on -> m.toggleFallback(fid, on) }
                }
            }

            // ── 3. Status ───────────────────────────────────────────────────
            ConfigHeader("Status")
            ConfigReadout(m.statusText, ink = if (m.statusOk) Color.White else null,
                plate = if (m.statusOk) Color(0x33000000) else Color(0x55DC2626))
            if (m.needsConsent) {
                ConfigButton("Allow the VPN for ${FleetDns.engineLabel(androidx.compose.ui.platform.LocalContext.current)}",
                    plate = Color(0xFFDC2626)) { m.requestConsent() }
            }
            ConfigButton("Test lookup") { m.testLookup() }
            ConfigReadout(m.results)
            // #1899 the same question with no DNS in it: the declared sites by fixed IP, inside the VPN and direct
            ConfigButton("Test reachability by IP") { m.testReach() }
            ConfigCaption("No name is resolved: each site is reached at its fixed address (a TLS handshake checked against its certificate name, and a TCP connect), on the normal path every fleet app uses and on a socket bound outside the VPN. Reachable by IP while lookups fail means DNS is the only issue; unreachable by IP means the network or the VPN.")
            ConfigReadout(m.reach, modifier = Modifier.testTag("dns:reach"),
                plate = if (m.reachOk) Color(0x33000000) else Color(0x55DC2626))

            // ── 4. Bridges · 5. DNS servers ─────────────────────────────────
            ConfigHeader("Bridges")
            ConfigCaption("How each fleet app resolves, as that app reports it (/api/net/dns), with what it talks to. Terminals go through their 127.0.0.1 bridge to Android's resolver; apps that resolve by themselves are flagged.")
            ConfigReadout(m.bridges)
            ConfigHeader("DNS servers")
            ConfigCaption("Every server the presets, the mesh and Android name: protocol, role, which preset uses it, reachable now (a test query) and which one answers.")
            ConfigReadout(m.servers)
            ConfigButton("Scan bridges and servers") { m.scan() }
            // #1924 the whole page as text, the same helper as the VPN page's Copy all / Export to Md
            ConfigButton("Copy all") { m.copyAll() }
            ConfigButton("Export to Markdown") { m.exportMarkdown() }
        }
    }
}

