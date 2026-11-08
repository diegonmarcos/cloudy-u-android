package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.diegonmarcos.superapp.updater.Fleet
import kotlin.concurrent.thread

/**
 * #redesign the Store ▸ Cloud per-app INSPECT sheets — Perms, Routes and Libs,
 * the three buttons added to the row's second line beside the existing About
 * (ApkDetailSheet). Each opens as a [ModalBottomSheet] OVER the Cloud page, so
 * the store's bottom nav and the Back gesture are untouched (the sheet dismisses
 * on Back; the nav bar stays visible beneath it) — the same overlay the Details/
 * About sheet has always used, never a screen that replaces the nav (the bug the
 * nav-shape guards were written for).
 *
 * All three draw REAL declared data and say "none declared" where a source names
 * none — they never fabricate a row:
 *  - [Perms]  — PackageManager.getPackageInfo(pkg, GET_PERMISSIONS): the manifest's
 *               requestedPermissions + their flags, split into Manifest / Auto grants
 *               (install-time normal) / Runtime (dangerous, granted-or-denied).
 *  - [Routes] — MCP and API from [AppsMcpApi] (assets/apps-mcp-api.json), IPC from
 *               [IpcMesh] (baked ipc-mesh.json). A tab with no entry for this app shows none.
 *  - [Libs]   — the fleet row's own `libs` (compiled in) and `engineLibs` (bound engines),
 *               carried from each app's build.json by data/regen.sh.
 */
object StoreInspect {

    const val TAG_PERMS = "store-inspect-perms"
    const val TAG_ROUTES = "store-inspect-routes"
    const val TAG_LIBS = "store-inspect-libs"

    private const val SHEET_BG = 0xFF140E1F.toInt()
    private val cText = 0xFFECE6F5.toInt(); private val cDim = 0xFF9B93AB.toInt()
    private val cLink = 0xFFB794F6.toInt(); private val cOk = 0xFF48BB78.toInt(); private val cBad = 0xFFF56565.toInt()

    // ── PERMS ────────────────────────────────────────────────────────────────

    private class Perm(val name: String, val base: Int, val granted: Boolean, val known: Boolean)

    /** Every manifest permission of [pkg], with its base protection level and grant state. */
    private fun perms(ctx: Context, pkg: String): List<Perm> {
        val info = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return emptyList()
        val names = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags
        return names.mapIndexed { i, name ->
            val granted = flags != null && i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            val pi = runCatching { ctx.packageManager.getPermissionInfo(name, 0) }.getOrNull()
            @Suppress("DEPRECATION")
            val base = pi?.let { it.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE } ?: -1
            Perm(name, base, granted, pi != null)
        }
    }

