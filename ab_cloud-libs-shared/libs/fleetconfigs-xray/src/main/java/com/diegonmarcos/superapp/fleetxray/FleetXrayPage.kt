package com.diegonmarcos.superapp.fleetxray

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigs
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.themebg.ThemeColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Test tags of the X-Ray tab (UiAutomator resource ids). */
public object FleetXrayTags {
    public const val PAGE: String = "fleetcfg_page_xray"
    public const val COPY_MD: String = "fleetxray_copy_md"
    public const val SAVE_HTML: String = "fleetxray_save_html"
    public const val SHARE: String = "fleetxray_share"
    public const val COCKPIT: String = "fleetxray_cockpit"
    public fun section(id: String): String = "fleetxray_section_$id"
}

/**
 * #1682 The adb-shell cockpit of the ONE ADB owner, opened by its declared action (the owner's exported ShellArmActivity
 * resolves it, whichever app owns ADB), so this lib names no in-process ADB class and needs no ADB lib at all.
 */
private const val ADB_SHELL_ACTION = "com.diegonmarcos.superapp.action.ARM_SHELL"

/**
 * Configs ▸ X-Ray: the app profiler — the thirteen trackers of [FleetXrayProbes] read live for THIS app, plus the Device
 * Profile Scan of the phone around it — with the same two-way export as the Logcat tab (Copy Markdown, Save HTML, Share) and a
 * deep link to the adb-shell cockpit for the apps that host it.
 */
