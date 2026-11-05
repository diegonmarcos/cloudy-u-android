package com.diegonmarcos.clouddrive.mounts

import com.diegonmarcos.superapp.perms.PermsPages
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

/**
 * What a mount needs from the system to LAST, and the one-tap fix for each gap. The decision is pure
 * ([missing]) so it is unit-tested; [facts] and [oemGuideIntent] are the thin Android half.
 */
object MountPermissions {
    enum class Need { NOTIFICATIONS, BATTERY_OPTIMIZATION, OEM_BATTERY_GUIDE }

    data class Facts(
        val sdk: Int,
        val notificationsGranted: Boolean,
        val ignoringBatteryOptimizations: Boolean,
        val manufacturer: String,
        /** The user confirmed the OEM guide was followed; the OS gives no API to read it. */
        val oemGuideConfirmed: Boolean,
    )

    /** The gaps, in the order the user should fix them. */
    fun missing(f: Facts, policy: MountPolicy): List<Need> = buildList {
        if (f.sdk >= 33 && !f.notificationsGranted) add(Need.NOTIFICATIONS)
        if (!f.ignoringBatteryOptimizations) add(Need.BATTERY_OPTIMIZATION)
        if (policy.guideFor(f.manufacturer) != null && !f.oemGuideConfirmed) add(Need.OEM_BATTERY_GUIDE)
    }

    private const val PREFS = "mount-keeper"
    private const val OEM_CONFIRMED = "oem_guide_confirmed"

    fun facts(ctx: Context): Facts {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val notif = Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return Facts(
            sdk = Build.VERSION.SDK_INT,
            notificationsGranted = notif,
            ignoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(ctx.packageName),
            manufacturer = Build.MANUFACTURER ?: "",
            oemGuideConfirmed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(OEM_CONFIRMED, false),
        )
    }

    fun confirmOemGuide(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(OEM_CONFIRMED, true).apply()

    /** The catalogue ids of the Perms rows that fix [need] (#1515: notifications and the battery exemption are granted in Configs > Perms). */
    fun permsIds(need: Need): List<String> = when (need) {
        Need.NOTIFICATIONS -> listOf("notifications")
        Need.BATTERY_OPTIMIZATION -> listOf("battery_opt")
        Need.OEM_BATTERY_GUIDE -> emptyList()
    }

    /** The OEM's own battery screen when the guide names one and it resolves, else this app's App info. */
    fun oemGuideIntent(ctx: Context, policy: MountPolicy): Intent {
        val guide = policy.guideFor(Build.MANUFACTURER ?: "")
        val cn = guide?.intentComponent?.takeIf { it.contains('/') }?.let { ComponentName.unflattenFromString(it) }
        val oem = cn?.let { Intent().setComponent(it) }
        return (if (oem != null && oem.resolveActivity(ctx.packageManager) != null) oem else PermsPages.appInfoIntent(ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
