package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.cloudbrowser.sites.VisitLog
import com.diegonmarcos.cloudbrowser.sites.WebyBookmarks
import com.diegonmarcos.cloudbrowser.sites.WebyCore
import com.diegonmarcos.cloudbrowser.sites.WebyFolder
import com.diegonmarcos.cloudbrowser.sites.WebyTile
import com.diegonmarcos.cloudlib.browsercore.BrowserCore
import com.diegonmarcos.superapp.browser.BrowserBookmark
import com.diegonmarcos.superapp.browser.BrowserBookmarkOps
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
}
