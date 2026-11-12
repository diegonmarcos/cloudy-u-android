package com.diegonmarcos.superapp.system

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.diegonmarcos.superapp.configs.DeviceControls

/**
 * #1689 Super power saving's foreground-service step: every SuperApp foreground service running at entry is stopped
 * (declared ones in build.json::ui.control_panel controls[id=super_power_saving].services, plus any undeclared one
 * found running), the set is REMEMBERED (super_power_prefs:stopped_services) and exactly that set is started again
 * on exit. adb_last services (the ADB owner channel) stop LAST and start FIRST; keep ones (the VPN) stay running
 * unless the owner confirmed stopping them.
 */
object SuperServices {
    data class Decl(val cls: String, val label: String, val adbLast: Boolean = false, val keep: Boolean = false)

    const val KEY_STOPPED = "stopped_services"

    fun declared(): List<Decl> = runCatching {
        val a = DeviceControls.declared(SuperPowerSaving.CONTROL_ID)?.optJSONArray("services") ?: return@runCatching emptyList()
        (0 until a.length()).map { a.getJSONObject(it) }.map { Decl(it.getString("class"), it.optString("label", it.getString("class").substringAfterLast('.')),
            it.optBoolean("adb_last"), it.optBoolean("keep")) }
    }.getOrDefault(emptyList())

    /** Pure: what to stop, in order (others, then adb_last), and what stays (keep, unless [stopKept]). */
    fun stopPlan(running: Set<String>, decl: List<Decl>, stopKept: Boolean): Pair<List<String>, List<String>> {
        val by = decl.associateBy { it.cls }
        val kept = running.filter { by[it]?.keep == true && !stopKept }
        val go = running - kept.toSet()
        val ordered = go.filter { by[it]?.adbLast != true }.sorted() + go.filter { by[it]?.adbLast == true }.sorted()
        return ordered to kept
    }

    /** Pure: restore order — adb_last first (the channel comes back before anything needs it), then the rest. */
    fun restoreOrder(stopped: Set<String>, decl: List<Decl>): List<String> {
        val by = decl.associateBy { it.cls }
        return stopped.filter { by[it]?.adbLast == true }.sorted() + stopped.filter { by[it]?.adbLast != true }.sorted()
    }

    /** Real state: this app's foreground services running now (own-uid getRunningServices). */
    @Suppress("DEPRECATION")
    fun runningForeground(ctx: Context): Set<String> = runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getRunningServices(Int.MAX_VALUE).orEmpty()
            .filter { it.service.packageName == ctx.packageName && it.foreground }
            .map { it.service.className }.toSet()
    }.getOrDefault(emptySet())

    fun label(cls: String) = declared().firstOrNull { it.cls == cls }?.label ?: "${cls.substringAfterLast('.')} (undeclared)"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(SuperPowerSaving.PREFS, Context.MODE_PRIVATE)
    fun remembered(ctx: Context): Set<String> = runCatching { sp(ctx).getStringSet(KEY_STOPPED, emptySet()).orEmpty().toSet() }.getOrDefault(emptySet())

    /** Stop; returns report lines. Never throws. */
    fun stopAll(ctx: Context, stopKept: Boolean): List<String> {
        val app = ctx.applicationContext
        val (order, kept) = stopPlan(runningForeground(app), declared(), stopKept)
        runCatching { sp(app).edit().putStringSet(KEY_STOPPED, order.toSet()).apply() }
        val out = ArrayList<String>()
        for (c in order) {
            runCatching { app.stopService(Intent().setComponent(ComponentName(app.packageName, c))) }
            out += "Service ${label(c)}: " + if (c in runningForeground(app)) "still running (bound by a live client)" else "stopped"
        }
        for (c in kept) out += "Service ${label(c)}: kept running (VPN; stop it only with the confirm)"
        if (order.isEmpty() && kept.isEmpty()) out += "Services: none running"
        return out
    }

    fun restoreAll(ctx: Context): List<String> {
        val app = ctx.applicationContext
        val out = ArrayList<String>()
        for (c in restoreOrder(remembered(app), declared())) {
            val ok = runCatching {
                val i = Intent().setComponent(ComponentName(app.packageName, c))
                if (android.os.Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
                true
            }.getOrElse { false }
            out += "Service ${label(c)}: " + if (ok) "started again" else "could not start (open its feature to start it)"
        }
        runCatching { sp(app).edit().remove(KEY_STOPPED).apply() }
        return out
    }
}
