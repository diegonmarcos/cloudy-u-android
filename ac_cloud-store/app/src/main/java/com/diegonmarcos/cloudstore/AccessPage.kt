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
import com.diegonmarcos.superapp.appstore.FleetIdentityPage
import com.diegonmarcos.superapp.appstore.IpcMeshPage
import com.diegonmarcos.superapp.fleetdata.DeviceAccess
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.superapp.updater.Fleet

/**
 * Store > Access (#1718): what this phone exposes and who may reach it, in five pages (build.json::ui access.pages: mcp, apis,
 * ipc, identity, android) MainActivity draws with PageTabs.
 *
 *  - MCP (device): the on-device MCP doors and the tools that come in through each.
 *  - APIs: each app's own API routes. The count is the declared map's (ops, listener routes, declared endpoints), never zero
 *    for an app that exposes one.
 *  - IPC: the per-app IPC routes behind the one mesh gateway (the webserver's api-ipc-map), and the fleet IPC registry
 *    (libs:appstore IpcMeshPage, the page SuperApp > Configs > Network > IPC draws).
 *  - Identity: which apps reach each other with no IPC route at all: one Linux uid (libs:appstore FleetIdentityPage) and the
 *    signature-level constellation permission. The fields of the former Cloud Perms page live here now: every fleet app carries
 *    the same signing key, so a page of its own only repeated it.
 *  - Android Perms: the OS's runtime grants per app, the grant action, and the apps that reach OS tools (the ADB owner and the
 *    privileged plane).
 *
 * MCP, APIs and the gateway half of IPC are drawn from ONE model, libs:fleet-data-client DeviceAccess, the same model Cloud C3
 * Webserver > API draws. Apps Mesh is gone (#1580: one mesh gateway, no per-app mesh endpoints). No pane renders a toggle for a
 * grant it does not control.
 */
@Composable
fun AccessPage(page: String) {
    when (AccessPages.resolve(page)) {
        PAGE_MCP -> DeviceMcpPage()
        PAGE_APIS -> DeviceApisPage()
        PAGE_IPC -> IpcAccessPage()
        PAGE_IDENTITY -> IdentityAccessPage()
        else -> AndroidPermsPage()
    }
}

const val PAGE_MCP = "mcp"
const val PAGE_APIS = "apis"
const val PAGE_IPC = "ipc"
const val PAGE_IDENTITY = "identity"
const val PAGE_ANDROID = "android"

/** The Access pages, and where an id that left (a saved tab, an old intent) lands now. */
object AccessPages {
    /** mesh: Apps Mesh was retired by #1580, its gateway is on IPC. mcpapi: split into MCP and APIs. cloud: Cloud Perms moved into Identity. */
    val MOVED = mapOf("mesh" to PAGE_IPC, "mcpapi" to PAGE_APIS, "cloud" to PAGE_IDENTITY)
    fun resolve(page: String): String = MOVED[page] ?: page

    /** The view each two-view page opens on; a test (or a deep link) sets it before the page composes. */
    @Volatile var ipcView: String = VIEW_GATEWAY
    @Volatile var identityView: String = VIEW_DIRECT
    const val VIEW_GATEWAY = "gateway"
    const val VIEW_REGISTRY = "registry"
    const val VIEW_DIRECT = "direct"
    const val VIEW_UID = "uid"
}

/** IPC: the gateway routes (the shared device access model) or the fleet IPC registry (libs:appstore IpcMeshPage, unchanged). */
@Composable
private fun IpcAccessPage() {
    var view by remember { mutableStateOf(AccessPages.ipcView) }
    Column(Modifier.fillMaxSize()) {
        ViewSwitch(listOf(AccessPages.VIEW_GATEWAY to "Gateway routes", AccessPages.VIEW_REGISTRY to "Endpoint registry"), view, "access-ipc-view:") { view = it; AccessPages.ipcView = it }
        Box(Modifier.weight(1f)) { if (view == AccessPages.VIEW_REGISTRY) IpcMeshPage() else DeviceIpcPage() }
    }
}

