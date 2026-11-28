package com.diegonmarcos.superapp.xray

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import com.diegonmarcos.superapp.BuildConfig
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

/**
 * #1985 Reads every tracker T01..T12 with what the app itself can see (no adb): ActivityManager, Debug, BatteryManager, PowerManager,
 * StatFs/StorageStatsManager, ConnectivityManager, ApplicationExitInfo, the app's own frame tally, /proc/self, PackageManager, Settings,
 * UsageStats when granted. Each field is a direct value, a labelled fallback for the adb-only dump, or adb-only with a reason
 * ([XrayNoAdb]). Blocking (a 1.5 s battery sample, a few network round trips): call from a background thread.
 */
@Suppress("DEPRECATION")
@SuppressLint("NewApi", "MissingPermission", "HardwareIds", "PrivateApi", "DiscouragedPrivateApi")
object XrayAppMeasure {

    private class Src(val direct: Boolean, val src: String, val limits: String, val read: () -> Pair<String, Double?>?)
    private fun d(src: String, limits: String = "", read: () -> Pair<String, Double?>?) = Src(true, src, limits, read)
    private fun fb(src: String, limits: String, read: () -> Pair<String, Double?>?) = Src(false, src, limits, read)

    /** First source that yields a value wins; none = adb-only with [reason]. */
    private fun fld(key: String, adb: String, reason: String, vararg s: Src): AField {
        for (x in s) {
            val r = runCatching { x.read() }.getOrNull() ?: continue
            return AField(key, r.first, if (x.direct) AField.DIRECT else AField.FALLBACK, x.src, x.limits, adb, r.second)
        }
        return AField(key, reason, AField.ADB_ONLY, adb = adb)
    }

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun mb(b: Long) = f1(b / 1048576.0) + "MB"
    private fun txt(p: String): String? = runCatching { File(p).readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    private fun num(v: Double) = v.toString() to v as Double?
    private fun fleetPkgs(ctx: Context): List<String> = runCatching {
        ctx.packageManager.getInstalledPackages(0).map { it.packageName }.filter { it.startsWith("com.diegonmarcos.") }.sorted()
    }.getOrDefault(emptyList())

    private val DAY30 = 30L * 24 * 3600 * 1000

    fun collect(ctx: Context): List<XSection> {
        val c = ctx.applicationContext
        val fleet = fleetPkgs(c)
        val exits: Map<String, List<ApplicationExitInfo>?> = (fleet + c.packageName).distinct().associateWith { p ->
            if (Build.VERSION.SDK_INT < 30) null else runCatching {
                (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getHistoricalProcessExitReasons(p, 0, 0)
                    .filter { it.timestamp > System.currentTimeMillis() - DAY30 }
            }.getOrNull()
        }
        val out = ArrayList<XSection>()
        val b = listOf<(Context, List<String>, Map<String, List<ApplicationExitInfo>?>) -> Pair<List<AField>, List<AFinding>>>(
            { x, _, _ -> t01(x) to emptyList() }, { x, _, _ -> t02(x) to emptyList() }, { x, f, _ -> t03(x, f) to emptyList() },
            { x, _, _ -> t04(x) }, { x, _, _ -> t05(x) to emptyList() }, { x, _, _ -> t06(x) to emptyList() },
            { x, _, _ -> t07(x) to emptyList() }, { x, _, e -> t08(x, e) }, { x, f, e -> t09(x, f, e) },
            { x, _, _ -> t10(x) }, { x, _, e -> t11(x, e) to emptyList() }, { x, f, _ -> t12(x, f) },
        )
        for (i in b.indices) {
            val n = i + 1
            val (fields, findings) = runCatching { b[i](c, fleet, exits) }.getOrElse {
                listOf(AField("collector", "the app-side reader threw ${it.javaClass.simpleName}", AField.ADB_ONLY)) to emptyList()
            }
            out += XrayNoAdb.section(n, XrayProbes.trackers[i].group, fields, findings)
        }
        return out
    }

    // ── T01 Performance ──────────────────────────────────────────────────
    private fun t01(c: Context): List<AField> {
        val cores = Runtime.getRuntime().availableProcessors()
        val upMs = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()).coerceAtLeast(1)
        val cpuMs = Process.getElapsedCpuTime()
        return listOf(
            fld("own_cpu_pct", "top -b -n 1", "no CPU clock readable", fb("Process.getElapsedCpuTime / process uptime", "own process, average since start, not the device busy %") { num(cpuMs * 100.0 / upMs) }),
            fld("own_cpu_time", "dumpsys cpuinfo", "no CPU clock readable", d("Process.getElapsedCpuTime") { "${cpuMs}ms of ${upMs}ms uptime" to null }),
            fld("cores", "ro.* / cpu topology", "no core count", d("/sys/devices/system/cpu/online") { txt("/sys/devices/system/cpu/online")?.trim()?.let { "online $it of $cores" to null } },
                d("Runtime.availableProcessors") { "$cores" to null }),
            fld("cpu_freq", "/sys cpufreq via shell", "cpufreq files are not readable by an app on this device (SELinux)",
                fb("/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq", "current MHz per core, only where the kernel exposes it to apps") {
                    (0 until cores).mapNotNull { i -> txt("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.trim()?.toLongOrNull()?.let { "cpu$i=${it / 1000}" } }.takeIf { it.isNotEmpty() }?.let { it.joinToString(" ") + " MHz" to null } }),
            fld("load_per_core", "uptime / dumpsys cpuinfo", "/proc/loadavg is denied to apps on this Android (SELinux)",
                fb("/proc/loadavg", "1-minute load of the whole device where readable") { txt("/proc/loadavg")?.trim()?.split(' ')?.firstOrNull()?.toDoubleOrNull()?.let { f1(it / cores) to it / cores } }),
            fld("running_processes", "top process list", "no process list",
                fb("ActivityManager.getRunningAppProcesses", "own uid's processes only since Android 5.1") {
                    (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses?.let { l -> "${l.size}: " + l.joinToString(",") { it.processName.removePrefix("com.diegonmarcos.") } to null } }),
            fld("top_own_threads", "top -H", "no thread stats",
                fb("/proc/self/task/*/stat utime+stime", "own threads only, ticks since start") {
                    File("/proc/self/task").listFiles()?.mapNotNull { t ->
                        txt("${t.path}/stat")?.let { s -> val name = s.substringAfter('(').substringBeforeLast(')'); val r = s.substringAfterLast(')').trim().split(' ')
                            (r.getOrNull(11)?.toLongOrNull() ?: 0L).plus(r.getOrNull(12)?.toLongOrNull() ?: 0L).let { name to it } }
                    }?.sortedByDescending { it.second }?.take(4)?.joinToString(" ") { "${it.first}=${it.second}" }?.let { it to null } }),
        )
    }

    // ── T02 Render & GPU ─────────────────────────────────────────────────
    private fun gl(): String? {
        val dsp = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        if (!EGL14.eglInitialize(dsp, v, 0, v, 1)) return null
        val cfg = arrayOfNulls<EGLConfig>(1); val cnt = IntArray(1)
        EGL14.eglChooseConfig(dsp, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, cfg, 0, 1, cnt, 0)
        val e = cfg[0] ?: return null
        val cx = EGL14.eglCreateContext(dsp, e, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val sf = EGL14.eglCreatePbufferSurface(dsp, e, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        try {
            if (!EGL14.eglMakeCurrent(dsp, sf, sf, cx)) return null
            return GLES20.glGetString(GLES20.GL_RENDERER)
        } finally {
            EGL14.eglMakeCurrent(dsp, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dsp, sf); EGL14.eglDestroyContext(dsp, cx)
        }
    }

    private fun t02(c: Context): List<AField> {
        val snap = XrayFrameTally.snapshot()
        val none = "no frame was drawn since the process started"
        return listOf(
            fld("frames", "dumpsys gfxinfo framestats", none, fb("FrameMetrics TOTAL_DURATION on the app's windows (XrayFrameTally)", "own windows only, since process start") { snap?.let { "${it.first} janky ${it.second}" to null } }),
            fld("jank_pct", "dumpsys gfxinfo (Janky frames)", none, fb("FrameMetrics, frames over 16.7 ms", "own windows, 60 Hz budget") { snap?.let { f1(it.second * 100.0 / it.first) + "%" to it.second * 100.0 / it.first } }),
            fld("frame_p90_ms", "dumpsys gfxinfo (90th percentile)", none, fb("FrameMetrics ring of the last 600 frames", "own windows") { snap?.let { f1(it.third) to it.third } }),
            fld("refresh_rate", "dumpsys SurfaceFlinger", "no display", d("Display.getRefreshRate") { (c.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(0)?.let { "${f1(it.refreshRate.toDouble())} Hz" to null } }),
            fld("gpu_renderer", "ro.hardware.egl / dumpsys SurfaceFlinger", "no EGL context could be created", d("GLES20.glGetString(GL_RENDERER) on a 1x1 pbuffer") { gl()?.let { it to null } },
                fb("ActivityManager.deviceConfigurationInfo", "GLES version only, no renderer name") { (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).deviceConfigurationInfo.glEsVersion.let { "gles $it" to null } }),
            fld("sf_missed_frames", "dumpsys SurfaceFlinger", "SurfaceFlinger counters are system data with no app API; the app's own frames are above"),
        )
    }

    // ── T03 Storage ──────────────────────────────────────────────────────
    private fun t03(c: Context, fleet: List<String>): List<AField> {
        val st = StatFs(Environment.getDataDirectory().path)
        val tot = st.totalBytes; val free = st.availableBytes
        val usedPct = (tot - free) * 100.0 / tot.coerceAtLeast(1)
        val ssm = c.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val uid = Process.myUserHandle()
        val sizes = (listOf(c.packageName) + fleet).distinct().mapNotNull { p ->
            runCatching { ssm.queryStatsForPackage(android.os.storage.StorageManager.UUID_DEFAULT, p, uid).let { p to (it.appBytes + it.dataBytes + it.cacheBytes) } }.getOrNull()
        }
        return listOf(
            fld("data_used_pct", "df -h /data", "no StatFs", fb("StatFs(/data)", "totals as the app sees them") { f1(usedPct) + "%" to usedPct }),
            fld("data_free", "dumpsys diskstats", "no StatFs", fb("StatFs(/data)", "free ${mb(free)} of ${mb(tot)}") { "${mb(free)} of ${mb(tot)}" to null }),
            fld("shared_storage", "df -h", "no StatFs", fb("StatFs(external storage dir)", "emulated volume only") { StatFs(Environment.getExternalStorageDirectory().path).let { "free ${mb(it.availableBytes)} of ${mb(it.totalBytes)}" to null } }),
            fld("app_sizes", "dumpsys diskstats (App Sizes)", "StorageStatsManager answered for no package",
                fb("StorageStatsManager.queryStatsForPackage", "own package always; other fleet apps only with Usage access (${sizes.size} of ${fleet.size + 1} readable)") {
                    sizes.takeIf { it.isNotEmpty() }?.sortedByDescending { it.second }?.take(8)?.joinToString(" ") { "${it.first.removePrefix("com.diegonmarcos.")}=${mb(it.second)}" }?.let { it to null } }),
            fld("own_dirs", "dumpsys diskstats", "no dirs", d("Context.filesDir/cacheDir size") { "files ${mb(dirSize(c.filesDir))} cache ${mb(dirSize(c.cacheDir))}" to null }),
        )
    }

    private fun dirSize(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L

    // ── T04 Battery & Power ──────────────────────────────────────────────
    private fun t04(c: Context): Pair<List<AField>, List<AFinding>> {
        val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        fun cur(): Long = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val cc0 = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER); val i0 = cur()
        Thread.sleep(1500)
        val cc1 = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER); val i1 = cur()
        val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1; val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val health = bi?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
        val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
        val discharging = status == BatteryManager.BATTERY_STATUS_DISCHARGING || status == BatteryManager.BATTERY_STATUS_NOT_CHARGING
        val avg = (Math.abs(i0) + Math.abs(i1)) / 2.0
        val ma = if (avg > 20000) avg / 1000 else avg
        val usage = runCatching {
            val u = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            u.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24 * 3600_000L, now)?.filter { it.totalTimeInForeground > 0 }?.sortedByDescending { it.totalTimeInForeground }?.take(5)
                ?.joinToString(" ") { "${it.packageName.removePrefix("com.diegonmarcos.")}=${it.totalTimeInForeground / 60000}min" }?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        val f = listOf(
            fld("battery_pct", "dumpsys battery", "no battery intent", d("ACTION_BATTERY_CHANGED level/scale") { if (lvl < 0) null else (lvl * 100.0 / scale).let { f1(it) + "%" to it } }),
            fld("battery_temp_c", "dumpsys battery (temperature)", "no battery intent", d("EXTRA_TEMPERATURE / 10") { bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it >= 0 }?.let { f1(it / 10.0) + "C" to it / 10.0 } }),
            fld("battery_state", "dumpsys battery", "no battery intent", d("EXTRA_STATUS / EXTRA_PLUGGED / EXTRA_VOLTAGE") { "status $status plugged ${bi?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)} ${bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)}mV" to null }),
            fld("battery_health_bad", "dumpsys battery (health)", "no health", d("EXTRA_HEALTH (2 = good)") { if (health == 0) null else "health $health" to (if (health == BatteryManager.BATTERY_HEALTH_GOOD || health == BatteryManager.BATTERY_HEALTH_UNKNOWN) 0.0 else 1.0) }),
            fld("charge_counter", "dumpsys batterystats", "0 reported", d("BATTERY_PROPERTY_CHARGE_COUNTER") { cc1.takeIf { it > 0 }?.let { "${it / 1000}mAh (delta ${(cc1 - cc0)}uAh in 1.5s)" to null } }),
            fld("drain_ma", "dumpsys batterystats (drain)", "current_now not reported or the device is charging",
                fb("BATTERY_PROPERTY_CURRENT_NOW, two samples 1.5 s apart", "whole-device instantaneous draw while discharging, vendor units normalised") { if (discharging && avg > 0) f1(ma) + "mA" to ma else null }),
            fld("power_save", "dumpsys power", "none", d("PowerManager.isPowerSaveMode / isDeviceIdleMode / isIgnoringBatteryOptimizations") { "save ${pm.isPowerSaveMode} idle ${pm.isDeviceIdleMode} ignoring-opt ${pm.isIgnoringBatteryOptimizations(c.packageName)}" to null }),
            fld("own_jobs", "dumpsys jobscheduler", "none", fb("JobScheduler.getAllPendingJobs", "own pending jobs only") { (c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).allPendingJobs.size.let { "$it pending" to null } }),
            fld("next_alarm", "dumpsys alarm", "none", fb("AlarmManager.getNextAlarmClock", "next user alarm clock only, not the alarm list") {
                (c.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).nextAlarmClock?.let { "set" to null } ?: ("none set" to null) }),
            fld("usage_top", "dumpsys usagestats", "Usage access is not granted to the app (Settings > Special access), so UsageStatsManager returns nothing",
                fb("UsageStatsManager.queryUsageStats (24 h)", "foreground time per app, only with Usage access") { usage?.let { it to null } }),
            fld("wakelocks_per_uid", "dumpsys batterystats --charged", "wakelock and per-uid mAh tables are BATTERY_STATS (system permission) data; the app has no signal for other uids"),
        )
        return f to emptyList()
    }

    // ── T05 Thermal ──────────────────────────────────────────────────────
    private fun t05(c: Context): List<AField> {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val bt = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it >= 0 }?.div(10.0)
        return listOf(
            fld("thermal_status", "dumpsys thermalservice (Thermal Status)", "needs Android 10", d("PowerManager.getCurrentThermalStatus (0 none .. 6 shutdown)") { pm.currentThermalStatus.let { "$it" to it.toDouble() } }),
            fld("thermal_headroom", "dumpsys thermalservice", "needs Android 11 or the HAL gives NaN", d("PowerManager.getThermalHeadroom(10 s)", "1.0 = throttling threshold") { pm.getThermalHeadroom(10).takeIf { !it.isNaN() }?.let { f1(it.toDouble()) to it.toDouble() } }),
            fld("sensors", "dumpsys thermalservice (every sensor)", "no sensor readable",
                fb("battery temperature, /sys/class/thermal/thermal_zone*/temp", "battery plus the zones the kernel exposes to apps") {
                    val z = File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }?.mapNotNull { zn ->
                        txt("${zn.path}/temp")?.trim()?.toDoubleOrNull()?.let { t -> (txt("${zn.path}/type")?.trim() ?: zn.name) + "=" + f1(if (t > 1000) t / 1000 else t) }
                    }?.take(8).orEmpty()
                    (listOfNotNull(bt?.let { "battery=${f1(it)}" }) + z).takeIf { it.isNotEmpty() }?.let { it.joinToString(" ") + " C" to null } }),
            fld("cooling_devices", "dumpsys thermalservice (CoolingDevice)", "cooling device state is HAL data with no app API (headroom above is the app-side measure)"),
        )
    }

    // ── T06 Memory ───────────────────────────────────────────────────────
    private fun t06(c: Context): List<AField> {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val availPct = mi.availMem * 100.0 / mi.totalMem.coerceAtLeast(1)
        val pssKb = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
        val rt = Runtime.getRuntime(); val heapPct = (rt.totalMemory() - rt.freeMemory()) * 100.0 / rt.maxMemory()
        val mem = txt("/proc/meminfo").orEmpty()
        fun kb(n: String): Long? = Regex("$n:\\s+(\\d+) kB").find(mem)?.groupValues?.get(1)?.toLongOrNull()
        val swapT = kb("SwapTotal"); val swapF = kb("SwapFree")
        val swap: Pair<Long, Long>? = if (swapT != null && swapF != null) swapT to (swapT - swapF) else null
        val others = am.runningAppProcesses?.filter { it.pid != Process.myPid() }?.take(6)?.mapNotNull { p ->
            runCatching { am.getProcessMemoryInfo(intArrayOf(p.pid)).firstOrNull()?.totalPss?.let { p.processName.removePrefix("com.diegonmarcos.") to it } }.getOrNull() }.orEmpty()
        return listOf(
            fld("mem_avail_pct", "dumpsys meminfo (Free RAM)", "no MemoryInfo", d("ActivityManager.MemoryInfo availMem/totalMem") { "${f1(availPct)}% (${mb(mi.availMem)} of ${mb(mi.totalMem)})" to availPct }),
            fld("low_memory", "dumpsys meminfo", "none", d("MemoryInfo.lowMemory, threshold ${mb(mi.threshold)}") { (if (mi.lowMemory) "true" else "false") to (if (mi.lowMemory) 1.0 else 0.0) }),
            fld("device_class", "ro.config.low_ram", "none", d("isLowRamDevice / memoryClass / largeMemoryClass") { "lowRam ${am.isLowRamDevice} heap ${am.memoryClass}MB large ${am.largeMemoryClass}MB" to null }),
            fld("own_pss_mb", "dumpsys meminfo (PSS)", "no PSS", d("Debug.MemoryInfo via getProcessMemoryInfo") { pssKb?.let { (it.totalPss / 1024.0).let { v -> "${f1(v)}MB java ${it.getMemoryStat("summary.java-heap")}KB native ${it.getMemoryStat("summary.native-heap")}KB graphics ${it.getMemoryStat("summary.graphics")}KB" to v } } }),
            fld("heap_used_pct", "dumpsys meminfo", "none", d("Runtime total-free / max") { f1(heapPct) + "%" to heapPct }),
            fld("native_heap", "dumpsys meminfo", "none", d("Debug.getNativeHeapAllocatedSize") { mb(Debug.getNativeHeapAllocatedSize()) + " of " + mb(Debug.getNativeHeapSize()) to null }),
            fld("swap", "dumpsys meminfo (ZRAM)", "/proc/meminfo is not readable by apps here", fb("/proc/meminfo SwapTotal/SwapFree", "device swap where readable") { swap?.let { "${mb(it.second * 1024)} of ${mb(it.first * 1024)}" to null } }),
            fld("pss_other_processes", "dumpsys meminfo (PSS per process)", "the platform only returns our own processes",
                fb("getProcessMemoryInfo for running app processes", "own uid's other processes only") { others.takeIf { it.isNotEmpty() }?.joinToString(" ") { "${it.first}=${it.second / 1024}MB" }?.let { it to null } }),
            fld("procstats_3h", "dumpsys procstats --hours 3", "procstats history is system_server data with no app API; current PSS is above"),
        )
    }

    // ── T07 Notification & IPC ───────────────────────────────────────────
    private fun t07(c: Context): List<AField> {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val listeners = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")?.split(':')?.filter { it.isNotBlank() }.orEmpty()
        return listOf(
            fld("own_active_notifications", "dumpsys notification (active records)", "none", fb("NotificationManager.getActiveNotifications", "own notifications only") { nm.activeNotifications.size.let { "$it" to it.toDouble() } }),
            fld("notifications_enabled", "dumpsys notification", "none", d("NotificationManager.areNotificationsEnabled / currentInterruptionFilter / channels") { "enabled ${nm.areNotificationsEnabled()} dnd-filter ${nm.currentInterruptionFilter} channels ${nm.notificationChannels.size}" to null }),
            fld("listeners", "settings get secure enabled_notification_listeners", "none", d("Settings.Secure enabled_notification_listeners") { "${listeners.size} bound" to null }),
            fld("binder_sent_tx", "dumpsys binder_calls_stats", "none", fb("Debug.getBinderSentTransactions", "own process total since start") { Debug.getBinderSentTransactions().let { "$it (received ${Debug.getBinderReceivedTransactions()}, proxies ${Debug.getBinderProxyObjectCount()})" to it.toDouble() } }),
            fld("posts_per_app", "dumpsys notification (numPostedByApp)", "per-app post counts of other apps are system_server data with no app API"),
        )
    }

    // ── T08 Responsiveness ───────────────────────────────────────────────
    private fun t08(c: Context, exits: Map<String, List<ApplicationExitInfo>?>): Pair<List<AField>, List<AFinding>> {
        val anr = exits.mapValues { e -> e.value?.count { it.reason == ApplicationExitInfo.REASON_ANR } }.filterValues { it != null }
        val total = anr.values.sumOf { it ?: 0 }
        val latch = CountDownLatch(1); val t0 = SystemClock.elapsedRealtime()
        Handler(Looper.getMainLooper()).post { latch.countDown() }
        val lat = if (latch.await(2, TimeUnit.SECONDS)) (SystemClock.elapsedRealtime() - t0).toDouble() else 2000.0
        val f = listOf(
            fld("anr_30d", "logcat events am_anr / dumpsys dropbox", "ApplicationExitInfo needs Android 11",
                fb("ApplicationExitInfo REASON_ANR, last 30 days", "readable for ${anr.size} of ${exits.size} fleet packages (other uids refuse), ${total} ANR") { if (anr.isEmpty()) null else "$total" to total.toDouble() }),
            fld("main_latency_ms", "dumpsys (ANR trace)", "none", d("Handler(main).post round trip") { f1(lat) + "ms" to lat }),
            fld("anr_traces", "ls /data/anr", "ANR trace files are root/system readable only"),
        )
        return f to anr.filter { (it.value ?: 0) > 0 }.map { AFinding(it.key, "anr", "${it.value} ANR in 30 d (ApplicationExitInfo)", 40.0 + Math.min(20, it.value ?: 0)) }
    }

    // ── T09 Crash ────────────────────────────────────────────────────────
    private fun t09(c: Context, fleet: List<String>, exits: Map<String, List<ApplicationExitInfo>?>): Pair<List<AField>, List<AFinding>> {
        val crashReasons = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
        val cr = exits.mapValues { e -> e.value?.filter { it.reason in crashReasons } }.filterValues { it != null }
        val total = cr.values.sumOf { it?.size ?: 0 }
        val readable = exits.count { it.value != null }
        val logs = runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "300", "*:W").redirectErrorStream(true).start()
            val lines = p.inputStream.bufferedReader().readLines(); p.destroy(); lines
        }.getOrNull()
        val err = logs?.count { Regex("""^\S+\s+\S+\s+\d+\s+\d+\s+[EF]\s""").containsMatchIn(it) }
        val f = listOf(
            fld("crash_30d", "dumpsys dropbox (system_app_crash)", "ApplicationExitInfo needs Android 11",
                fb("ApplicationExitInfo CRASH/CRASH_NATIVE, last 30 days", "readable for $readable of ${exits.size} fleet packages (other uids refuse; no fleet app exports a provider for it yet)") { if (cr.isEmpty()) null else "$total" to total.toDouble() }),
            fld("last_crash", "dumpsys dropbox", "no crash recorded in the readable packages",
                fb("ApplicationExitInfo newest crash", "readable packages only") { cr.flatMap { e -> (e.value ?: emptyList()).map { e.key to it } }.maxByOrNull { it.second.timestamp }?.let { (p, i) -> "${p.removePrefix("com.diegonmarcos.")} ${(System.currentTimeMillis() - i.timestamp) / 3600000}h ago ${i.description.orEmpty().take(60)}" to null } }),
            fld("log_errors", "logcat -d -t 300 *:W", "logcat could not be started",
                fb("own process logcat (logcat -d -t 300 *:W)", "own process lines only (no READ_LOGS grant), last 300") { err?.let { "$it E/F of ${logs?.size} W+ lines" to it.toDouble() } }),
            fld("exit_reasons_all", "dumpsys dropbox", "none", fb("ApplicationExitInfo all reasons", "readable packages only") {
                exits.values.filterNotNull().flatten().groupingBy { it.reason }.eachCount().entries.sortedByDescending { it.value }.take(6).joinToString(" ") { "r${it.key}=${it.value}" }.takeIf { it.isNotEmpty() }?.let { it to null } }),
        )
        return f to cr.filter { (it.value?.size ?: 0) > 0 }.map { AFinding(it.key, "crash", "${it.value?.size} crash/native crash in 30 d (ApplicationExitInfo)", 45.0 + Math.min(20, it.value?.size ?: 0)) }
    }

    // ── T10 Network ──────────────────────────────────────────────────────
    private fun t10(c: Context): Pair<List<AField>, List<AFinding>> {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val nc = net?.let { cm.getNetworkCapabilities(it) }
        val lp = net?.let { cm.getLinkProperties(it) }
        val host = runCatching { java.net.URI(BuildConfig.UI_C3_OPS_BASE_URL).host }.getOrNull()?.takeIf { it.isNotBlank() }
        var dns: Double? = null; var tcp: Double? = null; var tls: Double? = null
        if (host != null) runCatching {
            val t0 = System.nanoTime(); val ip = InetAddress.getByName(host); dns = (System.nanoTime() - t0) / 1e6
            val s = Socket(); val t1 = System.nanoTime(); s.connect(InetSocketAddress(ip, 443), 3000); tcp = (System.nanoTime() - t1) / 1e6
            val t2 = System.nanoTime(); (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, host, 443, true).use { (it as javax.net.ssl.SSLSocket).soTimeout = 4000; it.startHandshake() }; tls = (System.nanoTime() - t2) / 1e6
        }
        val transports = listOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "cellular", NetworkCapabilities.TRANSPORT_VPN to "vpn", NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet")
        val uid = Process.myUid()
        val f = listOf(
            fld("transport", "dumpsys connectivity (default network)", "no active network", d("NetworkCapabilities.hasTransport") { nc?.let { n -> transports.filter { n.hasTransport(it.first) }.joinToString("+") { it.second }.ifEmpty { "other" } to null } }),
            fld("net_unvalidated", "dumpsys connectivity (validation)", "no active network", d("NetworkCapabilities NET_CAPABILITY_VALIDATED") { nc?.let { n -> val v = n.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED); "validated $v internet ${n.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} metered ${!n.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)} vpn ${n.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}" to (if (v) 0.0 else 1.0) } }),
            fld("bandwidth", "dumpsys connectivity", "no active network", d("NetworkCapabilities link down/up kbps (estimate)") { nc?.let { "down ${it.linkDownstreamBandwidthKbps}kbps up ${it.linkUpstreamBandwidthKbps}kbps" + (if (Build.VERSION.SDK_INT >= 29 && it.signalStrength != Int.MIN_VALUE) " signal ${it.signalStrength}" else "") to null } }),
            fld("link", "dumpsys connectivity (LinkProperties)", "no link", d("LinkProperties dns/private dns/mtu/routes (addresses omitted)") { lp?.let { "dns ${it.dnsServers.size} private-dns ${it.isPrivateDnsActive}${it.privateDnsServerName?.let { n -> " ($n)" } ?: ""} mtu ${if (Build.VERSION.SDK_INT >= 29) it.mtu else 0} routes ${it.routes.size}" to null } }),
            fld("dns_ms", "dumpsys connectivity / netstats", "no declared fleet endpoint or lookup failed", fb("InetAddress.getByName(fleet ops host)", "own timed probe to ${host ?: "n/a"}, one sample") { dns?.let { f1(it) + "ms" to it } }),
            fld("tcp_ms", "dumpsys connectivity", "TCP connect to the fleet endpoint failed", fb("Socket.connect(:443)", "own timed probe, one sample") { tcp?.let { f1(it) + "ms" to it } }),
            fld("tls_ms", "dumpsys connectivity", "TLS handshake to the fleet endpoint failed", fb("SSLSocket.startHandshake", "own timed probe, one sample") { tls?.let { f1(it) + "ms" to it } }),
            fld("own_traffic", "dumpsys netstats --uid", "none", fb("TrafficStats.getUidRx/TxBytes(own uid)", "own uid since boot; other uids refuse") { TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 }?.let { "rx ${mb(it)} tx ${mb(TrafficStats.getUidTxBytes(uid))}" to null } }),
            fld("radio_signal", "dumpsys telephony.registry", "signal level needs Android 10 and a cellular default network", fb("NetworkCapabilities.getSignalStrength", "default network only") { nc?.takeIf { Build.VERSION.SDK_INT >= 29 && it.signalStrength != Int.MIN_VALUE }?.let { "${it.signalStrength}" to null } }),
        )
        return f to emptyList()
    }

    // ── T11 Process & Lifecycle ──────────────────────────────────────────
    private fun t11(c: Context, exits: Map<String, List<ApplicationExitInfo>?>): List<AField> {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val own = exits[c.packageName]
        val threads = txt("/proc/self/status")?.let { Regex("Threads:\\s+(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return listOf(
            fld("process_uptime", "ps / dumpsys activity processes", "none", d("SystemClock.elapsedRealtime - Process.getStartElapsedRealtime") { ((SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000).let { "${it}s (device up ${SystemClock.elapsedRealtime() / 60000}min)" to null } }),
            fld("threads", "top -H / ps -T", "none", d("/proc/self/status Threads") { threads?.let { "$it" to it.toDouble() } }, fb("Thread.getAllStackTraces", "JVM threads only") { Thread.getAllStackTraces().size.let { "$it" to it.toDouble() } }),
            fld("open_fds", "ls /proc/<pid>/fd", "/proc/self/fd not listable", d("/proc/self/fd") { File("/proc/self/fd").list()?.size?.let { "$it" to it.toDouble() } }),
            fld("restarts_30d", "logcat ActivityManager: Start proc", "ApplicationExitInfo needs Android 11",
                fb("ApplicationExitInfo (own package), last 30 days, excluding user-requested", "own package only") { own?.count { it.reason != ApplicationExitInfo.REASON_USER_REQUESTED && it.reason != ApplicationExitInfo.REASON_EXIT_SELF }?.let { "$it of ${own.size} exits" to it.toDouble() } }),
            fld("importance", "dumpsys activity processes (oom-adj)", "none", fb("RunningAppProcessInfo.importance", "own processes, lower = more important") {
                am.runningAppProcesses?.filter { it.uid == Process.myUid() }?.joinToString(" ") { "${it.processName.substringAfterLast(':', "main")}=${it.importance}" }?.takeIf { it.isNotEmpty() }?.let { it to null } }),
            fld("background_state", "dumpsys deviceidle / am get-standby-bucket", "none", d("ActivityManager.isBackgroundRestricted / UsageStatsManager.getAppStandbyBucket") {
                "bg-restricted ${am.isBackgroundRestricted} bucket ${(c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket}" to null }),
            fld("usage_access", "dumpsys usagestats", "none", d("AppOpsManager OPSTR_GET_USAGE_STATS") {
                (if (ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED) "granted" else "not granted (usage-based fields say so)") to null }),
            fld("services", "dumpsys activity services", "other apps' services are not listed to apps", fb("ActivityManager.getRunningServices", "own services only since Android 8") { am.getRunningServices(100).size.let { "$it" to null } }),
        )
    }

    // ── T12 Security & Perms ─────────────────────────────────────────────
    private val SENSITIVE = setOf("CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "READ_SMS", "READ_CONTACTS", "READ_CALL_LOG", "READ_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW", "BIND_ACCESSIBILITY_SERVICE", "READ_PHONE_STATE")

    private class PkgSec(val pkg: String, val target: Int, val sensitive: Int, val granted: Int, val sig: String)

    private fun pkgSec(c: Context, p: String, ownSigs: Set<Int>): PkgSec? = runCatching {
        val pi = c.packageManager.getPackageInfo(p, PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES)
        val req = pi.requestedPermissions.orEmpty(); val fl = pi.requestedPermissionsFlags ?: IntArray(0)
        val granted = req.indices.filter { fl.getOrElse(it) { 0 } and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }
        val sigs = pi.signingInfo?.apkContentsSigners?.map { it.hashCode() }?.toSet().orEmpty()
        PkgSec(p, pi.applicationInfo?.targetSdkVersion ?: 0, granted.count { req[it].substringAfterLast('.') in SENSITIVE }, granted.size, if (sigs.isEmpty()) "unknown" else if (sigs == ownSigs) "same-signer" else "other-signer")
    }.getOrNull()

    private fun t12(c: Context, fleet: List<String>): Pair<List<AField>, List<AFinding>> {
        val ownSigs = runCatching { c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.map { it.hashCode() }?.toSet() }.getOrNull().orEmpty()
        val all = (listOf(c.packageName) + fleet).distinct().mapNotNull { pkgSec(c, it, ownSigs) }
        val me = all.firstOrNull { it.pkg == c.packageName }
        val low = all.count { it.target in 1..32 }
        fun sec(k: String) = Settings.Secure.getString(c.contentResolver, k)?.takeIf { it.isNotBlank() && it != "null" }
        val kg = c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val f = listOf(
            fld("sensitive_granted", "dumpsys package (granted=true)", "SuperApp package info unreadable", d("PackageManager GET_PERMISSIONS, requestedPermissionsFlags (SuperApp)") { me?.let { "${it.sensitive} sensitive of ${it.granted} granted, target ${it.target}" to it.sensitive.toDouble() } }),
            fld("fleet_perms", "dumpsys package per app", "no fleet package visible", d("PackageManager per fleet app (target, sensitive/granted, signer)") {
                all.takeIf { it.isNotEmpty() }?.joinToString(" ") { "${it.pkg.removePrefix("com.diegonmarcos.")}:t${it.target},s${it.sensitive}/${it.granted},${it.sig}" }?.let { it to null } }),
            fld("fleet_low_target", "dumpsys package (targetSdk)", "none", d("PackageManager targetSdkVersion < 33") { "$low of ${all.size} fleet apps" to low.toDouble() }),
            fld("a11y", "settings get secure enabled_accessibility_services", "none", d("Settings.Secure enabled_accessibility_services") { "${sec("enabled_accessibility_services")?.split(':')?.size ?: 0} enabled" to null }),
            fld("notif_listeners", "settings get secure enabled_notification_listeners", "none", d("Settings.Secure") { "${sec("enabled_notification_listeners")?.split(':')?.size ?: 0} bound" to null }),
            fld("autofill", "settings get secure autofill_service", "none", d("Settings.Secure autofill_service") { (sec("autofill_service")?.substringBefore('/') ?: "none") to null }),
            fld("device_state", "settings / dumpsys", "none", d("KeyguardManager.isDeviceSecure, Settings.Global adb_enabled/development_settings_enabled") {
                "secure-lock ${kg.isDeviceSecure} adb ${Settings.Global.getInt(c.contentResolver, Settings.Global.ADB_ENABLED, 0)} dev-options ${Settings.Global.getInt(c.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)}" to null }),
            fld("appops_other_apps", "cmd appops get <pkg>", "app-ops of other packages need GET_APP_OPS_STATS (system permission)"),
        )
        val find = all.filter { it.pkg != c.packageName && it.sensitive >= 9 }.map { AFinding(it.pkg, "sensitive perms", "${it.sensitive} sensitive permissions granted", 35.0) } +
            all.filter { it.target in 1..32 }.map { AFinding(it.pkg, "old targetSdk", "targetSdk ${it.target} < 33", 30.0) }
        return f to find
    }
}
