package com.diegonmarcos.clouddrive.configs

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import com.diegonmarcos.superapp.perms.PermAction
import com.diegonmarcos.superapp.perms.PermEntry
import com.diegonmarcos.superapp.perms.PermItem
import com.diegonmarcos.superapp.perms.PermItemKind
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.Perms
import com.diegonmarcos.superapp.perms.PermsList
import com.diegonmarcos.superapp.perms.PermsStyle
import com.diegonmarcos.superapp.perms.PermsView

/**
 * #1333 the Perms section of Configs > General and its anchor row. The LIST is build.json::ui.general.perms (GeneralDecl);
 * this file only dispatches on an entry's `check` (how to read it) and `grant` (how to fix it) and walks its `intents` in
 * order. It holds no permission name, no reason and no settings screen of its own.
 */
object GeneralPerms {
    /** The `{name}` placeholders the declaration may use, the same ones the manifest takes from the fleet manifest. */
    fun vars(): Map<String, String> = mapOf("terminalPackage" to StoreAccess.ownerPackage(), "authMissionPermission" to BuildConfig.AUTH_MISSION_PERMISSION)

    private fun names(e: GeneralDecl.PermEntry) = e.permissions.map { GeneralDecl.resolve(it, vars()) }
    private fun held(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** The probe: dispatch on `check`. null = not readable. */
    @Suppress("DEPRECATION")
    fun granted(ctx: Context, e: GeneralDecl.PermEntry): Boolean? = when (e.check) {
        "permission" -> names(e).all { held(ctx, it) }
        // All-files access is a special access from Android 11; below it the declared legacy permissions are the grant.
        "all_files" -> if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
                       else names(e).filter { it != android.Manifest.permission.MANAGE_EXTERNAL_STORAGE }.all { held(ctx, it) }
        "battery_opt" -> (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
        "install_unknown" -> ctx.packageManager.canRequestPackageInstalls()
        "appop" -> (ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).let {
            val mode = if (Build.VERSION.SDK_INT >= 29) it.unsafeCheckOpNoThrow(e.appop, android.os.Process.myUid(), ctx.packageName)
                       else it.checkOpNoThrow(e.appop, android.os.Process.myUid(), ctx.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        }
        else -> null
    }

    fun status(ctx: Context, e: GeneralDecl.PermEntry): GeneralDecl.Status {
        val pkg = GeneralDecl.resolve(e.requiresPackage, vars())
        val installed = pkg.isBlank() || runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        return GeneralDecl.status(e, Build.VERSION.SDK_INT, Build.MANUFACTURER ?: "", granted(ctx, e), installed)
    }

    fun intentOf(ctx: Context, s: GeneralDecl.IntentSpec): Intent? {
        val i = when {
            s.component.contains('/') -> Intent().setComponent(ComponentName.unflattenFromString(s.component) ?: return null)
            s.action.isNotBlank() -> Intent(s.action)
            else -> return null
        }
        val pkg = GeneralDecl.resolve(s.target, vars()).ifBlank { ctx.packageName }
        if (s.packageUri) i.data = Uri.parse("package:$pkg")
        if (s.extraPackage) i.putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Tries the declared intents in order; the first that starts wins. false = none did. */
    fun open(ctx: Context, e: GeneralDecl.PermEntry): Boolean = e.intents.any { s ->
        val i = intentOf(ctx, s)
        i != null && runCatching { ctx.startActivity(i) }.isSuccess
    }
}

/** The icon row pinned above the page: one icon per declared anchor, a tap scrolls to that section. */
@Composable
fun GeneralAnchorRow(anchors: List<GeneralDecl.Anchor>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = DriveMetrics.gutter), verticalAlignment = Alignment.CenterVertically) {
            anchors.forEachIndexed { index, a ->
                IconButton(onClick = { onSelect(index) }, modifier = Modifier.size(DriveMetrics.tap).testTag("configs_general_anchor_" + a.id)) {
                    Icon(IconCatalog.vectorOrDefault(a.icon), contentDescription = a.label, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        HorizontalDivider()
    }
}

/**
 * #1372 a declared entry as a row of the shared libs:perms list: the catalogue's one-line reason and icon when the id is a
 * catalogue entry, else the first sentence of the declared reason; `grant: none` entries are Granted at install, the git
 * engine's grant is a helper row. The button is the runtime dialog or the declared settings screens.
 */
fun GeneralPerms.item(e: GeneralDecl.PermEntry, st: GeneralDecl.Status, cat: PermEntry?): PermItem {
    val state = when (st) {
        GeneralDecl.Status.GRANTED -> PermState.GRANTED
        GeneralDecl.Status.DENIED -> PermState.DENIED
        GeneralDecl.Status.NOT_APPLICABLE -> PermState.NOT_APPLICABLE
        GeneralDecl.Status.INFO -> PermState.UNKNOWN
    }
    val kind = when {
        e.grant == "none" -> PermItemKind.INSTALL
        e.grant == "runtime" -> PermItemKind.RUNTIME
        else -> PermItemKind.SPECIAL
    }
    val canFix = e.grant != "none" && (st == GeneralDecl.Status.DENIED || (st == GeneralDecl.Status.INFO && e.intents.isNotEmpty()))
    val line = cat?.why?.takeIf { it.isNotBlank() } ?: e.why.split(". ", ": ").first().trimEnd('.') + "."
    val icon = cat?.icon?.takeIf { it.isNotBlank() } ?: when (kind) { PermItemKind.INSTALL -> "check"; PermItemKind.HELPER -> "folder"; else -> if (e.oem.isNotBlank()) "bolt" else "security" }
    return PermItem(e.id, e.label, line, icon, kind, state,
        action = if (!canFix) null else if (e.grant == "runtime") PermAction.GRANT else PermAction.OPEN)
}

/** The Perms section body: libs:perms' shared list (summary, Grant all, groups) over the declared entries, re-read on resume. */
@Composable
fun GeneralPermsSection(perms: List<GeneralDecl.PermEntry>) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    var pending by remember { androidx.compose.runtime.mutableStateOf<GeneralDecl.PermEntry?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        // Denied for good (or the dialog is not shown any more): fall back to the declared settings screens.
        if (!ok) pending?.let { GeneralPerms.open(ctx, it) }
        tick++
    }
    val cat = remember { Perms.catalogue(ctx) }
    val items = remember(tick, perms) { perms.map { e -> GeneralPerms.item(e, GeneralPerms.status(ctx, e), cat.byId(e.id)) } }
    fun act(i: PermItem) {
        val e = perms.firstOrNull { it.id == i.id } ?: return
        val first = GeneralPerms.vars().let { v -> e.permissions.map { GeneralDecl.resolve(it, v) }.firstOrNull { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } }
        if (e.grant == "runtime" && first != null) { pending = e; launcher.launch(first) } else GeneralPerms.open(ctx, e)
    }
    // Grant all: the first missing row each tap (one dialog or settings screen at a time; the list re-reads on return)
    val next = PermsView.walk(items).firstOrNull()
    PermsList(items, ::act, next?.let { n -> { act(n) } }, Modifier.padding(horizontal = DriveMetrics.gutter).testTag(DriveTags.CONFIGS_CARD),
        PermsStyle(ink = MaterialTheme.colorScheme.onSurface, dim = MaterialTheme.colorScheme.onSurfaceVariant, accent = MaterialTheme.colorScheme.primary))
}
