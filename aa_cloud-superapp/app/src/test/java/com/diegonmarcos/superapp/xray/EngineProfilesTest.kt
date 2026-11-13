package com.diegonmarcos.superapp.xray

import com.diegonmarcos.superapp.devcontrol.DevControlFragment
import com.diegonmarcos.superapp.xray.EngineProfiles.Kind
import com.diegonmarcos.superapp.xray.EngineProfiles.Verdict
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1649 Configs ▸ X-Ray App is the SuperApp's own profiler, organised by its engines.
 *
 *   E1 the engine list is the app: every source package of the app and every lib build.json links is owned by exactly one engine
 *   E2 every engine has a profile with Performance, Battery and Bugs / health targets (and Debug rows), each with a real limit
 *   E3 every target of every engine is drawn with its measured value and its limit; nothing measured reads "no sample yet"
 *   E4 the recorder measures: count, percentiles, over-budget share, foreground / background CPU, throws
 *   E5 a target passes or fails on the measurement, never on a default
 *   E6 a crash is booked to the engine that owns its root frame
 *   E7 the page: ENGINE PROFILES leads X-Ray App, one block per engine, the ADB Shell link stays, nothing the page had is gone
 *   E8 the platform probe books a frame to ui/frame and a page to the engine that owns the fragment
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EngineProfilesTest {

    private val src = File("src/main/java/com/diegonmarcos/superapp")
    private var wall = 0L
    private var cpu = 0L
    private val realWall = EngineProfiler.wallClock
    private val realCpu = EngineProfiler.cpuClock
    private val realNow = EngineProfiler.nowMs

    @Before fun clocks() {
        EngineProfiler.wallClock = { wall }
        EngineProfiler.cpuClock = { cpu }
        EngineProfiler.nowMs = { 1_000_000L + wall / 1_000_000L }
        EngineProfiler.reset()
        EngineProfiler.foreground = true
    }

    @After fun restore() {
        EngineProfiler.wallClock = realWall
        EngineProfiler.cpuClock = realCpu
        EngineProfiler.nowMs = realNow
        EngineProfiler.reset()
    }

    private fun spend(engine: String, path: String, wallMs: Double, cpuMs: Double = 0.0) = EngineProfiler.time(engine, path) {
        wall += (wallMs * 1e6).toLong(); cpu += (cpuMs * 1e6).toLong()
    }

    private fun inputs(
        processCpuMs: Long? = 10_000L, appMah: Double? = null, crashes: List<EngineProfiles.Crash> = emptyList(),
        state: Map<String, Double> = emptyMap(), ledger: Map<String, EngineProfiles.Ledger> = emptyMap(),
    ) = EngineProfiles.Inputs(EngineProfiler.snapshot(), ledger, processCpuMs, appMah, crashes, nowMs = 10L * 86_400_000L, state = state)

    private fun row(r: EngineProfiles.Report, id: String) = r.rows.first { it.target.id == id }

    @Test
    fun `E1 every app package and every linked lib is owned by exactly one engine`() {
        val dirs = src.listFiles { f -> f.isDirectory }!!.map { it.name }.sorted()
        assertTrue("the app's source packages were not found from ${File(".").absolutePath}", dirs.size > 15)
        for (d in dirs) assertEquals("app package '$d' must be owned by exactly one engine", 1, EngineProfiles.ENGINES.count { d in it.appDirs })
        val owned = EngineProfiles.ENGINES.flatMap { it.appDirs }
        assertEquals("an engine names a package the app does not have", emptyList<String>(), owned - dirs.toSet())

        val modules = JSONObject(File("../build.json").readText()).getJSONObject("modules")
        val libs = modules.keys().asSequence().filter { it.startsWith("libs:") }.toList().sorted()
        assertTrue(libs.size > 30)
        for (l in libs) assertEquals("lib '$l' must be owned by exactly one engine", 1, EngineProfiles.ENGINES.count { l in it.libs })
        assertEquals("an engine names a lib build.json does not link", emptyList<String>(), EngineProfiles.ENGINES.flatMap { it.libs } - libs.toSet())

        // The engines the ticket names are there, each under its own id.
        val ids = EngineProfiles.ENGINES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            listOf("notify", "ui", "launcher", "store", "media", "bgpolicy", "shell", "network", "search", "feeds", "configs", "system", "xray"), ids)
    }

    @Test
    fun `E2 every engine has performance, battery and health targets with a limit, and debug rows`() {
        for (e in EngineProfiles.ENGINES) {
            assertTrue("${e.id}: no hot path", e.hotPaths.isNotEmpty())
            for (h in e.hotPaths) assertTrue("${e.id}/${h.id}: the hot path has no p95 target",
                e.targets.any { it.kind == Kind.PERFORMANCE && it.metric == "p95:${h.id}" && it.max == h.p95Ms })
            for (k in listOf(Kind.PERFORMANCE, Kind.BATTERY, Kind.HEALTH))
                assertTrue("${e.id}: no ${k.title} target with a limit", e.targets(k).any { it.max != null || it.min != null })
            assertTrue("${e.id}: no Debug row", e.targets(Kind.DEBUG).isNotEmpty())
            // The battery profile is the #1506 / #1629 tie-in: CPU share, background CPU, CPU time and the engine's part of the app's mAh.
            assertEquals("${e.id}: battery metrics", setOf("cpu.share", "cpu.bg", "cpu.ms", "drain"),
                e.targets(Kind.BATTERY).map { it.metric }.toSet().intersect(setOf("cpu.share", "cpu.bg", "cpu.ms", "drain")))
            assertTrue("${e.id}: crashes must be a zero-tolerance target", e.targets(Kind.HEALTH).any { it.metric == "crashes" && it.max == 0.0 })
            assertTrue("${e.id}: errors target", e.targets(Kind.HEALTH).any { it.metric == "errors" && it.max != null })
            assertEquals("${e.id}: target ids are unique", e.targets.size, e.targets.map { it.id }.toSet().size)
            for (t in e.targets) assertTrue("${e.id}/${t.id}: a target must say where its number comes from", t.source.isNotBlank())
        }
    }

    @Test
    fun `E3 every target is drawn with its measured value and limit, and an unmeasured one says so`() {
        val empty = inputs(processCpuMs = null)
        for (r in EngineProfiles.reports(empty)) {
            assertEquals(r.engine.targets.map { it.id }, r.rows.map { it.target.id })
            val text = EngineProfiles.text(r)
            for (k in Kind.values()) assertTrue("${r.engine.id}: ${k.title} heading", text.contains("  ${k.title}\n"))
            for (row in r.rows) {
                assertTrue("${r.engine.id}/${row.target.id} is not drawn", text.contains("    ${row.target.label}: ${row.value}\n"))
                if (row.target.limit.isNotEmpty()) assertTrue("${r.engine.id}/${row.target.id}: the limit is not shown", row.value.contains(row.target.limit))
            }
            // Nothing ran: no hot path may read as a pass.
            for (h in r.engine.hotPaths) {
                val p95 = row(r, "p95.${h.id}")
                assertNull(p95.measured); assertEquals(Verdict.NO_DATA, p95.verdict)
                assertTrue(p95.value.startsWith("no sample yet · ≤ "))
            }
            assertEquals(Verdict.NO_DATA, row(r, "slow").verdict)
            assertEquals("no process CPU reading = no share", Verdict.NO_DATA, row(r, "cpu.share").verdict)
            assertEquals(Verdict.NO_DATA, row(r, "drain").verdict)
            for (s in r.rows.filter { it.target.metric.startsWith("state:") }) assertEquals(Verdict.NO_DATA, s.verdict)
        }
    }

    @Test
    fun `E4 the recorder measures count, percentiles, the over-budget share, background CPU and throws`() {
        repeat(90) { spend("notify", "post", 2.0, 1.0) }
        EngineProfiler.foreground = false
        repeat(10) { spend("notify", "post", 50.0, 3.0) }
        wall += 3_600_000L * 1_000_000L // one hour in the background
        try { EngineProfiler.time("notify", "post") { wall += 1_000_000L; error("boom") } } catch (expected: IllegalStateException) { }

        val s = EngineProfiler.snapshot()
        val p = s.path("notify", "post")!!
        assertEquals(101L, p.calls)
        assertEquals(50.0, p.maxWallNs / 1e6, 1e-6)
        assertEquals(2.0, p.quantileMs(0.5)!!, 1e-6)
        assertEquals(50.0, p.quantileMs(0.95)!!, 1e-6)
        assertEquals(10 * 100.0 / 101, p.overPct(8.0)!!, 1e-6)
        assertEquals(120.0, p.cpuNs / 1e6, 1e-6)
        assertEquals(30.0, p.bgCpuNs / 1e6, 1e-6)
        assertEquals(1L, s.errors("notify"))
        assertEquals("post", s.lastError("notify")!!.where)
        assertTrue(s.lastError("notify")!!.what.contains("boom"))
        assertTrue("the hour in the background is counted", s.backgroundMs >= 3_600_000L)
        assertEquals(0L, s.errors("ui"))

        // The ring keeps the last RING runs only; the counters keep everything.
        repeat(EngineProfiler.RING + 5) { spend("ui", "icon", 1.0) }
        val icon = EngineProfiler.snapshot().path("ui", "icon")!!
        assertEquals((EngineProfiler.RING + 5).toLong(), icon.calls)
        assertEquals(EngineProfiler.RING, icon.samples.size)
    }

    @Test
    fun `E5 a target passes or fails on what was measured`() {
        val notify = EngineProfiles.byId("notify")!!
        repeat(100) { spend("notify", "post", 2.0, 1.0) }       // 100 ms CPU, p95 2 ms (budget 8)
        repeat(20) { spend("notify", "intake", 30.0, 1.0) }     // p95 30 ms (budget 12): over budget
        val ok = EngineProfiles.report(notify, inputs(processCpuMs = 10_000L, appMah = 50.0,
            state = mapOf("notify.listener" to 1.0, "notify.badges_dead" to 0.0)))
        assertEquals(Verdict.PASS, row(ok, "p95.post").verdict)
        assertEquals(Verdict.FAIL, row(ok, "p95.intake").verdict)
        assertTrue(row(ok, "p95.intake").value, row(ok, "p95.intake").value.startsWith("30 ms · ≤ 12 ms · FAIL · n=20"))
        assertEquals(Verdict.FAIL, row(ok, "slow").verdict)   // 20 of 120 runs over their budget
        assertEquals(120.0, row(ok, "cpu.ms").measured!!, 1e-6)
        assertEquals(1.2, row(ok, "cpu.share").measured!!, 1e-6)
        assertEquals(Verdict.PASS, row(ok, "cpu.share").verdict)
        assertEquals("the engine's part of the app's measured mAh", 0.6, row(ok, "drain").measured!!, 1e-6)
        assertEquals(Verdict.PASS, row(ok, "crashes").verdict)
        assertEquals(Verdict.PASS, row(ok, "state.listener").verdict)
        assertEquals(Verdict.PASS, row(ok, "state.badges_dead").verdict)
        assertEquals(Verdict.FAIL, ok.verdict)
        assertEquals(Verdict.NO_DATA, row(ok, "p95.alerts").verdict)

        val bad = EngineProfiles.report(notify, inputs(processCpuMs = 1_000L,
            state = mapOf("notify.listener" to 0.0, "notify.badges_dead" to 2.0, "notify.sent_per_h" to 500.0)))
        assertEquals("12 % of the process CPU is over the 5 % target", Verdict.FAIL, row(bad, "cpu.share").verdict)
        assertEquals(Verdict.FAIL, row(bad, "state.listener").verdict)
        assertEquals(Verdict.FAIL, row(bad, "state.badges_dead").verdict)
        assertEquals(Verdict.FAIL, row(bad, "state.sent_per_h").verdict)
        assertEquals("no batterystats reading = no drain figure", Verdict.NO_DATA, row(bad, "drain").verdict)

        // UI: jank is the share of frames over one vsync, frozen the share over 700 ms.
        repeat(95) { XrayLifecycleProbe.onFrame(false, 8_000_000L) }
        repeat(5) { XrayLifecycleProbe.onFrame(false, 20_000_000L) }
        assertEquals(-1L, XrayLifecycleProbe.onFrame(true, 900_000_000L)) // a first-draw frame is not jank
        val ui = EngineProfiles.report(EngineProfiles.byId("ui")!!, inputs())
        assertEquals(5.0, row(ui, "jank").measured!!, 1e-6)
        assertEquals(Verdict.PASS, row(ui, "jank").verdict)
        assertEquals(0.0, row(ui, "frozen").measured!!, 1e-6)
        assertEquals(100.0, row(ui, "frames").measured!!, 1e-6)
        XrayLifecycleProbe.onFrame(false, 900_000_000L)
        assertEquals(Verdict.FAIL, row(EngineProfiles.report(EngineProfiles.byId("ui")!!, inputs()), "frozen").verdict)

        // The ledger tags that existed before (#1506 / #1629) are the engine's CPU and wakeups too, counted once.
        EngineProfiler.time("media", "session", ledgerTag = "music.session") { cpu += 4_000_000L }
        val ledger = mapOf(
            "music.session" to EngineProfiles.Ledger(4_000_000L, 1, 0, 0),   // fed by the profiled path: not added twice
            "bg.battery_worker" to EngineProfiles.Ledger(7_000_000L, 3, 3, 0),
            "ui.galaxy" to EngineProfiles.Ledger(9_000_000L, 50, 0, 0),
        )
        val i = inputs(ledger = ledger)
        assertEquals(4.0, row(EngineProfiles.report(EngineProfiles.byId("media")!!, i), "cpu.ms").measured!!, 1e-6)
        assertEquals(7.0, row(EngineProfiles.report(EngineProfiles.byId("bgpolicy")!!, i), "cpu.ms").measured!!, 1e-6)
        assertEquals(9.0, row(EngineProfiles.report(EngineProfiles.byId("ui")!!, i), "cpu.ms").measured!!, 1e-6)
    }

    @Test
    fun `E6 a crash is booked to the engine that owns its root frame`() {
        assertEquals("notify", EngineProfiles.engineOfPackage("com.diegonmarcos.superapp.notificationcenter"))
        assertEquals("shell", EngineProfiles.engineOfPackage("com.diegonmarcos.superapp.shellbroker"))
        assertEquals("ui", EngineProfiles.engineOfPackage("com.diegonmarcos.superapp.shell"))
        assertEquals("network", EngineProfiles.engineOfPackage("com.diegonmarcos.superapp.network.mesh"))
        assertEquals("launcher", EngineProfiles.engineOfPackage("com.diegonmarcos.superapp"))
        assertNull(EngineProfiles.engineOfPackage("androidx.compose.ui"))
        assertEquals("com.diegonmarcos.superapp.notificationcenter", EngineProfiles.framePackage("com.diegonmarcos.superapp.notificationcenter.NotifyGate\$Slot.post"))

        val trace = """
            === Diego Superapp crash ===
            thread:      main

            java.lang.RuntimeException: Unable to start activity
            	at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:3782)
            	at com.diegonmarcos.superapp.MainActivity.onCreate(MainActivity.kt:120)
            Caused by: java.lang.IllegalStateException: no badge
            	at kotlin.collections.CollectionsKt.first(Collections.kt:1)
            	at com.diegonmarcos.superapp.notificationcenter.BadgeServices.status(BadgeServices.kt:229)
            	at com.diegonmarcos.superapp.launcher.HomeFragment.onViewCreated(HomeFragment.kt:10)
        """.trimIndent()
        assertEquals("the root cause's first app frame wins over the outer frames", "notify", EngineProfiles.attribute(trace))
        assertEquals("java.lang.IllegalStateException: no badge", EngineProfiles.headline(trace))
        assertNull(EngineProfiles.attribute("java.lang.OutOfMemoryError\n\tat android.graphics.Bitmap.nativeCreate(Native Method)"))

        val now = 10L * 86_400_000L
        val crashes = listOf(
            EngineProfiles.Crash(now - 3_600_000L, "notify", "x", "crash-a.txt"),
            EngineProfiles.Crash(now - 30L * 86_400_000L, "notify", "old", "crash-b.txt"),
            EngineProfiles.Crash(now - 60_000L, null, "platform", "crash-c.txt"),
        )
        val r = EngineProfiles.report(EngineProfiles.byId("notify")!!, inputs(crashes = crashes))
        assertEquals("only the last ${EngineProfiles.CRASH_DAYS} days count against the target", 1.0, row(r, "crashes").measured!!, 1e-6)
        assertEquals(Verdict.FAIL, row(r, "crashes").verdict)
        assertEquals("crash-a.txt", r.lastCrash!!.file)
        assertEquals(Verdict.PASS, row(EngineProfiles.report(EngineProfiles.byId("ui")!!, inputs(crashes = crashes)), "crashes").verdict)
    }

    @Test
    fun `E7 the page leads with the engine profiles, keeps the ADB Shell link and loses nothing`() {
        // Field parity with the page before #1649: every group / section X-Ray App drew is still drawn.
        val before = listOf("APP & BUILD", "REPO & RELEASES", "RESOURCES/Storage", "RESOURCES/Battery & Usage", "RESOURCES/Memory & CPU Usage", "AGENTS/SuperApp agents")
        assertEquals(listOf("ENGINE PROFILES") + before, DevControlFragment.APP_XRAY_MACROS.toList())

        val dev = File(src, "devcontrol/DevControlFragment.kt").readText()
        val from = dev.indexOf("macro(\"🧬  ENGINE PROFILES\")")
        val to = dev.indexOf("macro(\"📱  APP & BUILD\")")
        assertTrue("the ENGINE PROFILES group is declared before APP & BUILD", from in 0 until to)
        val block = dev.substring(from, to)
        assertTrue("one block per engine of the catalogue", block.contains("for (e in EngineProfiles.ENGINES) asyncSection(engineHead(e)"))
        assertTrue("every target kind is drawn", block.contains("for (k in EngineProfiles.Kind.values())") && block.contains("for (x in r.rows(k)) row(x.target.label, x.value)"))
        assertTrue("the scoreboard lists every engine", block.contains("asyncSection(ENGINE_SCOREBOARD") && block.contains("EngineProfiles.reports(inputs)"))
        assertTrue("the ADB Shell link is in the profiler", block.contains("button(\"ADB Shell ›\", gray = true) { com.diegonmarcos.superapp.shellclient.ShellClient.openArmPage(ctxAny()) }"))
        // ...and the links the page already had are still there (Repo & Releases ▸ Profile, Dev Tools).
        assertEquals(3, Regex("""button\("ADB Shell ›", gray = true\) \{ com\.diegonmarcos\.superapp\.shellclient\.ShellClient\.openArmPage\(ctxAny\(\)\) \}""").findAll(dev).count())
        assertEquals("every macro group the page had, plus the new one", 14, Regex("""(?m)^\s+macro\("([^"]+)"\)""").findAll(dev).count())

        // The page draws one block per engine, in catalogue order, under the one group; About does not get them.
        val heads = listOf(DevControlFragment.ENGINE_SCOREBOARD) + EngineProfiles.ENGINES.map { DevControlFragment.engineHead(it) }
        assertEquals(heads.size, heads.toSet().size)
        assertEquals("Engine · Notify", heads[1]); assertEquals("Engine · UI", heads[2])
        assertFalse(DevControlFragment.ABOUT_MACROS.contains("ENGINE PROFILES"))
        val pages = File(src, "launcher/SectionPages.kt").readText()
        assertTrue("xray-app still routes to the app-scoped X-Ray", Regex("""pageId == "xray-app" ->\s*com\.diegonmarcos\.superapp\.devcontrol\.DevControlFragment\.newInstance\(label, [^)]*APP_XRAY_MACROS\)""").containsMatchIn(pages))

        // The hot paths the catalogue names are really wrapped in the engines' code.
        fun has(file: String, what: String) = assertTrue("$file does not record $what", File(src, file).readText().contains(what))
        has("notificationcenter/NotifyGate.kt", "EngineProfiler.time(\"notify\", \"post\")")
        has("notificationcenter/PhoneNotificationListenerService.kt", "EngineProfiler.time(\"notify\", \"intake\")")
        has("notificationcenter/AlertsNotifier.kt", "EngineProfiler.time(\"notify\", \"alerts\")")
        has("notificationcenter/DataWidgetWakeReceiver.kt", "EngineProfiler.wake(\"notify\"")
        has("ui/AppIconCache.kt", "EngineProfiler.time(\"ui\", \"icon\")")
        has("extinstall/ExtAppInstalls.kt", "EngineProfiler.time(\"store\", \"installs\")")
        has("media/NowPlayingMonitor.kt", "EngineProfiler.time(\"media\", \"session\", ledgerTag = \"music.session\")")
        has("bgpolicy/BgEngine.kt", "EngineProfiler.time(\"bgpolicy\", \"shell\")")
        has("network/FleetDns.kt", "EngineProfiler.time(\"network\", \"dns\")")
        has("search/SuperappSearchIndex.kt", "EngineProfiler.time(\"search\", \"index\")")
        has("rss/RemoteFeed.kt", "EngineProfiler.time(\"feeds\", \"rss\")")
        has("cloud/CloudData.kt", "EngineProfiler.time(\"feeds\", \"cloud\")")
        assertTrue("the plane worker no longer records plane", File("../../ab_cloud-libs-shared/libs/adb-shell-core/src/main/java/com/diegonmarcos/superapp/system/PrivilegedPlaneWorker.kt").readText().contains("AdbOwnerHost.timed(\"system\", \"plane\")"))
        // #1682 P3 step 4d: the keeper is in libs:adb-shell-core and times itself through AdbOwnerHost.profile; App.onCreate installs EngineProfiler.time behind it
        assertTrue("the keeper no longer records wdkeeper", File("../../ab_cloud-libs-shared/libs/adb-shell-core/src/main/java/com/diegonmarcos/superapp/system/WirelessDebugKeeper.kt").readText().contains("AdbOwnerHost.profile(\"system\", \"wdkeeper\")"))
        has("App.kt", "AdbOwnerHost.profiler = { engine, path, block -> com.diegonmarcos.superapp.xray.EngineProfiler.time(engine, path) { block() } }")
        has("devcontrol/DevControlServer.kt", "EngineProfiler.time(\"xray\", \"http\")")
        has("xray/EngineProbes.kt", "EngineProfiler.time(\"xray\", \"report\")")
        has("App.kt", "XrayLifecycleProbe.install(this)")
        // Every hot path of the catalogue has a recorder: wrapped above, booked by the platform probe, or read from the broker's audit ring.
        val all = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        val owner = File("../../ab_cloud-libs-shared/libs/adb-shell-core/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (e in EngineProfiles.ENGINES) for (h in e.hotPaths) {
            val recorded = h.id in setOf("page", "activity", "frame") || (e.id == "shell" && h.id == "exec") ||
                all.contains("EngineProfiler.time(\"${e.id}\", \"${h.id}\"") ||
                // #1682 P3 step 4d: the owner code in libs:adb-shell-core times itself through the host hook (App installs EngineProfiler.time behind it)
                owner.contains("AdbOwnerHost.profile(\"${e.id}\", \"${h.id}\")") || owner.contains("AdbOwnerHost.timed(\"${e.id}\", \"${h.id}\")")
            assertTrue("${e.id}/${h.id}: nothing records this hot path", recorded)
        }
    }

    @Test
    fun `E8 the platform probe books a page to the engine that owns it`() {
        val page = EngineProfiles   // any object of an engine's package stands for its fragment: this one is X-Ray's
        XrayLifecycleProbe.begin(page)
        wall += 42_000_000L
        assertEquals("xray", XrayLifecycleProbe.end(page, "page"))
        assertNull("an end with no begin records nothing", XrayLifecycleProbe.end(page, "page"))
        val foreign = Any()
        XrayLifecycleProbe.begin(foreign)
        assertNull("a class of no engine is not booked", XrayLifecycleProbe.end(foreign, "page"))
        val p = EngineProfiler.snapshot().path("xray", "page")
        assertNotNull(p)
        assertEquals(42.0, p!!.quantileMs(0.95)!!, 1e-6)
        assertEquals(Verdict.PASS, row(EngineProfiles.report(EngineProfiles.byId("xray")!!, inputs()), "p95.page").verdict)
    }
}
