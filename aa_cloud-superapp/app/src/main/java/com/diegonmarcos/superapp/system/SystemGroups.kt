package com.diegonmarcos.superapp.system

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import com.diegonmarcos.superapp.battery.SysfsProc
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import com.diegonmarcos.superapp.xray.XrayProbes
import java.io.File
import java.util.Locale

/**
 * What the right "System" dropdown prints for each declared group (build.json::home_status_bar.dropdowns[right_system]).
 * App groups read the app's own APIs (no permission, cheap); ADB groups run an X-Ray probe through the shell broker and say
 * "needs ADB" when no channel is armed. Nothing here throws: a failed read is a line saying so.
 */
object SystemGroups {

    /** #1678 the marker of a needs-ADB row: the dropdown draws it as a link whose text is [AdbReadiness.State.linkText] (what is missing). */
    const val NEEDS_ADB = "needs ADB: ADB off - tap to open ADB Shell and connect"

    fun bytes(b: Long): String = DumpsysParsers.bytes(b)

    private fun usage(total: Long, free: Long): String {
        val used = (total - free).coerceAtLeast(0L)
        return "${bytes(used)} used of ${bytes(total)} (${if (total > 0) used * 100 / total else 0}%) · ${bytes(free)} free"
    }

    private fun <T> safe(v: String, f: () -> T?): T? = runCatching(f).getOrNull()

    /** The probe's command, from the one list X-Ray owns. */
    fun probeCmd(id: String): String? = XrayProbes.all.firstOrNull { it.id == id }?.cmd

    /** The probe's output, or null when no ADB channel answered (not armed, refused, timed out). Blocking: call off the main thread. */
    fun adb(ctx: Context, probeId: String): String? {
        val now = System.currentTimeMillis()
        probeCache[probeId]?.takeIf { now - it.first < PROBE_TTL_MS }?.let { return it.second }
        val cmd = probeCmd(probeId) ?: return null
        // #1678 One probe at a time: the dropdown opens ~20 ADB groups at once and the broker serves 3 calls; the rest came back "busy"
        // and were printed as "needs ADB" while the channel was connected. Queued here, each probe gets the channel in turn.
        return synchronized(probeLock) {
            probeCache[probeId]?.takeIf { System.currentTimeMillis() - it.first < PROBE_TTL_MS }?.let { return@synchronized it.second }
            if (!AdbReadiness.check(ctx).usable) { probeError.remove(probeId); return@synchronized null }
            val r = runCatching { ShellBroker.exec(ctx, ShellBroker.SELF, cmd, PROBE_TIMEOUT_MS, PROBE_MAX_BYTES) }.getOrNull()
            val out = (r as? com.diegonmarcos.superapp.shellclient.ShellResult.Ok)?.stdout?.takeIf { it.isNotBlank() }
            if (out != null) { probeCache[probeId] = System.currentTimeMillis() to out; probeError.remove(probeId) }
            else probeError[probeId] = when (r) {
                null -> "read failed"
                is com.diegonmarcos.superapp.shellclient.ShellResult.Ok -> "empty output"
                is com.diegonmarcos.superapp.shellclient.ShellResult.Denied -> "refused by the broker allowlist: ${r.reason}".take(160)
                is com.diegonmarcos.superapp.shellclient.ShellResult.Failed -> "read failed: ${r.reason}".take(160)
                else -> "channel dropped during the read"
            }
            out
        }
    }

    /** #1678 Why probe [probeId] gave nothing while ADB was usable (refused, busy, timed out, empty); null = ADB itself was off. */
    fun probeFailure(probeId: String): String? = probeError[probeId]

    private val probeLock = Any()
    private val probeError = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val PROBE_TIMEOUT_MS = 60_000L
    private const val PROBE_MAX_BYTES = 8 * 1024 * 1024

    /** Several groups share one probe (batterystats, gfxinfo): one read serves them all while the dropdown is open. */
    private const val PROBE_TTL_MS = 10_000L
    private val probeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    // ── Memory ──

