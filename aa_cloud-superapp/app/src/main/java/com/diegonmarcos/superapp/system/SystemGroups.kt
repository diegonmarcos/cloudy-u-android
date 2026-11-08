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

    const val NEEDS_ADB = "needs ADB: arm the channel on Configs > Network > ADB Shell"

    fun bytes(b: Long): String = when {
        b >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", b / 1_073_741_824.0)
        b >= 1_048_576L -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
        b >= 1024L -> String.format(Locale.US, "%.0f kB", b / 1024.0)
        else -> "$b B"
    }

    private fun usage(total: Long, free: Long): String {
        val used = (total - free).coerceAtLeast(0L)
        return "${bytes(used)} used of ${bytes(total)} (${if (total > 0) used * 100 / total else 0}%) · ${bytes(free)} free"
    }

    private fun <T> safe(v: String, f: () -> T?): T? = runCatching(f).getOrNull()

    /** The probe's command, from the one list X-Ray owns. */
    fun probeCmd(id: String): String? = XrayProbes.all.firstOrNull { it.id == id }?.cmd

    /** The probe's output, or null when no ADB channel answered (not armed, refused, timed out). Blocking: call off the main thread. */
    fun adb(ctx: Context, probeId: String): String? =
        probeCmd(probeId)?.let { cmd -> runCatching { ShellBroker.execText(ctx, cmd) }.getOrNull() }?.takeIf { it.isNotBlank() }

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
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        out += DdLine("Cores", "$cores · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "—"} · $soc")
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

    fun graphics(ctx: Context): List<DdLine> {
        val out = ArrayList<DdLine>()
        out += DdLine("FPS (home window)", FpsMeter.fps.toString())
        runCatching {
            val d = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val m = d.mode
            out += DdLine("Display", "${m.physicalWidth}x${m.physicalHeight} · ${String.format(Locale.US, "%.0f", d.refreshRate)} Hz")
            out += DdLine("Refresh modes", d.supportedModes.map { Math.round(it.refreshRate) }.distinct().sorted().joinToString(" / ") { "$it Hz" })
            out += DdLine("Density", "${ctx.resources.displayMetrics.densityDpi} dpi")
        }
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            out += DdLine("OpenGL ES", am.deviceConfigurationInfo.glEsVersion)
        }
        runCatching {
            val v = ctx.packageManager.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
            out += DdLine("Vulkan", if (v == null) "none" else "${(v shr 22) and 0x3ff}.${(v shr 12) and 0x3ff}")
        }
        out += DdLine("GPU / board", "${Build.HARDWARE} · ${Build.BOARD}")
        return out
    }

    // ── ADB groups ──

    /** The lines of an adb-source group from its probe's [text] (null text = no ADB). */
    fun adbLines(groupId: String, text: String?, rows: Int, label: (String) -> String = { it }): List<DdLine> {
        if (text == null) return listOf(DdLine("Status", NEEDS_ADB))
        val lines = when (groupId) {
            "running_services" -> DumpsysParsers.runningServices(text, rows)
            "memory_3h" -> DumpsysParsers.memory3h(text, rows)
            "since_charge_usage" -> DumpsysParsers.usageInfos(DumpsysParsers.batteryStats(text), label)
            "since_charge_apps" -> DumpsysParsers.perApp(DumpsysParsers.batteryStats(text), rows, label)
            else -> emptyList()
        }
        return lines.ifEmpty { listOf(DdLine("Status", "no data in the dump")) }
    }
}
