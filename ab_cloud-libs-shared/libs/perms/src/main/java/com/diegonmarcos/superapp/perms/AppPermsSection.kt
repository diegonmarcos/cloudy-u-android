package com.diegonmarcos.superapp.perms

import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * #1360 / #1372 what an app's Perms UI shows and does: its own declared grants (build.json::perms, catalogue rows), its
 * helper apps' grants, what Android granted at install, and any [extra] rows the host adds (Cloud Drive's git engine).
 * [act] runs one row's Grant / Open, [grantAll] WALKS every missing one: the runtime ones in one system dialog, then each
 * special page in turn (Android has no batch for special access), advancing when the user comes back; an entry the user
 * leaves denied is not opened twice in one walk.
 */
class AppPermsState internal constructor(
    val items: List<PermItem>,
    val act: (PermItem) -> Unit,
    val grantAll: () -> Unit,
    /** #1403 Grant all of the actions row: one system dialog for every missing runtime row. */
    val grantRuntime: () -> Unit = {},
) {
    val missing: Int get() = PermsView.walk(items).size
}

@Composable
fun rememberAppPerms(
    tick: Int,
    highlight: Set<String> = emptySet(),
    extra: List<PermItem> = emptyList(),
    onExtra: (PermItem) -> Unit = {},
    /** #1513 the host's adb channel for the adb-only rows' Grant, and where its one-line outcome goes. */
    adb: PermsAdb? = null,
    onAdbResult: (String) -> Unit = {},
): AppPermsState {
    val ctx = LocalContext.current
    var local by remember { mutableIntStateOf(0) }
    var walking by remember { mutableStateOf(false) }
    var walked by remember { mutableStateOf(emptySet<String>()) }
    var resumes by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { local++ }
    val activity = remember(ctx) { ctx.findActivity() }
    DisposableEffect(activity) {
        val owner = activity as? androidx.lifecycle.LifecycleOwner
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumes++ }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    val scope = rememberCoroutineScope()
    val own = remember(tick, local, resumes) { AppPerms.own(ctx, activity) }
    val helpers = remember(tick, local, resumes) { AppPerms.helpers(ctx) }
    val install = remember(tick) { AppPerms.installItems(ctx) }
    val items = own.map { PermsView.item(it.entry, it.status, it.entry.id in highlight) } +
        helpers.map { PermsView.helperItem(it.helper, it.entry, it.status, it.entry.id in highlight, AppPerms.helperName(ctx, it.helper)) } + install + extra
    // #1513 back from pairing or Settings: a held adb-only grant wakes the feature that waits on it (no polling, resume only).
    LaunchedEffect(resumes, tick) {
        own.filter { it.entry.kind == PermKind.ADB_ONLY && it.entry.permission.isNotEmpty() && AdbGrant.held(ctx, it.entry.permission) }
            .forEach { AdbGrant.onHeld?.invoke(ctx, it.entry.permission) }
    }
    val entries = own.associate { it.entry.id to it.entry }
    val helperRows = helpers.associateBy { "${it.helper.pkg}:${it.entry.id}" }
    val extraIds = extra.map { it.id }.toSet()

    fun request(ids: List<String>) {
        // #1519 one combined request (fine + coarse location together on API 31+); a permanent denial opens its settings page
        PermsRequest.ask(ctx, ids.mapNotNull { entries[it] }, { ask.launch(it) }, activity)
    }
    // #1513 the adb grant row: channel armed = grant now and read it back; else copy the line and open the pairing flow.
    fun adbGrant(i: PermItem) {
        val up = adb?.channel?.up() == true
        if (AdbGrant.offer(AdbGrant.held(ctx, i.permission), up) == AdbGrant.Offer.GRANT) {
            onAdbResult("Granting ${i.title}…")
            scope.launch {
                val ok = withContext(Dispatchers.IO) { AdbGrant.grant(ctx.packageName, i.permission, { adb?.channel?.exec(it) }, { AdbGrant.held(ctx, i.permission) }) }
                onAdbResult(if (ok) "${i.title}: granted." else "${i.title}: not granted, the channel refused it. Copy the command instead.")
                if (ok) AdbGrant.onHeld?.invoke(ctx, i.permission)
                local++
            }
        } else {
            val line = AdbGrant.adbLine(ctx.packageName, i.permission)
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("adb grant", line))
            onAdbResult("No channel armed. Copied: $line\nOr pair this app (opened): it then grants itself.")
            adb?.connect?.invoke()
        }
    }
    fun actOn(i: PermItem) {
        when {
            i.id in extraIds -> onExtra(i)
            i.permKind == PermKind.ADB_ONLY && i.permission.isNotEmpty() -> adbGrant(i)
            i.kind == PermItemKind.HELPER -> helperRows[i.id]?.let { AppPerms.openHelper(ctx, it) }
            i.action == PermAction.GRANT -> request(listOf(i.id))
            else -> entries[i.id]?.let { PermsSettings.open(ctx, it) }
        }
    }
    fun step(): Boolean {
        val todo = PermsView.walk(items).filter { it.id !in walked }
        if (todo.isEmpty()) return false
        val dialog = todo.filter { it.action == PermAction.GRANT && it.id !in extraIds }
        if (dialog.isNotEmpty()) { walked += dialog.map { it.id }; request(dialog.map { it.id }); return true }
        val next = todo.first(); walked += next.id; actOn(next); return true
    }
    // advance when the user comes back from the dialog or page (the host activity resumes), never on a refresh tick
    LaunchedEffect(resumes) { if (walking && !step()) walking = false }
    val runtime = { request(PermsView.walk(items).filter { it.action == PermAction.GRANT && it.id !in extraIds }.map { it.id }) }
    return AppPermsState(items, ::actOn, { walked = emptySet(); walking = step() }, runtime)
}

