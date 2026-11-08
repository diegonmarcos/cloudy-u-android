package com.diegonmarcos.superapp.xray

import com.diegonmarcos.superapp.notificationcenter.AiUsageClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** #1504 Export All: the declared X-Ray tree is covered, the MD/JSON schema, redaction, the ADB gate, the partial mode, the post ladder. */
class XrayExportTest {

    private val BATTERYSTATS = """
  Estimated power use (mAh):
    Capacity: 5000, Computed drain: 410, actual drain: 400-420
    Uid u0a150: 120.5 ( cpu=30.0 wakelock=80.0 mobile_radio=5.0 )
    Uid u0a151: 40.2 ( cpu=35.0 wifi=3.0 )
    Uid 1000: 12.0 ( cpu=12.0 )
    Screen: 100.0

  u0a150:
    Wake lock *alarm*: 1h 2m 3s 4ms (12 times) max=1s realtime
    Wake lock SyncLoop: 5m 0s 0ms (3 times) realtime
    Job com.fleet/.Sync: 10m 0s 0ms
    Cpu: 1m 0s 0ms usr + 30s 0ms krn
    Wakeup alarm *walarm*:x: 7 times
  u0a151:
    Cpu: 5m 0s 0ms usr + 1m 0s 0ms krn
"""
    private val PACKAGES = "package:com.diegonmarcos.fleet uid:10150\npackage:com.other.app uid:10151\npackage:android uid:1000\n"
    private val SERVICES = "  * ServiceRecord{abc u0 com.diegonmarcos.fleet/.SyncService}\n    isForeground=true foregroundId=1\n  * ServiceRecord{def u0 com.other.app/.Svc}\n    createdFromFg=false\n"
    private val STARTS = "11-05 10:00:00.000 I/ActivityManager( 1): Start proc 123:com.diegonmarcos.fleet/u0a150 for broadcast {com.diegonmarcos.fleet/.Boot}\n" +
        "11-05 10:01:00.000 I/ActivityManager( 1): Start proc 124:com.diegonmarcos.fleet/u0a150 for service {com.diegonmarcos.fleet/.S}\n"
    private val ALARM = "  Alarm Stats:\n  u0a150:com.diegonmarcos.fleet +1s234ms running, 25 wakeups:\n"
    private val JOBS = "  JOB #u0a150/9: 1a2b com.diegonmarcos.fleet/androidx.work.impl.background.systemjob.SystemJobService\n"

    private fun fixtureFor(cmd: String): String = when {
        cmd.startsWith("dumpsys batterystats") -> BATTERYSTATS
        cmd.startsWith("cmd package list") -> PACKAGES
        cmd.startsWith("dumpsys activity services") -> SERVICES
        cmd.startsWith("logcat -d -b") -> STARTS
        cmd.startsWith("dumpsys jobscheduler") -> JOBS
        cmd.startsWith("dumpsys alarm") -> ALARM
        else -> "ok for ${cmd.take(30)}"
    }

    private val shell = object : XrayShell {
        override val channel = "embedded-adb"
        val ran = ArrayList<String>()
        override fun exec(cmd: String): String? { ran += cmd; return fixtureFor(cmd) }
    }

    private val walk = listOf(
        XSection("X-Ray · APP & BUILD", "App", SRC_APP, "  Version: 1.2.3\n  token=abcdefghijklmnop123456\n"),
        XSection("X-Ray · RESOURCES", "Battery", SRC_APP, "  Level: 40\n"),
    )

    private fun build(sh: XrayShell? = shell, reason: String = "") =
        XrayExporter.build(XrayInputs("Pixel Test", "1.0 abc", 1_790_000_000_000L, walk, sh, reason, extra = listOf(XSection(XrayProbes.G_BATTERY, "Battery history", SRC_APP, "d1 | 9.0"))))

    // ── coverage ─────────────────────────────────────────────────────────

