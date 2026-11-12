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
    private val GFX = "Total frames rendered: 100\nJanky frames: 40 (40.00%)\n50th percentile: 20ms\n90th percentile: 40ms\nNumber Slow UI thread: 30\n"
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

    /** What a stressed device answers on top of [fixtureFor]: 40% jank, thermal status 3, /data 93% full, a recorded crash. */
    private fun richFor(cmd: String): String = when {
        cmd.startsWith("dumpsys gfxinfo") -> GFX
        cmd.startsWith("dumpsys thermalservice") -> "Thermal Status: 3\n  Temperature{mValue=47.5, mType=3, mName=skin, mStatus=2}\n"
        cmd.startsWith("df -h") -> "Filesystem Size Used Avail Use% Mounted on\n/dev/block/dm-9 220G 205G 15G 93% /data\n"
        cmd.startsWith("dumpsys diskstats") -> "Data-Free: 15728640K / 230686720K total = 7% free\nPackage Names: [\"com.big\"]\nApp Sizes: [5000000000]\nApp Data Sizes: [0]\nCache Sizes: [0]\n"
        cmd.startsWith("dumpsys dropbox") -> "2026-11-05 12:07:44 data_app_crash (text, 1 bytes)\nProcess: com.diegonmarcos.fleet\n"
        else -> fixtureFor(cmd)
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
        // #1623: Configs is one page of six flat groups, and the X-Ray-walked pages now span two of them — the Configs group
        // (Perms, Logcat, X-Ray App, About, and the hidden Battery) and the Tools group's observability pair (X-Ray Device,
        // Logcat Fleet). Collect the diagnostic pages of both groups — every page there, visible and hidden, except the
        // external Watchdog launch (it declares an action), the Agents hub (hub_sections) and the layout separators. The
        // UI-tree order no longer reflects the X-Ray walk order (the pages split across two groups), so compare as a SET;
        // XrayTree.PAGES is the X-Ray-internal walk order, still the one source of truth for what is walked.
        val pages = find(root)!!.getJSONArray("pages")
        val declared = (0 until pages.length()).map { pages.getJSONObject(it) }
            .filter { it.optString("group") in setOf("Tools", "Configs") && it.optString("action").isEmpty() &&
                !it.optBoolean("separator", false) && !it.has("hub_sections") }
            .map { it.getString("id") }
        assertEquals(XrayTree.PAGES.toSet(), declared.toSet())
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

    @Test fun `the probe list is ordered Profile Scan then the trackers 01-12 and carries every old and new probe`() {
        val ids = XrayProbes.all.map { it.id }
        val old = listOf("profile", "packages", "deviceidle", "buckets", "appops", "listeners", "a11y", "autofill", "credential", "thermal", "top", "battery", "netstats",
            "batterystats", "batterystats-top", "services", "startproc", "jobs", "alarm", "power", "gfxinfo", "surfaceflinger", "notification", "binder", "procstats", "meminfo", "anr", "logcat", "perms")
        for (n in old) assertTrue("nothing deleted: $n", n in ids)
        // #1666 locops: the system's location app-ops for this app (X-Ray App ▸ Location use)
        val new = listOf("df", "diskstats", "dropbox", "connectivity", "telephony", "procs", "pkgperms", "locops")
        for (n in new) assertTrue("new probe $n", n in ids)
        assertEquals(old.size + new.size, ids.size)
        // macro order: the Profile Scan group first, then the trackers by number; the group index never goes back
        val order = listOf(XrayProbes.G_DEVICE) + XrayProbes.trackers.map { it.group }
        val idx = XrayProbes.all.map { order.indexOf(it.group) }
        assertTrue("every probe sits in the Profile Scan or a tracker", idx.all { it >= 0 })
        assertEquals("probes never go back to an earlier tracker", idx.sorted(), idx)
        assertEquals(13, XrayProbes.trackers.size); assertEquals((1..13).toList(), XrayProbes.trackers.map { it.num })
        fun g(id: String) = XrayProbes.all.first { it.id == id }.group
        assertEquals(XrayProbes.G_PERF, g("top")); assertEquals(XrayProbes.G_RENDER, g("gfxinfo")); assertEquals(XrayProbes.G_RENDER, g("surfaceflinger"))
        assertEquals(XrayProbes.G_STORAGE, g("df")); assertEquals(XrayProbes.G_STORAGE, g("diskstats"))
        for (b in listOf("battery", "batterystats", "batterystats-top", "jobs", "alarm", "power")) assertEquals(XrayProbes.G_BATTERY, g(b))
        assertEquals(XrayProbes.G_THERMAL, g("thermal"))
        assertEquals(XrayProbes.G_MEMORY, g("procstats")); assertEquals(XrayProbes.G_MEMORY, g("meminfo"))
        assertEquals(XrayProbes.G_NOTIF, g("notification")); assertEquals(XrayProbes.G_NOTIF, g("binder"))
        assertEquals(XrayProbes.G_RESP, g("anr"))
        assertEquals(XrayProbes.G_CRASH, g("dropbox")); assertEquals(XrayProbes.G_CRASH, g("logcat"))
        for (n in listOf("netstats", "connectivity", "telephony")) assertEquals(XrayProbes.G_NETWORK, g(n))
        for (n in listOf("services", "startproc", "procs")) assertEquals(XrayProbes.G_PROCESS, g(n))
        assertEquals(XrayProbes.G_SECURITY, g("perms")); assertEquals(XrayProbes.G_SECURITY, g("pkgperms"))
        for (n in listOf("profile", "packages", "deviceidle", "buckets", "appops", "listeners", "a11y", "autofill", "credential")) assertEquals(XrayProbes.G_DEVICE, g(n))
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
        assertTrue(names.containsAll(listOf("Report", XrayProbes.G_BATTERY, XrayProbes.G_PERF, XrayProbes.G_RENDER, XrayProbes.G_DEVICE, "X-Ray · APP & BUILD")))
        val s = groups.getJSONObject(0).getJSONArray("sections").getJSONObject(0)
        assertTrue(s.has("title") && s.has("source") && s.has("ok") && s.has("text"))
    }

    @Test fun `the verbose Markdown follows Profile Scan, the trackers 01-12 and the 13th Suspects verdict in order, and names each source`() {
        val md = XrayFormat.markdown(build())
        assertTrue(md.startsWith("# X-Ray report · Pixel Test"))
        val order = (listOf(XrayProbes.G_DEVICE) + XrayProbes.trackers.map { it.group }).map { md.indexOf("\n# $it\n") }
        assertTrue("every macro group is present: $order", order.all { it >= 0 })
        assertEquals("in order", order.sorted(), order)
        assertTrue(md.indexOf("## Suspects (ranked)") > md.indexOf("\n# ${XrayProbes.G_PROCESS}\n"))
        assertTrue("the verdict precedes the walked pages", md.indexOf("\n# ${XrayProbes.G_SUSPECTS}\n") < md.indexOf("\n# X-Ray · APP & BUILD\n"))
        assertTrue(md.contains("_source: shell_"))
        assertTrue(md.contains("_source: app sandbox_"))
        assertTrue(md.contains("**com.diegonmarcos.fleet** (fleet)"))
        assertFalse(md.contains(PARTIAL_LABEL))
    }

    // ── the two light renderings: HTML for a person, compact Markdown for an AI ───

    /** A shell that answers like a device that has plenty to say: every unknown command returns 400 lines of raw dump noise. */
    private fun noisyShell(): XrayShell = object : XrayShell {
        override val channel = "embedded-adb"
        override fun exec(cmd: String): String? = richFor(cmd).let { f -> if (f.startsWith("ok for")) (1..400).joinToString("\n") { "  raw dump line $it: value=${it * 7919} some=noise" } else f + (1..400).joinToString("\n", prefix = "\n") { "  extra raw line $it" } }
    }

    private fun noisy() = XrayExporter.build(XrayInputs("Pixel Test", "1.0 abc", 1_790_000_000_000L, walk, noisyShell(), extra = listOf(XSection(XrayProbes.G_BATTERY, "Battery history", SRC_APP, "d1 | 9.0"))))

    @Test fun `the HTML is self-contained and carries the macro sections, every tracker and a status light each`() {
        val h = XrayFormat.html(noisy())
        assertTrue(h.startsWith("<!doctype html>"))
        assertFalse("no external deps", Regex("""(src|href)="https?://""").containsMatchIn(h) || h.contains("<link") || h.contains("<script"))
        assertTrue(h.contains("<style>"))
        fun light(n: Int, c: String) = Regex("""id="t$n"( open)?><summary><span class="dot $c"></span><h2 style="display:inline">%02d """.format(n)).containsMatchIn(h)
        val order = listOf("id=\"profile\"", "Profiling Trackers</h2>") + XrayProbes.trackers.map { "id=\"t${it.num}\"" }
        val idx = order.map { h.indexOf(it) }
        assertTrue("macro sections and all 13 trackers present: $idx", idx.all { it >= 0 })
        assertEquals("in order", idx.sorted(), idx)
        assertTrue(h.contains(XrayProbes.G_DEVICE))
        for (t in XrayProbes.trackers) assertTrue("tracker ${t.num} ${t.label} is named, with a status", h.contains(t.label.replace("&", "&amp;")) && listOf("ok", "warn", "bad", "na").any { light(t.num, it) })
        assertTrue(h.contains("<details"))
        assertTrue("jank 40% is red", light(2, "bad"))
        assertTrue("storage 93% used is red", light(3, "bad"))
        assertTrue("thermal status 3 is red", light(5, "bad"))
        assertTrue("inline bar meters", h.contains("class=\"bar") && h.contains("style=\"width:"))
        assertTrue("verdict summarised on top, full at the end", h.indexOf("Verdict (summary)") < h.indexOf(XrayProbes.G_DEVICE) && h.indexOf("id=\"t13\"") > h.indexOf("id=\"t12\""))
        assertTrue(h.contains("com.diegonmarcos.fleet"))
        assertFalse("html escapes", h.contains("<b>x</b>"))
    }

    @Test fun `a partial export marks the trackers na in both renderings`() {
        val r = XrayExporter.build(XrayInputs("Pixel", "1", 1L, walk, null, "no adb"))
        val h = XrayFormat.html(r); val c = XrayFormat.markdownCompact(r)
        assertTrue(h.contains("class=\"dot na\"") && h.contains(PARTIAL_LABEL))
        assertTrue(c.contains("T02:na") && c.contains("T13:na"))
        assertTrue("an unread tracker is one line", c.lines().count { it.startsWith("## T03") } == 1 && !c.lines().any { it.startsWith("df:") })
    }

    @Test fun `the compact Markdown has the trackers in order, the verdict first, and is far shorter than the verbose form`() {
        val r = noisy()
        val v = XrayFormat.markdown(r); val c = XrayFormat.markdownCompact(r)
        assertTrue(c.startsWith("xray/2 Pixel Test"))
        val sections = c.lines().filter { it.startsWith("## ") }
        assertEquals(listOf("## VERDICT", "## STATUS", "## P device"), sections.take(3).map { it.substringBefore(" st:") })
        val trackers = sections.drop(3)
        assertEquals((1..13).map { "%02d".format(it) }, trackers.map { it.substring(4, 6) })
        assertEquals(XrayProbes.trackers.map { it.label }, trackers.map { it.substring(7).substringBefore(" st:") })
        assertTrue("verdict before the trackers", c.indexOf("## VERDICT") < c.indexOf("## T01"))
        assertTrue(c.contains("T02:bad") && c.contains("T03:bad") && c.contains("T05:bad"))
        assertTrue("signals survive: janky frames, disk use, top suspect", c.contains("jank 40.0%") && c.contains("data 93% used") && c.contains("dm.fleet*"))
        assertTrue("crash parsed", c.contains("data_app_crash"))
        assertFalse("no raw dump noise: " + c.lines().filter { it.contains("raw") }.take(5), c.contains("raw dump line 2") || c.contains("extra raw line"))
        assertFalse("no code fences or emoji headers", c.contains("```") || c.contains("📤"))
        assertTrue("package prefix abbreviated, legend kept", c.contains("dm.=com.diegonmarcos.") && !c.substringAfter("\n").substringAfter("\n").contains("com.diegonmarcos."))
        // materially shorter: well under a tenth of the verbose characters and lines
        assertTrue("chars ${c.length} vs ${v.length}", c.length * 10 < v.length)
        assertTrue("lines ${c.lines().size} vs ${v.lines().size}", c.lines().size * 10 < v.lines().size)
        assertTrue(XrayFormat.html(r).length * 10 < v.length)
    }

    @Test fun `a tracker with nothing to say collapses to one line`() {
        val r = build()
        val c = XrayFormat.markdownCompact(r)
        // the plain fixture answers "ok for ..." to the unparsed probes: no signal, so the tracker is a single header line
        for (n in listOf("T08", "T10", "T12")) {
            val i = c.lines().indexOfFirst { it.startsWith("## $n") }
            assertTrue(n, i >= 0 && (c.lines().getOrNull(i + 1)?.startsWith("## ") ?: true))
        }
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
