package com.diegonmarcos.superapp.xray

import java.util.Locale

/**
 * #1649 Configs ▸ X-Ray App is the SuperApp's own debugger / profiler, organised by the app's ENGINES. This is the catalogue:
 * every engine of the SuperApp (the app's own packages under com.diegonmarcos.superapp plus the shared libs build.json::modules
 * links, each owned by exactly one engine; a unit test holds the list to the source tree and to the module graph), and for each
 * engine its TARGETS in four kinds: Performance, Battery, Bugs / health, Debug.
 *
 * A target names a real measurement ([Target.metric]) and the limit it must hold. [report] reads the measurements out of
 * [Inputs] (the [EngineProfiler] recorder, the battery lib's EnergyLedger, the process CPU clock, the battery Source of Truth,
 * the crash reports on disk, each engine's own live state) and says PASS / FAIL per target. A measurement that does not exist
 * yet (the path never ran, the reading needs a grant) reads "no sample yet": it is never a guess and never a pass.
 *
 * Pure: no Android, so the catalogue, the maths and the verdicts are covered by a plain JVM test.
 */
object EngineProfiles {

    enum class Kind(val title: String) { PERFORMANCE("Performance"), BATTERY("Battery"), HEALTH("Bugs / health"), DEBUG("Debug") }

    enum class Verdict(val word: String) { PASS("PASS"), FAIL("FAIL"), NO_DATA("no sample yet"), INFO("") }

    /**
     * One target. [metric] is what is measured:
     *  `p95:<path>` the 95th percentile wall time of a hot path (ms) · `over:<path>:<ms>` share of its runs slower than <ms> (%) ·
     *  `calls:<path>` its run count · `slow` share of all the engine's runs over their own budget (%) ·
     *  `cpu.ms` CPU time in the engine's profiled paths · `cpu.share` that as a share of the process CPU (%) ·
     *  `cpu.bg` CPU spent while the app was in the background, per background hour (ms/h) · `wake` wakeups per hour ·
     *  `bytes` network / IO bytes moved · `drain` the engine's part of the app's measured mAh (CPU share x batterystats mAh) ·
     *  `crashes` crash reports whose root frame is the engine's code (last [CRASH_DAYS] days) · `errors` failures since process
     *  start · `state:<key>` a live value of the engine itself.
     * [max] / [min] are the limit (neither = a context row with no verdict). [source] says where the number comes from.
     */
    data class Target(
        val id: String, val kind: Kind, val label: String, val metric: String, val unit: String = "",
        val max: Double? = null, val min: Double? = null, val source: String = "",
    ) {
        val limit: String get() = when {
            max != null -> "≤ ${num(max)}$unit"
            min != null -> "≥ ${num(min)}$unit"
            else -> ""
        }
    }

    /** A hot path of an engine: where it is in the code, and the p95 wall-time budget it must hold. */
    data class HotPath(val id: String, val label: String, val p95Ms: Double, val where: String)

    data class Engine(
        val id: String, val title: String, val what: String,
        /** The app's own source directories under com/diegonmarcos/superapp/ this engine owns. */
        val appDirs: List<String>,
        /** The shared libs (build.json::modules ids) this engine owns. */
        val libs: List<String>,
        /** Kotlin package prefixes of the engine's code: a crash whose root frame is inside one is this engine's. */
        val packages: List<String>,
        /** EnergyLedger tag prefixes that belong to this engine besides its own `id.` (the tags that existed before #1649). */
        val ledger: List<String>,
        val hotPaths: List<HotPath>,
        val targets: List<Target>,
    ) {
        fun targets(kind: Kind): List<Target> = targets.filter { it.kind == kind }
    }

    const val CRASH_DAYS = 7
    private const val APP = "com.diegonmarcos.superapp"
    private const val PAGE_WHERE = "fragment attached -> started, booked by the fragment's package (XrayLifecycleProbe)"
    private const val ACTIVITY_WHERE = "activity onCreate, Android 10+, booked by the activity's package (XrayLifecycleProbe)"

