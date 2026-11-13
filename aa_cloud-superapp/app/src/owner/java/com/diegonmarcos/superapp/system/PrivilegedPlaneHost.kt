package com.diegonmarcos.superapp.system

import android.content.Context
import com.diegonmarcos.superapp.configs.AdbOwnerHost

/**
 * #1682 P3 step 4d: the SuperApp's half of the privileged plane when it is the OWNER. The boot worker that arms the channel lives in
 * libs:adb-shell-core (PrivilegedPlaneWorker); what it does once the channel is up is [PrivilegedPlaneRun] (main), installed as
 * AdbOwnerHost.plane by the owner AdbSide. [exec] is the channel the worker armed.
 */
object PrivilegedPlaneHost : AdbOwnerHost.Plane {
    override fun tune(ctx: Context, exec: (String) -> String?) { PrivilegedPlaneRun.tune(ctx, exec) }
    override fun selfGrant(ctx: Context, exec: (String) -> String?) = PrivilegedPlaneRun.selfGrant(ctx, exec)
    override fun grantDump(ctx: Context, exec: (String) -> String?): String? = PrivilegedPlaneRun.grantDump(ctx, exec)
}