    fun memory(ctx: Context): List<DdLine> {
        val out = ArrayList<DdLine>()
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            out += DdLine("RAM", usage(mi.totalMem, mi.availMem))
            out += DdLine("Available", bytes(mi.availMem))
            out += DdLine("Low-mem threshold", bytes(mi.threshold) + if (mi.lowMemory) " · LOW MEMORY" else "")
        }
        val mem = runCatching { DumpsysParsers.meminfo(File("/proc/meminfo").readText()) }.getOrDefault(emptyMap())
        fun kb(k: String) = mem[k]?.times(1024)
        kb("MemFree")?.let { out += DdLine("Free (raw)", bytes(it)) }
        kb("Cached")?.let { out += DdLine("Cached", bytes(it)) }
        kb("Buffers")?.let { out += DdLine("Buffers", bytes(it)) }
        val st = kb("SwapTotal"); val sf = kb("SwapFree")
        out += DdLine("Swap", if (st == null || st <= 0L) "none" else usage(st, sf ?: 0L))
        psi("memory")?.let { out += DdLine("Pressure (PSI)", it) }
        runCatching { val r = Runtime.getRuntime(); out += DdLine("App heap", "${bytes(r.totalMemory() - r.freeMemory())} of ${bytes(r.maxMemory())}") }
        return out
    }

    private fun psi(resource: String): String? = safe("psi") {
        val f = File("/proc/pressure/$resource"); if (!f.canRead()) return@safe null
        f.readLines().joinToString(" · ") { l ->
            val kind = l.substringBefore(' ')
            "$kind ${Regex("avg10=([0-9.]+)").find(l)?.groupValues?.get(1) ?: "?"}/${Regex("avg60=([0-9.]+)").find(l)?.groupValues?.get(1) ?: "?"}"
        }.ifBlank { null }
    }

    // ── Storage ──

    fun storage(ctx: Context): List<DdLine> {
        val out = ArrayList<DdLine>()
        val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
        val total = safe("t") { ssm?.getTotalBytes(StorageManager.UUID_DEFAULT) }
        val free = safe("f") { ssm?.getFreeBytes(StorageManager.UUID_DEFAULT) }
        if (total != null && free != null) {
            out.add(DdLine("Internal", usage(total, free)))
        } else {
            val s = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
            out.add(DdLine("Internal /data", if (s == null) "—" else usage(s.blockCountLong * s.blockSizeLong, s.availableBlocksLong * s.blockSizeLong)))
        }
        // The same buckets Settings > Storage shows for the shared storage of this user (needs Usage access).
        val e = runCatching { ssm?.queryExternalStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle()) }.getOrNull()
        if (e == null) {
            out.add(DdLine("Images / Videos / Audio / Apps", "needs Usage access (Perms)"))
        } else {
            out.add(DdLine("Images", bytes(e.imageBytes))); out.add(DdLine("Videos", bytes(e.videoBytes)))
            out.add(DdLine("Audio", bytes(e.audioBytes))); out.add(DdLine("Apps (external)", bytes(e.appBytes)))
            out.add(DdLine("Shared total", bytes(e.totalBytes)))
            if (total != null && free != null) out.add(DdLine("System / other", bytes((total - free - e.totalBytes).coerceAtLeast(0L))))
        }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (v in sm.storageVolumes) {
                val dir = v.directory
                if (dir == null || (v.isPrimary && v.isEmulated)) continue
                val s = StatFs(dir.path)
                out.add(DdLine(v.getDescription(ctx) + if (v.isRemovable) " (removable)" else "", usage(s.blockCountLong * s.blockSizeLong, s.availableBlocksLong * s.blockSizeLong)))
            }
        }
        return out
    }

    // ── CPU ──

    fun cpu(ctx: Context): List<DdLine> {
        val out = ArrayList<DdLine>()
        val cores = Runtime.getRuntime().availableProcessors()
        SysfsProc.cpuLoad()?.let { c ->
            out += DdLine("Load 1m / 5m / 15m", String.format(Locale.US, "%.2f / %.2f / %.2f (%.1fx per core)", c.loadAvg1m, c.loadAvg5m, c.loadAvg15m, c.loadAvg1m / c.cores.coerceAtLeast(1)))
            out += DdLine("Usage", "${(c.busyFraction * 100).toInt()}% busy over ${c.cores} cores")
        }
        psi("cpu")?.let { out += DdLine("Pressure (PSI)", it) }
        val freqs = (0 until cores).map { i ->
            val cur = safe("cur") { File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLong() / 1000 }
            val max = safe("max") { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000 }
            "cpu$i" to if (cur == null) "n/a (kernel hides cpufreq)" else "$cur MHz" + (max?.let { " / $it" } ?: "")
        }
        out += if (freqs.all { it.second.startsWith("n/a") }) listOf(DdLine("Per-core freq", freqs.first().second)) else freqs.map { DdLine(it.first, it.second) }
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { out += DdLine("Battery temp", "${it / 10.0} C") }
        return out
    }

    // ── Graphics ──

    /** Live graphics: the home window's FPS (the dropdown appends the gfxinfo frame stats when ADB is up). */
    fun graphics(ctx: Context): List<DdLine> = listOf(DdLine("FPS (home window)", FpsMeter.fps.toString()))

    // ── ① Device Profile Scan (static machine) ──

    fun device(ctx: Context): List<DdLine> = listOf(
        DdLine("Model", "${Build.MANUFACTURER} ${Build.MODEL}"),
        DdLine("Android", "${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT} · patch ${Build.VERSION.SECURITY_PATCH}"),
        DdLine("Build", "${Build.ID} · ${Build.VERSION.INCREMENTAL}"),
        DdLine("Uptime", android.os.SystemClock.elapsedRealtime().let { ms -> String.format(Locale.US, "%dd %dh %dm", ms / 86_400_000, ms / 3_600_000 % 24, ms / 60_000 % 60) }),
    )

    fun soc(ctx: Context): List<DdLine> {
        val out = ArrayList<DdLine>()
        val cores = Runtime.getRuntime().availableProcessors()
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        out += DdLine("Cores", "$cores · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "—"} · $soc")
        val maxes = (0 until cores).map { i -> safe("max") { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000 } }
        val known = maxes.filterNotNull()
        if (known.isNotEmpty()) out += DdLine("Clusters (max MHz)", known.groupingBy { it }.eachCount().toSortedMap().entries.joinToString(" · ") { "${it.value}x ${it.key}" })
        else out += DdLine("Clusters", "n/a (kernel hides cpufreq)")
        safe("gov") { File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").readText().trim() }?.let { out += DdLine("Governor", it) }
        return out
    }

    fun gpu(ctx: Context): List<DdLine> {
        val out = ArrayList<DdLine>()
        out += DdLine("GPU / board", "${Build.HARDWARE} · ${Build.BOARD}")
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            out += DdLine("OpenGL ES", am.deviceConfigurationInfo.glEsVersion)
        }
        runCatching {
            val v = ctx.packageManager.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
            out += DdLine("Vulkan", if (v == null) "none" else "${(v shr 22) and 0x3ff}.${(v shr 12) and 0x3ff}")
        }
        return out
    }

    fun display(ctx: Context): List<DdLine> {
        val out = ArrayList<DdLine>()
        runCatching {
            val d = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val m = d.mode
            out += DdLine("Display", "${m.physicalWidth}x${m.physicalHeight} · ${String.format(Locale.US, "%.0f", d.refreshRate)} Hz")
            out += DdLine("Refresh modes", d.supportedModes.map { Math.round(it.refreshRate) }.distinct().sorted().joinToString(" / ") { "$it Hz" })
            out += DdLine("Density", "${ctx.resources.displayMetrics.densityDpi} dpi")
        }
        return out
    }

    fun capacity(ctx: Context): List<DdLine> {
        val out = ArrayList<DdLine>()
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            out += DdLine("RAM total", bytes(ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem))
        }
        val total = safe("t") { (ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager)?.getTotalBytes(StorageManager.UUID_DEFAULT) }
        out += DdLine("Storage total", total?.let { bytes(it) } ?: "—")
        return out
    }

    // ── ② Battery & Power tracker: live vitals ──

    fun battery(ctx: Context): List<DdLine> {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return listOf(DdLine("Battery", "—"))
        val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return listOf(
            DdLine("Level", if (lvl < 0) "—" else "${lvl * 100 / scale.coerceAtLeast(1)}%"),
            DdLine("Charging", if (charging) "yes" else "no"),
            DdLine("Temperature", if (t == Int.MIN_VALUE) "—" else "${t / 10.0} C"),
        )
    }

    // ── ADB groups ──

    /** The lines of an adb-source group from its probe's [text] (null text = no ADB). */
    fun adbLines(groupId: String, text: String?, rows: Int, label: (String) -> String = { it }): List<DdLine> {
        if (text == null) return listOf(DdLine("Status", NEEDS_ADB))
        val lines = when (groupId) {
            "running_services" -> DumpsysParsers.runningServices(text, rows)
            "memory_3h" -> DumpsysParsers.memory3h(text, rows)
            "thermal" -> DumpsysParsers.thermalFull(text).take(rows)
            "wakelocks" -> DumpsysParsers.wakelockLines(text, rows, label)
            "power_by_uid" -> DumpsysParsers.uidActivityLines(text, rows, label)
            "alarms" -> DumpsysParsers.alarmLines(text, rows)
            "jobs" -> DumpsysParsers.jobLines(text, rows)
            "jank" -> DumpsysParsers.gfxinfoJank(text)
            "notif_rate" -> DumpsysParsers.notifications(text, rows)
            "binder" -> DumpsysParsers.binder(text, rows, label)
            "meminfo_detail" -> DumpsysParsers.meminfoDetail(text, rows)
            "anr" -> DumpsysParsers.anrLines(text, rows)
            "df" -> DumpsysParsers.dfLines(text, rows)
            "diskstats" -> DumpsysParsers.diskstats(text, rows)
            "app_sizes" -> DumpsysParsers.appSizeLines(text, rows)
            "crashes" -> DumpsysParsers.crashLines(text, rows)
            "net_usage" -> DumpsysParsers.netUsageLines(text, rows, label)
            "net_conn" -> DumpsysParsers.connectivity(text, rows)
            "net_signal" -> DumpsysParsers.signal(text, rows)
            "oom_adj" -> DumpsysParsers.oomLines(text, rows)
            "sec_perms" -> DumpsysParsers.permLines(text, rows)
            "since_charge_usage" -> DumpsysParsers.usageInfos(DumpsysParsers.batteryStats(text), label)
            "since_charge_apps" -> DumpsysParsers.perApp(DumpsysParsers.batteryStats(text), rows, label)
            else -> emptyList()
        }
        return lines.ifEmpty { listOf(DdLine("Status", "no data in the dump")) }
    }

    /** ④ Suspects: the cross-family ranking over the probes it needs (each cached for the open), one line per suspect: score, why. Null with no ADB. */
    fun suspectLines(ctx: Context, rows: Int): List<DdLine>? {
        val raw = LinkedHashMap<String, String>()
        for (id in listOf("batterystats", "packages", "services", "alarm", "jobs", "notification", "gfxinfo")) adb(ctx, id)?.let { raw[id] = it }
        if (raw["batterystats"] == null && raw["notification"] == null) return null
        return com.diegonmarcos.superapp.xray.XrayAnalyze.suspects(raw, rows).map {
            DdLine("#${it.rank} ${it.pkg.removePrefix("com.diegonmarcos.").take(26)}",
                String.format(Locale.US, "%.0f pts · %s · %s", it.score, it.cause, it.detail.substringBefore(';').removePrefix("why: ")))
        }
    }
}
