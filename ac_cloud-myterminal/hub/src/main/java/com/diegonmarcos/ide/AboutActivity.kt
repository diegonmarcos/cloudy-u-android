package com.diegonmarcos.ide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.ide.update.UpdateProgress
import com.diegonmarcos.ide.update.Updater
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * Configs → About — the FULL Cloud-SuperApp About architecture (DevControl
 * "About" page), ported via its ea_cloud-comms sibling: same sections, same
 * helper framework (title / section / row / small / actionButton / fmtMillis /
 * sizeStr), same monospace-value style, long-press-to-copy on every row, and
 * "Copy All Infos" at the tail.
 *
 * Sections: About · Updater/GHCR · APK · Storage · Stack (languages LOC +
 * frameworks + build metrics — scanned at build time, never hardcoded) ·
 * Device · Memory · System load · Permissions · Updates (live progress).
 * Every fact comes from BuildConfig (baked from build.json/data) or runtime
 * PackageManager / ActivityManager / proc — no hardcoded facts.
 */
class AboutActivity : AppCompatActivity() {

    /** Mirrors everything written via title/section/row/small so "Copy All
     *  Infos" can dump the entire page as text. */
    private var infoBuf = StringBuilder()

    /** Live updater state for the Updates section's progress bar + status line. */
    private var updateState by mutableStateOf<UpdateProgress.State>(UpdateProgress.State.Idle)
    private var updateText by mutableStateOf("")

    // The page as data: the helpers below collect it (and mirror it into infoBuf) while onCreate
    // runs, and AboutPage draws it. One sealed list instead of a View tree.
    private sealed interface Item
    private class Title(val text: String) : Item
    private class SectionItem(val head: String, val items: List<Item>) : Item
    private class RowItem(val key: String, val value: String) : Item
    private class Small(val text: String) : Item
    private class ActionItem(val label: String, val onClick: () -> Unit) : Item
    private object UpdatePanel : Item
    private class Items { val list = mutableListOf<Item>(); fun addView(i: Item) { list += i } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.about_title)
        infoBuf = StringBuilder()

        val ctx = this
        val column = Items()

        column.addView(title(ctx, getString(R.string.about_heading, getString(R.string.app_name))))

        // ── About ──────────────────────────────────────────────────────
        section(ctx, column, "About") {
            row(ctx, it, "Name",         BuildConfig.APPLICATION_ID)
            row(ctx, it, "Version",      BuildConfig.VERSION_NAME)
            row(ctx, it, "Version code", BuildConfig.VERSION_CODE.toString())
            row(ctx, it, "Git sha",      BuildConfig.GIT_SHORT_SHA)
            row(ctx, it, "Built (UTC)",  BuildConfig.BUILD_TIMESTAMP)
            row(ctx, it, "Build type",   BuildConfig.BUILD_TYPE)
            row(ctx, it, "Debuggable",   BuildConfig.DEBUG.toString())
        }

        // ── Updater / GHCR ─────────────────────────────────────────────
        section(ctx, column, "Updater") {
            row(ctx, it, "Registry",  BuildConfig.GHCR_REGISTRY)
            row(ctx, it, "Namespace", BuildConfig.GHCR_NAMESPACE)
            row(ctx, it, "Image",     BuildConfig.GHCR_IMAGE)
            row(ctx, it, "Tag",       BuildConfig.AUTO_UPDATE_TAG)
            row(ctx, it, "Full URL",
                "${BuildConfig.GHCR_REGISTRY}/${BuildConfig.GHCR_NAMESPACE}/${BuildConfig.GHCR_IMAGE}:${BuildConfig.AUTO_UPDATE_TAG}")
            row(ctx, it, "Media type",     BuildConfig.GHCR_MEDIA_TYPE)
            row(ctx, it, "Check interval", "${BuildConfig.AUTO_UPDATE_INTERVAL_HOURS}h")
            row(ctx, it, "Enabled",        BuildConfig.AUTO_UPDATE_ENABLED.toString())
            row(ctx, it, "Install mode",   BuildConfig.AU_INSTALL_MODE)
            row(ctx, it, "Require unmetered", BuildConfig.AU_REQUIRE_UNMETERED.toString())
            row(ctx, it, "Require charging",  BuildConfig.AU_REQUIRE_CHARGING.toString())
        }

