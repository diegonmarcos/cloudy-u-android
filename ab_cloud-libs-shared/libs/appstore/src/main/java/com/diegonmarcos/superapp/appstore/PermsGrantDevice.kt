package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.perms.PermsGrant
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.diegonmarcos.superapp.adbdebug.ControlStatus
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.updater.Fleet

/** The device side of [PermsGrant]: what PackageManager says, and the live privileged channel. */
object PermsGrantDevice {

    private const val TAG = "PermsGrant"

    /** The channel's last measured state (the W Debugging button's own probe). */
    fun channelUp(ctx: Context): Boolean = StoreStatus.channel(ctx)?.shellUsable == true

    /** Runs commands through the channel the owner's CURRENT mode selects ([ShellChannels.active]). */
    fun channel(ctx: Context): PermsGrant.GrantChannel {
        val app = ctx.applicationContext
        return object : PermsGrant.GrantChannel {
            override fun up() = channelUp(app)
            override fun exec(command: String): String? = ShellChannels.active(app)?.exec(app, command)
        }
    }

    fun kindOf(ctx: Context): (String) -> PermsGrant.Kind {
        val pm = ctx.packageManager
        return { perm ->
            PermsGrant.kind(perm, runCatching { pm.getPermissionInfo(perm, 0).protectionLevel and 0xF }.getOrDefault(-1))
        }
    }

    @Suppress("DEPRECATION")
    private fun ours(ctx: Context, pkg: String) = runCatching {
        ctx.packageManager.checkSignatures(ctx.packageName, pkg) == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)

    private fun held(ctx: Context, pkg: String, perm: String, kind: PermsGrant.Kind): Boolean {
        val pm = ctx.packageManager
        val op = PermsGrant.appOp(perm)
        if (kind == PermsGrant.Kind.APPOP && op != null) runCatching {
            val uid = pm.getApplicationInfo(pkg, 0).uid
            val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29) aom.unsafeCheckOpNoThrow("android:" + op.lowercase(), uid, pkg)
                else aom.checkOpNoThrow("android:" + op.lowercase(), uid, pkg)
            return mode == AppOpsManager.MODE_ALLOWED
        }
        return pm.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED
    }

    /** Every fleet app that is installed, as [PermsGrant.App]; third-party apps are not in the fleet. */
    fun read(ctx: Context, fleet: List<Fleet.App>): List<PermsGrant.App> {
        val kind = kindOf(ctx)
        return fleet.filter { it.pkg != ctx.packageName }.mapNotNull { app ->
            val pkg = Fleet.installedId(ctx, app) ?: return@mapNotNull null
            val declared = runCatching {
                ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
            }.getOrNull().orEmpty().filter { it.startsWith("android.permission.") }
            PermsGrant.App(pkg, app.label, ours(ctx, pkg), declared,
                declared.filter { held(ctx, pkg, it, kind(it)) }.toSet())
        }
    }

    fun log(line: String) { Log.i(TAG, line) }
}
