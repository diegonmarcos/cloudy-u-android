package com.diegonmarcos.superapp.configs

import android.content.Context

/**
 * #1682 P3 step 4b/4d: the hooks the owner side needs from the app that hosts it, so the keeper, the boot worker, the cockpit and the
 * broker page live in libs:adb-shell-core (one copy for the SuperApp today and the Cloud Webserver after the flip). The host installs
 * them at process start (SuperApp: App.onCreate), the same pattern as AdbReadiness.connector / hostOpener. Not installed = the defaults
 * below (a flag reads as undeclared, the profiler is a plain call, the plane does nothing): never a crash.
 */
object AdbOwnerHost {
    /** A flag of a declared control (build.json::ui.control_panel entry [id], key [flag]); the keeper's shipped default is data. */
    @Volatile var declaredFlag: (id: String, flag: String) -> Boolean = { _, _ -> false }

    /** Times a hot path for the host's engine profiler (SuperApp: X-Ray EngineProfiler.time). */
    @Volatile var profiler: (engine: String, path: String, block: () -> Any?) -> Any? = { _, _, block -> block() }

    /** A background wake and an error booked on the host's profiler (SuperApp: EngineProfiler.wake / error). */
    @Volatile var waker: (engine: String, source: String) -> Unit = { _, _ -> }
    @Volatile var errors: (engine: String, where: String, what: String) -> Unit = { _, _, _ -> }

    /** What the host does once the boot worker has the channel up: its own device tuning and its declared grants. */
    interface Plane {
        fun tune(ctx: Context, exec: (String) -> String?)
        fun selfGrant(ctx: Context, exec: (String) -> String?)
        /** Self-grant android.permission.DUMP through [exec] (the debug server's adb/grant-dump); null = not run. */
        fun grantDump(ctx: Context, exec: (String) -> String?): String? = null
    }
    object NoPlane : Plane {
        override fun tune(ctx: Context, exec: (String) -> String?) {}
        override fun selfGrant(ctx: Context, exec: (String) -> String?) {}
    }
    @Volatile var plane: Plane = NoPlane

    fun declaredFlag(id: String, flag: String): Boolean = runCatching { declaredFlag.invoke(id, flag) }.getOrDefault(false)

    @Suppress("UNCHECKED_CAST")
    fun <T> timed(engine: String, path: String, block: () -> T): T {
        var ran = false
        var out: Any? = null
        runCatching { profiler.invoke(engine, path) { ran = true; block().also { out = it } } }
        // a profiler that threw before running the block must not lose the work
        return if (ran) out as T else block()
    }
    fun profile(engine: String, path: String, block: () -> Unit) { timed(engine, path, block) }

    fun wake(engine: String, source: String) { runCatching { waker.invoke(engine, source) } }
    fun error(engine: String, where: String, what: String) { runCatching { errors.invoke(engine, where, what) } }
    fun tune(ctx: Context, exec: (String) -> String?) { runCatching { plane.tune(ctx, exec) } }
    fun selfGrant(ctx: Context, exec: (String) -> String?) { runCatching { plane.selfGrant(ctx, exec) } }
    fun grantDump(ctx: Context, exec: (String) -> String?): String? = runCatching { plane.grantDump(ctx, exec) }.getOrNull()
}
