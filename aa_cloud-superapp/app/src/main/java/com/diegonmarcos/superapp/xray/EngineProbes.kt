package com.diegonmarcos.superapp.xray

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.diegonmarcos.superapp.battery.BatteryRepository
import com.diegonmarcos.superapp.battery.EnergyLedger
import com.diegonmarcos.superapp.crash.CrashReports
import com.diegonmarcos.superapp.shellclient.ShellClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * #1649 The live half of the engine profiles: reads everything [EngineProfiles.report] is computed from, on the device, now.
 * Every value is the real state (the recorder's own runs, the process CPU clock, the battery Source of Truth, the crash reports
 * on disk, a live round-trip through the shell broker, the platform's exit records); a reading that cannot be had is left out
 * with its reason ([EngineProfiles.Inputs.missing]) and shows as "no sample yet", never as a default. Blocking: off the main thread.
 */
object EngineProbes {
    private const val MAX_CRASH_FILES = 200
    private const val MAX_CRASH_BYTES = 64 * 1024
    private const val EXITS = 16

    /** One collect shared by every engine block of a page (they open together): fresh for [FRESH_MS], then read again. */
    class Shared(private val ctx: Context) {
        private val lock = Mutex()
        private var at = 0L
        private var last: EngineProfiles.Inputs? = null

        suspend fun get(): EngineProfiles.Inputs = lock.withLock {
            val now = System.currentTimeMillis()
            last?.takeIf { now - at < FRESH_MS } ?: EngineProfiler.time("xray", "report") { collect(ctx) }.also { last = it; at = now }
        }

        fun invalidate() { at = 0L }

        companion object { const val FRESH_MS = 5_000L }
    }

    fun collect(ctx: Context): EngineProfiles.Inputs {
        val app = ctx.applicationContext
        val snap = EngineProfiler.snapshot()
        val state = HashMap<String, Double>()
        val missing = HashMap<String, String>()
        /** One live value; a throw or a null is recorded as the reason it is missing. */
        fun put(key: String, read: () -> Number?) {
            runCatching { read() }
                .onSuccess { v -> if (v != null) state[key] = v.toDouble() else if (key !in missing) missing[key] = "not readable now" }
                .onFailure { missing[key] = "${it.javaClass.simpleName}: ${it.message ?: "no detail"}".take(120) }
        }
        fun flag(b: Boolean) = if (b) 1 else 0

        val crashes = crashes(app)
        val uid = app.applicationInfo.uid
        val cycles = runCatching { BatteryRepository.cycles(app) }.getOrDefault(emptyList())
        val appMah = cycles.firstOrNull { it.endTs == null }?.apps?.get(uid)

        // Notify
        put("notify.sent_per_h") {
            if (snap.recordedMs < 60_000L) { missing["notify.sent_per_h"] = "under 1 min of process time"; return@put null }
            com.diegonmarcos.superapp.notificationcenter.NotifyGate.notifyCount / (snap.recordedMs / 3_600_000.0)
        }
        val badges = runCatching { com.diegonmarcos.superapp.notificationcenter.BadgeServices.statuses(app) }
        for ((key, st) in listOf(
            "badges_dead" to com.diegonmarcos.superapp.notificationcenter.BadgeServices.State.DEAD,
            "badges_live" to com.diegonmarcos.superapp.notificationcenter.BadgeServices.State.LIVE,
            "badges_blocked" to com.diegonmarcos.superapp.notificationcenter.BadgeServices.State.BLOCKED,
        )) put("notify.$key") { badges.getOrThrow().count { it.state == st } }
        put("notify.listener") { flag(app.packageName in androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(app)) }
        put("notify.history") { com.diegonmarcos.superapp.notificationcenter.PhoneNotificationStore.count(app) }
        put("notify.alerts") { com.diegonmarcos.superapp.notificationcenter.AlertStore.visible(app).size }

        // UI
        put("ui.fps") { com.diegonmarcos.superapp.system.FpsMeter.fps }
        put("ui.icons") { com.diegonmarcos.superapp.ui.AppIconCache.size() }

        // Launcher
        val sections = runCatching { com.diegonmarcos.superapp.launcher.Sections.all() }
        put("launcher.sections") { sections.getOrThrow().size }
        put("launcher.pages") { sections.getOrThrow().sumOf { it.allPages.size } }

        // Store
        val tracker = runCatching { com.diegonmarcos.superapp.extinstall.ExtAppInstalls.tracker }
        put("store.tracked") { tracker.getOrThrow().trackedPackages().size }
        put("store.active") { tracker.getOrThrow().let { t -> t.trackedPackages().count { t.snapshot(it)?.phase?.active == true } } }

        // Background policy & battery
        put("bgpolicy.supersaving") { flag(com.diegonmarcos.superapp.bgpolicy.SuperSavingRuntime.active(app)) }
        put("bgpolicy.cycles") { cycles.size }
        put("bgpolicy.app_mah") { appMah.also { if (it == null) missing["bgpolicy.app_mah"] = "no per-app batterystats reading yet (needs the ADB shell)" } }

        // Shell / ADB broker: the broker's own audit ring is the measurement (every call, its real duration and outcome).
        val audit = runCatching { ShellClient.audit(app) ?: throw IllegalStateException("the ADB owner refused the audit read") }
        put("shell.failed") { audit.getOrThrow().count { it.outcome == "timeout" || it.outcome == "legacy-fail" } }
        put("shell.nonzero") { audit.getOrThrow().count { it.outcome == "exit" } }
        put("shell.denied") { audit.getOrThrow().count { it.outcome == "denied" } }
        put("shell.callers") { audit.getOrThrow().map { it.caller }.distinct().size }
        put("shell.armed") { flag(com.diegonmarcos.superapp.system.AdbReadiness.check(app).usable) }
        val extra = audit.getOrNull().orEmpty().filter { it.outcome != "denied" }.takeIf { it.isNotEmpty() }?.let { l ->
            listOf(EngineProfiler.Path("shell", "exec", l.size.toLong(), l.sumOf { it.ms } * 1_000_000L, l.maxOf { it.ms } * 1_000_000L,
                0L, 0L, l.maxOf { it.ts }, l.map { it.ms * 1_000_000L }, ledgerTag = ""))
        }.orEmpty()

        // Network & mesh
        put("network.mesh_up") { flag(com.diegonmarcos.superapp.network.FleetDns.meshUp(app)) }
        put("network.firewall") { flag(com.diegonmarcos.superapp.firewall.FirewallController.isEnabled(app)) }
        put("network.dns_chosen") { flag(com.diegonmarcos.superapp.network.FleetDns.Prefs(app).chosen) }

        // System services: how this process ended the last times (the platform's own record), the crash files, the heap.
        val exitReasons: Result<List<Int>> = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .getHistoricalProcessExitReasons(app.packageName, 0, EXITS).map { it.reason }
            } else error("needs Android 11")
        }
        fun exits(reason: Int) = exitReasons.getOrThrow().count { it == reason }
        put("system.anr") { exits(ApplicationExitInfo.REASON_ANR) }
        put("system.native_crash") { exits(ApplicationExitInfo.REASON_CRASH_NATIVE) }
        put("system.java_crash") { exits(ApplicationExitInfo.REASON_CRASH) }
        put("system.low_memory") { exits(ApplicationExitInfo.REASON_LOW_MEMORY) }
        put("system.unattributed") { crashes.count { it.engine == null } }
        put("system.crash_files") { crashes.size }
        put("system.heap_mb") { Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1024.0 * 1024.0) } }
        put("system.threads") { Thread.activeCount() }

        // X-Ray itself
        put("xray.records") { snap.records }
        put("xray.paths") { snap.paths.size }
        put("xray.server") { flag(com.diegonmarcos.superapp.devcontrol.DevControlServer.isRunning()) }

        return EngineProfiles.Inputs(
            snap = snap,
            ledger = EnergyLedger.snapshot().second.mapValues { (_, s) -> EngineProfiles.Ledger(s.cpuNanos, s.calls, s.wakeups, s.bytes) },
            processCpuMs = runCatching { android.os.Process.getElapsedCpuTime() }.getOrNull()?.takeIf { it > 0 },
            appMah = appMah, crashes = crashes, nowMs = System.currentTimeMillis(), state = state, missing = missing, extraPaths = extra,
        )
    }

    /** Every crash report on disk (CrashLogger's files and the libs:crash reports), newest first, each attributed to its engine. */
    fun crashes(ctx: Context): List<EngineProfiles.Crash> {
        val dirs = listOfNotNull(
            runCatching { File(ctx.getExternalFilesDir(null), "crashes") }.getOrNull(),
            runCatching { CrashReports.dir(ctx) }.getOrNull(),
        )
        val files = dirs.flatMap { d -> d.listFiles { f -> f.isFile && f.length() > 0 && ".txt" in f.name }?.toList().orEmpty() }
            .sortedByDescending { it.lastModified() }.take(MAX_CRASH_FILES)
        return files.mapNotNull { f ->
            val text = runCatching { f.inputStream().use { String(it.readNBytesCompat(MAX_CRASH_BYTES)) } }.getOrNull() ?: return@mapNotNull null
            EngineProfiles.Crash(f.lastModified(), EngineProfiles.attribute(text), EngineProfiles.headline(text).take(160), f.name)
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val buf = ByteArray(max); var n = 0
        while (n < max) { val r = read(buf, n, max - n); if (r <= 0) break; n += r }
        return buf.copyOf(n)
    }
}
