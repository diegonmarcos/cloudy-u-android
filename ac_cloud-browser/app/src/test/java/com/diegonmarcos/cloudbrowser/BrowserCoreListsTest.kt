package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.cloudbrowser.sites.HistoryDays
import com.diegonmarcos.cloudbrowser.sites.VisitLog
import com.diegonmarcos.cloudbrowser.sites.Visited
import com.diegonmarcos.superapp.browser.BrowserSuggest
import com.diegonmarcos.superapp.browser.BrowserTab
import com.diegonmarcos.cloudbrowser.sites.WebyBookmarks
import com.diegonmarcos.cloudbrowser.sites.WebyCore
import com.diegonmarcos.cloudbrowser.sites.WebyFolder
import com.diegonmarcos.cloudbrowser.sites.WebyTile
import com.diegonmarcos.cloudlib.browsercore.BrowserCore
import com.diegonmarcos.superapp.browser.BrowserBookmark
import com.diegonmarcos.superapp.browser.BrowserSetting
import com.diegonmarcos.superapp.browser.BrowserSettingsCatalogue
import com.diegonmarcos.superapp.browser.BrowserSitePerm
import com.diegonmarcos.superapp.browser.BrowserSitePolicy
import com.diegonmarcos.superapp.browser.BrowserBookmarkOps
import com.diegonmarcos.superapp.browser.BrowserDownload
import com.diegonmarcos.superapp.browser.BrowserDownloadIndex
import com.diegonmarcos.superapp.browser.download.DlFormat
import com.diegonmarcos.superapp.browser.download.DlCodec
import com.diegonmarcos.superapp.browser.download.DlJob
import com.diegonmarcos.superapp.browser.download.DlPolicy
import com.diegonmarcos.superapp.browser.download.DlSegment
import com.diegonmarcos.superapp.browser.download.DlState
import com.diegonmarcos.superapp.browser.BrowserFavourites
import com.diegonmarcos.superapp.browser.BrowserVisit
import com.diegonmarcos.superapp.browser.FavSeed
import com.diegonmarcos.superapp.browser.FavSeedItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * #1841 the list stores' parity (history, bookmarks, Quickmarks, the visit log). ONE fixture corpus (browser-core-rs/fixtures/lists-corpus.json)
 * holds the Rust core (cargo test, src/tests.rs) and the Kotlin twin (here); where JNI is loaded (a phone, the emulator) the core's own
 * answers are held to it too, on the JVM (no libbrowser_core.so) the twin IS what answers. The ORIGINAL Kotlin rules (BrowserBookmarkOps,
 * BrowserFavourites, VisitLog, WebyBookmarks.allRules) are the oracle for behaviour: nothing the old code did was dropped (Rule 16).
 */
class BrowserCoreListsTest {
    private val corpus = JSONObject(File("../../ab_cloud-libs-shared/libs/browser-core-rs/fixtures/lists-corpus.json").readText())

