package com.diegonmarcos.superapp.xray

/**
 * #1504 What Export All asks the privileged shell (uid 2000), declared as data: one row per probe. Adding a diagnosis is one row.
 * [id] keys the raw output for [XrayAnalyze]; [appKey] names the app-sandbox reader used when there is no adb (null = that probe
 * is skipped in a partial report); [cap] bounds the characters kept in the report (the parsers see the whole output).
 */
data class Probe(val id: String, val group: String, val title: String, val cmd: String, val cap: Int = 40_000, val appKey: String? = null)

object XrayProbes {

    // The export has TWO macro buckets: ① DEVICE PROFILE SCAN (the static snapshot, read once) then ② PROFILING TRACKERS (twelve named
    // trackers, 01..12) and the 13th, the SUSPECTS verdict. Adding a future tracker = one group constant + one [Tracker] row + its probes.
    const val G_DEVICE = "① Device Profile Scan"
    const val G_PERF = "② Tracker 01 · Performance"
    const val G_RENDER = "② Tracker 02 · Render & GPU"
    const val G_STORAGE = "② Tracker 03 · Storage"
    const val G_BATTERY = "② Tracker 04 · Battery & Power"
    const val G_THERMAL = "② Tracker 05 · Thermal"
    const val G_MEMORY = "② Tracker 06 · Memory"
    const val G_NOTIF = "② Tracker 07 · Notification & IPC"
    const val G_RESP = "② Tracker 08 · Responsiveness"
    const val G_CRASH = "② Tracker 09 · Crash"
    const val G_NETWORK = "② Tracker 10 · Network"
    const val G_PROCESS = "② Tracker 11 · Process & Lifecycle"
    const val G_SECURITY = "② Tracker 12 · Security & Perms"
    const val G_SUSPECTS = "② Tracker 13 · Suspects"
    /** The Observability pages Logcat and Permissions are covered by the Crash and Security trackers. */
    const val G_LOGCAT = G_CRASH
    const val G_PERMS = G_SECURITY

    /** One named tracker: its number, short label and export group. The macro order of the export and the dropdown. */
    data class Tracker(val num: Int, val label: String, val group: String)

    val trackers: List<Tracker> = listOf(
        Tracker(1, "Performance", G_PERF), Tracker(2, "Render & GPU", G_RENDER), Tracker(3, "Storage", G_STORAGE),
        Tracker(4, "Battery & Power", G_BATTERY), Tracker(5, "Thermal", G_THERMAL), Tracker(6, "Memory", G_MEMORY),
        Tracker(7, "Notification & IPC", G_NOTIF), Tracker(8, "Responsiveness", G_RESP), Tracker(9, "Crash", G_CRASH),
        Tracker(10, "Network", G_NETWORK), Tracker(11, "Process & Lifecycle", G_PROCESS), Tracker(12, "Security & Perms", G_SECURITY),
        Tracker(13, "Suspects", G_SUSPECTS),
    )

    private const val FLEET_PKGS = "pm list packages | grep com.diegonmarcos | cut -d: -f2"

