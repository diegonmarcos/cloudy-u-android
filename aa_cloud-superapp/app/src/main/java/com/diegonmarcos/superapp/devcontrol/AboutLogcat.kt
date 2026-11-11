package com.diegonmarcos.superapp.devcontrol

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The logcat reader behind Configs ▸ Observability ▸ Logcat (LogcatFragment).
 *
 * Android hands an unprivileged app only its OWN lines, so reading anything else takes elevation. There are two ways to have
 * it, and they are very different to live with:
 *
 *   READ_LOGS granted  — the good one. `logcat` in our own process returns EVERY app's lines. Nothing needs to be running:
 *                        Shizuku can be stopped and adb unplugged. This is why the grant is offered as a one-off.
 *   shell channel      — the fallback until then. Works, but only while Shizuku or a paired adb is actually up.
 *
 * A single app is narrowed by UID, not by pid: a pid needs the app to be RUNNING, while the uid is stable and still works for
 * one that has already crashed — usually the app whose log you want.
 */
internal object AboutLogcat {

    /** True once READ_LOGS has been granted; from then on the whole buffer is readable IN THIS PROCESS, with no shell live. */
    fun holdsReadLogs(ctx: Context): Boolean =
        ctx.checkSelfPermission("android.permission.READ_LOGS") == android.content.pm.PackageManager.PERMISSION_GRANTED

    const val SCOPE_THIS_APP = -1
    const val SCOPE_ALL_APPS = -2

    fun read(ctx: Context, uid: Int, errorsOnly: Boolean): String {
        val elevated = holdsReadLogs(ctx)

        // In-process whenever it can answer: own logs always, everything once READ_LOGS is held. Served from the shared
        // LogPipe stream and filtered in memory rather than by re-running `logcat --uid=N`: holding READ_LOGS sends every
        // separate exec to the log-consent dialog, so the old one-exec-per-refresh viewer re-prompted roughly every minute.
        if (uid == SCOPE_THIS_APP || elevated) {
            // "this app" must filter to OUR uid explicitly: once READ_LOGS is held the stream carries the whole device, and
            // leaving it unfiltered would quietly turn "this app" into "all apps".
            val scope = when {
                uid >= 0 -> uid
                uid == SCOPE_THIS_APP -> android.os.Process.myUid()
                else -> com.diegonmarcos.superapp.devtools.LogPipe.ANY_UID
            }
            return com.diegonmarcos.superapp.devtools.LogPipe.tail(n = 5000, uid = scope, errorsOnly = errorsOnly)
        }

        // Not granted yet: borrow a shell channel for this one read.
        val args = buildList {
            add("logcat"); add("-d"); add("-v"); add("time")
            // --uid landed in Android 10; below that logcat rejects it outright.
            if (uid >= 0 && android.os.Build.VERSION.SDK_INT >= 29) add("--uid=$uid")
            if (errorsOnly) add("*:E")
        }
        if (uid >= 0 && android.os.Build.VERSION.SDK_INT < 29) {
            return "Per-app filtering needs Android 10+ (logcat --uid). Use All Apps on this device."
        }
        val cmd = args.joinToString(" ")
        // The chosen mode's channel; Shizuku directly only where the mode uses Shizuku (never in the adb modes).
        val out = com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)?.exec(ctx, cmd)
            ?: (if (com.diegonmarcos.superapp.adbdebug.ShellChannels.modeUses(ctx, com.diegonmarcos.superapp.adbdebug.ChannelSelector.SHIZUKU))
                com.diegonmarcos.superapp.adbdebug.ShizukuAdb.exec(ctx, cmd) else null)
        return out?.ifBlank { "(empty — nothing in the buffer for this scope)" }
            ?: ("Reading another app's logs needs READ_LOGS, which Android will not give an " +
                "app at install time.\n\nTap \"Enable all-app logs\" below: it runs " +
                "`pm grant` through Shizuku or a paired adb ONCE, and the grant sticks — after " +
                "that this works with Shizuku stopped.\n\nShell channel: " +
                (com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)?.name() ?: "none ready") +
                "\nShizuku: " + com.diegonmarcos.superapp.adbdebug.ShizukuAdb.status())
    }

    /** Every installed app as (label, uid), A–Z. */
    fun installedApps(ctx: Context): List<Pair<String, Int>> {
        val pm = ctx.packageManager
        return runCatching {
            pm.getInstalledApplications(0).map { (pm.getApplicationLabel(it).toString()) to it.uid }
                .sortedBy { it.first.lowercase() }
        }.getOrDefault(emptyList())
    }

    /** The file name a log export is offered under. */
    fun exportName(scopeUid: Int, scopePkg: String, stamp: String): String {
        val who = when {
            scopeUid == SCOPE_THIS_APP -> "thisapp"
            scopeUid == SCOPE_ALL_APPS -> "allapps"
            else -> scopePkg.ifBlank { "uid$scopeUid" }
        }
        return "logcat-$who-$stamp.log"
    }
}

/**
 * Live logcat viewer (own-process logs) with the scope choice (this app | all apps | one app), the All | Errors filter, and
 * Refresh / Copy / Export / Post. DO NOT auto-refresh on open: any `logcat` exec by a non-shell app on Android 13+ (Samsung
 * especially) pops the system LogAccessDialogActivity, which launched OVER the About page every time it opened — the screen
 * looked like it "would not open / crashed" (2026-09-03). The user taps This App / All Apps / Refresh when they want logs, so
 * merely opening this tab never execs logcat.
 */
