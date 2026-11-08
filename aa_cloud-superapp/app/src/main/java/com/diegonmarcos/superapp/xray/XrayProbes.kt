package com.diegonmarcos.superapp.xray

/**
 * #1504 What Export All asks the privileged shell (uid 2000), declared as data: one row per probe. Adding a diagnosis is one row.
 * [id] keys the raw output for [XrayAnalyze]; [appKey] names the app-sandbox reader used when there is no adb (null = that probe
 * is skipped in a partial report); [cap] bounds the characters kept in the report (the parsers see the whole output).
 */
data class Probe(val id: String, val group: String, val title: String, val cmd: String, val cap: Int = 40_000, val appKey: String? = null)

object XrayProbes {

    // The export follows the four-tier triage: 1 PROFILE, 2 RUNTIME, 3 DIAGNOSIS by family, 4 SUSPECTS (the ranked verdict, rendered last).
    const val G_DEVICE = "① Profile · Device and config"
    const val G_RUNTIME = "② Runtime · Vitals (CPU, thermal, network)"
    const val G_BATTERY = "② Runtime · Battery"
    const val G_POWER = "③ Diagnosis · Power drain"
    const val G_JANK = "③ Diagnosis · Jank and rendering"
    const val G_NOTIF = "③ Diagnosis · Notification and IPC storm"
    const val G_MEMORY = "③ Diagnosis · Memory"
    const val G_RESP = "③ Diagnosis · Responsiveness (ANR)"
    const val G_LOGCAT = "③ Diagnosis · Observability · LogCat"
    const val G_PERMS = "③ Diagnosis · Observability · Permissions"

    private const val FLEET_PKGS = "pm list packages | grep com.diegonmarcos | cut -d: -f2"