    @Test fun `the declared Observability pages equal the walked tree and each one is covered`() {
        val root = JSONObject(File("../build.json").readText())
        fun find(o: Any?): JSONObject? = when (o) {
            is JSONObject -> if (o.optString("id") == "config" && o.has("groups")) o else o.keys().asSequence().mapNotNull { find(o.get(it)) }.firstOrNull()
            is JSONArray -> (0 until o.length()).asSequence().mapNotNull { find(o.get(it)) }.firstOrNull()
            else -> null
        }
        // The Watchtower - Obsv subgroup (was Observability; every page declared there, visible and hidden) plus the four pages
        // relocated to Setup > Launcher (Perms, Logcat, X-Ray App, About), in declaration order.
        val pages = find(root)!!.getJSONArray("pages")
        val relocated = setOf("perms", "logcat", "xray-app", "app-about")
        val declared = (0 until pages.length()).map { pages.getJSONObject(it) }
            .filter { it.optString("group") == "Setup" && (it.optString("subgroup") == "Watchtower - Obsv" || it.getString("id") in relocated) }
            .map { it.getString("id") }
        assertEquals(declared, XrayTree.PAGES)
        val r = build()
        val groupsSeen = r.sections.map { it.group }.toSet()
        for (p in declared) {
            val cover = XrayTree.COVERAGE[p]!!
            assertTrue("page $p is covered by $cover", cover.startsWith(XrayTree.WALKED) || cover in groupsSeen)
        }
        assertTrue(r.sections.first { it.title == "Pages and groups walked" }.text.contains("config/battery"))
    }

    @Test fun `every probe is run at shell uid and every page section of the walk is in the report`() {
        shell.ran.clear()
        val r = build()
        assertEquals(XrayProbes.all.size, shell.ran.size)
        for (p in XrayProbes.all) assertTrue(p.title, r.sections.any { it.title == p.title && it.source == SRC_SHELL })
        assertTrue(r.sections.any { it.group == "X-Ray · APP & BUILD" && it.title == "App" && it.source == SRC_APP })
        assertTrue(r.sections.any { it.title == "Battery history" })
        assertEquals("packages is read first", "packages", XrayProbes.order.first().id)
    }

    @Test fun `the suspects are ranked by estimated power with their cause and the fleet is highlighted`() {
        val r = build()
        assertEquals(listOf(1, 2, 3), r.suspects.map { it.rank })
        val top = r.suspects.first()
        assertEquals("com.diegonmarcos.fleet", top.pkg)
        assertTrue(top.fleet)
        assertEquals("wakelock", top.cause)
        assertTrue(top.detail.contains("25 alarm wakeups") || top.detail.contains("alarm wakeups"))
        assertTrue(top.detail.contains("foreground"))
        assertTrue(top.detail.contains("1 pending jobs (1 WorkManager)"))
        assertTrue(top.detail.contains("process starts"))
        assertEquals("com.other.app", r.suspects[1].pkg)
        assertFalse(r.suspects[1].fleet)
        assertTrue(r.fleet.single().startsWith("com.diegonmarcos.fleet: 120.5 mAh"))
        assertTrue(r.fleet.single().contains("woken by"))
    }

    @Test fun `the probe list is ordered by tier and carries the new diagnosis probes`() {
        val ids = XrayProbes.all.map { it.id }
        for (n in listOf("gfxinfo", "top", "notification", "binder", "meminfo", "anr", "thermal", "profile")) assertTrue(n, n in ids)
        fun tier(g: String) = g.first()
        val tiers = XrayProbes.all.map { tier(it.group) }.filter { it in "①②③" }
        assertEquals("probes never go back to an earlier tier", tiers.sorted(), tiers)
        assertEquals(XrayProbes.G_JANK, XrayProbes.all.first { it.id == "gfxinfo" }.group)
        assertEquals(XrayProbes.G_NOTIF, XrayProbes.all.first { it.id == "notification" }.group)
    }

    @Test fun `a notification storm outranks a heavier power user in the composite suspects`() {
        val raw = mapOf(
            "batterystats" to BATTERYSTATS, "packages" to PACKAGES + "package:com.chatty.app uid:10160\n",
            "notification" to "  AggregatedStats{\n    key='com.chatty.app',\n    numPostedByApp=1000,\n    numUpdatedByApp=216000,\n  }\n",
            "gfxinfo" to "Total frames rendered: 100\nJanky frames: 40 (40.00%)\n",
        )
        val sus = XrayAnalyze.suspects(raw)
        assertEquals("com.chatty.app", sus.first().pkg)
        assertEquals("notification storm", sus.first().cause)
        assertTrue(sus.first().detail.startsWith("why: 217000 notification posts"))
        assertTrue(sus.any { it.pkg == "com.diegonmarcos.superapp" && it.cause == "jank" })
        assertEquals("power is still an input", true, sus.any { it.pkg == "com.diegonmarcos.fleet" && it.mAh > 100 })
    }

    // ── schema ───────────────────────────────────────────────────────────

