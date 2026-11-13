package com.diegonmarcos.superapp.configs

/**
 * #1682 P3 step 4b/4d: the hooks the owner side needs from the app that hosts it, so the keeper, the cockpit and the broker page
 * live in libs:adb-shell-core (one copy for the SuperApp today and the Cloud Webserver after the flip). The host installs them at
 * process start (SuperApp: App.onCreate), the same pattern as AdbReadiness.connector / hostOpener. Not installed = the defaults
 * below (a flag reads as undeclared, the profiler is a plain call): never a crash.
 */
object AdbOwnerHost {
    /** A flag of a declared control (build.json::ui.control_panel entry [id], key [flag]); the keeper's shipped default is data. */
    @Volatile var declaredFlag: (id: String, flag: String) -> Boolean = { _, _ -> false }

    /** Times a hot path for the host's engine profiler (SuperApp: X-Ray EngineProfiler.time). */
    @Volatile var profiler: (engine: String, path: String, block: () -> Unit) -> Unit = { _, _, block -> block() }

    fun declaredFlag(id: String, flag: String): Boolean = runCatching { declaredFlag.invoke(id, flag) }.getOrDefault(false)
    fun profile(engine: String, path: String, block: () -> Unit) { profiler.invoke(engine, path, block) }
}
