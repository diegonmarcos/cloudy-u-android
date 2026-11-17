package com.diegonmarcos.superapp.appstore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1772 Search > Phone and Torrent as icon grids: the grid adapter, the catalogue's image field and a parity proof that no field was dropped. Pure, no device. */
class StoreGridTest {
    private fun repoFile(rel: String): File = listOf(rel, "../$rel", "../../$rel", "../../../$rel", "../../../../$rel", "../../../../../$rel").map(::File).firstOrNull { it.exists() }
        ?: error("$rel not found from ${File(".").absolutePath}")

    private fun catalogue() = JSONObject(repoFile("data/fleet/torrent-catalogue.json").readText())
    private fun entries(): List<JSONObject> = catalogue().getJSONArray("entries").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun rec(o: JSONObject) = StoreRecs.Rec(o.getString("id"), o.getString("name"), o.getString("category"), o.optLong("size_bytes", -1L), o.optString("licence"),
        o.optString("publisher"), o.optString("source_page"), o.optString("torrent_url").ifEmpty { null }, o.optString("magnet").ifEmpty { null },
        o.optString("info_hash").ifEmpty { null }, null, o.optString("image"))

    // ── grid adapter ──────────────────────────────────────────────────
    @Test fun theGridUsesTheLauncherColumns() {
        assertEquals(4, StoreGrid.columns(320)); assertEquals(4, StoreGrid.columns(360)); assertEquals(5, StoreGrid.columns(411)); assertEquals(5, StoreGrid.columns(600))
        assertEquals(32, StoreGrid.ICON_DP)
    }

    @Test fun rowsChunkInOrderAndKeepEveryItem() {
        val items = (1..11).toList()
        val rows = StoreGrid.rows(items, 4)
        assertEquals(listOf(4, 4, 3), rows.map { it.size })
        assertEquals(items, rows.flatten())
        assertEquals(emptyList<List<Int>>(), StoreGrid.rows(emptyList<Int>(), 5))
        assertEquals(listOf(listOf(1), listOf(2)), StoreGrid.rows(listOf(1, 2), 0)) // a bad column count never throws
    }

    @Test fun fallbackTileIsDeterministicWithAnInitial() {
        assertEquals(StoreGrid.colorOf("movies"), StoreGrid.colorOf("movies"))
        assertEquals("N", StoreGrid.initial("  Newpipe")); assertEquals("1", StoreGrid.initial("1Password")); assertEquals("?", StoreGrid.initial("  "))
        assertEquals("É", StoreGrid.initial("élan"))
    }

    @Test fun phoneTileCarriesNameSourceBadgeAndTheRealIconUrl() {
        val r = StoreSuggest.Result("k", "org.schabi.newpipe", "NewPipe", "TeamNewPipe", listOf("fdroid", "izzy"), true, "", "", "https://f-droid.org/repo/icons-640/x.png")
        val t = StoreGrid.phoneTile(r, r.icon) { if (it == "fdroid") "F-Droid" else it }
        assertEquals("NewPipe", t.label); assertEquals("F-Droid", t.badge); assertEquals("org.schabi.newpipe", t.sub)
        assertEquals("https://f-droid.org/repo/icons-640/x.png", StoreGrid.imagePlan(t))
        val none = StoreGrid.phoneTile(r, "") { it }
        assertNull("no icon known: the generated tile", StoreGrid.imagePlan(none)); assertEquals("N", none.initial)
        assertNull("only https is ever fetched", StoreGrid.imagePlan(StoreGrid.phoneTile(r, "http://x/y.png") { it }))
    }

    @Test fun mergeKeepsTheFirstIconAnySourceKnows() {
        val hits = listOf(StoreSuggest.Hit("fdroid", "a.b", "A"), StoreSuggest.Hit("izzy", "a.b", "A", icon = "https://i/a.png"), StoreSuggest.Hit("github", "", "o/a", repo = "https://github.com/o/a", icon = "https://avatars/o.png"))
        val m = StoreSuggest.merge(listOf("fdroid", "izzy", "github"), hits)
        assertEquals("https://i/a.png", m.first { it.pkg == "a.b" }.icon)
        assertEquals("a repo-only GitHub hit keeps its avatar", "https://avatars/o.png", m.first { it.key.startsWith("repo:") }.icon)
    }

    @Test fun fdroidIconsComeFromTheRepoListing() {
        val json = """{"repo":{},"apps":[{"packageName":"a.b","name":"A","icon":"a.b.12.png"},{"packageName":"c.d","name":"C"},{"packageName":"e.f","icon":"e.f.3.png"}],"packages":{}}"""
        assertEquals(mapOf("a.b" to "https://f-droid.org/repo/icons-640/a.b.12.png"), FDroidIndex.icons(json.byteInputStream(), setOf("a.b", "c.d"), "https://f-droid.org/repo/"))
        assertEquals("https://f-droid.org/repo/icons-640/a.b.12.png", FDroidIndex.search(json.byteInputStream(), "a.b", 5, "https://f-droid.org/repo/").first().icon)
        assertEquals("", FDroidIndex.iconUrl("https://f-droid.org/repo/", ""))
    }