@Composable
internal fun AboutLogcatViewer(onExport: (text: String, fileName: String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val actions = LocalAboutActions.current
    val scope = rememberCoroutineScope()
    var scopeUid by remember { mutableIntStateOf(AboutLogcat.SCOPE_THIS_APP) }
    var scopePkg by remember { mutableStateOf("") }
    var errorsOnly by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("Tap “This App”, “All Apps”, or “Refresh” to load logs.") }
    var picker by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    var granting by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(AboutLogcat.holdsReadLogs(ctx)) }

    fun refresh(uid: Int = scopeUid, eo: Boolean = errorsOnly) {
        text = "Loading…"
        scope.launch { text = withContext(Dispatchers.IO) { AboutLogcat.read(ctx, uid, eo) } }
    }

    @Composable
    fun Btn(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
        com.diegonmarcos.superapp.uikit.KitButton(onClick = onClick, modifier = modifier.testTag("about:log:$label")) { Text(label, fontSize = 12.sp, maxLines = 1) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Btn("This App", Modifier.weight(1f)) { scopeUid = AboutLogcat.SCOPE_THIS_APP; scopePkg = ""; refresh() }
            Btn("All Apps", Modifier.weight(1f)) { scopeUid = AboutLogcat.SCOPE_ALL_APPS; scopePkg = ""; refresh() }
            Btn("Select a App", Modifier.weight(1f)) {
                scope.launch {
                    val apps = withContext(Dispatchers.IO) { AboutLogcat.installedApps(ctx) }
                    if (apps.isEmpty()) actions.toast("No apps visible") else picker = apps
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Btn("All Logs", Modifier.weight(1f)) { errorsOnly = false; refresh(eo = false) }
            Btn("Error Logs", Modifier.weight(1f)) { errorsOnly = true; refresh(eo = true) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Btn("Refresh", Modifier.weight(1f)) { refresh() }
            Btn("Copy All", Modifier.weight(1f)) { actions.copy("Copied", text) }
            // SAF, not a FileProvider: the user picks the destination, so this needs no provider declaration and no
            // storage permission.
            Btn("Export File", Modifier.weight(1f)) {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                onExport(text, AboutLogcat.exportName(scopeUid, scopePkg, stamp))
            }
            // Same buffer as "Copy All", posted instead of pasted: hands the dump straight to c3-infra-api so a broken
            // phone reports its own evidence. Secrets are stripped in LogUpload.redact before it leaves the device.
            Btn("Post c3-api", Modifier.weight(1f)) {
                val body = text
                actions.toast("Sending…")
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        com.diegonmarcos.superapp.core.LogUpload.post(BuildConfig.LOG_INGEST_URL, "cloud-superapp", body)
                    }
                    actions.toast(when (r) {
                        is com.diegonmarcos.superapp.core.LogUpload.Result.Ok -> "Sent → ${r.file}"
                        is com.diegonmarcos.superapp.core.LogUpload.Result.Failed -> "Send failed: ${r.reason}"
                    })
                }
            }
        }

        // One-time capability row. Shown only while the grant is missing — once READ_LOGS is held it has nothing left to do
        // and disappears, which is also how you can tell at a glance whether it worked.
        if (held) {
            Text("✓ READ_LOGS granted — All Apps and Select a App read every app, in this process, with Shizuku stopped.",
                color = p.ok, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        } else {
            com.diegonmarcos.superapp.uikit.KitButton(enabled = !granting, modifier = Modifier.fillMaxWidth().testTag("about:log:grant"), onClick = {
                granting = true
                scope.launch {
                    // Binds a shell channel — never on the main thread.
                    val r = withContext(Dispatchers.IO) {
                        com.diegonmarcos.superapp.adbdebug.ShizukuAdb.grant(ctx, "android.permission.READ_LOGS")
                    }
                    actions.toast(when {
                        r.held -> "READ_LOGS granted"
                        !r.ran -> r.output
                        else -> "pm grant ran but the permission is still not held: ${r.output}"
                    })
                    granting = false
                    held = AboutLogcat.holdsReadLogs(ctx)
                    if (r.held) refresh()
                }
            }) { Text(if (granting) "Granting…" else "Enable all-app logs (one-time grant)") }
            Text("Runs `pm grant` once through Shizuku or a paired adb. The grant persists, so nothing needs to stay running afterwards.",
                color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }

        // Bordered, independently-scrollable frame nested in the page; long lines scroll horizontally too.
        SelectionContainer {
            Text(text, color = p.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 10.sp, softWrap = false,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(340.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xFF0D0D12))
                    .border(1.dp, Color(0xFF4DA3FF), RoundedCornerShape(10.dp))
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp)
                    .testTag("about:log:pane"))
        }
    }

    picker?.let { apps ->
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text("Logs for which app") },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(apps) { (label, uid) ->
                        Text(label, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().selectable(selected = false, onClick = {
                                scopeUid = uid; scopePkg = label; picker = null; refresh(uid = uid)
                            }).padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { com.diegonmarcos.superapp.uikit.KitTextButton(onClick = { picker = null }) { Text("Cancel") } },
        )
    }
}