/**
 * #1360 / #1372 THE Perms section of every app (Configs ▸ General ▸ Perms, the libs:perms page): the summary card with
 * Grant all, then Runtime, Special access, Helper apps, Granted at install. Re-read on every [tick] and on resume.
 * [highlight] = ids drawn first and bold (the rows a feature asked for).
 */
@Composable
fun AppPermsSection(
    tick: Int,
    style: PermsStyle = PermsStyle(),
    highlight: Set<String> = emptySet(),
    extra: List<PermItem> = emptyList(),
    onExtra: (PermItem) -> Unit = {},
    modifier: Modifier = Modifier,
    /** #1403 the host's adb channel for the adb actions (null: those are off, Copy Adb Grants still works). */
    adb: PermsAdb? = null,
    /** #1403 a dense host page (SuperApp Configs): the toolbar and the list in their dense sizes. */
    dense: Boolean = false,
) {
    var adbNote by remember { mutableStateOf<String?>(null) }
    val s = rememberAppPerms(tick, highlight, extra, onExtra, adb, { adbNote = it })
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var states by remember { mutableStateOf(emptySet<StateFilter>()) }
    var kinds by remember { mutableStateOf(emptySet<KindFilter>()) }
    var result by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.CompositionLocalProvider(LocalPermsDense provides dense) { Column(modifier) {
        PermsToolbar(s.items, states, kinds, { states = it }, { kinds = it }, s.grantRuntime, s.grantAll, adb,
            onAdb = { mode -> adb?.let { a -> result = "Granting over adb…"; scope.launch { result = PermsComponents.grant(ctx, s.items, mode, a) } } },
            onCopy = { result = PermsComponents.copy(ctx, s.items) }, result = adbNote ?: result)
        PermsList(PermsActions.filter(s.items, states, kinds), s.act, s.grantAll.takeIf { s.missing > 0 }, Modifier, style)
    } }
}

/** The helper's launcher label when it is installed, else its canonical name in words. */
internal fun AppPerms.helperName(ctx: Context, h: HelperNeed): String = Perms.appLabel(ctx, h.pkg, h.label)

/** The install-time (normal) permissions Android granted with the app, in plain words; catalogue rows excluded. */
internal fun AppPerms.installItems(ctx: Context): List<PermItem> {
    val info = runCatching {
        @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
    }.getOrNull() ?: return emptyList()
    val names = info.requestedPermissions ?: return emptyList()
    val flags = info.requestedPermissionsFlags
    val catalogued = Perms.catalogue(ctx).entries.map { it.permission }.toSet()
    return names.mapIndexedNotNull { i, name ->
        if (name in catalogued) return@mapIndexedNotNull null
        val pi = runCatching { ctx.packageManager.getPermissionInfo(name, 0) }.getOrNull() ?: return@mapIndexedNotNull null
        @Suppress("DEPRECATION") val base = pi.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        val granted = flags != null && i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        if (base != PermissionInfo.PROTECTION_NORMAL || !granted) return@mapIndexedNotNull null
        val label = pi.loadLabel(ctx.packageManager).toString().takeIf { it.isNotBlank() && it != name }
            ?: name.substringAfterLast('.').lowercase().replace('_', ' ')
        PermItem(name, label.replaceFirstChar { it.uppercase() }, "Granted when the app was installed", "check", PermItemKind.INSTALL, PermState.GRANTED)
    }.distinctBy { it.title }.sortedBy { it.title }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}
