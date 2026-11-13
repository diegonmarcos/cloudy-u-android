package com.diegonmarcos.clouddrive.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.files.FileOps
import com.diegonmarcos.clouddrive.storage.StorageCoordinator
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.clouddrive.ui.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #1537 Manage ▸ Overview: every volume with its usage (used / free / total and a bar), grouped LOCAL (Emulated, Termux
 * home, Nix home, external drives) and CLOUD (Cloud-S3, Google Drive, the other declared mounts). The sources are
 * build.json ui.manage; the numbers come from [VolumeOverview] (StatFs and StorageManager for local, `rclone about` for cloud).
 * A volume that cannot be read shows n/a and why. One dense KitStoreRow line per volume.
 */
@Composable
fun OverviewPage(storage: StorageCoordinator) {
    val ctx = LocalContext.current
    val probe = remember { AndroidVolumeProbe(ctx) }
    val decl = Declarations.manage
    var local by remember { mutableStateOf<List<VolumeUsage>?>(null) }
    LaunchedEffect(Unit) { local = withContext(Dispatchers.IO) { VolumeOverview.local(decl.local, probe) } }
    val targets = remember { VolumeOverview.cloudTargets(decl.cloud, Declarations.remotes, Declarations.mountProviders) }
    var cloud by remember { mutableStateOf(emptyMap<String, VolumeUsage>()) }
    LaunchedEffect(Unit) {
        // rclone about is slow and offline-prone: each row fills in when its own answer arrives.
        for (t in targets) {
            val u = withContext(Dispatchers.IO) { VolumeOverview.cloudUsage(t) { name -> storage.ops.about(name).map { CloudAbout(it.used, it.total, it.free) } } }
            cloud = cloud + (t.id to u)
        }
    }
    val terminals = remember {
        OverviewTerminals.resolve(decl.terminals, Declarations.constellation) { pkg -> ctx.packageManager.getLaunchIntentForPackage(pkg) != null }
    }
    LazyColumn(Modifier.fillMaxSize().testTag(DriveTags.OVERVIEW)) {
        item { XrayMemoryRow() }
        item { SectionHeader(stringResource(R.string.manage_overview_local), count = local?.size) }
        items(local.orEmpty(), key = { it.id }) { VolumeRow(it.label, it.icon, it) }
        item { SectionHeader(stringResource(R.string.manage_overview_cloud), count = targets.size) }
        items(targets, key = { it.id }) { t -> VolumeRow(t.label, t.icon, cloud[t.id], id = t.id) }
        if (terminals.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.manage_overview_terminals), count = terminals.size) }
            item { TerminalLinks(terminals) }
        }
    }
}

/** The terminals as icon links side by side: tap opens the app (launch intent of its package); an absent one says "not installed" and offers Cloud Store on that app. */
@Composable
private fun TerminalLinks(links: List<TerminalLink>) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(DriveMetrics.gutter), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DriveMetrics.pad)) {
        for (l in links) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val tint = if (l.installed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                androidx.compose.foundation.layout.Box(
                    Modifier.testTag(DriveTags.terminalLink(l.id)).fillMaxWidth().clickable(enabled = l.installed) { openTerminal(ctx, l) }.padding(DriveMetrics.pad),
                    contentAlignment = Alignment.Center,
                ) { Icon(IconCatalog.vectorOrDefault(l.icon), contentDescription = stringResource(R.string.manage_terminal_open, l.label), Modifier.size(DriveMetrics.glyphIcon * 2), tint = tint) }
                KitStoreName(l.label)
                if (l.installed) KitStoreCaption(l.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, mono = true)
                else {
                    KitStoreCaption(stringResource(R.string.manage_terminal_not_installed), color = MaterialTheme.colorScheme.error)
                    Pill(stringResource(R.string.manage_terminal_get), { openStore(ctx, l) }, modifier = Modifier.testTag(DriveTags.terminalGet(l.id)).padding(top = DriveMetrics.pad), filled = true, enabled = l.packageName.isNotBlank())
                }
            }
        }
    }
    Hairline()
}

