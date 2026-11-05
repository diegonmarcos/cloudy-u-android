package com.diegonmarcos.cloudstore

import com.diegonmarcos.superapp.perms.PermsLink
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

/**
 * #1078 What keeps Cloud Store's background update checks (libs:appstore ConstellationWorker, a WorkManager periodic job
 * that WorkManager re-schedules after a reboot; nothing of this app's own re-arms it at boot, #1502) running on a phone: the three
 * things only the user can allow. Each one is asked the way Android intends it, through its own system prompt or
 * settings screen. Nothing here grants anything: no pm grant, no WRITE_SECURE_SETTINGS, no battery-optimization
 * exemption request (the app never asks the system to exempt it; it opens the list and the user decides).
 */
object UpdateChecks {
    enum class Need { NOTIFICATIONS, INSTALL_SOURCE, BATTERY }

    /** Test tags: the card, a row by what it is about, and that row's action button. */
    const val TAG_CARD = "update_checks_card"
    fun row(n: Need) = "update_checks_row_${n.name.lowercase()}"
    fun action(n: Need) = "update_checks_action_${n.name.lowercase()}"
    const val TAG_PROMPT = "update_checks_battery_prompt"

    private const val PREFS = "update_checks"
    private const val KEY_BATTERY_PROMPTED = "battery_prompted"

    fun notificationsGranted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** "Install unknown apps" for this app: a special access, granted on its own settings screen. */
    fun installSourceAllowed(ctx: Context): Boolean = runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    fun batteryExempt(ctx: Context): Boolean =
        runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)

    fun missing(ctx: Context): List<Need> = Need.values().filter { !granted(ctx, it) }

    fun granted(ctx: Context, n: Need): Boolean = when (n) {
        Need.NOTIFICATIONS -> notificationsGranted(ctx)
        Need.INSTALL_SOURCE -> installSourceAllowed(ctx)
        Need.BATTERY -> batteryExempt(ctx)
    }

    /** #1515 the Configs > Perms row (catalogue id) that grants [n]: every grant is made there, never from this card. */
    fun permsId(n: Need): String = when (n) {
        Need.NOTIFICATIONS -> "notifications"
        Need.INSTALL_SOURCE -> "install_unknown"
        Need.BATTERY -> "battery_opt"
    }

    /** Opens Configs > Perms on the row of [n]. */
    fun open(ctx: Context, n: Need) = PermsLink.open(ctx, "updates", listOf(permsId(n)))

    /** The in-app battery prompt is asked once, and only while the app is still battery-optimized. */
    fun shouldPromptBattery(ctx: Context): Boolean =
        !batteryExempt(ctx) && !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BATTERY_PROMPTED, false)

    fun markBatteryPrompted(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply()
    }
}
