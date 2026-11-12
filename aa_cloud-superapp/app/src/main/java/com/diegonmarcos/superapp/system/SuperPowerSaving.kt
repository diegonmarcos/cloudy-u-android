package com.diegonmarcos.superapp.system

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.diegonmarcos.superapp.configs.AdbToggle
import com.diegonmarcos.superapp.configs.BrokerGrantChannel

/**
 * #1689 "Super power saving": one launcher mode, declared once in build.json::ui.control_panel
 * (controls[id=super_power_saving]), drawn on Quick actions (control panel tile) and on Configs > Perms.
 *
 * ON:  #1687 low-power mode follows + the system power-save is switched on ([PowerSaving.set]); then the SuperApp's
 *      OWN device admin (LockScreenAdminReceiver, DevicePolicyManager.removeActiveAdmin, no ADB) and its OWN
 *      notification access (PhoneNotificationListenerService: requestUnbind + `cmd notification disallow_listener`
 *      over the ADB owner channel) are released — only those that are on now, and which ones is REMEMBERED
 *      (super_power_prefs, fleet-config.json).
 * OFF: restores exactly the remembered ones: `dpm set-active-admin` (fallback: the system activation dialog) and
 *      `cmd notification allow_listener` (fallback: the Notification access settings page). Power saving goes off.
 * Accessibility is never touched. Broker rule: superpower-self (own components only).
 */
object SuperPowerSaving {
    const val CONTROL_ID = "super_power_saving"
    const val PREFS = "super_power_prefs"
    const val KEY_ON = "enabled"
    const val KEY_ADMIN = "released_admin"
    const val KEY_LISTENER = "released_listener"

    /** Pure plan: which grants to release (only those on) / restore (only those remembered). Tested in SuperPowerSavingTest. */
    data class Plan(val admin: Boolean, val listener: Boolean)

    fun releasePlan(adminOn: Boolean, listenerOn: Boolean) = Plan(adminOn, listenerOn)
    fun restorePlan(releasedAdmin: Boolean, releasedListener: Boolean) = Plan(releasedAdmin, releasedListener)

    fun adminComponent(ctx: Context): ComponentName = ComponentName(ctx, LockScreenAdminReceiver::class.java)
    fun listenerComponent(ctx: Context): ComponentName =
        ComponentName(ctx.packageName, "com.diegonmarcos.superapp.notificationcenter.PhoneNotificationListenerService")