    // ── the catalogue ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun engine(
        id: String, title: String, what: String, appDirs: List<String>, libs: List<String>, libPackages: List<String> = emptyList(),
        ledger: List<String> = emptyList(), hotPaths: List<HotPath>,
        cpuSharePct: Double, bgCpuMsPerH: Double, wakesPerH: Double? = null, wakeSource: String = "", maxErrors: Double = 0.0,
        extra: List<Target> = emptyList(),
    ): Engine {
        val t = ArrayList<Target>()
        for (h in hotPaths) t += Target("p95.${h.id}", Kind.PERFORMANCE, "${h.label} p95", "p95:${h.id}", " ms", max = h.p95Ms, source = h.where)
        t += Target("slow", Kind.PERFORMANCE, "Runs over budget", "slow", " %", max = 5.0, source = "the last ${EngineProfiler.RING} runs of each path above")
        t += extra.filter { it.kind == Kind.PERFORMANCE }
        t += Target("cpu.share", Kind.BATTERY, "CPU share", "cpu.share", " %", max = cpuSharePct, source = "thread CPU in the profiled paths / process CPU")
        t += Target("cpu.bg", Kind.BATTERY, "Background CPU", "cpu.bg", " ms/h", max = bgCpuMsPerH, source = "thread CPU while no activity is started, per background hour")
        if (wakesPerH != null) t += Target("wake", Kind.BATTERY, "Wakeups", "wake", " /h", max = wakesPerH, source = wakeSource)
        t += Target("cpu.ms", Kind.BATTERY, "CPU time", "cpu.ms", " ms", source = "thread CPU in the profiled paths since process start")
        t += Target("drain", Kind.BATTERY, "Drain part", "drain", " mAh", source = "CPU share x this app's mAh in the open battery cycle (batterystats, battery SoT)")
        t += extra.filter { it.kind == Kind.BATTERY }
        t += Target("crashes", Kind.HEALTH, "Crashes ${CRASH_DAYS}d", "crashes", "", max = 0.0, source = "crash reports whose root frame is in this engine's packages")
        t += Target("errors", Kind.HEALTH, "Errors", "errors", "", max = maxErrors, source = "throws out of the profiled paths and handled failures, since process start")
        t += extra.filter { it.kind == Kind.HEALTH }
        t += extra.filter { it.kind == Kind.DEBUG }
        return Engine(id, title, what, appDirs, libs, appDirs.map { "$APP.$it" } + libPackages, ledger, hotPaths, t)
    }

    private fun state(kind: Kind, key: String, label: String, unit: String = "", max: Double? = null, min: Double? = null, source: String) =
        Target("state.$key", kind, label, "state:$key", unit, max, min, source)

    val ENGINES: List<Engine> = listOf(
        engine("notify", "Notify", "the notification engine: the one notify() door, the phone-notification intake, the badges / data widgets, alerts",
            appDirs = listOf("notificationcenter", "daytimelines"), libs = listOf("libs:decisions-link", "libs:kde-connect", "libs:ai-usage", "libs:fleet-notify"),
            libPackages = listOf("$APP.decisions.link", "$APP.kdeconnect"),
            hotPaths = listOf(
                HotPath("post", "notify() door", 8.0, "NotifyGate.post"),
                HotPath("intake", "Phone intake", 12.0, "PhoneNotificationListenerService.record"),
                HotPath("alerts", "Alerts redraw", 60.0, "AlertsNotifier.refresh"),
            ),
            cpuSharePct = 5.0, bgCpuMsPerH = 3_000.0, wakesPerH = 30.0, wakeSource = "DataWidgetWakeReceiver broadcasts and data-widget alarm schedules",
            extra = listOf(
                state(Kind.BATTERY, "sent_per_h", "notify() sent", " /h", max = 120.0, source = "NotifyGate.notifyCount (posts that really reached NotificationManager) per process hour"),
                state(Kind.HEALTH, "badges_dead", "Dead badges", max = 0.0, source = "BadgeServices.statuses: switched on, grants held, card not in the shade"),
                state(Kind.DEBUG, "listener", "Listener grant", min = 1.0, source = "notification access granted to this app (1 = yes)"),
                state(Kind.DEBUG, "badges_live", "Live badges", source = "BadgeServices.statuses"),
                state(Kind.DEBUG, "badges_blocked", "Blocked badges", source = "BadgeServices.statuses: a declared grant is missing"),
                state(Kind.DEBUG, "history", "History rows", source = "PhoneNotificationStore"),
                state(Kind.DEBUG, "alerts", "Alerts shown", source = "AlertStore.visible"),
            )),
        engine("ui", "UI", "the frame pipeline of every window, the shell chrome and effects, the icon cache, the floating nav and screensaver overlays",
            appDirs = listOf("ui", "floatingnav", "shell", "light"),
            libs = listOf("libs:ui-kit", "libs:ui-icons", "libs:bottomnav", "libs:ui-nav-bars", "libs:theme-background", "libs:light", "libs:web-core", "libs:text-tools"),
            libPackages = listOf("$APP.uikit", "$APP.uiicons", "$APP.bottomnav", "$APP.texttools", "com.diegonmarcos.cloudlib.web"),
            hotPaths = listOf(
                HotPath("frame", "Frame", 32.0, "FrameMetrics TOTAL_DURATION of every resumed activity's window (XrayLifecycleProbe)"),
                HotPath("icon", "Icon load", 40.0, "AppIconCache.load (first sight of a package)"),
            ),
            cpuSharePct = 25.0, bgCpuMsPerH = 500.0,
            extra = listOf(
                Target("jank", Kind.PERFORMANCE, "Janky frames", "over:frame:16.7", " %", max = 10.0, source = "frames slower than one 60 Hz vsync"),
                Target("frozen", Kind.PERFORMANCE, "Frozen frames", "over:frame:700", " %", max = 0.0, source = "frames slower than 700 ms"),
                Target("frames", Kind.DEBUG, "Frames drawn", "calls:frame", source = "FrameMetrics callbacks since process start"),
                state(Kind.DEBUG, "fps", "FPS now", source = "FpsMeter (home window, 0 when idle)"),
                state(Kind.DEBUG, "icons", "Icons cached", source = "AppIconCache"),
            )),
        engine("launcher", "Launcher", "the home shell: sections and pages, tiles, phone apps and folders, boards, recents, one-hand and zoomies",
            appDirs = listOf("launcher", "apps", "boards", "recentapps"),
            libs = listOf("libs:launcher-apptabs", "libs:launcher-onehand", "libs:launcher-zoomies"),
            libPackages = listOf("$APP.apptabs", "$APP.onehand", "$APP.zoomies"),
            hotPaths = listOf(
                HotPath("page", "Page open", 150.0, PAGE_WHERE),
                HotPath("activity", "Activity create", 900.0, ACTIVITY_WHERE),
            ),
            cpuSharePct = 20.0, bgCpuMsPerH = 500.0,
            extra = listOf(
                state(Kind.DEBUG, "sections", "Sections", source = "Sections.all (build.json::ui.sections)"),
                state(Kind.DEBUG, "pages", "Pages", source = "Sections.all, hidden pages included"),
            )),
        engine("store", "Store", "the app store and its installs: external installs, the updater, the shared store, USB side-load",
            appDirs = listOf("extinstall"), libs = listOf("libs:appstore", "libs:updater", "libs:shared-store", "libs:usb-drive"),
            libPackages = listOf("$APP.appstore", "$APP.updater", "$APP.usbdrive", "com.diegonmarcos.cloudlib.sharedstore"),
            hotPaths = listOf(HotPath("installs", "Install re-check", 80.0, "ExtAppInstalls.refresh"), HotPath("page", "Page open", 250.0, PAGE_WHERE)),
            cpuSharePct = 5.0, bgCpuMsPerH = 1_000.0,
            extra = listOf(
                state(Kind.DEBUG, "tracked", "Tracked installs", source = "ExtAppInstalls.tracker"),
                state(Kind.DEBUG, "active", "Installs running", source = "ExtAppInstalls.tracker: phase active"),
            )),
        engine("media", "Media", "now-playing: the media session monitor and the music island",
            appDirs = listOf("media"), libs = emptyList(), ledger = listOf("music."),
            hotPaths = listOf(HotPath("session", "Session evaluate", 10.0, "NowPlayingMonitor.evaluateAndDispatch")),
            cpuSharePct = 3.0, bgCpuMsPerH = 1_500.0,
            extra = listOf(Target("evals", Kind.DEBUG, "Evaluations", "calls:session", source = "media session callbacks handled since process start"))),
        engine("bgpolicy", "Background policy", "background policy and battery: per-app policy, Super Saving, Battery Hunger, the battery Source of Truth",
            appDirs = listOf("bgpolicy", "batterystats"), libs = listOf("libs:battery"), libPackages = listOf("$APP.battery"), ledger = listOf("bg."),
            hotPaths = listOf(HotPath("shell", "Policy shell call", 2_500.0, "BgEngine.run"), HotPath("page", "Page open", 250.0, PAGE_WHERE)),
            cpuSharePct = 3.0, bgCpuMsPerH = 1_000.0, wakesPerH = 8.0, wakeSource = "BatterySessionWorker ticks and the EnergyWatchdog sampler (EnergyLedger bg.*)",
            extra = listOf(
                state(Kind.DEBUG, "supersaving", "Super Saving", source = "SuperSavingRuntime.active (1 = on)"),
                state(Kind.DEBUG, "cycles", "Battery cycles", source = "BatteryRepository.cycles (the SoT db)"),
                state(Kind.DEBUG, "app_mah", "App mAh, cycle", " mAh", source = "this app's uid in the open battery cycle (batterystats)"),
            )),
        engine("shell", "Shell / ADB broker", "the privileged shell: the broker and its allowlist, the client, the self-contained ADB channel, runtime grants",
            appDirs = emptyList(), // #1682 side-aware: the owner libs are in this process only while the SuperApp is the ADB owner (AdbSide.OWNER); a client links the rest
            libs = listOf("libs:shell-client", "libs:adb-readiness", "libs:adb-grants", "libs:perms") +
                (if (com.diegonmarcos.superapp.adbside.AdbSide.OWNER) listOf("libs:shell-broker", "libs:shizuku-adb-debug-tools", "libs:adb-shell-core") else emptyList()),
            libPackages = listOf("$APP.shellbroker", "$APP.shellclient", "$APP.adbdebug", "$APP.perms"),
            hotPaths = listOf(
                HotPath("exec", "Broker exec", 3_000.0, "ShellBroker audit ring (every call, its real duration)"),
                HotPath("activity", "Activity create", 400.0, ACTIVITY_WHERE),
            ),
            cpuSharePct = 3.0, bgCpuMsPerH = 500.0,
            extra = listOf(
                state(Kind.HEALTH, "failed", "Failed execs", max = 0.0, source = "audit ring: outcome timeout / legacy-fail"),
                state(Kind.HEALTH, "nonzero", "Non-zero exits", source = "audit ring: outcome exit"),
                state(Kind.DEBUG, "armed", "Channel armed", min = 1.0, source = "ShellBroker.status: a live round-trip through the armed channel (1 = up)"),
                state(Kind.DEBUG, "denied", "Denied by allowlist", source = "audit ring: outcome denied"),
                state(Kind.DEBUG, "callers", "Callers", source = "audit ring: distinct callers"),
            )),
        engine("network", "Network & mesh", "DNS, the mesh supervisor and WireGuard, the firewall, Wi-Fi and Bluetooth pages, the local web server, fleet endpoints",
            appDirs = listOf("network", "location"), // #1690 location/ = the Cloud Nav location reader the GPS views use
            libs = listOf("libs:net", "libs:firewall", "libs:sysdns", "libs:wifi", "libs:bluetooth", "libs:webserver", "libs:fleet-endpoints", "libs:fleet-endpoints-android"),
            libPackages = listOf("$APP.net", "$APP.firewall", "$APP.wifi", "$APP.bluetooth", "$APP.webserver", "$APP.fleetendpoints", "com.diegonmarcos.cloudlib.sysdns"),
            ledger = listOf("net.", "location."),
            hotPaths = listOf(HotPath("dns", "DNS sync + check", 6_000.0, "FleetDns.syncAndCheck (includes its settle wait)"), HotPath("page", "Page open", 250.0, PAGE_WHERE)),
            cpuSharePct = 5.0, bgCpuMsPerH = 1_500.0,
            extra = listOf(
                state(Kind.DEBUG, "mesh_up", "Mesh up", source = "FleetDns.meshUp (the tunnel's state, 1 = up)"),
                state(Kind.DEBUG, "firewall", "Firewall on", source = "FirewallController.isEnabled"),
                state(Kind.DEBUG, "dns_chosen", "DNS chosen", source = "FleetDns.Prefs.chosen"),
            )),
        engine("search", "Search", "the search sheet: the index of apps, pages and configs, scopes, live lookups",
            appDirs = listOf("search"), libs = listOf("libs:search"),
            hotPaths = listOf(HotPath("index", "Scope index", 150.0, "SuperappSearchIndex.hitsFor"), HotPath("page", "Sheet open", 150.0, PAGE_WHERE)),
            cpuSharePct = 5.0, bgCpuMsPerH = 200.0,
            extra = listOf(Target("lookups", Kind.DEBUG, "Index builds", "calls:index", source = "scope indexes built since process start"))),
        engine("feeds", "Feeds & data", "the data panes: RSS, calendar and tasks, cloud / C3 health, ops, mail, chat, health, AI usage, the data backends",
            appDirs = listOf("rss", "cal", "cloud", "c3"),
            libs = listOf("libs:databackend", "libs:fleet-data-client", "libs:ops", "libs:mail", "libs:chat", "libs:health", "libs:datamanager", "libs:clock-tools", "libs:clock-tools-core"),
            libPackages = listOf("$APP.databackend", "$APP.fleetdata", "$APP.ops", "$APP.mail", "$APP.chat", "$APP.health", "$APP.datamanager", "com.diegonmarcos.clocktools"),
            hotPaths = listOf(
                HotPath("rss", "RSS fetch", 4_000.0, "RemoteFeed.fetch (the bound feed engine)"),
                HotPath("cloud", "Cloud data fetch", 4_000.0, "CloudData.fetchRaw"),
                HotPath("page", "Page open", 250.0, PAGE_WHERE),
            ),
            cpuSharePct = 5.0, bgCpuMsPerH = 1_000.0, maxErrors = 3.0,
            extra = listOf(
                Target("bytes", Kind.BATTERY, "Bytes moved", "bytes", " B", source = "response bytes of the profiled fetches (radio energy scales with them)"),
                Target("rss_fetches", Kind.DEBUG, "RSS fetches", "calls:rss", source = "feed-engine fetches since process start"),
                Target("cloud_fetches", Kind.DEBUG, "Cloud fetches", "calls:cloud", source = "cloud data HTTP fetches since process start"),
            )),
        engine("configs", "Configs", "the Configs pages and settings: launcher settings and presets, perms, folders, one-hand, the Account link and sign-in state, the fleet config model",
            appDirs = listOf("configs", "settings"), libs = listOf("libs:fleetconfig-model", "libs:auth"),
            libPackages = listOf("$APP.fleetconfig", "$APP.account", "$APP.profile", "com.diegonmarcos.cloudlib.auth"),
            hotPaths = listOf(HotPath("page", "Page open", 150.0, PAGE_WHERE)),
            cpuSharePct = 10.0, bgCpuMsPerH = 200.0,
            extra = listOf(Target("opens", Kind.DEBUG, "Pages opened", "calls:page", source = "Configs pages opened since process start"))),
        engine("system", "System services", "process plumbing: the privileged plane worker, the wireless-debug keeper, crash and trace logging, recovery, analytics",
            appDirs = listOf("system", "recovery"), libs = listOf("libs:core", "libs:crash", "libs:analytics"),
            libPackages = listOf("$APP.core", "$APP.crash", "$APP.analytics"),
            hotPaths = listOf(
                HotPath("plane", "Privileged plane", 20_000.0, "PrivilegedPlaneWorker.doWork"),
                HotPath("wdkeeper", "WD keeper sync", 200.0, "WirelessDebugKeeper.sync"),
            ),
            cpuSharePct = 5.0, bgCpuMsPerH = 2_000.0, wakesPerH = 6.0, wakeSource = "PrivilegedPlaneWorker runs (WorkManager)",
            extra = listOf(
                state(Kind.HEALTH, "anr", "ANR exits", max = 0.0, source = "ApplicationExitInfo REASON_ANR of this process (last 16 exits)"),
                state(Kind.HEALTH, "native_crash", "Native crashes", max = 0.0, source = "ApplicationExitInfo REASON_CRASH_NATIVE (last 16 exits)"),
                state(Kind.HEALTH, "java_crash", "Crash exits", source = "ApplicationExitInfo REASON_CRASH (last 16 exits)"),
                state(Kind.HEALTH, "unattributed", "Crashes, no engine", source = "crash reports whose root frame is in no engine's packages"),
                state(Kind.DEBUG, "low_memory", "Low-memory kills", source = "ApplicationExitInfo REASON_LOW_MEMORY (last 16 exits)"),
                state(Kind.DEBUG, "crash_files", "Crash reports", source = "CrashLogger and libs:crash report files on disk"),
                state(Kind.DEBUG, "heap_mb", "Java heap", " MB", source = "Runtime total - free"),
                state(Kind.DEBUG, "threads", "Threads", source = "Thread.activeCount"),
            )),
        engine("xray", "X-Ray", "the profiler itself and the debug surface: this page, X-Ray Device, Export All, the Dev Control HTTP API, agents, logcat",
            appDirs = listOf("xray", "devcontrol", "agents"), libs = listOf("libs:devtools", "libs:fleetconfigs-xray"), libPackages = listOf("$APP.devtools"),
            hotPaths = listOf(
                HotPath("report", "Engine report", 400.0, "EngineProbes.collect + EngineProfiles.report (this page's own cost)"),
                HotPath("http", "Dev Control request", 1_500.0, "DevControlServer.handle"),
                HotPath("page", "Page open", 150.0, PAGE_WHERE),
            ),
            cpuSharePct = 3.0, bgCpuMsPerH = 500.0,
            extra = listOf(
                state(Kind.DEBUG, "records", "Runs recorded", source = "EngineProfiler: every profiled run of every engine since process start"),
                state(Kind.DEBUG, "paths", "Paths seen", source = "EngineProfiler: hot paths with at least one run"),
                state(Kind.DEBUG, "server", "Dev Control up", source = "DevControlServer.isRunning (1 = listening)"),
            )),
    )

    fun byId(id: String): Engine? = ENGINES.firstOrNull { it.id == id }

    // ── attribution ────────────────────────────────────────────────────────────────────────────────────────────────

    private val FRAME = Regex("""^\s*at\s+([\w.$]+)\(""")

    /** The package of a stack frame's class ("a.b.C.m" or "a.b.C$1.m" -> "a.b"). */
    internal fun framePackage(fqMethod: String): String =
        fqMethod.split('.').takeWhile { it.isNotEmpty() && it[0].isLowerCase() }.joinToString(".")

    /** The engine that owns [pkg] (longest declared prefix), or null. The app's root classes (App, MainActivity) are the launcher's. */
    fun engineOfPackage(pkg: String): String? {
        if (pkg == APP) return "launcher"
        var best: Engine? = null; var len = -1
        for (e in ENGINES) for (p in e.packages) if ((pkg == p || pkg.startsWith("$p.")) && p.length > len) { best = e; len = p.length }
        return best?.id
    }

    /**
     * The engine a crash belongs to: the first frame of the ROOT cause (the last "Caused by:" block, else the trace itself) that
     * is inside an engine's packages. Null when no frame is the app's code (a platform or third-party crash).
     */
    fun attribute(trace: String): String? {
        val lines = trace.lines()
        val root = lines.indexOfLast { it.trimStart().startsWith("Caused by:") }
        fun first(from: Int): String? {
            for (i in from until lines.size) {
                val m = FRAME.find(lines[i]) ?: continue
                engineOfPackage(framePackage(m.groupValues[1]))?.let { return it }
            }
            return null
        }
        return (if (root >= 0) first(root) else null) ?: first(0)
    }

    /** The first line of a report that names the throwable ("java.lang.IllegalStateException: why"). */
    fun headline(trace: String): String =
        trace.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("Caused by:") }?.removePrefix("Caused by:")?.trim()
            ?: trace.lineSequence().map { it.trim() }.firstOrNull { Regex("""^[\w.$]+(Exception|Error)\b""").containsMatchIn(it) }
            ?: trace.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    // ── the report ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** One EnergyLedger tag, copied out (pure mirror of EnergyLedger.Stat). */
    data class Ledger(val cpuNs: Long, val calls: Long, val wakeups: Long, val bytes: Long)

    /** One crash report on disk: when, the engine it was attributed to (null = none), its headline and where the file is. */
    data class Crash(val atMs: Long, val engine: String?, val headline: String, val file: String)

    /** Everything a report is computed from. Every field is a real reading; null / absent = not readable now. */
    data class Inputs(
        val snap: EngineProfiler.Snapshot,
        val ledger: Map<String, Ledger> = emptyMap(),
        /** CPU time of the whole process since it started (ms), or null. */
        val processCpuMs: Long? = null,
        /** This app's uid in the open battery cycle (mAh, batterystats through the battery SoT), or null without a reading. */
        val appMah: Double? = null,
        val crashes: List<Crash> = emptyList(),
        val nowMs: Long = 0L,
        /** Live values of the engines themselves, keyed "engine.key". */
        val state: Map<String, Double> = emptyMap(),
        /** Why a state value is missing, keyed "engine.key". */
        val missing: Map<String, String> = emptyMap(),
        /** Paths measured outside the recorder (the shell broker's audit ring), same shape. */
        val extraPaths: List<EngineProfiler.Path> = emptyList(),
    )

    data class Row(val target: Target, val measured: Double?, val verdict: Verdict, val detail: String) {
        /** "12.4 ms · ≤ 40 ms · PASS", "no sample yet · ≤ 40 ms", "3" (a context row). */
        val value: String get() = buildString {
            append(if (measured == null) Verdict.NO_DATA.word else num(measured) + target.unit)
            if (target.limit.isNotEmpty()) append(" · ").append(target.limit)
            if (verdict == Verdict.PASS || verdict == Verdict.FAIL) append(" · ").append(verdict.word)
            if (detail.isNotEmpty()) append(" · ").append(detail)
        }
    }

    data class Report(val engine: Engine, val rows: List<Row>, val lastError: EngineProfiler.Err?, val lastCrash: Crash?, val lastActiveMs: Long) {
        fun rows(kind: Kind): List<Row> = rows.filter { it.target.kind == kind }
        fun count(v: Verdict): Int = rows.count { it.verdict == v }
        /** FAIL if any target fails, else PASS if any passed, else NO_DATA. */
        val verdict: Verdict get() = when {
            count(Verdict.FAIL) > 0 -> Verdict.FAIL
            count(Verdict.PASS) > 0 -> Verdict.PASS
            else -> Verdict.NO_DATA
        }
        val summary: String get() = "${verdict.word} · ${count(Verdict.PASS)} pass · ${count(Verdict.FAIL)} fail · ${count(Verdict.NO_DATA)} no sample"
    }

    private fun paths(e: Engine, i: Inputs): List<EngineProfiler.Path> = i.snap.paths(e.id) + i.extraPaths.filter { it.engine == e.id }

    private fun ownsTag(e: Engine, tag: String) = tag.startsWith("${e.id}.") || e.ledger.any { tag.startsWith(it) }

    /** The engine's CPU (ns): its profiled paths, plus the ledger tags it owns that no profiled path already feeds. */
    internal fun cpuNs(e: Engine, i: Inputs): Long {
        val ps = paths(e, i)
        val fed = i.snap.paths.map { it.ledgerTag }.toSet()
        return ps.sumOf { it.cpuNs } + i.ledger.filter { (tag, _) -> ownsTag(e, tag) && tag !in fed }.values.sumOf { it.cpuNs }
    }

    private fun measure(e: Engine, t: Target, i: Inputs): Pair<Double?, String> {
        val ps = paths(e, i)
        fun path(id: String) = ps.firstOrNull { it.path == id }
        val m = t.metric
        return when {
            m.startsWith("p95:") -> path(m.substring(4)).let { p ->
                p?.quantileMs(0.95) to (p?.let { "n=${it.calls}, max ${num(it.maxWallNs / 1e6)} ms" } ?: "")
            }
            m.startsWith("over:") -> m.split(':').let { f -> path(f[1])?.overPct(f[2].toDouble()) to "" }
            m.startsWith("calls:") -> (path(m.substring(6))?.calls?.toDouble() ?: 0.0) to ""
            m == "slow" -> {
                var n = 0; var over = 0
                for (h in e.hotPaths) path(h.id)?.let { p -> n += p.samples.size; over += p.samples.count { it / 1e6 > h.p95Ms } }
                (if (n == 0) null else over * 100.0 / n) to (if (n == 0) "" else "$over of $n")
            }
            m == "cpu.ms" -> (cpuNs(e, i) / 1e6) to ""
            m == "cpu.share" -> i.processCpuMs?.takeIf { it > 0 }?.let { cpuNs(e, i) / 1e6 * 100.0 / it } to
                (i.processCpuMs?.let { "process ${it} ms" } ?: "process CPU not readable")
            m == "cpu.bg" -> (if (i.snap.backgroundMs < 60_000L) null else ps.sumOf { it.bgCpuNs } / 1e6 / (i.snap.backgroundMs / 3_600_000.0)) to
                (if (i.snap.backgroundMs < 60_000L) "under 1 min in the background so far" else "${num(ps.sumOf { it.bgCpuNs } / 1e6)} ms in ${dur(i.snap.backgroundMs)}")
            m == "wake" -> i.ledger.filter { ownsTag(e, it.key) }.values.sumOf { it.wakeups }.let { w ->
                (if (i.snap.recordedMs < 60_000L) null else w / (i.snap.recordedMs / 3_600_000.0)) to "$w in ${dur(i.snap.recordedMs)}"
            }
            m == "bytes" -> i.ledger.filter { ownsTag(e, it.key) }.values.sumOf { it.bytes }.toDouble() to ""
            m == "drain" -> {
                val cpu = i.processCpuMs?.takeIf { it > 0 }
                (if (cpu == null || i.appMah == null) null else i.appMah * (cpuNs(e, i) / 1e6 / cpu).coerceIn(0.0, 1.0)) to
                    (if (i.appMah == null) "no per-app batterystats reading yet (needs the ADB shell)" else "derived: app ${num(i.appMah)} mAh")
            }
            m == "crashes" -> i.crashes.count { it.engine == e.id && i.nowMs - it.atMs <= CRASH_DAYS * 86_400_000L }.toDouble() to
                "${i.crashes.count { it.engine == e.id }} on disk"
            m == "errors" -> i.snap.errors(e.id).toDouble() to ""
            m.startsWith("state:") -> "${e.id}.${m.substring(6)}".let { k -> i.state[k] to (i.missing[k] ?: "") }
            else -> null to "unknown metric"
        }
    }

    fun report(e: Engine, i: Inputs): Report {
        val rows = e.targets.map { t ->
            val (v, detail) = measure(e, t, i)
            val verdict = when {
                v == null -> Verdict.NO_DATA
                t.max != null -> if (v <= t.max + 1e-9) Verdict.PASS else Verdict.FAIL
                t.min != null -> if (v >= t.min - 1e-9) Verdict.PASS else Verdict.FAIL
                else -> Verdict.INFO
            }
            Row(t, v, verdict, detail)
        }
        return Report(e, rows, i.snap.lastError(e.id), i.crashes.filter { it.engine == e.id }.maxByOrNull { it.atMs },
            paths(e, i).maxOfOrNull { it.lastAtMs } ?: 0L)
    }

    fun reports(i: Inputs): List<Report> = ENGINES.map { report(it, i) }

    /** The whole profile as text (the page's copy buffer and the tests read the same lines the page draws). */
    fun text(r: Report): String = buildString {
        append("## ").append(r.engine.title).append(" · ").append(r.summary).append('\n')
        for (k in Kind.values()) {
            append("  ").append(k.title).append('\n')
            for (row in r.rows(k)) append("    ").append(row.target.label).append(": ").append(row.value).append('\n')
        }
    }

    internal fun num(d: Double): String = when {
        d == Math.floor(d) && Math.abs(d) < 1e12 -> String.format(Locale.US, "%,d", d.toLong())
        Math.abs(d) >= 100 -> String.format(Locale.US, "%,.0f", d)
        Math.abs(d) >= 1 -> String.format(Locale.US, "%.1f", d)
        else -> String.format(Locale.US, "%.2f", d)
    }

    internal fun dur(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "${s / 3600}h ${(s % 3600) / 60}m" else if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
    }
}