private fun openTerminal(ctx: android.content.Context, l: TerminalLink) {
    val intent = runCatching { ctx.packageManager.getLaunchIntentForPackage(l.packageName) }.getOrNull()
    val r = if (intent == null) Result.failure(IllegalStateException("not installed")) else runCatching { ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    r.exceptionOrNull()?.let { android.widget.Toast.makeText(ctx, ctx.getString(R.string.manage_terminal_failed, l.label, it.message ?: it.javaClass.simpleName), android.widget.Toast.LENGTH_SHORT).show() }
}

/** Cloud Store, the installer of record, opened on that app (libs:updater StoreHandoff). */
private fun openStore(ctx: android.content.Context, l: TerminalLink) {
    val r = runCatching { ctx.startActivity(com.diegonmarcos.superapp.updater.install.StoreHandoff.intent(ctx.packageName, l.packageName)) }
    r.exceptionOrNull()?.let { android.widget.Toast.makeText(ctx, ctx.getString(R.string.manage_terminal_failed, "Cloud Store", it.message ?: it.javaClass.simpleName), android.widget.Toast.LENGTH_SHORT).show() }
}

@Composable
private fun VolumeRow(label: String, icon: String, usage: VolumeUsage?, id: String = usage?.id.orEmpty()) {
    KitStoreRowLine(Modifier.testTag(DriveTags.overviewRow(id))) {
        Icon(IconCatalog.vectorOrDefault(icon), contentDescription = null, Modifier.size(DriveMetrics.glyphIcon))
        Spacer(Modifier.width(KitStoreRow.gap))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                KitStoreName(label, Modifier.weight(1f))
            }
            when {
                usage == null -> KitStoreCaption(stringResource(R.string.manage_overview_checking), color = MaterialTheme.colorScheme.onSurfaceVariant)
                usage.unavailable != null -> KitStoreCaption(stringResource(R.string.manage_overview_na, usage.unavailable), color = MaterialTheme.colorScheme.error)
                else -> {
                    if (usage.hasBar) LinearProgressIndicator(progress = { usage.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = KitStoreRow.tight).height(KitStoreRow.gap).testTag(DriveTags.OVERVIEW_BAR))
                    KitStoreCaption(usageLine(usage), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            usage?.path?.let { KitStoreCaption(it, color = MaterialTheme.colorScheme.onSurfaceVariant, mono = true) }
        }
    }
    Hairline()
}

@Composable
private fun usageLine(u: VolumeUsage): String {
    val used = u.used?.let { FileOps.humanBytes(it) }
    val free = u.free?.let { FileOps.humanBytes(it) }
    val total = u.total?.let { FileOps.humanBytes(it) }
    return if (used != null && free != null && total != null) stringResource(R.string.manage_overview_usage, used, free, total)
    else stringResource(R.string.manage_overview_used_only, used ?: free ?: total ?: "?")
}

/** The X-Ray Memory state: idle, working, a finished report (kept in memory for Save / Share) or the reason it failed. */
private sealed class XrayState {
    object Idle : XrayState()
    object Working : XrayState()
    data class Ready(val markdown: String, val fileName: String, val sections: Int) : XrayState()
    data class Failed(val reason: String) : XrayState()
}

/** Overview > X-Ray Memory: one action that builds the full device report and offers it as a .md file (system save dialog, or the share sheet). */
@Composable
internal fun XrayMemoryRow() {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var state by remember { mutableStateOf<XrayState>(XrayState.Idle) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val ready = state as? XrayState.Ready
        if (uri != null && ready != null) scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(ready.markdown.toByteArray(Charsets.UTF_8)) } } }
            if (r.isFailure) state = XrayState.Failed(r.exceptionOrNull()?.message ?: "could not write the file")
        }
    }
    KitStoreRowLine(Modifier.testTag(DriveTags.XRAY_ROW)) {
        Icon(IconCatalog.vectorOrDefault("smartphone"), contentDescription = null, Modifier.size(DriveMetrics.glyphIcon))
        Spacer(Modifier.width(KitStoreRow.gap))
        Column(Modifier.weight(1f)) {
            KitStoreName(stringResource(R.string.manage_xray_title))
            KitStoreCaption(stringResource(R.string.manage_xray_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val st = state
            val line = when (st) {
                XrayState.Idle -> null
                XrayState.Working -> stringResource(R.string.manage_xray_working)
                is XrayState.Ready -> stringResource(R.string.manage_xray_ready, FileOps.humanBytes(st.markdown.toByteArray().size.toLong()), st.sections)
                is XrayState.Failed -> stringResource(R.string.manage_xray_failed, st.reason)
            }
            if (line != null) KitStoreCaption(line, Modifier.testTag(DriveTags.XRAY_STATUS), color = if (st is XrayState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (st is XrayState.Ready) Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DriveMetrics.pad), modifier = Modifier.padding(top = DriveMetrics.pad)) {
                Pill(stringResource(R.string.manage_xray_save), { save.launch(st.fileName) }, modifier = Modifier.testTag(DriveTags.XRAY_SAVE), filled = true)
                Pill(stringResource(R.string.manage_xray_share), { XrayShare.share(ctx, st.markdown, st.fileName) }, modifier = Modifier.testTag(DriveTags.XRAY_SHARE))
            }
        }
        Pill(stringResource(R.string.manage_xray_generate), {
            state = XrayState.Working
            scope.launch {
                state = withContext(Dispatchers.IO) {
                    runCatching {
                        val snap = runCatching { AppSnapshot.collect(ctx, useShell = false) }.getOrNull()
                        val inputs = XrayMemoryCollector.collect(ctx, snap, com.diegonmarcos.clouddrive.BuildConfig.VERSION_NAME)
                        val md = XrayMemoryReport.markdown(inputs)
                        val name = "xray-memory-" + inputs.device.model.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + "-" + inputs.device.timestamp.replace(Regex("[^0-9T]"), "") + ".md"
                        XrayState.Ready(md, name, XrayMemoryReport.SECTIONS.size) as XrayState
                    }.getOrElse { XrayState.Failed(it.message ?: it.javaClass.simpleName) }
                }
            }
        }, modifier = Modifier.testTag(DriveTags.XRAY_GENERATE), filled = true, enabled = state != XrayState.Working)
    }
    Hairline()
}

/** The share sheet for a finished report: the Markdown as text (capped, so the binder call stays small; Save .md writes the whole file). */
private object XrayShare {
    const val MAX_CHARS = 200_000
    fun share(ctx: android.content.Context, md: String, name: String) {
        val text = if (md.length > MAX_CHARS) md.take(MAX_CHARS) + "\n\n(truncated for sharing; use Save .md for the whole report)\n" else md
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(android.content.Intent.EXTRA_SUBJECT, name); putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        runCatching { ctx.startActivity(android.content.Intent.createChooser(send, name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