        // ── APK ────────────────────────────────────────────────────────
        section(ctx, column, "APK") {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(packageName, 0)
            val path = info.applicationInfo?.sourceDir ?: "—"
            val size = runCatching { java.io.File(path).length() }.getOrDefault(0L)
            row(ctx, it, "Path",       path)
            row(ctx, it, "Size",       sizeStr(size))
            row(ctx, it, "Installed",  fmtMillis(info.firstInstallTime))
            row(ctx, it, "Updated",    fmtMillis(info.lastUpdateTime))
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val src = runCatching { packageManager.getInstallSourceInfo(packageName) }.getOrNull()
                row(ctx, it, "Installed by", src?.installingPackageName ?: "—")
            } else {
                @Suppress("DEPRECATION")
                row(ctx, it, "Installed by",
                    runCatching { packageManager.getInstallerPackageName(packageName) }.getOrNull() ?: "—")
            }
        }

        // ── Storage ────────────────────────────────────────────────────
        section(ctx, column, "Storage") {
            @Suppress("DEPRECATION")
            val pkg = packageManager.getPackageInfo(packageName, 0)
            val appInfo    = pkg.applicationInfo
            val apkBytes   = runCatching { java.io.File(appInfo?.sourceDir ?: "").length() }.getOrDefault(0L)
            val dataDir    = appInfo?.dataDir?.let { d -> java.io.File(d) } ?: dataDir
            val cacheBytes = dirSize(cacheDir)
            val dataBytes  = (dirSize(dataDir) - cacheBytes).coerceAtLeast(0L)
            val totalBytes = apkBytes + dataBytes + cacheBytes

            row(ctx, it, "Files dir",  filesDir.absolutePath)
            row(ctx, it, "Cache dir",  cacheDir.absolutePath)
            row(ctx, it, "Data root",  dataDir.absolutePath)
            row(ctx, it, "External",   getExternalFilesDir(null)?.absolutePath ?: "—")
            it.addView(small(ctx, "Breakdown — same buckets Android system settings shows:"))
            row(ctx, it, "Aplicación", sizeStr(apkBytes))
            row(ctx, it, "Datos",      sizeStr(dataBytes))
            row(ctx, it, "Caché",      sizeStr(cacheBytes))
            row(ctx, it, "Total",      sizeStr(totalBytes))
        }

        // ── Stack ──────────────────────────────────────────────────────
        section(ctx, column, "Stack") {
            it.addView(small(ctx, "What's actually inside this APK — scanned by the build, never a hardcoded list."))
            val langs = runCatching {
                JSONObject(String(Base64.decode(BuildConfig.STACK_LANGUAGES_JSON_B64, Base64.DEFAULT)))
            }.getOrDefault(JSONObject())
            var totalFiles = 0; var totalLoc = 0
            val langList = mutableListOf<Triple<String, Int, Int>>()
            val keys = langs.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = langs.optJSONObject(k) ?: continue
                val files = o.optInt("files"); val loc = o.optInt("loc")
                totalFiles += files; totalLoc += loc
                langList += Triple(k, files, loc)
            }
            row(ctx, it, "Total LOC",   "%,d lines".format(totalLoc))
            row(ctx, it, "Total files", totalFiles.toString())
            for ((lang, files, loc) in langList.sortedByDescending { tr -> tr.third }) {
                val pct = if (totalLoc > 0) " (%d%%)".format(loc * 100 / totalLoc) else ""
                row(ctx, it, lang, "%,d lines · %d file(s)%s".format(loc, files, pct))
            }

            it.addView(small(ctx, "Frameworks (Maven coordinates scanned from every *.gradle):"))
            val fw = runCatching {
                JSONArray(String(Base64.decode(BuildConfig.STACK_FRAMEWORKS_JSON_B64, Base64.DEFAULT)))
            }.getOrDefault(JSONArray())
            row(ctx, it, "Dep count", fw.length().toString())
            for (i in 0 until fw.length()) {
                val dep = fw.getString(i)
                val parts = dep.split(":")
                val head = if (parts.size >= 2) "${parts[0]}:${parts[1]}" else dep
                val ver  = if (parts.size >= 3) parts[2] else "?"
                row(ctx, it, head, ver)
            }

            it.addView(small(ctx, "Build metrics (from GitHub Actions history):"))
            row(ctx, it, "Build avg time", fmtSecs(BuildConfig.STACK_BUILD_AVG_SECS) +
                if (BuildConfig.STACK_BUILD_SAMPLE > 0) " (n=${BuildConfig.STACK_BUILD_SAMPLE})" else "")
            row(ctx, it, "Build last",     fmtSecs(BuildConfig.STACK_BUILD_LAST_SECS))
            row(ctx, it, "Build SHA",      BuildConfig.GIT_SHORT_SHA)
        }

        // ── Device / stack ─────────────────────────────────────────────
        section(ctx, column, "Device / stack") {
            row(ctx, it, "Manufacturer", android.os.Build.MANUFACTURER)
            row(ctx, it, "Model",        android.os.Build.MODEL)
            row(ctx, it, "Brand",        android.os.Build.BRAND)
            row(ctx, it, "Device",       android.os.Build.DEVICE)
            row(ctx, it, "Hardware",     android.os.Build.HARDWARE)
            row(ctx, it, "Android",      "${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            row(ctx, it, "ABIs",         android.os.Build.SUPPORTED_ABIS.joinToString(", "))
            row(ctx, it, "Locale",       java.util.Locale.getDefault().toLanguageTag())
        }

        // ── Memory ─────────────────────────────────────────────────────
        section(ctx, column, "Memory") {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            row(ctx, it, "System avail",      sizeStr(mi.availMem))
            row(ctx, it, "System total",      sizeStr(mi.totalMem))
            row(ctx, it, "Threshold",         sizeStr(mi.threshold))
            row(ctx, it, "System low-memory", mi.lowMemory.toString())
            row(ctx, it, "Heap class limit",  am?.memoryClass?.let { c -> "$c MB" } ?: "—")
            row(ctx, it, "Heap class (large)", am?.largeMemoryClass?.let { c -> "$c MB" } ?: "—")
            val rt = Runtime.getRuntime()
            row(ctx, it, "JVM heap used",
                "${sizeStr(rt.totalMemory() - rt.freeMemory())} / ${sizeStr(rt.maxMemory())}")
        }

        // ── System load ────────────────────────────────────────────────
        section(ctx, column, "System load") {
            row(ctx, it, "CPU", "${Runtime.getRuntime().availableProcessors()} cores · ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "—"}")
            readLoadAvg()?.let { la -> row(ctx, it, "Load (1·5·15m)", la) }
            readCpuBusyIdle()?.let { bi -> row(ctx, it, "Busy / idle", bi) }
            row(ctx, it, "Process uptime", readUptime())
        }

        // ── Permissions ────────────────────────────────────────────────
        section(ctx, column, "Permissions") {
            it.addView(small(ctx, "Manifest permissions — ✓ Granted · ✗ Denied. Normal/signature perms auto-grant at install; runtime perms need a prompt."))
            val perms = listOf(
                "INTERNET"           to "android.permission.INTERNET",
                "Network state"      to "android.permission.ACCESS_NETWORK_STATE",
                "Install packages"   to "android.permission.REQUEST_INSTALL_PACKAGES",
                "Post notifications" to "android.permission.POST_NOTIFICATIONS",
            )
            for ((label, perm) in perms) {
                row(ctx, it, label, permissionState(perm))
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                it.addView(actionButton(ctx, "Fix notifications in Perms ›") {
                    com.diegonmarcos.superapp.perms.PermsLink.open(this, "about", listOf("notifications"))
                })
            } else {
                it.addView(small(ctx, "Pre-API 33 — notifications are granted by default."))
            }
            it.addView(actionButton(ctx, "Open System App Settings") { openAppSettings() })

            // ── Auto-update — silent self-update toggle (default ON). ON →
            //    installer commits with USER_ACTION_NOT_REQUIRED (no prompt);
            //    OFF → normal prompt. Silent only skips the prompt once "Install
            //    unknown apps" is granted — Android prompts transparently without it.
            it.addView(small(ctx, "Auto-update — silent self-update (default ON). Grant 'Install unknown apps' to skip the install prompt:"))
            row(ctx, it, "Auto-update (silent)",
                if (com.diegonmarcos.ide.update.AutoUpdatePrefs.silent(ctx)) "✓ ON" else "✗ OFF")
            row(ctx, it, "Install unknown apps",
                if (com.diegonmarcos.ide.update.AutoUpdatePrefs.canInstallSilently(ctx)) "✓ Granted" else "◯ Not granted")
            it.addView(actionButton(ctx,
                if (com.diegonmarcos.ide.update.AutoUpdatePrefs.silent(ctx)) "Auto-update: ON → tap to turn OFF"
                else "Auto-update: OFF → tap to turn ON") {
                com.diegonmarcos.ide.update.AutoUpdatePrefs.setSilent(ctx, !com.diegonmarcos.ide.update.AutoUpdatePrefs.silent(ctx))
                recreate()
            })
            if (!com.diegonmarcos.ide.update.AutoUpdatePrefs.canInstallSilently(ctx)) {
                it.addView(actionButton(ctx, "Grant install unknown apps") { openUnknownAppSourcesSettings() })
            }
        }

        // ── Updates ────────────────────────────────────────────────────
        section(ctx, column, "Updates") {
            it.addView(UpdatePanel)
            it.addView(actionButton(ctx, getString(R.string.about_check_updates)) {
                Updater.checkNow(ctx)
                updateText = getString(R.string.about_update_started)
            })
        }

        // ── Diagnostics (download / push logs to OpenObserve, the Loki-equiv) ──
        section(ctx, column, "Diagnostics") {
            it.addView(small(ctx, "Capture this app's logcat + device info. Download saves a JSON bundle to Downloads; Send posts it to the cloud log store (OpenObserve)."))
            it.addView(actionButton(ctx, "Download logs") {
                Thread {
                    val name = "cloud-diag-${BuildConfig.APPLICATION_ID}-${BuildConfig.GIT_SHORT_SHA}.json"
                    val saved = DiagnosticsPush.downloadBundle(ctx, name, DiagnosticsPush.captureRecord(ctx))
                    runOnUiThread { Toast.makeText(ctx, if (saved != null) "Saved $saved to Downloads" else "Download failed", Toast.LENGTH_LONG).show() }
                }.start()
            })
            it.addView(actionButton(ctx, "Send logs → cloud") {
                Thread {
                    val code = DiagnosticsPush.pushToCloud(DiagnosticsPush.captureRecord(ctx))
                    val msg = when { code in 200..299 -> "Sent to cloud ($code)"; code == -2 -> "Cloud sink not configured (build.json diagnostics.log_sink_url)"; else -> "Send failed ($code)" }
                    runOnUiThread { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
                }.start()
            })
        }

        // ── Copy All Infos ─────────────────────────────────────────────
        column.addView(actionButton(ctx, "Copy All Infos") {
            val snapshot = infoBuf.toString()
            copy(ctx, snapshot)
            Toast.makeText(ctx,
                "Copied ${snapshot.length} chars (${snapshot.count { c -> c == '\n' }} lines)",
                Toast.LENGTH_SHORT).show()
        })

        setContent { AboutPage(column.list) }
    }

    override fun onResume() {
        super.onResume()
        UpdateProgress.setListener { s -> runOnUiThread { renderProgress(s) } }
    }

    override fun onPause() {
        super.onPause()
        UpdateProgress.setListener(null)
    }

    private fun renderProgress(s: UpdateProgress.State) {
        updateState = s
        updateText = when (s) {
            is UpdateProgress.State.Idle -> ""
            is UpdateProgress.State.CheckingManifest -> "Checking GHCR manifest…"
            is UpdateProgress.State.Downloading -> "Downloading: ${s.percent}%"
            is UpdateProgress.State.Installing -> "Installing…"
            is UpdateProgress.State.UpToDate -> "Up to date (${s.tag})."
            is UpdateProgress.State.Done -> "Update complete."
            is UpdateProgress.State.Failed -> "Failed: ${s.message}"
        }
    }

    // ── helpers (ported from DevControlFragment via comms) ─────────────

    private fun section(ctx: Context, host: Items, head: String, body: (Items) -> Unit) {
        infoBuf.append("\n## ").append(head).append("\n")
        val grp = Items()
        host.addView(SectionItem(head, grp.list))
        body(grp)
    }

    private fun row(ctx: Context, host: Items, key: String, value: String) {
        infoBuf.append("  ").append(key).append(": ").append(value).append("\n")
        host.addView(RowItem(key, value))
    }

    private fun title(ctx: Context, text: String): Item {
        infoBuf.append("# ").append(text).append("\n")
        return Title(text)
    }

    private fun small(ctx: Context, text: String): Item {
        infoBuf.append("  ").append(text).append("\n")
        return Small(text)
    }

    private fun actionButton(ctx: Context, label: String, onClick: () -> Unit): Item = ActionItem(label, onClick)

    // ── the page ────────────────────────────────────────────────────────

    @Composable
    private fun AboutPage(items: List<Item>) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            for (item in items) Block(item)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Block(item: Item) {
        val p = LocalKitPalette.current
        val soft = colorResource(R.color.ide_accent_soft)
        when (item) {
            is Title -> Text(item.text, color = colorResource(R.color.ide_lavender), fontSize = 24.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            is SectionItem -> Column(Modifier.padding(bottom = 8.dp)) {
                Text(item.head, color = p.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                for (child in item.items) Block(child)
            }
            is RowItem -> Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = {
                    copy(this@AboutActivity, "${item.key}: ${item.value}")
                    Toast.makeText(this@AboutActivity, "Copied ${item.key}", Toast.LENGTH_SHORT).show()
                }).padding(vertical = 4.dp),
            ) {
                Text(item.key, color = p.textPrimary.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.width(120.dp))
                Text(item.value, color = soft, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f))
            }
            is Small -> Text(item.text, color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
            is ActionItem -> Text(
                item.label, color = p.textPrimary, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().background(p.accent)
                    .clickable { item.onClick() }.padding(horizontal = 12.dp, vertical = 10.dp),
            )
            is UpdatePanel -> Column {
                val st = updateState
                when {
                    st is UpdateProgress.State.Downloading ->
                        LinearProgressIndicator(progress = { st.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    st is UpdateProgress.State.CheckingManifest || st is UpdateProgress.State.Installing ->
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else -> {}
                }
                Text(updateText, color = p.textPrimary.copy(alpha = 0.8f), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
            }
        }
    }

    private fun copy(ctx: Context, v: String) {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clip?.setPrimaryClip(ClipData.newPlainText("about", v))
    }

    private fun permissionState(perm: String): String {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(this, perm) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) "✓ Granted" else "✗ Denied"
    }

    // #1352 every settings screen opens through libs:perms (the one place that builds a settings intent).
    private fun openAppSettings() {
        com.diegonmarcos.superapp.perms.PermsPages.openAppInfo(this, packageName)
    }

    /** Settings → "Install unknown apps" for this app (REQUEST_INSTALL_PACKAGES, what lets auto-update skip the prompt),
     *  with libs:perms' fallbacks for the catalogue entry install_unknown. */
    private fun openUnknownAppSourcesSettings() {
        val e = com.diegonmarcos.superapp.perms.Perms.catalogue(this).byId("install_unknown")
        if (e == null || !com.diegonmarcos.superapp.perms.PermsSettings.open(this, e)) openAppSettings()
    }

    private fun fmtMillis(ms: Long): String =
        DateFormat.getDateTimeInstance().format(Date(ms))

    private fun dirSize(dir: java.io.File): Long = runCatching {
        if (!dir.exists()) return@runCatching 0L
        dir.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() }
    }.getOrDefault(0L)

    private fun readLoadAvg(): String? = try {
        val parts = java.io.File("/proc/loadavg").readText().trim().split(' ')
        if (parts.size >= 3) "${parts[0]} · ${parts[1]} · ${parts[2]}" else null
    } catch (_: Throwable) { null }

    private fun readCpuBusyIdle(): String? {
        return try {
            val parts = java.io.File("/proc/uptime").readText().trim().split(' ').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            val uptime = parts[0].toDouble(); val idle = parts[1].toDouble()
            val cores = Runtime.getRuntime().availableProcessors()
            val busy = (uptime * cores - idle).coerceAtLeast(0.0)
            val frac = if (busy + idle > 0) busy / (busy + idle) else 0.0
            "busy ${fmtSecs((busy).toLong())} · idle ${fmtSecs(idle.toLong())} · %.1f %%".format(frac * 100.0)
        } catch (_: Throwable) { null }
    }

    private fun readUptime(): String {
        val ms = (android.os.SystemClock.elapsedRealtime() - AppProcessUptime.startedAtElapsed).coerceAtLeast(0L)
        return fmtSecs(ms / 1000)
    }

    private fun fmtSecs(s: Long): String = when {
        s < 0    -> "—"
        s < 60   -> "${s}s"
        s < 3600 -> "%dm %02ds".format(s / 60, s % 60)
        else     -> "%dh %02dm".format(s / 3600, (s % 3600) / 60)
    }

    private fun sizeStr(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GiB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> "%.2f MiB".format(bytes / 1_048_576.0)
        bytes >= 1024          -> "%.1f KiB".format(bytes / 1024.0)
        else                   -> "$bytes B"
    }

}