    val all: List<Probe> = listOf(
        // ── ① DEVICE PROFILE SCAN: the static machine, read once ──
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
        // ── ② 01 PERFORMANCE ──
        Probe("top", G_PERF, "Top processes by CPU (top -b -n 1, else dumpsys cpuinfo)",
            "o=\$(top -b -n 1 -m 15 -s 9 2>/dev/null | head -40); if [ -n \"\$o\" ]; then echo \"\$o\"; else dumpsys cpuinfo | head -40; fi", cap = 8_000),
        // ── ② 02 RENDER & GPU ──
        Probe("gfxinfo", G_RENDER, "Frame stats of the SuperApp (dumpsys gfxinfo framestats: jank, percentiles, histogram, slow-frame causes, draw/triangle/GPU memory where printed)",
            "dumpsys gfxinfo com.diegonmarcos.superapp framestats | grep -vE '^[0-9,]+\$' | head -300", cap = 20_000),
        Probe("surfaceflinger", G_RENDER, "SurfaceFlinger frame and GLES summary (missed frames, refresh rate, GLES renderer)",
            "dumpsys SurfaceFlinger | grep -iE 'GLES|missed|refresh|jank|total frames' | head -60", cap = 8_000),
        // ── ② 03 STORAGE (new) ──
        Probe("df", G_STORAGE, "Disk used and free per mount (df -h)", "df -h 2>&1 | head -40", cap = 8_000),
        Probe("diskstats", G_STORAGE, "Disk stats: free space, media and cache sizes, per-app sizes (dumpsys diskstats)", "dumpsys diskstats | head -120", cap = 30_000),
        // ── ② 04 BATTERY & POWER ──
        Probe("battery", G_BATTERY, "dumpsys battery (state)", "dumpsys battery", cap = 4_000, appKey = "battery"),
        Probe("batterystats", G_BATTERY, "batterystats --charged (per uid: estimated power, wakelocks, kernel wakelocks, wakeups, jobs, syncs, radio, cpu, gps, sensors)",
            "dumpsys batterystats --charged", cap = 150_000),
        Probe("batterystats-top", G_BATTERY, "Top background apps (usage stats, last day)",
            "dumpsys usagestats | grep -E 'package=.*totalTimeForegroundServiceUsed=' | head -60", cap = 12_000),
        Probe("jobs", G_BATTERY, "JobScheduler and WorkManager jobs (dumpsys jobscheduler)",
            "dumpsys jobscheduler | grep -E 'JOB #|Pending queue|Active jobs|Registered|Standby bucket|Backoff' | head -400", cap = 40_000),
        Probe("alarm", G_BATTERY, "Alarms and alarm wakeups (dumpsys alarm)", "dumpsys alarm | head -1500", cap = 60_000),
        Probe("power", G_BATTERY, "Active wakelocks and power state (dumpsys power)",
            "dumpsys power | grep -E 'Wake Locks|WAKE_LOCK|mWakefulness|mWakeLockSummary|Suspend Blockers|Display Power|mHoldingWakeLock' | head -200", cap = 20_000),
        // ── ② 05 THERMAL ──
        Probe("thermal", G_THERMAL, "Thermal (dumpsys thermalservice: status, every sensor, throttle, cooling devices)", "dumpsys thermalservice | head -300", cap = 20_000),
        // ── ② 06 MEMORY ──
        Probe("procstats", G_MEMORY, "Process memory over the last 3 hours (dumpsys procstats --hours 3)", "dumpsys procstats --hours 3", cap = 60_000),
        Probe("meminfo", G_MEMORY, "Memory detail (dumpsys meminfo: RAM totals, PSS per process)", "dumpsys meminfo | head -600", cap = 40_000),
        // ── ② 07 NOTIFICATION & IPC ──
        Probe("notification", G_NOTIF, "Notification post counts per app (dumpsys notification usage stats and active records)",
            "dumpsys notification | grep -E 'AggregatedStats|key=|numPostedByApp|numUpdatedByApp|numRemovedByApp|numQuotaViolations|NotificationRecord\\(' | head -3000", cap = 40_000),
        Probe("binder", G_NOTIF, "Binder call stats per uid (dumpsys binder_calls_stats; empty where the shell cannot read it)",
            "dumpsys binder_calls_stats 2>/dev/null | head -400", cap = 20_000),
        // ── ② 08 RESPONSIVENESS ──
        Probe("anr", G_RESP, "Recent ANRs (events log am_anr, and /data/anr trace files where readable)",
            "logcat -d -b events -v threadtime -t 3000 2>/dev/null | grep am_anr | tail -20; echo ---; ls -l /data/anr 2>&1 | tail -20", cap = 8_000),
        // ── ② 09 CRASH (dropbox is new; LogCat is the Observability page) ──
        Probe("dropbox", G_CRASH, "Recent crashes and ANRs (dumpsys dropbox --print: system_app_crash, data_app_crash, *_anr)",
            "dumpsys dropbox --print 2>/dev/null | grep -E '^(Process|Package|Subject): |^[0-9]{4}-[0-9]{2}-[0-9]{2} .*(crash|anr)' | head -300", cap = 20_000),
        Probe("logcat", G_CRASH, "LogCat: last 300 warnings and errors", "logcat -d -v threadtime -t 300 '*:W'", cap = 40_000),
        // ── ② 10 NETWORK ──
        Probe("netstats", G_NETWORK, "Network stats per uid (dumpsys netstats)", "dumpsys netstats --uid | head -600", cap = 40_000),
        Probe("connectivity", G_NETWORK, "Connectivity (dumpsys connectivity: default network, transports, validation)", "dumpsys connectivity | head -200", cap = 20_000),
        Probe("telephony", G_NETWORK, "Radio and signal (dumpsys telephony.registry)",
            "dumpsys telephony.registry | grep -E 'mSignalStrength|mServiceState|mDataConnectionState|mCallState|mNetworkType' | head -40", cap = 8_000),
        // ── ② 11 PROCESS & LIFECYCLE ──
        Probe("services", G_PROCESS, "Running and foreground services per package (dumpsys activity services)", "dumpsys activity services", cap = 60_000),
        Probe("startproc", G_PROCESS, "Process start reasons (logcat ActivityManager: Start proc)",
            "logcat -d -b system,main -v time ActivityManager:I '*:S' | grep 'Start proc'", cap = 60_000),
        Probe("procs", G_PROCESS, "Processes by importance (dumpsys activity processes: LRU list, oom-adj)", "dumpsys activity processes | head -200", cap = 30_000),
        // ── ② 12 SECURITY & PERMS ──
        Probe("perms", G_SECURITY, "This app's granted permissions and app-ops",
            "dumpsys package com.diegonmarcos.superapp | grep -E 'granted=true' | head -120; echo ---; cmd appops get com.diegonmarcos.superapp | head -80", cap = 20_000),
        Probe("pkgperms", G_SECURITY, "This app's requested, install and runtime permissions (dumpsys package)",
            "dumpsys package com.diegonmarcos.superapp | grep -E -A70 'requested permissions:|install permissions:|runtime permissions:' | head -260", cap = 20_000),
        // ── ② 13 SUSPECTS: the cross-tracker ranked verdict is computed (XrayAnalyze.suspects), not probed ──
    )

    /** The ids the parsers read, in the order they are run (packages first: every other table is labelled through it). */
    val order: List<Probe> = all.sortedBy { if (it.id == "packages") 0 else 1 }
}
