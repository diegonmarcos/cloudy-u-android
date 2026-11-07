package com.diegonmarcos.superapp.devcontrol

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.settings.ConfigHeader
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitFilterChips
import com.diegonmarcos.superapp.uikit.KitTextButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configs ▸ Setup ▸ Watchtower - Obsv ▸ Logcat Fleet (build.json config page `logcat-fleet`): the fleet-wide logcat.
 *
 *  - WHICH apps: a multi-select picker (every installed app, the fleet's com.diegonmarcos.* first), plus Fleet apps / This app /
 *    Clear selection shortcuts; the selection shows as chips.
 *  - ONE app: the per-app strip under Load switches the pane between Combined and a single selected app.
 *  - EXPORT: Export (save-as), Copy, Share and Post c3-api output the SELECTED apps' logs, one titled block per app.
 *
 * Reading other apps' lines takes elevation, through the plane [AboutLogcat] already uses: READ_LOGS granted once (Shizuku or a
 * paired adb `pm grant`, then in-process with nothing running), else a shell channel `logcat --uid`. The page states which is
 * in force and offers the one-time grant; when neither is reachable the pane shows the mechanism instead of failing. It never
 * execs logcat on open (an Android 13+ consent dialog) — the user taps Load.
 */
class FleetLogcatFragment : KitComposeFragment() {
    override fun palette() = LauncherPalette.kit(requireContext())

    private val log = LogcatSink(this)

    @Composable
    override fun Content() {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            ConfigHeader("Logcat Fleet")
            FleetLogcatScreen(onExport = log::export, onShare = log::share, onCopy = log::copy, modifier = Modifier.weight(1f))
        }
    }

    companion object { fun newInstance() = FleetLogcatFragment() }
}

