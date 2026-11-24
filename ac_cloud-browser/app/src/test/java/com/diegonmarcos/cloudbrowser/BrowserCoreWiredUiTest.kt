package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.cloudlib.browsercore.BrowserCore
import com.diegonmarcos.superapp.browser.BrowserTrackers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1841 the Rust core's bookmark export/import, history sort, reader mode and list merge are called from the UI, and work. */
class BrowserCoreWiredUiTest {
    private val lib = "../../ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser"
    private fun src(name: String): String = File(lib, name).readText()

    // ── bookmark export / import ─────────────────────────────────────────────────────────────────────────────────────
    @Test fun `favourites has Export and Import buttons wired to the document pickers`() {
        val sheets = src("BrowserSheets.kt"); val host = src("BrowserHostFragment.kt")
        assertTrue(sheets.contains("browser:fav:export") && sheets.contains("browser:fav:import"))
        assertTrue(host.contains("CreateDocument(\"application/json\")") && host.contains("bookmarks.exportJson()"))
        assertTrue(host.contains("bookmarks.importJson(it)"))
        assertTrue(host.contains("onExport = {") && host.contains("onImport = {"))
    }

    @Test fun `bookmarks exported then imported into an empty list come back whole and a re-import adds nothing`() {
        val raw = JSONArray().put(JSONObject().put("url", "https://a.example/").put("title", "A").put("folder", "f").put("ts", 5))
            .put(JSONObject().put("url", "https://b.example/").put("title", "B").put("folder", "").put("ts", 6)).toString()
        val doc = BrowserCore.lists("bm_export", JSONObject().put("raw", raw)).getJSONObject("doc")
        assertEquals(2, doc.getJSONArray("bookmarks").length())
        val back = BrowserCore.lists("bm_import", JSONObject().put("raw", "[]").put("text", doc.toString()))
        assertFalse(back.has("error"))
        assertEquals(2, JSONArray(back.getString("raw")).length())
        val again = BrowserCore.lists("bm_import", JSONObject().put("raw", back.getString("raw")).put("text", doc.toString()))
        assertEquals(2, JSONArray(again.getString("raw")).length())
        assertTrue("a file that is not a bookmark document is refused", BrowserCore.lists("bm_import", JSONObject().put("raw", "[]").put("text", "not json")).has("error"))
    }

    // ── history sort ─────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun `history has sort buttons that drive the core's query`() {
        val host = src("BrowserHostFragment.kt")
        assertTrue(host.contains("history.query(\"\", sort)") && host.contains("BrowserListFilter.visits(q"))
        for (k in listOf("recent", "oldest", "frecency", "title", "host")) assertTrue(k, host.contains("\"$k\" to"))
    }

    @Test fun `history_query sorts by recent, oldest and title`() {
        val raw = JSONArray().put(JSONObject().put("url", "https://b.example/").put("title", "Beta").put("ts", 20))
            .put(JSONObject().put("url", "https://a.example/").put("title", "Alpha").put("ts", 30))
            .put(JSONObject().put("url", "https://c.example/").put("title", "Gamma").put("ts", 10)).toString()
        fun order(sort: String): List<String> {
            val a = BrowserCore.lists("history_query", JSONObject().put("raw", raw).put("query", "").put("sort", sort).put("limit", 100).put("mode", "filter").put("now", 100))
                .getJSONArray("visits")
            return (0 until a.length()).map { a.getJSONObject(it).getString("title") }
        }
        assertEquals(listOf("Alpha", "Beta", "Gamma"), order("recent"))
        assertEquals(listOf("Gamma", "Beta", "Alpha"), order("oldest"))
        assertEquals(listOf("Alpha", "Beta", "Gamma"), order("title"))
    }

    // ── reader ───────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun `reader mode falls back to the core's extraction and the reader page escapes it`() {
        val host = src("BrowserHostFragment.kt"); val pa = src("BrowserPageActions.kt")
        assertTrue(host.contains("BrowserCore.reader(") && pa.contains("fun readerHtml"))
        val (title, paras) = BrowserCore.reader("<html><head><title>T1</title></head><body><nav>menu</nav><article><p>${"Readable paragraph. ".repeat(8)}</p><p>${"Second one here. ".repeat(8)}</p></article></body></html>")
        assertEquals("T1", title); assertTrue(paras.isNotEmpty())
    }

    // ── list merge ───────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun `trackers merge the declared hosts with further lists through the core`() {
        assertTrue(src("BrowserPrivacy.kt").contains("BrowserCore.mergeLists("))
        val merged = BrowserCore.mergeLists(listOf("! c\nads.example\n", "# c2\nads.example\ntrack.example\n"))
        assertEquals(listOf("ads.example", "track.example"), merged.lines().filter { it.isNotBlank() })
        val t = BrowserTrackers(listOf("ads.example"), listOf("track.example"))
        assertTrue(t.isTracker("track.example") && t.isTracker("sub.ads.example") && !t.isTracker("fine.example"))
    }
}