    @Test fun `the JSON schema carries suspects, fleet, groups and the source of every section`() {
        val j = XrayFormat.json(build())
        assertEquals(XRAY_SCHEMA, j.getString("schema"))
        assertFalse(j.getBoolean("partial"))
        assertEquals("embedded-adb", j.getString("adb"))
        assertTrue(j.getJSONArray("suspects").length() >= 1)
        assertTrue(j.getJSONArray("fleet").length() >= 1)
        val groups = j.getJSONArray("groups")
        val names = (0 until groups.length()).map { groups.getJSONObject(it).getString("group") }
        assertTrue(names.containsAll(listOf("Report", XrayProbes.G_BATTERY, XrayProbes.G_POWER, XrayProbes.G_RUNTIME, XrayProbes.G_DEVICE, "X-Ray · APP & BUILD")))
        val s = groups.getJSONObject(0).getJSONArray("sections").getJSONObject(0)
        assertTrue(s.has("title") && s.has("source") && s.has("ok") && s.has("text"))
    }

    @Test fun `the Markdown follows the four tiers, the verdict last, and names each source`() {
        val md = XrayFormat.markdown(build())
        assertTrue(md.startsWith("# X-Ray report · Pixel Test"))
        val order = listOf(XrayProbes.G_DEVICE, XrayProbes.G_RUNTIME, XrayProbes.G_BATTERY, XrayProbes.G_POWER, XrayProbes.G_JANK, XrayProbes.G_NOTIF,
            XrayProbes.G_MEMORY, XrayProbes.G_RESP, "# ④ SUSPECTS (verdict)").map { md.indexOf(if (it.startsWith("#")) it else "\n# $it\n") }
        assertTrue("every tier is present", order.all { it >= 0 })
        assertEquals("tiers are in order", order.sorted(), order)
        assertTrue(md.indexOf("## Suspects (ranked)") > md.indexOf("# ③ Diagnosis · Memory"))
        assertTrue(md.contains("_source: shell_"))
        assertTrue(md.contains("_source: app sandbox_"))
        assertTrue(md.contains("**com.diegonmarcos.fleet** (fleet)"))
        assertFalse(md.contains(PARTIAL_LABEL))
    }

    // ── redaction ────────────────────────────────────────────────────────

    @Test fun `nothing secret-shaped survives in either rendering`() {
        val secrets = listOf("ghp_abcdefghijklmnopqrstuvwxyz0123", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig",
            "0123456789abcdef0123456789abcdef01234567", "sk-abcdefghijklmnopqrstuv")
        val w = listOf(XSection("G", "T", SRC_APP, "Authorization: Bearer abcdefghijklmnopqrst\n" + secrets.joinToString("\n") +
            "\napi_key=\"zzzzzzzzzzzz\"\n-----BEGIN PRIVATE KEY-----\nMIIEvQ\n-----END PRIVATE KEY-----\ncredential_service=com.google.android.gms/.Cred"))
        val r = XrayExporter.build(XrayInputs("d", "v", 1L, w, null))
        val both = XrayFormat.markdown(r) + XrayFormat.jsonText(r)
        for (s in secrets + listOf("abcdefghijklmnopqrst", "zzzzzzzzzzzz", "MIIEvQ")) assertFalse("leaked $s", both.contains(s))
        assertTrue("a service name is not a secret", both.contains("com.google.android.gms/.Cred"))
        assertTrue(both.contains(XrayRedact.MASK))
    }

    // ── ADB gate and partial mode ────────────────────────────────────────

    @Test fun `a connected channel needs no prompt`() = runBlocking {
        var asked = 0
        val r = XrayAdbGate.run({ true }, { false }, {}, { asked++; XrayAdbGate.Choice.CANCEL })
        assertEquals(XrayAdbGate.Result.Ready, r); assertEquals(0, asked)
    }

    @Test fun `without adb the export asks, re-arm then continues when the channel comes up`() = runBlocking {
        var up = false; val msgs = ArrayList<String>()
        val r = XrayAdbGate.run({ up }, { up = true; true }, {}, { msgs += it; XrayAdbGate.Choice.REARM })
        assertEquals(XrayAdbGate.Result.Ready, r); assertEquals(1, msgs.size)
    }

    @Test fun `wireless debugging is opened, then it asks again and continues once connected`() = runBlocking {
        var up = false; var opened = 0; var asks = 0
        val r = XrayAdbGate.run({ up }, { false }, { opened++ }, {
            asks++; if (asks == 1) XrayAdbGate.Choice.OPEN_WIRELESS_DEBUGGING else { up = true; XrayAdbGate.Choice.REARM }
        })
        assertEquals(XrayAdbGate.Result.Ready, r); assertEquals(1, opened); assertEquals(2, asks)
    }