@Composable
internal fun FleetLogcatScreen(
    onExport: (text: String, fileName: String) -> Unit,
    onShare: (text: String, subject: String) -> Unit,
    onCopy: (text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<FleetLogcat.App>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }       // package names
    var picking by remember { mutableStateOf(false) }
    var errorsOnly by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf(VIEW_ALL) }                          // VIEW_ALL or a package name
    var logs by remember { mutableStateOf<Map<String, List<LogcatModel.Rec>>>(emptyMap()) }  // package -> lines
    var status by remember { mutableStateOf("Pick the apps, then tap Load.") }
    var held by remember { mutableStateOf(AboutLogcat.holdsReadLogs(ctx)) }
    var granting by remember { mutableStateOf(false) }

    fun ensureApps(then: () -> Unit = {}) {
        if (apps.isNotEmpty()) { then(); return }
        scope.launch { apps = withContext(Dispatchers.IO) { FleetLogcat.apps(ctx) }; then() }
    }

    fun load() {
        val chosen = apps.filter { it.pkg in selected }
        if (chosen.isEmpty()) { status = "No app selected — Pick apps first."; return }
        status = "Loading ${chosen.size} app(s)…"
        scope.launch {
            val raw = withContext(Dispatchers.IO) { FleetLogcat.read(ctx, chosen.map { it.uid }.toSet(), errorsOnly) }
            logs = chosen.associate { it.pkg to LogcatModel.parse(raw[it.uid].orEmpty()) }
            if (view != VIEW_ALL && view !in logs) view = VIEW_ALL
            status = "Loaded ${logs.values.sumOf { it.size }} lines from ${chosen.size} app(s)" + (if (errorsOnly) " (errors only)" else "")
        }
    }

    val chosenApps = apps.filter { it.pkg in selected }
    val shownRecs: List<LogcatModel.Rec> =
        if (view == VIEW_ALL) logs.values.flatten() else logs[view].orEmpty()
    fun exportText(): String = LogcatModel.combine(chosenApps.filter { it.pkg in logs }.map { "${it.label} (${it.pkg})" to logs.getValue(it.pkg) })
    fun stamp() = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
    fun exportName() = "logcat-fleet-${chosenApps.size}apps-${stamp()}.log"

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ── the reader: the mechanism in force ────────────────────────────────
        val reader = remember(held, granting, status) {
            if (held) "Reader: READ_LOGS granted — in-process, every app, nothing needs to stay running."
            else "Reader: shell channel (${com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)?.name() ?: "none ready"}; " +
                "Shizuku: ${com.diegonmarcos.superapp.adbdebug.ShizukuAdb.status()}). Other apps' logs need READ_LOGS or a live channel."
        }
        Text(reader, color = if (held) p.ok else p.textSecondary, fontSize = 11.sp, modifier = Modifier.testTag("logfleet:reader"))
        if (!held) {
            KitButton(enabled = !granting, modifier = Modifier.fillMaxWidth().testTag("logfleet:grant"), onClick = {
                granting = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        com.diegonmarcos.superapp.adbdebug.ShizukuAdb.grant(ctx, "android.permission.READ_LOGS")
                    }
                    status = when {
                        r.held -> "READ_LOGS granted"
                        !r.ran -> r.output
                        else -> "pm grant ran but the permission is still not held: ${r.output}"
                    }
                    granting = false
                    held = AboutLogcat.holdsReadLogs(ctx)
                }
            }) { Text(if (granting) "Granting…" else "Enable all-app logs (one-time grant)", fontSize = 12.sp) }
        }

        // ── APPS: which apps' logs ────────────────────────────────────────────
        Column(Modifier.fillMaxWidth().testTag("logfleet:apps"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Apps · ${selected.size} selected", color = p.textPrimary, fontSize = 12.sp)
            KitActionBar(listOf(
                KitAction("Pick apps", "logfleet:pick") { ensureApps { picking = true } },
                KitAction("Fleet apps", "logfleet:fleet") { ensureApps { selected = apps.filter { it.fleet }.map { it.pkg }.toSet() } },
                KitAction("This app", "logfleet:this") { selected = setOf(BuildConfig.APPLICATION_ID); ensureApps() },
                KitAction("Clear selection", "logfleet:none") { selected = emptySet(); logs = emptyMap(); view = VIEW_ALL },
            ), filledFirst = false)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (pkg in selected.sorted()) {
                    val label = apps.firstOrNull { it.pkg == pkg }?.label ?: pkg
                    com.diegonmarcos.superapp.uikit.KitChip(label, onRemove = { selected = selected - pkg; logs = logs - pkg; if (view == pkg) view = VIEW_ALL }, tag = "logfleet:chip:$pkg")
                }
            }
        }

        // ── FILTER + LOAD + per-app view ──────────────────────────────────────
        Column(Modifier.fillMaxWidth().testTag("logfleet:filters"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("View", color = p.textPrimary, fontSize = 12.sp)
            KitFilterChips(listOf("all" to "All logs", "err" to "Errors"), if (errorsOnly) "err" else "all",
                { errorsOnly = it == "err" }, group = "logfleet-level")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AppPill("Combined", view == VIEW_ALL, "logfleet:view:all") { view = VIEW_ALL }
                for (a in chosenApps) AppPill(a.label, view == a.pkg, "logfleet:view:${a.pkg}") { view = a.pkg }
            }
        }

        // ── ACTIONS ───────────────────────────────────────────────────────────
        Column(Modifier.fillMaxWidth().testTag("logfleet:actions"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Actions", color = p.textPrimary, fontSize = 12.sp)
            KitActionBar(listOf(
                KitAction("Load", "logfleet:act:load") { ensureApps { load() } },
                KitAction("Copy", "logfleet:act:copy") { onCopy(exportText()) },
                KitAction("Export", "logfleet:act:export") { onExport(exportText(), exportName()) },
                KitAction("Share", "logfleet:act:share") { onShare(exportText(), "Fleet logcat") },
                KitAction("Post c3-api", "logfleet:act:post") {
                    val body = exportText()
                    status = "Sending…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            com.diegonmarcos.superapp.core.LogUpload.post(BuildConfig.LOG_INGEST_URL, "cloud-superapp", body)
                        }
                        status = when (r) {
                            is com.diegonmarcos.superapp.core.LogUpload.Result.Ok -> "Sent → ${r.file}"
                            is com.diegonmarcos.superapp.core.LogUpload.Result.Failed -> "Send failed: ${r.reason}"
                        }
                    }
                },
            ), filledFirst = true)
        }

        Text(status, color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.testTag("logfleet:status"))
        LogPane(shownRecs, autoscroll = false, tag = "logfleet:pane", modifier = Modifier.weight(1f))
    }

    if (picking) AppPickerDialog(apps, selected, { selected = it }, { picking = false })
}

private const val VIEW_ALL = ""

@Composable
private fun AppPill(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) p.accent else p.surfaceSelected).clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag)) {
        Text(label, color = if (on) p.tileInk else p.textSecondary, fontSize = 12.sp, maxLines = 1)
    }
}

/** The multi-select: a search box over a checkbox list; the selection applies as it is ticked. */
@Composable
private fun AppPickerDialog(apps: List<FleetLogcat.App>, selected: Set<String>, onSelected: (Set<String>) -> Unit, onDone: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = apps.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Logs for which apps (${selected.size})") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Search") }, modifier = Modifier.fillMaxWidth().testTag("logfleet:picker:search"))
                LazyColumn(Modifier.height(320.dp).testTag("logfleet:picker:list")) {
                    items(shown) { a ->
                        val on = a.pkg in selected
                        Row(Modifier.fillMaxWidth().clickable { onSelected(if (on) selected - a.pkg else selected + a.pkg) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, { onSelected(if (on) selected - a.pkg else selected + a.pkg) })
                            Column {
                                Text(a.label, fontSize = 13.sp)
                                Text(a.pkg + if (a.fleet) " · fleet" else "", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { KitTextButton(onClick = onDone, modifier = Modifier.testTag("logfleet:picker:done")) { Text("Done") } },
    )
}