/** Identity: who reaches whom with no IPC (signature level, shared uid; the former Cloud Perms fields) or each app's Linux identity (FleetIdentityPage, unchanged). */
@Composable
private fun IdentityAccessPage() {
    var view by remember { mutableStateOf(AccessPages.identityView) }
    Column(Modifier.fillMaxSize()) {
        ViewSwitch(listOf(AccessPages.VIEW_DIRECT to "Direct access", AccessPages.VIEW_UID to "Linux identity"), view, "access-identity-view:") { view = it; AccessPages.identityView = it }
        Box(Modifier.weight(1f)) { if (view == AccessPages.VIEW_UID) FleetIdentityPage() else DirectAccessPage() }
    }
}

/** A two-option switch in the Store's chip style. */
@Composable
internal fun ViewSwitch(options: List<Pair<String, String>>, selected: String, tagPrefix: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = dpOf(StoreDensity.S8), vertical = dpOf(StoreDensity.S4)), horizontalArrangement = Arrangement.spacedBy(dpOf(StoreDensity.S4))) {
        for ((id, label) in options) Text(label, Modifier.weight(1f).background(if (id == selected) UP else IDLE).clickable { onSelect(id) }
            .padding(dpOf(StoreDensity.S8), dpOf(StoreDensity.S6)).testTag(tagPrefix + id),
            color = Color.White, fontWeight = if (id == selected) FontWeight.Bold else FontWeight.Normal, fontSize = StoreDensity.T_META.sp, maxLines = 1)
    }
}

/**
 * Identity > Direct access: the apps that call each other with no IPC route in between.
 * Signature level: every APK carries the SAME key, so Android grants each one CONSTELLATION_DATA at install and they read each
 * other's guarded providers directly. Shared uid: a declared group runs as one Linux user and reads the same files. Each app row
 * carries what the Cloud Perms page showed (installed, same key, holds the permission, what that means), nothing dropped.
 */