    @Test fun `declining exports a partial report marked partial no adb, with sandbox reads and skipped probes`() = runBlocking {
        val g = XrayAdbGate.run({ false }, { false }, {}, { XrayAdbGate.Choice.PARTIAL })
        val reason = (g as XrayAdbGate.Result.Partial).reason
        val r = XrayExporter.build(XrayInputs("Pixel", "1", 1L, walk, null, reason, app = { k -> if (k == "secure:enabled_accessibility_services") "com.x/.A11y" else null }))
        assertTrue(r.partial); assertEquals("none", r.adbChannel)
        assertTrue(r.sections.first { it.title.startsWith("batterystats") }.source == SRC_SKIPPED)
        assertEquals(SRC_APP, r.sections.first { it.title.startsWith("Accessibility") }.source)
        assertTrue(r.suspects.isEmpty())
        assertTrue(XrayFormat.markdown(r).contains("**$PARTIAL_LABEL**"))
        assertEquals(PARTIAL_LABEL, XrayFormat.json(r).getString("partial_label"))
        assertTrue(r.sections.any { it.group == "X-Ray · APP & BUILD" })
    }

    @Test fun `cancelling the prompt exports nothing`() = runBlocking {
        assertEquals(XrayAdbGate.Result.Cancelled, XrayAdbGate.run({ false }, { false }, {}, { XrayAdbGate.Choice.CANCEL }))
    }

    // ── posting ──────────────────────────────────────────────────────────

    private val calls = ArrayList<String>()
    private fun tp(name: String, f: (String) -> AiUsageClient.Reply?) = XrayPoster.PostTransport { url, h, _ -> calls += "$name $url auth=${h.containsKey("Authorization")}"; f(url) }
    private val bases = listOf("https://h/cloudy-c3-api", "https://h/c3-infra-api")

    @Test fun `the report posts to xray report on the first base through the fleet leg`() {
        val o = XrayPoster.post(bases, "tok", build(), tp("fleet") { AiUsageClient.Reply(200, "{}") }, tp("plain") { error("no") })
        assertEquals(XrayPoster.Outcome.Ok("fleet", "/xray/report"), o)
        assertEquals(listOf("fleet https://h/cloudy-c3-api/xray/report auth=false"), calls)
    }

    @Test fun `until the route is live it falls back to the generic app-log route`() {
        val o = XrayPoster.post(bases, "tok", build(), tp("fleet") { u -> if (u.endsWith("/xray/report")) AiUsageClient.Reply(404, "") else AiUsageClient.Reply(200, "{}") }, tp("plain") { null })
        assertEquals(XrayPoster.Outcome.Ok("fleet", "/apk/cloud-superapp/logs"), o)
        assertTrue(calls.any { it.endsWith("c3-infra-api/xray/report auth=false") })
        assertTrue(JSONObject(XrayPoster.fallbackBody(build())).getJSONObject("meta").getString("schema") == XRAY_SCHEMA)
        assertEquals("probe", JSONObject(XrayPoster.fallbackBody(build())).getString("kind"))
    }

    @Test fun `a refused fleet leg goes public with the bearer and no bearer fails clearly`() {
        val o = XrayPoster.post(bases.take(1), "tok", build(), tp("fleet") { AiUsageClient.Reply(401, "") }, tp("plain") { AiUsageClient.Reply(202, "") })
        assertEquals(XrayPoster.Outcome.Ok("public", "/xray/report"), o)
        assertTrue(calls.last().endsWith("auth=true"))
        val f = XrayPoster.post(bases.take(1), "", build(), tp("fleet") { AiUsageClient.Reply(403, "") }, tp("plain") { error("no") })
        assertTrue((f as XrayPoster.Outcome.Failed).reason.contains("no fleet bearer"))
        val io = XrayPoster.post(bases.take(1), "t", build(), tp("fleet") { throw IOException("x") }, tp("plain") { throw IOException("y") })
        assertTrue(io is XrayPoster.Outcome.Failed)
    }

    @Test fun `the upload body is bounded and the bearer is never in it`() {
        val big = XReport("d", "v", 1L, false, "", "x", emptyList(), emptyList(), listOf(XSection("G", "T", SRC_SHELL, "x ".repeat(2_000_000))))
        val b = XrayPoster.body(big)
        assertTrue(b.length <= XrayPoster.MAX_BODY)
        assertFalse(XrayPoster.body(build()).contains("Authorization"))
    }
}
