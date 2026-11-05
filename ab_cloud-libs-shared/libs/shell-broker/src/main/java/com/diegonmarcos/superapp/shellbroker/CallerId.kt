package com.diegonmarcos.superapp.shellbroker

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process
import com.diegonmarcos.superapp.shellclient.ShellWire

/** #1510 Who is calling: the package behind the BINDER uid, never a string the client typed. */
object CallerId {
    /** The signature permission (the fleet key) is held, or the call is this very process. */
    fun permitted(ctx: Context, uid: Int = Binder.getCallingUid()): Boolean =
        uid == Process.myUid() || ctx.checkCallingPermission(ShellWire.PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * The calling package: [claimed] (the provider's callingPackage / the app's own name) when it really belongs to [uid];
     * else the first package of a shared uid (cld.termux.shared is one). Null = unknown, which the allowlist denies.
     */
    fun of(ctx: Context, uid: Int, claimed: String?): String? {
        val pkgs = ctx.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        return if (claimed != null && claimed in pkgs) claimed else pkgs.firstOrNull()
    }
}
