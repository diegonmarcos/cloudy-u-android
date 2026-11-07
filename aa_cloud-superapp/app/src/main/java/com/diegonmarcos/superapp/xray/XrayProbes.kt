package com.diegonmarcos.superapp.xray

/**
 * #1504 What Export All asks the privileged shell (uid 2000), declared as data: one row per probe. Adding a diagnosis is one row.
 * [id] keys the raw output for [XrayAnalyze]; [appKey] names the app-sandbox reader used when there is no adb (null = that probe
 * is skipped in a partial report); [cap] bounds the characters kept in the report (the parsers see the whole output).
 */
data class Probe(val id: String, val group: String, val title: String, val cmd: String, val cap: Int = 40_000, val appKey: String? = null)

object XrayProbes {

    const val G_BATTERY = "Battery"
    const val G_RUNNING = "Running state"
    const val G_DEVICE = "Device"
    const val G_LOGCAT = "Observability · LogCat"
    const val G_PERMS = "Observability · Permissions"

    private const val FLEET_PKGS = "pm list packages | grep com.diegonmarcos | cut -d: -f2"

    val all: List<Probe> = listOf(
        // ── Battery ──
        Probe("batterystats", G_BATTERY, "batterystats --charged (per uid: estimated power, wakelocks, kernel wakelocks, wakeups, jobs, syncs, radio, cpu, gps, sensors)",
            "dumpsys batterystats --charged", cap = 150_000),
        Probe("battery", G_BATTERY, "dumpsys battery (state)", "dumpsys battery", cap = 4_000, appKey = "battery"),
        Probe("batterystats-top", G_BATTERY, "Top background apps (usage stats, last day)",
            "dumpsys usagestats | grep -E 'package=.*totalTimeForegroundServiceUsed=' | head -60", cap = 12_000),
        // ── Running state ──
        Probe("services", G_RUNNING, "Running and foreground services per package (dumpsys activity services)", "dumpsys activity services", cap = 60_000),
        Probe("procstats", G_RUNNING, "Process memory over the last 3 hours (dumpsys procstats --hours 3)", "dumpsys procstats --hours 3", cap = 60_000),
        Probe("startproc", G_RUNNING, "Process start reasons (logcat ActivityManager: Start proc)",
            "logcat -d -b system,main -v time ActivityManager:I '*:S' | grep 'Start proc'", cap = 60_000),
        Probe("jobs", G_RUNNING, "JobScheduler and WorkManager jobs (dumpsys jobscheduler)",
            "dumpsys jobscheduler | grep -E 'JOB #|Pending queue|Active jobs|Registered|Standby bucket|Backoff' | head -400", cap = 40_000),
        Probe("alarm", G_RUNNING, "Alarms and alarm wakeups (dumpsys alarm)", "dumpsys alarm | head -1500", cap = 60_000),
        Probe("power", G_RUNNING, "Active wakelocks and power state (dumpsys power)",
            "dumpsys power | grep -E 'Wake Locks|WAKE_LOCK|mWakefulness|mWakeLockSummary|Suspend Blockers|Display Power|mHoldingWakeLock' | head -200", cap = 20_000),
        // ── Device ──
        Probe("thermal", G_DEVICE, "Thermal (dumpsys thermalservice)", "dumpsys thermalservice | head -150", cap = 12_000),
        Probe("netstats", G_DEVICE, "Network stats per uid (dumpsys netstats)", "dumpsys netstats --uid | head -600", cap = 40_000),
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
        // ── the other declared pages of Configs ▸ Observability ──
        Probe("logcat", G_LOGCAT, "LogCat: last 300 warnings and errors", "logcat -d -v threadtime -t 300 '*:W'", cap = 40_000),
        Probe("perms", G_PERMS, "This app's granted permissions and app-ops",
            "dumpsys package com.diegonmarcos.superapp | grep -E 'granted=true' | head -120; echo ---; cmd appops get com.diegonmarcos.superapp | head -80", cap = 20_000),
        Probe("packages", G_DEVICE, "Packages and uids (cmd package list packages -U)", "cmd package list packages -U", cap = 30_000),
    )

    /** The ids the parsers read, in the order they are run (packages first: every other table is labelled through it). */
    val order: List<Probe> = all.sortedBy { if (it.id == "packages") 0 else 1 }
}