    // ── catalogue image field ─────────────────────────────────────────
    @Test fun everyCatalogueEntryHasAnImageUrlOrTheFallbackTile() {
        val es = entries(); assertTrue(es.size >= 90)
        for (o in es) {
            val t = StoreGrid.torrentTile(rec(o), null)
            val plan = StoreGrid.imagePlan(t)
            if (plan != null) assertTrue(o.getString("id") + " image is https", plan.startsWith("https://"))
            else { assertTrue(o.getString("id") + " fallback has an initial", t.initial.isNotEmpty()); assertEquals(StoreGrid.colorOf(o.getString("category")), t.color) }
            assertNotNull(plan) // the catalogue declares an image for every entry; the fallback is only for a failed or missing load
        }
    }

    @Test fun theCatalogueSchemaCarriesTheImageField() {
        val c = catalogue()
        assertTrue(c.getString("_doc").contains("image"))
        val required = setOf("id", "name", "category", "publisher", "licence", "size_bytes", "info_hash", "magnet", "torrent_url", "source_page")
        for (o in entries()) {
            val keys = o.keys().asSequence().toSet()
            assertTrue(o.getString("id") + " keeps every pre-existing field", keys.containsAll(required))
            assertTrue(o.getString("id") + " has image", "image" in keys && o.get("image") is String)
            assertEquals(required + "image", keys)
        }
        // archive.org items use the item thumbnail service with the item's own identifier
        for (o in entries()) {
            val m = Regex("https://archive\\.org/details/([^/?#]+)").find(o.getString("source_page")) ?: continue
            val img = o.getString("image")
            assertTrue(o.getString("id"), img == "https://archive.org/services/img/${m.groupValues[1]}" || img.startsWith("https://commons.wikimedia.org/wiki/Special:FilePath/"))
        }
        // the parser keeps image optional: an entry without one is still valid (fallback tile)
        assertEquals("", rec(JSONObject("""{"id":"x","name":"X","category":"os"}""")).image)
    }

    // ── parity: nothing dropped ───────────────────────────────────────
    @Test fun everyTorrentFieldTheRowShowedIsStillReachable() {
        val recs = entries().map(::rec)
        val before = StoreRecs.top(recs)                                // the legitimacy filter + top-10 cut are unchanged
        assertEquals(StoreRecs.TORRENT_CATEGORIES.map { it.id }, before.map { it.first.id })
        assertEquals(recs.filter(StoreRecs::legit).groupBy { it.category }.mapValues { minOf(it.value.size, StoreRecs.TOP) },
            before.filter { it.second.isNotEmpty() }.associate { it.first.id to it.second.size })
        val st = StoreRecs.Stat(12, 3, "Internet Archive")
        for ((_, rows) in before) for (r in rows) {
            val t = StoreGrid.torrentTile(r, st)
            assertEquals(r.name, t.label)                                         // name
            assertEquals(StoreTorrentHost.sizeText(r.sizeBytes), t.sub)           // size
            assertEquals("S 12 / L 3", t.badge)                                   // S/L when known
            assertEquals("", StoreGrid.torrentTile(r, null).badge)                // and no badge when unknown
            // the old row's second line, character for character, is the tile's detail
            val legacy = StoreTorrentHost.sizeText(r.sizeBytes) + "  ·  S " + StoreTorrentHost.seedText(12) + " / L " + StoreTorrentHost.seedText(3) +
                "  ·  " + "Internet Archive" + "  ·  " + r.licence
            assertEquals(legacy, StoreGrid.recLine(r, st))
            val noStat = StoreTorrentHost.sizeText(r.sizeBytes) + "  ·  S — / L —  ·  " + r.publisher.ifEmpty { "catalogue" } + "  ·  " + r.licence
            assertEquals(noStat, StoreGrid.recLine(r, null))
        }
    }

    @Test fun everyPhoneAppStillGetsATileWithNameAndSource() {
        val recs = StoreRecs.parsePhone(repoFile("data/fleet/store-phone-recommendations.json").readText())
        val groups = StoreRecs.phoneByCategory(recs, recs.apps)
        assertEquals(recs.apps.map { it.pkg }.toSet(), groups.flatMap { it.second }.map { it.pkg }.toSet())
        for (a in recs.apps) {
            val r = StoreSuggest.Result("rec:${a.pkg}", a.pkg, a.name, a.licence, listOf(a.source), true, "", "")
            val t = StoreGrid.phoneTile(r, "") { "F-Droid" }
            assertEquals(a.name, t.label); assertEquals(a.pkg, t.sub); assertEquals("F-Droid", t.badge); assertFalse(t.initial.isEmpty())
        }
    }
}
