package com.diegonmarcos.cloudstore

import com.diegonmarcos.superapp.perms.PermsPages
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import com.diegonmarcos.superapp.perms.PermsGrant
import com.diegonmarcos.superapp.appstore.PermsGrantDevice
import com.diegonmarcos.superapp.appstore.StoreBar
import kotlin.concurrent.thread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.appstore.BuildConfig as StoreBuild
import com.diegonmarcos.superapp.appstore.StoreDensity
import com.diegonmarcos.superapp.appstore.AppsMeshFragment
import com.diegonmarcos.superapp.appstore.FleetIdentityPage
import com.diegonmarcos.superapp.appstore.IpcMeshPage
import com.diegonmarcos.superapp.appstore.AppsMcpApiPage
import androidx.fragment.compose.AndroidFragment
import com.diegonmarcos.superapp.updater.Fleet

/**
 * Store > Access: Apps Mesh, Apps MCP-API, IPC, Identity, Android Perms and Cloud Perms, one bottom-nav destination whose
 * top tabs (build.json::ui access.pages: mesh, mcpapi, ipc, identity, android, cloud) MainActivity draws with PageTabs.
 * #ipc IPC is the fleet's IPC Mesh (libs:appstore IpcMeshPage): every IPC endpoint each member exposes.
 * Apps Mesh left the Cloud page; the permissions page that was here split in two - the OS's own
 * runtime grants, and the constellation permission - one page each instead of two panes per app.
 * (The logic is the old Perms tab's, drawn with Views before.) The constellation is a trusted
 * environment because every APK is signed with the SAME key, which is what makes signature-level
 * permissions usable between our apps. Each app card carries two panes: Android Perms (the OS's
 * own runtime grants, read-only here: only the system UI may change them) and Cloud Perms
 * (CONSTELLATION_DATA, protectionLevel="signature", granted at install to every APK carrying our
 * key). Neither pane renders a toggle: a switch could only misreport state it does not control.
 */
@Composable
fun AccessPage(page: String) {
    when (page) {
        PAGE_MESH -> AndroidFragment<AppsMeshFragment>(Modifier.fillMaxSize(),
            arguments = androidx.core.os.bundleOf(AppsMeshFragment.ARG_SHOW_ENDPOINTS to false))
        // #1211 Apps MCP-API: the endpoints each Store > Cloud > Apps app declares (libs:appstore AppsMcpApiPage).
        PAGE_MCPAPI -> AppsMcpApiPage()
        // #ipc IPC Mesh: the same shared composable SuperApp > Configs > Network > IPC draws (libs:appstore).
        PAGE_IPC -> IpcMeshPage()
        // Identity: the same shared composable SuperApp > Configs > Apps Mesh > Identity draws (libs:appstore).
        PAGE_IDENTITY -> FleetIdentityPage()
        PAGE_ANDROID -> AndroidPermsPage()
        else -> PermsList(cloud = true)
    }
}

const val PAGE_MESH = "mesh"
const val PAGE_MCPAPI = "mcpapi"
const val PAGE_IPC = "ipc"
const val PAGE_IDENTITY = "identity"
const val PAGE_ANDROID = "android"
const val PAGE_CLOUD = "cloud"

@Composable
private fun PermsList(cloud: Boolean) {
    val ctx = LocalContext.current
    val fleet = remember { Fleet.parse(StoreBuild.CONSTELLATION_FLEET_B64) }
    val pad = StoreDensity.dpValue(StoreDensity.S12).dp
    LazyColumn(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(dpOf(StoreDensity.S4))) {
        item {
            Text(if (cloud)
                "Cloud Perms: our constellation permission, granted automatically to every app carrying the Cloud signing key, so they talk freely to each other by default."
            else
                "Android Perms: the OS's own runtime grants, read-only here - the system screen owns them.",
                color = DIM, fontSize = StoreDensity.T_META.sp)
        }
        items(fleet.filter { it.pkg != ctx.packageName }, key = { it.id }) { app -> PermsCard(ctx, app, cloud) }
    }
}

