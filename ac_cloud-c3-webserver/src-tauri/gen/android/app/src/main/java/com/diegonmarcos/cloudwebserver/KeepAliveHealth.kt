package com.diegonmarcos.cloudwebserver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * #1724 Keep-alive health: every requirement the keep-alive really has, with its REAL state (never assumed), shown on Home with a fix.
 * [KeepAliveHealth.evaluate] is pure over a [Probe] (a JVM test holds each requirement's detection); [ContextProbe] reads the device.
 * Declared once: build.json perms.needs (notifications, battery_opt, background_run, exact_alarm, Samsung rows) plus the manifest; asked and granted only on Configs > Perms (perms guard P7).
 */
enum class HealthState { OK, MISSING, UNKNOWN, NA }

/** How the Fix button acts: open Configs > Perms (the one place that asks and grants, with ADB and the real state read back), or nothing. */
enum class HealthFix { NONE, PERMS }

data class HealthRow(val id: String, val label: String, val state: HealthState, val detail: String, val fix: HealthFix)

interface Probe {
    val sdk: Int
    val samsung: Boolean
    /** android.permission.X as declared in the merged manifest. */
    fun declared(permission: String): Boolean
    fun notificationsAllowed(): Boolean?
    fun ignoringBatteryOptimizations(): Boolean?
    fun bootReceiverEnabled(): Boolean?
    fun canScheduleExactAlarms(): Boolean?
    /** appops RUN_ANY_IN_BACKGROUND mode: "allow" / other / null when it cannot be read without a shell. */
    fun runAnyInBackground(): String?
    fun keepAliveOn(): Boolean
    fun lastRefusal(): String
}

object KeepAliveHealth {
    fun evaluate(p: Probe): List<HealthRow> {
        val rows = ArrayList<HealthRow>()
        fun yn(b: Boolean?) = when (b) { true -> HealthState.OK; false -> HealthState.MISSING; null -> HealthState.UNKNOWN }

        val fgsPerms = listOf("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE").filter { !p.declared(it) }
        rows += HealthRow("fgs", "Foreground service (specialUse)",
            if (fgsPerms.isEmpty()) HealthState.OK else HealthState.MISSING,
            if (fgsPerms.isEmpty()) "FOREGROUND_SERVICE + FOREGROUND_SERVICE_SPECIAL_USE declared (granted at install)" else "not declared: ${fgsPerms.joinToString(", ")} (reinstall a build that has them)",
            HealthFix.NONE)

        val notif = if (p.sdk < 33) true else p.notificationsAllowed()
        rows += HealthRow("notifications", "Notifications (POST_NOTIFICATIONS)", yn(notif),
            when (notif) { true -> "allowed"; false -> "off: the service still runs but its notification is hidden"; null -> "unknown" },
            if (notif == false) HealthFix.PERMS else HealthFix.NONE)

        val batt = p.ignoringBatteryOptimizations()
        rows += HealthRow("battery_opt", "Battery optimisation exempt", yn(batt),
            when (batt) { true -> "exempt from Doze (granted)"; false -> "optimised: Android may stop the service in Doze"; null -> "unknown" },
            if (batt == false) HealthFix.PERMS else HealthFix.NONE)

        val boot = p.bootReceiverEnabled()
        val bootDecl = p.declared("android.permission.RECEIVE_BOOT_COMPLETED")
        val bootState = when { !bootDecl -> HealthState.MISSING; !p.keepAliveOn() -> HealthState.NA; else -> yn(boot) }
        rows += HealthRow("boot", "Restart after reboot (RECEIVE_BOOT_COMPLETED)", bootState,
            when { !bootDecl -> "RECEIVE_BOOT_COMPLETED not declared"; !p.keepAliveOn() -> "not needed: Keep alive is OFF"; boot == true -> "boot receiver enabled"; boot == false -> "boot receiver disabled while Keep alive is ON"; else -> "unknown" },
            if (bootState == HealthState.MISSING) HealthFix.PERMS else HealthFix.NONE)

        val bg = p.runAnyInBackground()
        val bgState = when (bg) { null -> HealthState.UNKNOWN; "allow" -> HealthState.OK; "default" -> if (batt == true) HealthState.OK else HealthState.MISSING; else -> HealthState.MISSING }
        rows += HealthRow("background", "Run in background (unrestricted battery)", bgState,
            when (bg) { null -> "unknown here: read it from Configs > Perms (needs ADB) or open App battery"; "allow" -> "allowed"; "default" -> "default" + if (batt == true) " (exempt)" else " (restricted when unused)"; else -> "restricted ($bg)" },
            if (bgState == HealthState.MISSING) HealthFix.PERMS else HealthFix.NONE)

        if (p.samsung) rows += HealthRow("samsung", "Samsung: Never sleeping apps / Unrestricted", if (batt == true && bg != "ignore" && bg != "deny") HealthState.UNKNOWN else HealthState.MISSING,
            "One UI keeps its own list that no API reads: add this app under Battery > Background usage limits > Never sleeping apps",
            HealthFix.PERMS)

        if (p.declared("android.permission.SCHEDULE_EXACT_ALARM")) {
            val ex = if (p.sdk < 31) true else p.canScheduleExactAlarms()
            // The service schedules no wake and no alarm (KeepAliveService doc): declared by parity only, so never a failure here.
            rows += HealthRow("exact_alarm", "Exact alarms (SCHEDULE_EXACT_ALARM)", if (ex == true) HealthState.OK else HealthState.NA,
                if (ex == true) "allowed" else "not used by the keep-alive (declared for fleet parity)", HealthFix.NONE)
        }

        val refusal = p.lastRefusal()
        rows += HealthRow("last_start", "Last service start", if (refusal.isEmpty()) HealthState.OK else HealthState.MISSING,
            if (refusal.isEmpty()) (if (p.keepAliveOn()) "no refusal recorded" else "Keep alive is OFF: no service") else refusal,
            if (refusal.isEmpty()) HealthFix.NONE else HealthFix.PERMS)
        return rows
    }

    fun summary(rows: List<HealthRow>): String {
        val miss = rows.count { it.state == HealthState.MISSING }
        return if (miss == 0) "all OK" else "$miss missing"
    }
}

class ContextProbe(private val ctx: Context) : Probe {
    override val sdk: Int get() = Build.VERSION.SDK_INT
    override val samsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    private val requested: Set<String> by lazy {
        runCatching {
            val pi = if (Build.VERSION.SDK_INT >= 33) ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                     else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            pi.requestedPermissions?.toSet().orEmpty()
        }.getOrDefault(emptySet())
    }
    override fun declared(permission: String) = permission in requested
    override fun notificationsAllowed(): Boolean? = runCatching {
        val nm = androidx.core.app.NotificationManagerCompat.from(ctx)
        val perm = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        perm && nm.areNotificationsEnabled()
    }.getOrNull()
    override fun ignoringBatteryOptimizations(): Boolean? = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrNull()
    override fun bootReceiverEnabled(): Boolean? = runCatching {
        val st = ctx.packageManager.getComponentEnabledSetting(android.content.ComponentName(ctx, BootReceiver::class.java))
        st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }.getOrNull()
    override fun canScheduleExactAlarms(): Boolean? = runCatching {
        if (Build.VERSION.SDK_INT < 31) true else {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            opMode(ops, "android:schedule_exact_alarm").let { it == android.app.AppOpsManager.MODE_ALLOWED || it == android.app.AppOpsManager.MODE_DEFAULT }
        }
    }.getOrNull()
    @Suppress("DEPRECATION")
    private fun opMode(ops: android.app.AppOpsManager, op: String): Int =
        if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(op, android.os.Process.myUid(), ctx.packageName)
        else ops.checkOpNoThrow(op, android.os.Process.myUid(), ctx.packageName)
    override fun runAnyInBackground(): String? = runCatching {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = opMode(ops, "android:run_any_in_background")
        when (mode) { android.app.AppOpsManager.MODE_ALLOWED -> "allow"; android.app.AppOpsManager.MODE_DEFAULT -> "default"; android.app.AppOpsManager.MODE_IGNORED -> "ignore"; else -> "deny" }
    }.getOrNull()
    override fun keepAliveOn(): Boolean = PowerState.keepAliveOn(ctx)
    override fun lastRefusal(): String = KeepAliveReason.get(ctx)
}