    /** Grant every DENIED runtime (dangerous) permission of [pkg] through the store's EXISTING
     *  privileged door — the [StoreShell] broker that Stop uses — with `pm grant`. The broker
     *  returning null means no channel is armed: say so (there is no requestPermissions for another
     *  package), the same fallback Stop gives. */
    private fun askGrantAll(ctx: Context, pkg: String, missing: List<String>, onDone: () -> Unit) {
        if (missing.isEmpty()) { Toast.makeText(ctx, "Nothing to grant", Toast.LENGTH_SHORT).show(); return }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        thread(name = "store-perms-grant-$pkg") {
            // Package names are [A-Za-z0-9._]; safe as a shell word.
            var served = false
            missing.forEach { if (StoreShell.exec(ctx, "pm grant $pkg $it") != null) served = true }
            val ok = served
            main.post {
                if (!ok) Toast.makeText(ctx, "No shell channel armed — use Open to grant in Android settings", Toast.LENGTH_LONG).show()
                onDone()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Perms(app: Fleet.App, onDismiss: () -> Unit) {
        val ctx = LocalContext.current
        val pkg = remember(app.id) { Fleet.installedId(ctx, app) ?: app.pkg }
        var tick by remember(app.id) { mutableIntStateOf(0) }
        val all = remember(app.id, tick) { perms(ctx, pkg) }
        Sheet(TAG_PERMS, app.label, pkg, onDismiss) {
            val runtime = all.filter { it.base == PermissionInfo.PROTECTION_DANGEROUS }
            val missing = runtime.filter { !it.granted }.map { it.name }
            // TOP action row: Open (system per-app screen) · Ask Grant All (missing runtime).
            Row(Modifier.fillMaxWidth().padding(bottom = sdp(StoreDensity.S8)), horizontalArrangement = Arrangement.spacedBy(sdp(StoreDensity.S4))) {
                StoreBar.Button("Open", 0xFF2B6CB0.toInt(), {
                    runCatching { ctx.startActivity(com.diegonmarcos.superapp.perms.PermsPages.appInfoIntent(pkg)) }
                        .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
                }, Modifier.weight(1f).testTag("$TAG_PERMS:open"))
                StoreBar.Button("Ask Grant All", 0xFF7C3AED.toInt(), {
                    askGrantAll(ctx, pkg, missing) { tick++ }
                }, Modifier.weight(1f).testTag("$TAG_PERMS:grant-all"))
            }
            if (all.isEmpty()) { Note("none declared"); return@Sheet }
            // SEPARATED tables.
            Table("Manifest", "$TAG_PERMS:manifest", all.size) {
                all.forEach { PermRow(it.name, level(it), null) }
            }
            val auto = all.filter { it.known && it.base != PermissionInfo.PROTECTION_DANGEROUS }
            Table("Auto grants", "$TAG_PERMS:auto", auto.size) {
                if (auto.isEmpty()) Note("none declared")
                else auto.forEach { PermRow(it.name, level(it), it.granted) }
            }
            Table("Runtime", "$TAG_PERMS:runtime", runtime.size) {
                if (runtime.isEmpty()) Note("none declared")
                else runtime.forEach { PermRow(it.name, "dangerous", it.granted) }
            }
        }
    }

    private fun level(p: Perm): String = when (p.base) {
        PermissionInfo.PROTECTION_NORMAL -> "normal"
        PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
        PermissionInfo.PROTECTION_SIGNATURE -> "signature"
        else -> "—"
    }

    @Composable
    private fun PermRow(name: String, level: String, granted: Boolean?) {
        Row(Modifier.fillMaxWidth().padding(vertical = sdp(StoreDensity.S2))) {
            Text(name.substringAfterLast('.'), color = cText.ink(), fontSize = StoreDensity.T_META.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Text(level, color = cDim.ink(), fontSize = StoreDensity.T_CAPTION.sp,
                modifier = Modifier.padding(end = sdp(StoreDensity.S8)))
            if (granted != null) Text(if (granted) "granted" else "denied",
                color = (if (granted) cOk else cBad).ink(), fontSize = StoreDensity.T_CAPTION.sp, fontWeight = FontWeight.Bold)
        }
    }

    // ── ROUTES ─────────────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Routes(app: Fleet.App, onDismiss: () -> Unit) {
        val ctx = LocalContext.current
        var tab by remember(app.id) { mutableIntStateOf(0) }
        val tabStyle = rememberStoreControls().groupTab
        // MCP + API from AppsMcpApi (apps-mcp-api.json), IPC from IpcMesh (baked ipc-mesh.json).
        val mcpApi = remember(app.id) {
            runCatching { AppsMcpApi.rows(AppsMcpApi.load(ctx), listOf(app.id to app.label)).firstOrNull() }.getOrNull()
        }
        val ipc = remember(app.id) {
            runCatching { IpcMesh.baked().members.firstOrNull { it.id == app.id }?.endpoints }.getOrNull().orEmpty()
        }
        Sheet(TAG_ROUTES, app.label, app.pkg, onDismiss) {
            StoreTabs.Bar(listOf(listOf(
                StoreControls.Control("MCP", "", tabStyle),
                StoreControls.Control("IPC", "", tabStyle),
                StoreControls.Control("API", "", tabStyle),
            )), tab) { tab = it }
            when (tab) {
                0 -> {
                    val eps = mcpApi?.byKind?.get("mcp").orEmpty()
                    if (eps.isEmpty()) Note("none declared")
                    else eps.forEach { RouteRow(it.method, it.url, it.what) }
                }
                1 -> {
                    if (ipc.isEmpty()) Note("none declared")
                    else ipc.forEach { RouteRow(it.kind, it.address, it.permission?.let { p -> "perm: ${p.substringAfterLast('.')}" } ?: it.name) }
                }
                else -> {
                    val eps = (mcpApi?.byKind?.get("app").orEmpty()) + (mcpApi?.byKind?.get("debug").orEmpty())
                    if (eps.isEmpty()) Note("none declared")
                    else eps.forEach { RouteRow(it.method, it.url, it.what) }
                }
            }
        }
    }

    @Composable
    private fun RouteRow(lead: String, addr: String, what: String) {
        Column(Modifier.fillMaxWidth().padding(vertical = sdp(StoreDensity.S4))) {
            Row(Modifier.fillMaxWidth()) {
                Text(lead, color = cLink.ink(), fontSize = StoreDensity.T_CAPTION.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = sdp(StoreDensity.S8)))
                Text(addr, color = cText.ink(), fontSize = StoreDensity.T_META.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f))
            }
            if (what.isNotBlank()) Text(what, color = cDim.ink(), fontSize = StoreDensity.T_CAPTION.sp)
        }
    }

    // ── LIBS ────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Libs(app: Fleet.App, onDismiss: () -> Unit) {
        val ctx = LocalContext.current
        val fleet = remember { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64).associateBy { it.id } }
        Sheet(TAG_LIBS, app.label, app.pkg, onDismiss) {
            LibGroup("Compiled in — linked into this APK, nothing to install", "$TAG_LIBS:compiled", app.libs, fleet)
            LibGroup("Installed separately — bound engines, own APK", "$TAG_LIBS:engines", app.engineLibs, fleet)
        }
    }

    @Composable
    private fun LibGroup(title: String, tag: String, ids: List<String>, fleet: Map<String, Fleet.App>) {
        Table(title, tag, ids.size) {
            if (ids.isEmpty()) Note("none declared")
            else ids.forEach { id ->
                Text(fleet[id]?.label ?: id, color = cText.ink(), fontSize = StoreDensity.T_META.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = sdp(StoreDensity.S2)))
            }
        }
    }

    // ── sheet frame + small helpers ──────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Sheet(tag: String, title: String, pkg: String, onDismiss: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SHEET_BG.ink(),
            modifier = Modifier.testTag(tag),
        ) {
            Column(Modifier.padding(start = sdp(StoreDensity.GLYPH), top = sdp(StoreDensity.S12), end = sdp(StoreDensity.GLYPH), bottom = sdp(StoreDensity.S8))) {
                Text(title, color = cText.ink(), fontSize = StoreDensity.T_HEAD.sp, fontWeight = FontWeight.Bold)
                Text(pkg, color = cDim.ink(), fontSize = StoreDensity.T_META.sp)
            }
            Column(Modifier.fillMaxWidth().height(sdp(StoreDensity.SHEET)).verticalScroll(rememberScrollState())
                .padding(horizontal = sdp(StoreDensity.S12)), content = body)
        }
    }

    @Composable
    private fun Table(title: String, tag: String, count: Int, content: @Composable ColumnScope.() -> Unit) {
        val shape = RoundedCornerShape(sdp(StoreDensity.S12))
        Column(Modifier.padding(top = sdp(StoreDensity.S8)).fillMaxWidth().clip(shape).background(Ink.CARD.ink())
            .border(sdp(StoreDensity.S1), 0xFF322A44.toInt().ink(), shape)
            .padding(horizontal = sdp(StoreDensity.S12), vertical = sdp(StoreDensity.S8))) {
            Text("$title ($count)", color = cLink.ink(), fontSize = StoreDensity.T_BODY.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag(tag).padding(bottom = sdp(StoreDensity.S4)))
            content()
        }
    }

    @Composable
    private fun Note(text: String) =
        Text(text, color = cDim.ink(), fontSize = StoreDensity.T_CAPTION.sp, modifier = Modifier.padding(vertical = sdp(StoreDensity.S4)))
}