@Composable
private fun PermsCard(ctx: Context, app: Fleet.App, cloud: Boolean) {
    PermsCard(ctx, app.label + (if (app.kind == "lib") "  ·  lib" else ""), Fleet.installedId(ctx, app), app.pkg, cloud)
}

@Composable
private fun PermsCard(ctx: Context, title: String, pkg: String?, fleetPkg: String, cloud: Boolean) {
    val h = dpOf(StoreDensity.S12); val v = dpOf(StoreDensity.S6)
    Column(Modifier.fillMaxWidth().background(CARD).padding(h, v)) {
        Text(title, color = Color.White,
            fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_TITLE.sp)
        Text(pkg ?: fleetPkg, color = DIM, fontFamily = FontFamily.Monospace, fontSize = StoreDensity.T_CAPTION.sp)
        when {
            pkg == null -> Text("◯ not installed", color = MISSING, fontSize = StoreDensity.T_META.sp)
            sameSignature(ctx, pkg) -> Text("🔑 same key  ·  eligible for signature-level data access", color = UP, fontSize = StoreDensity.T_META.sp)
            else -> Text("⚠ different signature  ·  NOT eligible — reinstall from our release", color = BLOCKED, fontSize = StoreDensity.T_META.sp)
        }
        if (pkg == null) return@Column
        if (cloud) CloudPerms(ctx, pkg) else AndroidPerms(ctx, pkg)
    }
}

/**
 * Android Perms: an action row (permission filter, the Wireless Debugging chip that opens ADB Shell, Grant missing
 * perms) over the fleet's apps, A-Z. The grant plan, filter and sort are [PermsGrant]'s.
 */