@Composable
internal fun DirectAccessPage(model: DeviceAccess.Doc? = null) {
    val ctx = LocalContext.current
    val doc = rememberDeviceAccess(model)
    val fleet = remember { Fleet.parse(StoreBuild.CONSTELLATION_FLEET_B64).filter { it.pkg != ctx.packageName } }
    val seen = remember { fleet.map { app -> DirectAccess.read(ctx, app.label + (if (app.kind == "lib") "  ·  lib" else ""), Fleet.installedId(ctx, app), app.pkg) } }
    LazyColumn(Modifier.fillMaxSize().padding(dpOf(StoreDensity.S12)), verticalArrangement = Arrangement.spacedBy(dpOf(StoreDensity.S4))) {
        item {
            Text("Direct access: apps that reach each other without an IPC route. Every fleet app carries the Cloud signing key, so Android grants each one " +
                "the constellation permission at install and they talk freely to each other by default; a shared-uid group goes further and runs as one Linux user.",
                color = DIM, fontSize = StoreDensity.T_META.sp)
        }
        item { Text(DirectAccess.summary(seen), Modifier.testTag(DirectAccess.TAG_SUMMARY), color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_META.sp) }
        item {
            Column(Modifier.fillMaxWidth().background(CARD).padding(dpOf(StoreDensity.S12), dpOf(StoreDensity.S6)).testTag(DirectAccess.TAG_SIGNATURE)) {
                Text("Signature level  ·  ${seen.count { it.holds }} of ${seen.count { it.installed }} installed apps hold it", color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_TITLE.sp)
                Text(doc?.permission?.ifEmpty { null } ?: CONSTELLATION_PERM, color = DIM, fontFamily = FontFamily.Monospace, fontSize = StoreDensity.T_CAPTION.sp)
                Text("protectionLevel ${doc?.permissionLevel?.ifEmpty { null } ?: "signature"}: granted at install to every APK carrying our key, no prompt, and no outside APK can obtain it. " +
                    "Holders read each other's guarded providers directly" + (doc?.doorAuthority?.takeIf { it.isNotEmpty() }?.let { " (the fleet door content://$it, method ${doc.doorMethod})." } ?: "."),
                    color = DIM, fontSize = StoreDensity.T_META.sp)
            }
        }
        val groups = doc?.groups.orEmpty()
        item {
            Column(Modifier.fillMaxWidth().background(CARD).padding(dpOf(StoreDensity.S12), dpOf(StoreDensity.S6)).testTag(DirectAccess.TAG_UID)) {
                Text("Shared uid  ·  ${groups.size} group(s)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_TITLE.sp)
                if (groups.isEmpty()) Text(doc?.problem ?: if (doc == null) "loading the device access model…" else "No shared-uid group is declared: every app runs as its own Linux user.", color = DIM, fontSize = StoreDensity.T_META.sp)
                for (g in groups) {
                    Text("${g.label}  ·  ${g.sharedUserId ?: "pending: each on its own uid today"}", color = if (g.sharedUserId != null) UP else MISSING, fontSize = StoreDensity.T_META.sp)
                    Text(g.members.joinToString("  ·  "), color = DIM, fontFamily = FontFamily.Monospace, fontSize = StoreDensity.T_CAPTION.sp)
                }
                Text("Members of one group read and write the same files with no IPC at all. Linux identity lists every app's uid.", color = DIM, fontSize = StoreDensity.T_CAPTION.sp)
            }
        }
        items(fleet, key = { it.id }) { app -> PermsCard(ctx, app, cloud = true) }
    }
}

/** What Identity > Direct access reads from the device for one app: the same four facts the Cloud Perms card drew. */
object DirectAccess {
    const val TAG_SUMMARY = "access-direct-summary"
    const val TAG_SIGNATURE = "access-direct-signature"
    const val TAG_UID = "access-direct-uid"
    const val TAG_APP = "access-direct-app:"

    class Seen(val title: String, val pkg: String, val installed: Boolean, val sameKey: Boolean, val holds: Boolean)

    fun read(ctx: Context, title: String, installedPkg: String?, fleetPkg: String): Seen {
        val pkg = installedPkg ?: return Seen(title, fleetPkg, installed = false, sameKey = false, holds = false)
        val holds = runCatching { ctx.packageManager.checkPermission(CONSTELLATION_PERM, pkg) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return Seen(title, pkg, installed = true, sameKey = sameSignature(ctx, pkg), holds = holds)
    }

    fun summary(seen: List<Seen>): String =
        "${seen.size} fleet apps  ·  ${seen.count { it.installed }} installed  ·  ${seen.count { it.sameKey }} same key  ·  " +
            "${seen.count { it.holds }} hold the constellation permission  ·  ${seen.count { it.installed && !it.sameKey }} different signature"
}

@Composable
private fun PermsCard(ctx: Context, app: Fleet.App, cloud: Boolean) {
    PermsCard(ctx, app.label + (if (app.kind == "lib") "  ·  lib" else ""), Fleet.installedId(ctx, app), app.pkg, cloud)
}

@Composable
private fun PermsCard(ctx: Context, title: String, pkg: String?, fleetPkg: String, cloud: Boolean) {
    val h = dpOf(StoreDensity.S12); val v = dpOf(StoreDensity.S6)
    Column(Modifier.fillMaxWidth().background(CARD).padding(h, v).testTag((if (cloud) DirectAccess.TAG_APP else OsReach.TAG_APP) + fleetPkg)) {
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
        item { OsReachCard(ctx, apps.map { it.label to it.pkg }, up) }
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
        // Our constellation permission lives on Identity > Direct access; here the platform's own.
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
internal fun sameSignature(ctx: Context, pkg: String): Boolean = runCatching {
    ctx.packageManager.checkSignatures(ctx.packageName, pkg) == PackageManager.SIGNATURE_MATCH
}.getOrDefault(false)

/** Declared in libs:core's manifest at protectionLevel="signature" and merged into every constellation app. */
internal const val CONSTELLATION_PERM = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

internal fun dpOf(step: Int): Dp = StoreDensity.dpValue(step).dp
internal val UP = Color(0xFF48BB78)
internal val MISSING = Color(0xFF63B3ED)
internal val BLOCKED = Color(0xFFF56565)
internal val DIM = Color(0x99FFFFFF)
internal val CARD = Color(0xFF1C1C24)
internal val IDLE = Color(0xFF2A2A33)


/**
 * Android Perms > OS reach: the apps that can reach OS tools. The ADB owner (the one app that compiles the embedded adb and
 * exports the shell broker; every other app goes through it), whether its channel answers right now (the Wireless Debugging
 * chip's own measurement, never a proxy), and each installed fleet app's grants above the runtime level (development, appop,
 * privileged, installer: what `pm grant` over the privileged plane hands out and no dialog ever does).
 */
object OsReach {
    const val TAG_CARD = "access-os-reach"
    const val TAG_OWNER = "access-os-owner"
    const val TAG_APP = "access-android-app:"

    /** [perm] is above the runtime level when its protection carries one of these flags. */
    fun flags(protectionFlags: Int): List<String> = buildList {
        if (protectionFlags and android.content.pm.PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0) add("privileged")
        if (protectionFlags and android.content.pm.PermissionInfo.PROTECTION_FLAG_DEVELOPMENT != 0) add("development")
        if (protectionFlags and android.content.pm.PermissionInfo.PROTECTION_FLAG_APPOP != 0) add("appop")
        if (protectionFlags and android.content.pm.PermissionInfo.PROTECTION_FLAG_INSTALLER != 0) add("installer")
    }

    class Grant(val perm: String, val flags: List<String>, val granted: Boolean)

    /** The platform permissions [pkg] requests that sit above the runtime level, with whether it holds each. Never throws. */
    fun read(ctx: Context, pkg: String): List<Grant> = runCatching {
        val pm = ctx.packageManager
        pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().filter { it.startsWith("android.permission.") }.sorted().mapNotNull { p ->
            val info = runCatching { pm.getPermissionInfo(p, 0) }.getOrNull() ?: return@mapNotNull null
            @Suppress("DEPRECATION") val f = flags(if (android.os.Build.VERSION.SDK_INT >= 28) info.protectionFlags else info.protectionLevel)
            if (f.isEmpty()) null else Grant(p, f, pm.checkPermission(p, pkg) == PackageManager.PERMISSION_GRANTED)
        }
    }.getOrDefault(emptyList())
}

@Composable
private fun OsReachCard(ctx: Context, apps: List<Pair<String, String>>, channelUp: Boolean) {
    val doc = rememberDeviceAccess(null)
    val reach = remember(apps) { apps.map { (label, pkg) -> Triple(label, pkg, OsReach.read(ctx, pkg)) }.filter { it.third.isNotEmpty() } }
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(CARD).padding(dpOf(StoreDensity.S12), dpOf(StoreDensity.S6)).testTag(OsReach.TAG_CARD)) {
        Text("OS reach: apps that can reach OS tools", color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_TITLE.sp)
        val owner = doc?.adbOwner
        val ownerInstalled = owner?.pkg?.takeIf { it.isNotEmpty() }?.let { p -> runCatching { ctx.packageManager.getPackageInfo(p, 0) }.isSuccess }
        Text(when {
            doc == null -> "ADB owner: loading the device access model…"
            owner == null || owner.pkg.isEmpty() -> "ADB owner: " + (doc.problem ?: "not declared")
            else -> "ADB owner: ${doc.app(owner.pkg)?.label ?: owner.app}  ·  ${owner.pkg}  ·  ${if (ownerInstalled == true) "installed" else "not installed"}"
        }, Modifier.testTag(OsReach.TAG_OWNER), color = if (ownerInstalled == true) UP else MISSING, fontSize = StoreDensity.T_META.sp)
        Text("The one app that owns the ADB shell (pairing, embedded adb, the shell broker: Cloud C3 Webserver); every other app reaches the shell through it.", color = DIM, fontSize = StoreDensity.T_CAPTION.sp)
        Text(if (channelUp) "● privileged channel up: a real shell round-trip answered" else "● privileged channel down: turn on Wireless debugging (the chip above)",
            color = if (channelUp) UP else BLOCKED, fontSize = StoreDensity.T_META.sp)
        Text((if (open) "▾ " else "▸ ") + "${reach.size} installed app(s) request grants above the runtime level  ·  ${reach.sumOf { r -> r.third.count { it.granted } }} held",
            Modifier.fillMaxWidth().clickable { open = !open }.padding(top = dpOf(StoreDensity.S4)), color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_META.sp)
        if (open) for ((label, pkg, grants) in reach) {
            Text("$label  ·  $pkg", color = Color.White, fontSize = StoreDensity.T_CAPTION.sp)
            for (g in grants) Text((if (g.granted) "✓  " else "·  ") + g.perm.removePrefix("android.permission.") + "  (" + g.flags.joinToString(", ") + ")",
                color = if (g.granted) UP else MISSING, fontSize = StoreDensity.T_CAPTION.sp)
        }
    }
}