    /** A key-order-free text of a json value. */
    private fun canon(x: Any?): String = when {
        x == null || x == JSONObject.NULL -> "null"
        x is JSONObject -> x.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(x.opt(it)) }
        x is JSONArray -> (0 until x.length()).joinToString(",", "[", "]") { canon(x.opt(it)) }
        x is String -> JSONObject.quote(x)
        else -> x.toString()
    }

    private fun holds(name: String, want: JSONObject, got: JSONObject, who: String) {
        for (k in want.keys()) assertEquals("$who: $name: key $k\n got $got", canon(want.opt(k)), canon(got.opt(k)))
    }

    @Test fun `the Kotlin twin answers every case of the shared corpus`() {
        val cases = corpus.getJSONArray("cases")
        assertTrue("the corpus shrank to ${cases.length()} cases", cases.length() >= 50)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got = BrowserCore.listsTwin(c.getString("op"), JSONObject(c.getJSONObject("in").toString()))
            holds(c.getString("name"), c.getJSONObject("out"), got, "twin")
        }
    }

    @Test fun `where JNI is loaded the core answers the same corpus`() {
        val cases = corpus.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val a = JSONObject(c.getJSONObject("in").toString())
            val got = BrowserCore.lists(c.getString("op"), a)   // the core when loaded (a phone / the emulator), the twin on the JVM
            holds(c.getString("name"), c.getJSONObject("out"), got, if (BrowserCore.native) "core" else "lists()")
        }
    }

    @Test fun `field parity - every field of the Kotlin models is in the shared models, and so in the Rust ones`() {
        fun fields(c: Class<*>) = c.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        val models = corpus.getJSONObject("models")
        fun want(k: String) = models.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        assertEquals(want("visit"), fields(BrowserVisit::class.java))
        assertEquals(want("bookmark"), fields(BrowserBookmark::class.java))
        assertEquals(want("dljob"), fields(DlJob::class.java))
        assertEquals(want("dlpolicy"), fields(DlPolicy::class.java))
        assertEquals(want("download"), fields(BrowserDownload::class.java))
        // the catalogue and the permission declarations: the core reads its core fields, the rest is display metadata that stays on the Kotlin side
        assertEquals(want("setting_core") + want("setting_display"), fields(com.diegonmarcos.superapp.browser.BrowserSetting::class.java))
        assertEquals(want("sitePerm_core") + want("sitePerm_display"), fields(com.diegonmarcos.superapp.browser.BrowserSitePerm::class.java))
        // an answer's rows carry exactly those fields
        val h = BrowserCore.lists("history_record", JSONObject().put("raw", "[]").put("url", "u").put("title", "t").put("now", 1)).getJSONArray("visits").getJSONObject(0)
        assertEquals(want("visit"), h.keys().asSequence().toSet())
        val b = BrowserCore.lists("bm_add", JSONObject().put("raw", "[]").put("url", "u").put("title", "t").put("folder", "f").put("now", 1)).getJSONArray("bookmarks").getJSONObject(0)
        assertEquals(want("bookmark"), b.keys().asSequence().toSet())
    }

    // ── the old Kotlin rules as the oracle ─────────────────────────────────────────────────────────────────────────────

    private fun bm(u: String, t: String, f: String, ts: Long) = BrowserBookmark(u, t, f, ts)
    private fun bmArr(l: List<BrowserBookmark>) = BrowserBookmarkOps.toJson(l)
    private fun core(op: String, l: List<BrowserBookmark>, f: JSONObject.() -> Unit = {}): List<BrowserBookmark> =
        BrowserBookmarkOps.fromArray(BrowserCore.lists(op, JSONObject().put("bookmarks", bmArr(l)).apply(f)).optJSONArray("bookmarks"))

    private val list = listOf(
        bm("https://a.example/", "A", "", 5), bm("https://b.example/", "B", "Work", 4), bm("https://c.example/", "C", "Work/Docs", 3),
        bm("https://d.example/", "D", "Work/Docs/Deep", 2), bm("https://e.example/", "E", "Workshop", 1), bm("https://f.example/", "F", "search/engines", 0),
    )

    @Test fun `the stored text of the old store loads unchanged through the core`() {
        val raws = listOf(
            bmArr(list).toString(), "[]", "not json", "{}", "[{\"url\":\"u\"}]", "[{\"url\":\"\",\"title\":\"x\"},{\"title\":\"no url\"},3,{\"url\":\"u2\",\"title\":\"T\",\"folder\":\"a/b\",\"ts\":9}]",
            "[{\"url\":\"u\",\"title\":\"\",\"folder\":\" a \",\"ts\":\"7\"}]",
        )
        for (raw in raws) {
            val old = BrowserBookmarkOps.fromJson(raw)
            val now = BrowserBookmarkOps.fromArray(BrowserCore.lists("bm_load", JSONObject().put("raw", raw)).optJSONArray("bookmarks"))
            assertEquals("bookmarks of $raw", old, now)
        }
    }

    @Test fun `bookmark ops match the old pure rules`() {
        assertEquals(BrowserBookmarkOps.add(list, BrowserBookmark("https://b.example/", "B2", "x/y", 99)),
            core("bm_add", list) { put("url", "https://b.example/").put("title", "B2").put("folder", " x / y ").put("now", 99) })
        assertEquals(BrowserBookmarkOps.add(list, BrowserBookmark("https://new.example/", "https://new.example/", "", 7)),
            core("bm_add", list) { put("url", "https://new.example/").put("title", "").put("folder", "").put("now", 7) })
        assertEquals(BrowserBookmarkOps.remove(list, "https://c.example/"), core("bm_remove", list) { put("url", "https://c.example/") })
        for (f in listOf("Work", "Work/Docs", "Wor", "", " Work/ ", "search", "nope")) {
            assertEquals("delete $f", BrowserBookmarkOps.deleteFolder(list, f), core("bm_delete_folder", list) { put("folder", f) })
            for (t in listOf("", "Home", "Work/Moved", "a/b/c")) assertEquals("move $f -> $t", BrowserBookmarkOps.moveFolder(list, f, t), core("bm_move_folder", list) { put("from", f).put("to", t) })
        }
        assertEquals(BrowserBookmarkOps.folders(list), BrowserCore.lists("bm_folders", JSONObject().put("bookmarks", bmArr(list))).getJSONArray("folders").let { a -> (0 until a.length()).map { a.getString(it) } })
        val edited = list.map { if (it.url == "https://c.example/") it.copy(title = "C!", folder = BrowserBookmarkOps.normFolder("/Q/R/")) else it }
        assertEquals(edited, core("bm_edit", list) { put("url", "https://c.example/").put("title", "C!").put("folder", "/Q/R/") })
    }

    @Test fun `sections, chip and word filter match the old Fav page rules`() {
        val secs = BrowserFavourites.sections(list)
        val fromCore = BrowserFavourites.decodeSections(BrowserCore.lists("bm_sections", JSONObject().put("bookmarks", bmArr(list))).optJSONArray("sections"))
        assertEquals(secs, fromCore)
        for (chip in listOf(null, "Work", "search", "nope")) for (q in listOf("", "  ", "a.example", "docs", "work e", "EXAMPLE", "zzz", "d  c")) {
            assertEquals("chip=$chip q=$q", BrowserFavourites.filter(BrowserFavourites.byFilter(secs, chip), q), BrowserFavourites.view(secs, chip, q))
        }
    }

    @Test fun `the seed merge matches the old rule`() {
        val seed = FavSeed(3, listOf(FavSeedItem("S1", "https://a.example/", "Public"), FavSeedItem("S2", "https://s2.example/", " Private (mesh) "), FavSeedItem("S3", "https://f.example/", "Public")),
            emptyList(), setOf("search/engines"))
        val seen = setOf("https://a.example/")
        val (wantList, wantSeen) = BrowserFavourites.merge(list, seed, seen, 50)
        val r = BrowserCore.lists("bm_merge_seed", JSONObject().put("bookmarks", bmArr(list)).put("now", 50).put("stored_version", 1).put("seen", JSONArray(seen.toList()))
            .put("seed", JSONObject().put("version", 3).put("legacyGroups", JSONArray(seed.legacyGroups.toList()))
                .put("items", JSONArray().also { a -> seed.items.forEach { a.put(JSONObject().put("title", it.title).put("url", it.url).put("group", it.group)) } })))
        assertEquals(wantList, BrowserBookmarkOps.fromArray(r.optJSONArray("bookmarks")))
        assertEquals(wantSeen, r.getJSONArray("seen").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
        assertTrue(r.getBoolean("changed"))
    }

    @Test fun `the visit log merge matches the old rule`() {
        val log = listOf(BrowserVisit("a", "A", 3), BrowserVisit("b", "B", 1), BrowserVisit("z", "Z", 0))
        val history = listOf(BrowserVisit("a", "A", 3), BrowserVisit("c", "C", 2), BrowserVisit("c", "C", 2), BrowserVisit("d", "D", 9))
        for (cap in listOf(20000, 3, 1)) {
            val want = VisitLog.merge(log, history, cap)
            val r = BrowserCore.lists("visitlog_merge", JSONObject().put("log_raw", VisitLog.toJson(log)).put("history_raw", JSONArray().also { a -> history.forEach { a.put(JSONObject().put("url", it.url).put("title", it.title).put("ts", it.ts)) } }.toString()).put("cap", cap))
            val got = r.getJSONArray("visits").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> BrowserVisit(o.getString("url"), o.getString("title"), o.getLong("ts")) } } }
            assertEquals("cap $cap", want, got)
            assertEquals("the stored text is the old u/t/s format", want, VisitLog.fromJson(r.getString("raw")))
        }
    }

    @Test fun `Weby's Bookmarks (all) matches the old rule and a Quickmarks store round-trips every field`() {
        val quick = listOf(WebyFolder("Primary", listOf(WebyTile("p1", "One", "https://one", "com.one", "ic1")), listOf(WebyFolder("Sub", listOf(WebyTile("p2", "Two", null))))))
        val cat = listOf(WebyFolder("Phone apps", emptyList(), listOf(WebyFolder("F", listOf(WebyTile("c", "C", null, null, "icc"))))))
        for (browser in listOf(emptyList(), listOf(Triple("https://a.org/x", "A", ""), Triple("https://b.org", " ", "Work/Docs"), Triple("https://c.org/", "C", "Work/Docs"), Triple("nourl", "N", "Unsorted")))) {
            assertEquals(WebyBookmarks.allRules(quick, browser, cat), WebyBookmarks.all(quick, browser, cat))
            assertEquals(WebyBookmarks.allRules(emptyList(), browser, emptyList()), WebyBookmarks.all(emptyList(), browser, emptyList()))
        }
        // the store: pkg survives, a blank url is none; the icon is not persisted (as before) but passes through the merge
        val saved = BrowserCore.lists("weby_encode", JSONObject().put("folders", WebyCore.toCore(quick))).getString("raw")
        val back = WebyCore.fromCore(BrowserCore.lists("weby_load", JSONObject().put("raw", saved)).getJSONArray("folders"))
        assertEquals(quick.map { f -> f.copy(tiles = f.tiles.map { it.copy(icon = null) }) }, back)
        assertEquals(com.diegonmarcos.cloudbrowser.sites.WebyOps.fromJson(saved), back)
    }

    @Test fun `history record follows the old rule - dedupe, move up, blank title, about blank, cap`() {
        fun record(l: List<BrowserVisit>, url: String, title: String, now: Long, cap: Int): List<BrowserVisit> {
            if (url.isBlank() || url == "about:blank") return l.sortedByDescending { it.ts }
            val list = l.sortedByDescending { it.ts }.filterNot { it.url == url }.toMutableList()
            list.add(0, BrowserVisit(url, title.ifBlank { url }, now))
            return list.take(cap)
        }
        var l = emptyList<BrowserVisit>()
        var want = emptyList<BrowserVisit>()
        val steps = listOf("https://a/" to "A", "https://b/" to "", "about:blank" to "x", "https://a/" to "A again", " " to "t", "https://c/" to "C", "https://d/" to "D", "https://b/" to "B")
        steps.forEachIndexed { i, (u, t) ->
            want = record(want, u, t, 100L + i, 4)
            val r = BrowserCore.lists("history_record", JSONObject().put("visits", JSONArray().also { a -> l.forEach { a.put(JSONObject().put("url", it.url).put("title", it.title).put("ts", it.ts)) } })
                .put("url", u).put("title", t).put("now", 100L + i).put("cap", 4))
            l = r.getJSONArray("visits").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> BrowserVisit(o.getString("url"), o.getString("title"), o.getLong("ts")) } } }
            assertEquals("after step $i", want, l)
        }
    }

    // ── phase 2: downloads ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the download index matches the old rules`() {
        for (raw in listOf("[]", "oops", "[{\"id\":5,\"url\":\"u\",\"file\":\"f\",\"mime\":\"m\",\"ts\":9},{\"url\":\"noid\"},3,{\"id\":\"7\"}]")) {
            assertEquals(raw, BrowserDownloadIndex.fromJson(raw), BrowserDownloadIndex.fromArray(BrowserCore.lists("dl_index_load", JSONObject().put("raw", raw)).optJSONArray("downloads")))
        }
        var want = emptyList<BrowserDownload>()
        var raw = "[]"
        for (i in 0 until BrowserDownloadIndex.CAP + 5) {
            val d = BrowserDownload((i % 150).toLong(), "u$i", "f", "m", i.toLong())
            want = BrowserDownloadIndex.add(want, d)
            raw = BrowserCore.lists("dl_index_add", JSONObject().put("raw", raw).put("cap", BrowserDownloadIndex.CAP)
                .put("download", JSONObject().put("id", d.id).put("url", d.url).put("file", d.file).put("mime", d.mime).put("ts", d.ts))).getString("raw")
        }
        assertEquals(want, BrowserDownloadIndex.fromJson(raw))
        for (c in listOf(1, 2, 4, 8, 16, 3)) assertEquals(BrowserDownloadIndex.status(c), BrowserCore.lists("dl_status", JSONObject().put("code", c)).getString("status"))
    }

    @Test fun `sizes in words match java's own formatting`() {
        fun old(n: Long): String {
            if (n < 0) return "?"
            if (n < 1024) return "$n B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var v = n / 1024.0; var i = 0
            while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
            return String.format(java.util.Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
        }
        val ns = (-2L..6000L).toList() + (1..60).map { it * 1000L * 37 } + (0..40).map { 1L shl it } + (1..40).map { (1L shl it) * 3 + 640 } + listOf(1024L * 1024 * 1024 * 1024 * 1024)
        for (n in ns) assertEquals("bytes $n", old(n), DlFormat.bytes(n))
        assertEquals("2.0 KB/s", DlFormat.speed(2048)); assertEquals("--", DlFormat.eta(-1)); assertEquals("1h 1m", DlFormat.eta(3700))
    }

    @Test fun `a download job survives the core's codec with every field`() {
        val j = DlJob(id = "a", url = "https://x/f.bin", fileName = "f.bin", mime = "application/octet-stream", dir = "d", kind = "http", userAgent = "ua", referer = "r", cookie = "c=1",
            checksum = "sum", state = DlState.PAUSED, total = 100, downloaded = 40, etag = "e", lastModified = "lm", acceptRanges = true, segments = listOf(DlSegment(0, 49, 10), DlSegment(50, 99, 0)),
            attempts = 2, nextAttemptAt = 7, error = "boom", file = "/f", publishedUri = "content://p", saveToSharedStore = true, createdAt = 5, finishedAt = 6,
            infoHash = "h", selectedFiles = listOf(0, 2), sequential = true, seed = true)
        val bare = DlJob(id = "b", url = "u", fileName = "n")
        assertEquals(listOf(j, bare), DlCodec.fromJson(DlCodec.toJson(listOf(j, bare))))
        val running = j.copy(state = DlState.RUNNING)
        assertEquals(DlState.QUEUED, DlCodec.fromJson(DlCodec.toJson(listOf(running)), requeue = true).single().state)
        assertEquals(DlState.RUNNING, DlCodec.fromJson(DlCodec.toJson(listOf(running))).single().state)
        val p = DlPolicy(parallel = 2, wifiOnly = true, allowMetered = false, speedLimitKbps = 500, segments = 6, sharedStore = true, seed = true, seedRatio = 150, seedMinutes = 30, uploadKbps = 9)
        assertEquals(p, DlPolicy.fromJson(p.toJson().toString()))
    }

    // ── phase 2: settings and site permissions, the old Kotlin rules as the oracle ─────────────────────────────────────────

    private val catalogue = BrowserSettingsCatalogue(listOf(
        BrowserSetting("desktop", "bool", false), BrowserSetting("zoom", "int", 100, min = 50, max = 300), BrowserSetting("lo", "int", null, min = 5),
        BrowserSetting("theme", "enum", "auto", values = listOf("auto", "dark", "light")), BrowserSetting("addons", "set", setOf("a"), values = listOf("a", "b", "c")),
        BrowserSetting("anyset", "set", emptySet<String>()), BrowserSetting("home", "string", "about:blank"),
    ))

    @Test fun `setting validation matches the catalogue's own rules, message for message`() {
        val raws = listOf("true", " YES ", "off", "maybe", "150", "abc", "10", "301", "-5", "99999999999", "", "dark", " dark ", "blue", "a, c ,a", "a,z,y", "x,y", "  hi ", "+7", "3")
        for (key in listOf("desktop", "zoom", "lo", "theme", "addons", "anyset", "home", "nope")) for (raw in raws) {
            val old = runCatching { catalogue.parseValue(key, raw) }
            val r = BrowserCore.lists("set_parse", JSONObject().put("catalogue", catalogue.toJson()).put("key", key).put("raw", raw))
            if (old.isFailure) assertEquals("$key=$raw", old.exceptionOrNull()!!.message, r.optString("error"))
            else {
                assertTrue("$key=$raw gave ${r}", !r.has("error"))
                val v = r.get("value")
                val got: Any = if (v is JSONArray) (0 until v.length()).map { v.getString(it) }.toSet() else v
                assertEquals("$key=$raw", old.getOrNull(), got)
            }
        }
    }

    @Test fun `the settings snapshot matches the catalogue's own`() {
        val stored = mapOf<String, Any?>("desktop" to true, "zoom" to 150, "addons" to setOf("c", "a"))
        val old = catalogue.snapshot { stored[it.key] }
        val values = JSONObject().also { o -> stored.forEach { (k, v) -> o.put(k, if (v is Set<*>) JSONArray(v.toList()) else v) } }
        val got = BrowserCore.lists("set_snapshot", JSONObject().put("catalogue", catalogue.toJson()).put("values", values))
        assertEquals(canon(old), canon(got))
    }

    private val rules = mapOf("example.org|camera" to "deny", "*|camera" to "allow", "a.example.org|mic" to "ask", "*|mic" to "deny", "example.org|zoom" to "500", "b.example.org|zoom" to "abc", "*|zoom" to "90",
        "x.com|camera" to "ask", "y.co.uk|zoom" to "120", "y.co.uk|mic" to "allow")

    @Test fun `site permission resolution, zoom and hosts match the old rules`() {
        for (host in listOf("example.org", "A.B.example.org", "b.example.org", "x.com", "www.x.com", "y.co.uk", "z.y.co.uk", "nowhere.net", "", "org", "a.example.org"))
            for (perm in listOf("camera", "mic", "geolocation", "zoom", "notifications"))
                assertEquals("$host $perm", BrowserSitePolicy.resolveRules(rules, host, perm, "ask"), BrowserSitePolicy.resolve(rules, host, perm, "ask"))
        assertEquals(300, BrowserSitePolicy.zoomOf(rules, "example.org")); assertEquals(null, BrowserSitePolicy.zoomOf(rules, "b.example.org"))
        assertEquals(120, BrowserSitePolicy.zoomOf(rules, "a.y.co.uk")); assertEquals(null, BrowserSitePolicy.zoomOf(rules, "none.net"))
        assertEquals(BrowserSitePolicy.hostsWithRulesRules(rules), BrowserSitePolicy.hostsWithRules(rules))
        assertEquals(BrowserSitePolicy.hostsWithRulesRules(rules).keys.toList(), BrowserSitePolicy.hostsWithRules(rules).keys.toList())
        val perms = listOf(BrowserSitePerm("a", "A", "ask"), BrowserSitePerm("b", "B", "allow", top = true), BrowserSitePerm("c", "C", "deny"), BrowserSitePerm("d", "D", "ask", top = true))
        assertEquals(perms.filter { it.top } + perms.filterNot { it.top }, BrowserSitePolicy.ordered(perms))
        assertEquals("allow", BrowserSitePolicy.defaultOf(rules, perms[0].copy(id = "camera"))); assertEquals("deny", BrowserSitePolicy.defaultOf(rules, perms[2]))
        for (st in listOf("allow", "deny", "ask")) {
            assertEquals(st != "allow", BrowserSitePolicy.autoplayNeedsGesture(st))
            assertEquals(when (st) { "allow" -> 0; "deny" -> 1; else -> 2 }, BrowserSitePolicy.mixedContentMode(st))
            assertEquals(st != "deny", BrowserSitePolicy.onUnlessBlocked(st))
            for (g in listOf(true, false)) assertEquals(when (st) { "allow" -> true; "deny" -> false; else -> g }, BrowserSitePolicy.popupAllowed(st, g))
        }
    }
}