@Composable
private fun AndroidPermsPage() {
    val ctx = LocalContext.current
    val fleet = remember { Fleet.parse(StoreBuild.CONSTELLATION_FLEET_B64) }
    var tick by remember { mutableStateOf(0) }          // a grant ran: re-read the device
    var chan by remember { mutableStateOf(0) }          // the channel's status changed: redraw
    val apps = remember(tick) { PermsGrant.sorted(PermsGrantDevice.read(ctx, fleet)) }
    val kind = remember { PermsGrantDevice.kindOf(ctx) }
    val options = remember(apps) { PermsGrant.filterOptions(apps, kind) }
    var selected by remember { mutableStateOf(PermsGrant.ALL) }
    if (selected !in options) selected = PermsGrant.ALL
    val shown = PermsGrant.view(apps, selected)
    val plan = remember(apps) { PermsGrant.plan(apps, kind) }
    val up = chan >= 0 && PermsGrantDevice.channelUp(ctx)
    val reason = PermsGrant.disabledReason(up)
    var confirm by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<PermsGrant.Summary?>(null) }
    var logLines by remember { mutableStateOf(listOf<String>()) }
    var needsOpen by remember { mutableStateOf(false) }
    val s4 = dpOf(StoreDensity.S4)

    LazyColumn(Modifier.fillMaxSize().padding(dpOf(StoreDensity.S8)), verticalArrangement = Arrangement.spacedBy(s4)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(s4), verticalAlignment = Alignment.CenterVertically) {
                PermFilter(options, selected, { selected = it }, Modifier.weight(1f))
                StoreBar.ChannelButton(Modifier.weight(1.4f), onChange = { chan++ })
                val can = up && !running && plan.steps.isNotEmpty()
                Text(if (running) "Granting..." else "Grant missing perms (${plan.steps.size})",
                    Modifier.weight(1.2f).background(if (can) UP else IDLE).clickable(enabled = can) { confirm = true }
                        .padding(dpOf(StoreDensity.S8), dpOf(StoreDensity.S6)),
                    color = if (can) Color.White else DIM, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_META.sp, maxLines = 1)
            }
        }
        if (reason != null) item { Text(reason, color = BLOCKED, fontSize = StoreDensity.T_CAPTION.sp) }
        summary?.let { sm ->
            item {
                Column(Modifier.fillMaxWidth().background(CARD).padding(dpOf(StoreDensity.S8), s4)) {
                    Text(sm.text(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_META.sp)
                    for (f in sm.failures) Text("✕ ${f.step.label} ${PermsGrant.short(f.step.perm)}: ${f.reason}", color = BLOCKED, fontSize = StoreDensity.T_CAPTION.sp)
                    for (l in logLines) Text(l, color = DIM, fontFamily = FontFamily.Monospace, fontSize = StoreDensity.T_CAPTION.sp)
                }
            }
        }
        if (plan.needsYou.isNotEmpty()) {
            item {
                Text((if (needsOpen) "▾ " else "▸ ") + "Needs you (${plan.needsYou.size}) - special access, only a settings screen grants it",
                    Modifier.fillMaxWidth().clickable { needsOpen = !needsOpen }, color = MISSING,
                    fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_META.sp)
            }
            if (needsOpen) items(plan.needsYou, key = { it.pkg + it.perm }) { n ->
                Row(Modifier.fillMaxWidth().background(CARD).padding(dpOf(StoreDensity.S8), dpOf(StoreDensity.S2)), verticalAlignment = Alignment.CenterVertically) {
                    Text(n.label + "  " + PermsGrant.short(n.perm), Modifier.weight(1f), color = Color.White, fontSize = StoreDensity.T_CAPTION.sp)
                    Text("Settings ↗", Modifier.clickable { openSettings(ctx, n.action, n.pkg) }.padding(horizontal = dpOf(StoreDensity.S4)),
                        color = MISSING, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_CAPTION.sp)
                }
            }
        }
        items(shown, key = { it.pkg }) { a -> PermsCard(ctx, a.label, a.pkg, a.pkg, cloud = false) }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(plan.confirmText()) },
        text = { Text("Through the privileged channel, in the current mode. Only constellation-signed apps; special access is left to you.") },
        confirmButton = { TextButton({
            confirm = false; running = true
            val ch = PermsGrantDevice.channel(ctx); val lines = ArrayList<String>()
            thread(name = "grant-missing-perms") {
                val sm = PermsGrant.run(plan, ch) { lines += it; PermsGrantDevice.log(it) }
                summary = sm; logLines = lines.toList(); running = false; tick++
            }
        }) { Text("Grant") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

/** The permission dropdown: a dense box that opens a searchable list; "All" first. */
@Composable
private fun PermFilter(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Box(modifier) {
        Text("▾ " + (if (selected == PermsGrant.ALL) selected else PermsGrant.short(selected)),
            Modifier.fillMaxWidth().background(IDLE).clickable { open = true }.padding(dpOf(StoreDensity.S8), dpOf(StoreDensity.S6)),
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_META.sp, maxLines = 1)
        DropdownMenu(open, { open = false }) {
            BasicTextField(query, { query = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = StoreDensity.T_META.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth().background(IDLE).padding(dpOf(StoreDensity.S8), dpOf(StoreDensity.S4)),
                decorationBox = { inner -> if (query.isEmpty()) Text("search permission", color = DIM, fontSize = StoreDensity.T_META.sp); inner() })
            Column {
                for (o in PermsGrant.search(options, query)) Text(if (o == PermsGrant.ALL) o else PermsGrant.short(o),
                    Modifier.fillMaxWidth().clickable { onSelect(o); open = false; query = "" }.padding(dpOf(StoreDensity.S8), dpOf(StoreDensity.S4)),
                    color = if (o == selected) UP else Color.White, fontSize = StoreDensity.T_META.sp)
            }
        }
    }
}

/** Opens the settings screen for [action]; falls back to the app's own details screen. */
private fun openSettings(ctx: Context, action: String, pkg: String?) {
    fun go(i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    val uri = pkg?.let { Uri.fromParts("package", it, null) }
    if (uri != null && go(Intent(action, uri))) return
    if (go(Intent(action))) return
    if (uri == null || !go(Intent(PermsGrant.FALLBACK_SETTINGS, uri))) Toast.makeText(ctx, "No settings screen", Toast.LENGTH_SHORT).show()
}

/** Android's own runtime permissions for [pkg]: what it requests and whether it holds it, then a hand-off to the system screen. */
@Composable
private fun AndroidPerms(ctx: Context, pkg: String) {
    val pm = ctx.packageManager
    val requested = runCatching {
        pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
    }.getOrNull().orEmpty()
        // Our constellation permission lives on the Cloud Perms page; here the platform's own.
        .filter { it.startsWith("android.permission.") }.sorted()
    if (requested.isEmpty()) {
        Text("Requests no Android permissions.", color = DIM, fontSize = StoreDensity.T_META.sp)
    } else for (p in requested) {
        val granted = pm.checkPermission(p, pkg) == PackageManager.PERMISSION_GRANTED
        Text((if (granted) "✓  " else "·  ") + p.removePrefix("android.permission."),
            color = if (granted) UP else MISSING, fontSize = StoreDensity.T_CAPTION.sp)
    }
    Text("System settings ↗", Modifier.padding(top = dpOf(StoreDensity.S4))
        .background(IDLE).clickable {
            if (!PermsPages.openAppInfo(ctx, pkg)) Toast.makeText(ctx, "No settings screen", Toast.LENGTH_SHORT).show()
        }.padding(dpOf(StoreDensity.S8), dpOf(StoreDensity.S4)),
        color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_META.sp)
}

/** The constellation's own permission. There is deliberately no switch: CONSTELLATION_DATA is
 *  protectionLevel="signature", so Android grants it at install to every APK carrying our signing key. */
@Composable
private fun CloudPerms(ctx: Context, pkg: String) {
    val pm = ctx.packageManager
    val holds = pm.checkPermission(CONSTELLATION_PERM, pkg) == PackageManager.PERMISSION_GRANTED
    val weHold = pm.checkPermission(CONSTELLATION_PERM, ctx.packageName) == PackageManager.PERMISSION_GRANTED
    Text(if (holds) "✓  Cloud data access — granted" else "✕  Cloud data access — not granted",
        color = if (holds) UP else BLOCKED, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_BODY.sp)
    Text(CONSTELLATION_PERM, color = DIM, fontFamily = FontFamily.Monospace, fontSize = StoreDensity.T_CAPTION.sp)
    Text(when {
        holds && weHold -> "Two-way: this app and Cloud Store can each read the other's constellation data. " +
            "Granted automatically at install because both carry the Cloud signing key — no prompt, and no outside APK can obtain it."
        holds -> "This app holds it but Cloud Store does not — reinstall Cloud Store from our release."
        sameSignature(ctx, pkg) -> "Same signing key, but this build predates the constellation permission. " +
            "Update it from the Cloud page; the grant lands on reinstall."
        else -> "Signed with a different key, so Android refuses this permission. " +
            "Reinstall from our release to bring it into the constellation."
    }, color = DIM, fontSize = StoreDensity.T_META.sp)
}

/** True when [pkg] is signed with the same key as us: the whole basis of `signature`-level permissions. */
@Suppress("DEPRECATION")
private fun sameSignature(ctx: Context, pkg: String): Boolean = runCatching {
    ctx.packageManager.checkSignatures(ctx.packageName, pkg) == PackageManager.SIGNATURE_MATCH
}.getOrDefault(false)

/** Declared in libs:core's manifest at protectionLevel="signature" and merged into every constellation app. */
private const val CONSTELLATION_PERM = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

private fun dpOf(step: Int): Dp = StoreDensity.dpValue(step).dp
private val UP = Color(0xFF48BB78)
private val MISSING = Color(0xFF63B3ED)
private val BLOCKED = Color(0xFFF56565)
private val DIM = Color(0x99FFFFFF)
private val CARD = Color(0xFF1C1C24)
private val IDLE = Color(0xFF2A2A33)