    val all: List<Probe> = listOf(
        // ── ① PROFILE: the static machine ──
        Probe("profile", G_DEVICE, "Device profile (model, Android, build, SoC, CPU topology and governor, GPU, display)",
            "for k in ro.product.manufacturer ro.product.model ro.build.version.release ro.build.version.sdk ro.build.fingerprint ro.soc.manufacturer ro.soc.model ro.hardware ro.board.platform ro.hardware.egl ro.hardware.vulkan ro.opengles.version; do echo \"\$k=\$(getprop \$k)\"; done; " +
                "for c in /sys/devices/system/cpu/cpu[0-9]*; do echo \"\$(basename \$c) max=\$(cat \$c/cpufreq/cpuinfo_max_freq 2>/dev/null) gov=\$(cat \$c/cpufreq/scaling_governor 2>/dev/null)\"; done; wm size; wm density; uptime",
            cap = 8_000),
        Probe("packages", G_DEVICE, "Packages and uids (cmd package list packages -U)", "cmd package list packages -U", cap = 30_000),
        Probe("deviceidle", G_DEVICE, "Doze / deviceidle state and whitelist",
            "echo deep=$(dumpsys deviceidle get deep) light=$(dumpsys deviceidle get light); dumpsys deviceidle whitelist", cap = 12_000),
        Probe("buckets", G_DEVICE, "Standby buckets (am get-standby-bucket; fleet apps, then usagestats for all)",
            "for p in \$($FLEET_PKGS); do echo \"\$p \$(am get-standby-bucket \$p)\"; done; echo ---; dumpsys usagestats | grep -E 'package=.*bucket=' | head -300", cap = 30_000),
        Probe("appops", G_DEVICE, "App-ops RUN_IN_BACKGROUND (packages denied or ignored)",
            "echo ignore:; cmd appops query-op RUN_IN_BACKGROUND ignore; echo deny:; cmd appops query-op RUN_IN_BACKGROUND deny", cap = 12_000),
        Probe("listeners", G_DEVICE, "Notification listeners bound", "settings get secure enabled_notification_listeners", cap = 4_000, appKey = "secure:enabled_notification_listeners"),
        Probe("a11y", G_DEVICE, "Accessibility services bound", "settings get secure enabled_accessibility_services", cap = 4_000, appKey = "secure:enabled_accessibility_services"),
        Probe("autofill", G_DEVICE, "Autofill service", "settings get secure autofill_service", cap = 2_000, appKey = "secure:autofill_service"),
        Probe("credential", G_DEVICE, "Credential services",
            "echo credential_service=\$(settings get secure credential_service); echo primary=\$(settings get secure credential_service_primary)", cap = 2_000,
            appKey = "secure:credential_service"),
        // ── ② RUNTIME: live vitals ──
        Probe("thermal", G_RUNTIME, "Thermal (dumpsys thermalservice: status, every sensor, throttle, cooling devices)", "dumpsys thermalservice | head -300", cap = 20_000),
        Probe("top", G_RUNTIME, "Top processes by CPU (top -b -n 1, else dumpsys cpuinfo)",
            "o=\$(top -b -n 1 -m 15 -s 9 2>/dev/null | head -40); if [ -n \"\$o\" ]; then echo \"\$o\"; else dumpsys cpuinfo | head -40; fi", cap = 8_000),
        Probe("battery", G_BATTERY, "dumpsys battery (state)", "dumpsys battery", cap = 4_000, appKey = "battery"),
        Probe("netstats", G_RUNTIME, "Network stats per uid (dumpsys netstats)", "dumpsys netstats --uid | head -600", cap = 40_000),
        // ── ③ DIAGNOSIS · power drain ──
        Probe("batterystats", G_POWER, "batterystats --charged (per uid: estimated power, wakelocks, kernel wakelocks, wakeups, jobs, syncs, radio, cpu, gps, sensors)",
            "dumpsys batterystats --charged", cap = 150_000),
        Probe("batterystats-top", G_POWER, "Top background apps (usage stats, last day)",
            "dumpsys usagestats | grep -E 'package=.*totalTimeForegroundServiceUsed=' | head -60", cap = 12_000),
        Probe("services", G_POWER, "Running and foreground services per package (dumpsys activity services)", "dumpsys activity services", cap = 60_000),
        Probe("startproc", G_POWER, "Process start reasons (logcat ActivityManager: Start proc)",
            "logcat -d -b system,main -v time ActivityManager:I '*:S' | grep 'Start proc'", cap = 60_000),
        Probe("jobs", G_POWER, "JobScheduler and WorkManager jobs (dumpsys jobscheduler)",
            "dumpsys jobscheduler | grep -E 'JOB #|Pending queue|Active jobs|Registered|Standby bucket|Backoff' | head -400", cap = 40_000),
        Probe("alarm", G_POWER, "Alarms and alarm wakeups (dumpsys alarm)", "dumpsys alarm | head -1500", cap = 60_000),
        Probe("power", G_POWER, "Active wakelocks and power state (dumpsys power)",
            "dumpsys power | grep -E 'Wake Locks|WAKE_LOCK|mWakefulness|mWakeLockSummary|Suspend Blockers|Display Power|mHoldingWakeLock' | head -200", cap = 20_000),
        // ── ③ DIAGNOSIS · jank and rendering ──
        Probe("gfxinfo", G_JANK, "Frame stats of the SuperApp (dumpsys gfxinfo framestats: jank, percentiles, histogram, slow-frame causes, draw/triangle/GPU memory where printed)",
            "dumpsys gfxinfo com.diegonmarcos.superapp framestats | grep -vE '^[0-9,]+\$' | head -300", cap = 20_000),
        Probe("surfaceflinger", G_JANK, "SurfaceFlinger frame and GLES summary (missed frames, refresh rate, GLES renderer)",
            "dumpsys SurfaceFlinger | grep -iE 'GLES|missed|refresh|jank|total frames' | head -60", cap = 8_000),
        // ── ③ DIAGNOSIS · notification and IPC storm ──
        Probe("notification", G_NOTIF, "Notification post counts per app (dumpsys notification usage stats and active records)",
            "dumpsys notification | grep -E 'AggregatedStats|key=|numPostedByApp|numUpdatedByApp|numRemovedByApp|numQuotaViolations|NotificationRecord\\(' | head -3000", cap = 40_000),
        Probe("binder", G_NOTIF, "Binder call stats per uid (dumpsys binder_calls_stats; empty where the shell cannot read it)",
            "dumpsys binder_calls_stats 2>/dev/null | head -400", cap = 20_000),
        // ── ③ DIAGNOSIS · memory ──
        Probe("procstats", G_MEMORY, "Process memory over the last 3 hours (dumpsys procstats --hours 3)", "dumpsys procstats --hours 3", cap = 60_000),
        Probe("meminfo", G_MEMORY, "Memory detail (dumpsys meminfo: RAM totals, PSS per process)", "dumpsys meminfo | head -600", cap = 40_000),
        // ── ③ DIAGNOSIS · responsiveness ──
        Probe("anr", G_RESP, "Recent ANRs (events log am_anr, and /data/anr trace files where readable)",
            "logcat -d -b events -v threadtime -t 3000 2>/dev/null | grep am_anr | tail -20; echo ---; ls -l /data/anr 2>&1 | tail -20", cap = 8_000),
        // ── the other declared pages of Configs ▸ Observability ──
        Probe("logcat", G_LOGCAT, "LogCat: last 300 warnings and errors", "logcat -d -v threadtime -t 300 '*:W'", cap = 40_000),
        Probe("perms", G_PERMS, "This app's granted permissions and app-ops",
            "dumpsys package com.diegonmarcos.superapp | grep -E 'granted=true' | head -120; echo ---; cmd appops get com.diegonmarcos.superapp | head -80", cap = 20_000),
    )

    /** The ids the parsers read, in the order they are run (packages first: every other table is labelled through it). */
    val order: List<Probe> = all.sortedBy { if (it.id == "packages") 0 else 1 }
}
