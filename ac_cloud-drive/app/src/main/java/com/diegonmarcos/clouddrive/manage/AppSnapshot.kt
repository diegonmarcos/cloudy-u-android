package com.diegonmarcos.clouddrive.manage

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.storage.StorageManager
import com.diegonmarcos.cloudlib.disk.AppSizes
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellResult

/**
 * Manage ▸ Apps' reader: every installed app with its sizes, running state and RAM, from the best
 * source this phone allows. Blocking; call off the main thread.
 *
 *  - sizes: StorageStatsManager.queryStatsForPackage. Usage access (PACKAGE_USAGE_STATS, granted in
 *    Settings) is needed for every package but this one; without it those cells are null ("—").
 *  - running + RAM: ActivityManager.runningAppProcesses + getProcessMemoryInfo (PSS). Since Android
 *    5.1 that lists only this app's own processes, so for everyone else the fleet's privileged shell
 *    channel (libs:shizuku-adb-debug-tools: the SuperApp bridge, our embedded adb, or Shizuku) answers
 *    `ps -A -o NAME` and `dumpsys meminfo` when one is up. Otherwise the cells are null ("—").
 */
object AppSnapshot {
    data class Snapshot(val rows: List<AppRow>, val usageAccess: Boolean, val shell: String?)

    fun collect(ctx: Context, useShell: Boolean = true): Snapshot {
        val pm = ctx.packageManager
        val usage = AppSizes.hasUsageAccess(ctx)
        val ss = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val apps = runCatching { pm.getInstalledApplications(PackageManager.GET_META_DATA) }.getOrDefault(emptyList())
        val own = runCatching {
            val procs = am.runningAppProcesses.orEmpty()
            val infos = am.getProcessMemoryInfo(procs.map { it.pid }.toIntArray())
            procs.mapIndexed { i, p -> p.pkgList.orEmpty().map { it to infos[i].totalPss.toLong() } }.flatten()
                .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        }.getOrDefault(emptyMap())
        val rows = apps.map { ai ->
            val pkg = ai.packageName
            val sizes = if (usage || pkg == ctx.packageName) runCatching {
                val s = ss.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
                Triple(s.appBytes, (s.dataBytes - s.cacheBytes).coerceAtLeast(0), s.cacheBytes)
            }.getOrNull() else null
            AppRow(
                pkg = pkg,
                label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg),
                system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                appBytes = sizes?.first, dataBytes = sizes?.second, cacheBytes = sizes?.third,
                running = if (own.containsKey(pkg)) true else null,
                ramKb = own[pkg],
            )
        }
        if (!useShell) return Snapshot(AppTable.sort(rows), usage, null)
        // #1510 through the SuperApp's shell broker (libs:shell-client): both commands are on this app's allowlist entry; unarmed = no shell columns.
        val client = ShellClient.over(ctx)
        fun sh(cmd: String): ShellResult.Ok? = runCatching { client.exec(cmd, timeoutMs = 20_000L) }.getOrNull() as? ShellResult.Ok
        val psOut = sh("ps -A -o NAME")
        val ps = psOut?.stdout?.let(AppTable::parsePs)?.takeIf { it.isNotEmpty() }
        val mem = sh("dumpsys meminfo")?.stdout?.let(AppTable::parseMeminfo)?.takeIf { it.isNotEmpty() }
        return Snapshot(AppTable.sort(AppTable.withShell(rows, ps, mem)), usage, psOut?.source)
    }
}