@Composable
public fun FleetXrayPage(dark: Boolean) {
    val c = ThemeBackground.colors(dark)
    val ctx = LocalContext.current
    val profile = remember { buildProfile(ctx) }
    Column(Modifier.fillMaxSize().testTag(FleetXrayTags.PAGE).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            XrayButton("Copy", c, FleetXrayTags.COPY_MD) { copyMarkdown(ctx, profile) }
            XrayButton("Save", c, FleetXrayTags.SAVE_HTML) { saveHtml(ctx, profile) }
            XrayButton("Share", c, FleetXrayTags.SHARE) { shareMarkdown(ctx, profile) }
        }
        XrayButton("Open ADB Shell cockpit", c, FleetXrayTags.COCKPIT) { openCockpit(ctx) }
        Spacer(Modifier.height(10.dp))
        profile.sections.forEach { s ->
            XrayCard(s.title, c, Modifier.testTag(FleetXrayTags.section(s.id))) {
                s.rows.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(r.key, Modifier.padding(end = 10.dp), color = c.inkDim, fontSize = 12.sp)
                        Text(r.value, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        com.diegonmarcos.superapp.fleetxray.perf.XrayPerfSection(c)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun XrayCard(title: String, c: ThemeColors, modifier: Modifier, body: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).background(c.tabStrip, shape).border(1.dp, c.islandBorder, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(title, color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        body()
    }
}

@Composable
private fun XrayButton(label: String, c: ThemeColors, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        label,
        Modifier.clip(shape).background(c.tabSelected, shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp).testTag(tag),
        color = c.onTabSelected, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Default,
    )
}

// ── live probes ────────────────────────────────────────────────────────────────────────────────────────────────

private fun row(k: String, v: String) = FleetXrayExport.Row(k, v)

private fun buildProfile(ctx: Context): FleetXrayExport.Profile {
    val decl = FleetConfigs.current
    val pm = ctx.packageManager
    val pkg = ctx.packageName
    val pi: PackageInfo? = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES) }.getOrNull()
    val ai = ctx.applicationInfo
    val rt = Runtime.getRuntime()
    val sections = ArrayList<FleetXrayExport.Section>(FleetXrayProbes.TRACKERS.size + 1)
    fun add(id: String, title: String, rows: List<FleetXrayExport.Row>) = sections.add(FleetXrayExport.Section(id, title, rows))

    add("identity", "Identity", listOf(
        row("package", pkg),
        row("uid", ai.uid.toString()),
        row("process", runCatching { android.app.Application.getProcessName() }.getOrDefault(pkg)),
        row("version", decl.version.ifBlank { pi?.versionName ?: "?" }),
    ))
    add("build", "Build", listOf(
        row("sha", decl.sha.ifBlank { "local" }),
        row("date", decl.buildDate.ifBlank { "?" }),
        row("target sdk", ai.targetSdkVersion.toString()),
        row("android", Build.VERSION.SDK_INT.toString() + " (" + Build.VERSION.RELEASE + ")"),
    ))
    val mem = runCatching { ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) } }.getOrNull()
    add("memory", "Memory", listOf(
        row("java heap used", FleetXrayProbes.humanBytes(rt.totalMemory() - rt.freeMemory())),
        row("java heap max", FleetXrayProbes.humanBytes(rt.maxMemory())),
        row("native heap", FleetXrayProbes.humanBytes(Debug.getNativeHeapAllocatedSize())),
        row("system avail", mem?.let { FleetXrayProbes.humanBytes(it.availMem) } ?: "?"),
        row("low memory", mem?.lowMemory?.toString() ?: "?"),
    ))
    val dataDir = File(ai.dataDir)
    val stat = runCatching { android.os.StatFs(dataDir.path) }.getOrNull()
    add("storage", "Storage", listOf(
        row("app data", FleetXrayProbes.humanBytes(dirSize(dataDir))),
        row("cache", FleetXrayProbes.humanBytes(dirSize(ctx.cacheDir))),
        row("fs free", stat?.let { FleetXrayProbes.humanBytes(it.availableBytes) } ?: "?"),
    ))
    add("threads", "CPU & threads", listOf(
        row("active threads", Thread.activeCount().toString()),
        row("cores", rt.availableProcessors().toString()),
    ))
    val powered = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(pkg) }.getOrDefault(false)
    val bm = runCatching { ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager }.getOrNull()
    add("battery", "Battery", listOf(
        row("level", bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.toString()?.plus("%") ?: "?"),
        row("unrestricted", powered.toString()),
    ))
    add("network", "Network", listOf(row("transport", networkKind(ctx))))
    val granted = pi?.requestedPermissions?.indices?.count { ((pi.requestedPermissionsFlags?.getOrNull(it) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 } ?: 0
    add("permissions", "Permissions", listOf(
        row("declared", pi?.requestedPermissions?.size?.toString() ?: "0"),
        row("granted", granted.toString()),
    ))
    add("uptime", "Process & uptime", listOf(
        row("process uptime", humanMillis(SystemClock.elapsedRealtime() - Process.startElapsedRealtimeOrZero())),
        row("device uptime", humanMillis(SystemClock.elapsedRealtime())),
    ))
    val dm = ctx.resources.displayMetrics
    add("display", "Display", listOf(
        row("resolution", "${dm.widthPixels}×${dm.heightPixels}"),
        row("density", "${dm.densityDpi} dpi (×${dm.density})"),
    ))
    add("locale", "Locale & config", listOf(
        row("locale", ctx.resources.configuration.locales[0].toLanguageTag()),
        row("night mode", (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK).toString()),
    ))
    add("runtime", "Runtime flags", listOf(
        row("debuggable", ((ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0).toString()),
        row("allow backup", ((ai.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0).toString()),
    ))
    add("components", "Components", listOf(
        row("activities", pi?.activities?.size?.toString() ?: "?"),
        row("services", pi?.services?.size?.toString() ?: "?"),
    ))
    // Device Profile Scan: the phone around the app.
    add(FleetXrayProbes.DEVICE_PROFILE_ID, FleetXrayProbes.DEVICE_PROFILE_TITLE, listOf(
        row("model", Build.MANUFACTURER + " " + Build.MODEL),
        row("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL else Build.HARDWARE),
        row("abis", Build.SUPPORTED_ABIS.joinToString(", ")),
        row("total ram", mem?.let { FleetXrayProbes.humanBytes(it.totalMem) } ?: "?"),
        row("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"),
    ))

    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
    return FleetXrayExport.Profile(decl.name.ifBlank { pkg }, pkg, decl.sha, stamp, sections)
}

private object Process {
    fun startElapsedRealtimeOrZero(): Long =
        runCatching { if (Build.VERSION.SDK_INT >= 24) android.os.Process.getStartElapsedRealtime() else 0L }.getOrDefault(0L)
}

private fun networkKind(ctx: Context): String = runCatching {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
    when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }
}.getOrDefault("?")

private fun humanMillis(ms: Long): String {
    if (ms <= 0) return "?"
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m ${sec}s" else "${sec}s"
}

private fun dirSize(f: File?): Long {
    if (f == null || !f.exists()) return 0L
    var total = 0L
    var seen = 0
    f.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach {
        if (it.isFile) total += it.length()
        if (++seen > 200_000) return total
    }
    return total
}

// ── actions ────────────────────────────────────────────────────────────────────────────────────────────────────

private fun copyMarkdown(ctx: Context, p: FleetXrayExport.Profile) {
    runCatching {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("xray", FleetXrayExport.markdown(p)))
        Toast.makeText(ctx, "X-Ray copied as Markdown", Toast.LENGTH_SHORT).show()
    }
}

private fun shareMarkdown(ctx: Context, p: FleetXrayExport.Profile) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, FleetXrayExport.markdown(p)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(Intent.createChooser(send, "Share X-Ray").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun saveHtml(ctx: Context, p: FleetXrayExport.Profile) {
    runCatching {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val out = File(dir, "xray-" + System.currentTimeMillis() + ".html")
        out.writeText(FleetXrayExport.html(p))
        Toast.makeText(ctx, "Saved " + out.absolutePath, Toast.LENGTH_LONG).show()
    }
}

private fun openCockpit(ctx: Context) {
    val i = Intent(ADB_SHELL_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { ctx.startActivity(i) }.isFailure) {
        Toast.makeText(ctx, "ADB Shell cockpit is not reachable: install or update the ADB owner app", Toast.LENGTH_SHORT).show()
    }
}
