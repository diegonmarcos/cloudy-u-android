package com.diegonmarcos.cloudwebserver

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * #1724 the one place that asks for the keep-alive service (MainActivity.syncKeepAlive, BootReceiver, the Rust core's sync hook).
 * [KeepAliveFlow.decideStart] says whether a start request may be made; a refusal by Android (ForegroundServiceStartNotAllowedException,
 * SecurityException) is caught and kept as the reason Home shows, never thrown. Nothing here can crash the app.
 */
object KeepAliveStarter {
    private const val TAG = "C3KeepAlive"

    /** True when the process is visible or runs a foreground service (the states Android 12+ allows a foreground start from). */
    fun appForeground(ctx: Context): Boolean = runCatching {
        val st = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(st)
        st.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }.getOrDefault(false)

    /** Start (or stop, when keep-alive is off) the service. Returns the reason it was not started, or null. */
    fun sync(ctx: Context, origin: KeepAliveFlow.Origin): String? = runCatching {
        val allowed = PowerState.serviceAllowed(ctx)
        val svc = Intent(ctx, KeepAliveService::class.java)
        if (!allowed) {
            ctx.stopService(svc)
            KeepAliveReason.clear(ctx)
            return@runCatching null
        }
        val d = KeepAliveFlow.decideStart(allowed, Build.VERSION.SDK_INT, appForeground(ctx), origin)
        if (!d.start) { KeepAliveReason.set(ctx, d.reason.orEmpty()); Log.w(TAG, d.reason.orEmpty()); return@runCatching d.reason }
        try {
            ctx.startForegroundService(svc)
            null
        } catch (t: Throwable) {
            val r = KeepAliveFlow.reasonOf(t)
            KeepAliveReason.set(ctx, r); Log.w(TAG, "startForegroundService refused: $r")
            r
        }
    }.getOrElse { "keep-alive sync failed: ${it.javaClass.simpleName}" }
}