    fun adminActive(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)?.isAdminActive(adminComponent(ctx)) == true
    }.getOrDefault(false)

    fun listenerEnabled(ctx: Context): Boolean = runCatching {
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty().split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }.any { it == listenerComponent(ctx) }
    }.getOrDefault(false)

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isOn(ctx: Context): Boolean = runCatching { sp(ctx).getBoolean(KEY_ON, false) }.getOrDefault(false)
    fun releasedAdmin(ctx: Context) = runCatching { sp(ctx).getBoolean(KEY_ADMIN, false) }.getOrDefault(false)
    fun releasedListener(ctx: Context) = runCatching { sp(ctx).getBoolean(KEY_LISTENER, false) }.getOrDefault(false)

    // The four declared lines (own components only; the broker rule superpower-self / roles-release-self).
    fun releaseAdminCmd(ctx: Context) = "dpm remove-active-admin --user 0 ${adminComponent(ctx).flattenToShortString()}"
    fun restoreAdminCmd(ctx: Context) = "dpm set-active-admin --user 0 ${adminComponent(ctx).flattenToShortString()}"
    fun releaseListenerCmd(ctx: Context) = "cmd notification disallow_listener ${listenerComponent(ctx).flattenToShortString()}"
    fun restoreListenerCmd(ctx: Context) = "cmd notification allow_listener ${listenerComponent(ctx).flattenToShortString()}"

    /** The two steps as #1688 toggles (Copy command + Run on ADB), from the REAL grant state. */
    fun steps(ctx: Context): List<AdbToggle> = listOf(
        AdbToggle("spm_admin", "Device administrator (double-tap lock fallback)", adminActive(ctx),
            listOf(restoreAdminCmd(ctx)), listOf(releaseAdminCmd(ctx))),
        AdbToggle("spm_listener", "Notification access (Phone Notifications)", listenerEnabled(ctx),
            listOf(restoreListenerCmd(ctx)), listOf(releaseListenerCmd(ctx))),
    )

    data class Report(val ok: Boolean, val lines: List<String>, val needs: List<Intent> = emptyList())

    /** Blocking; call off the main thread. Never throws. */
    fun turnOn(ctx: Context, stopVpn: Boolean = false): Report {
        val out = ArrayList<String>(); var ok = true
        val app = ctx.applicationContext
        PowerSaving.setFollowSystem(app, true)
        val ps = runCatching { PowerSaving.set(app, true) }.getOrNull()
        out += "Power saving: " + (ps?.let { "${it.channel}: ${it.detail}" } ?: "could not switch")
        if (ps?.ok != true) ok = false
        val plan = releasePlan(adminActive(app), listenerEnabled(app))
        sp(app).edit().putBoolean(KEY_ON, true).putBoolean(KEY_ADMIN, plan.admin).putBoolean(KEY_LISTENER, plan.listener).apply()
        if (plan.admin) {
            runCatching { (app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).removeActiveAdmin(adminComponent(app)) }
            val gone = await { !adminActive(app) }
            out += "Device admin: " + if (gone) "released" else "still active (removal pending or refused)"
            if (!gone) ok = false
        } else out += "Device admin: was off, left off"
        if (plan.listener) {
            runCatching { com.diegonmarcos.superapp.notificationcenter.PhoneNotificationListenerService.unbindSelf() }
            val ch = BrokerGrantChannel(app)
            val r = AdbToggle.run(steps(app)[1].copy(on = true), ch) { if (await { !listenerEnabled(app) }) false else listenerEnabled(app) }
            out += "Notification access: " + r.message
            if (!r.ok) ok = false
        } else out += "Notification access: was off, left off"
        out += "Accessibility: untouched"
        // Last: the foreground services (the ADB keepers after every ADB step above).
        out += SuperServices.stopAll(app, stopKept = stopVpn)
        return Report(ok, out)
    }

    fun turnOff(ctx: Context): Report {
        val out = ArrayList<String>(); var ok = true; val needs = ArrayList<Intent>()
        val app = ctx.applicationContext
        val plan = restorePlan(releasedAdmin(app), releasedListener(app))
        // First: the services it stopped (ADB keepers first), so the channel is back for the steps below.
        out += SuperServices.restoreAll(app)
        val ch = BrokerGrantChannel(app)
        if (plan.admin) {
            val r = AdbToggle.run(steps(app)[0].copy(on = false), ch) { if (await { adminActive(app) }) true else adminActive(app) }
            out += "Device admin: " + r.message
            if (!r.ok) {
                ok = false
                needs += Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(app))
                out += "Device admin: opening the system activation dialog instead"
            }
        }
        if (plan.listener) {
            val r = AdbToggle.run(steps(app)[1].copy(on = false), ch) { if (await { listenerEnabled(app) }) true else listenerEnabled(app) }
            out += "Notification access: " + r.message
            if (!r.ok) {
                ok = false
                needs += Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                out += "Notification access: opening the Notification access page instead"
            }
        }
        val ps = runCatching { PowerSaving.set(app, false) }.getOrNull()
        out += "Power saving: " + (ps?.let { "${it.channel}: ${it.detail}" } ?: "could not switch")
        sp(app).edit().putBoolean(KEY_ON, false).putBoolean(KEY_ADMIN, false).putBoolean(KEY_LISTENER, false).apply()
        return Report(ok, out, needs)
    }

    /** Launch the fallbacks of [r] (activation dialog, settings page); never throws. */
    fun openFallbacks(ctx: Context, r: Report) {
        for (i in r.needs) runCatching {
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(ctx.packageManager) != null) ctx.startActivity(i)
        }
    }

    /** What the mode has paused, for the double-tap lock and the Phone Notifications panel. */
    fun pausedReason(ctx: Context): String? = if (isOn(ctx)) "paused by Super power saving" else null

    private fun await(check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + 2000
        while (!check()) { if (System.currentTimeMillis() > end) return false; runCatching { Thread.sleep(80) } }
        return true
    }
}
