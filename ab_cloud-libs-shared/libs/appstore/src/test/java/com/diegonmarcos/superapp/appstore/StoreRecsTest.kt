package com.diegonmarcos.superapp.appstore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1749 Search recommendations: Phone excludes the fleet, Torrent shows the twelve categories and only allowlisted legitimate entries. Pure, no device. */
class StoreRecsTest {
    private fun repoFile(rel: String): File = listOf(rel, "../$rel", "../../$rel", "../../../$rel", "../../../../$rel", "../../../../../$rel").map(::File).firstOrNull { it.exists() }
        ?: error("$rel not found from ${File(".").absolutePath}")

    private fun rec(id: String, cat: String = "movies", lic: String = "CC BY 3.0", name: String = id, page: String = "https://archive.org/details/$id",
                    url: String? = "https://archive.org/download/$id/$id.torrent", hash: String? = null) =
        StoreRecs.Rec(id, name, cat, 1, lic, "Internet Archive", page, url, null, hash)

    // ── Phone ─────────────────────────────────────────────────────────
    @Test fun phoneNeverRecommendsAFleetPackage() {
        val recs = StoreRecs.parsePhone(repoFile("data/fleet/store-phone-recommendations.json").readText())
        assertEquals(emptyList<String>(), recs.problems)
        assertTrue(recs.apps.size >= 30 && recs.categories.size >= 8)
        val fleet = fleetPackages()
        assertTrue("the fleet list is real", fleet.size > 10)
        val shown = StoreRecs.phoneApps(recs, fleet)
        assertTrue(shown.none { it.pkg in fleet })
        // and the filter really filters: a recommendation that IS a fleet package is dropped
        val withFleet = StoreRecs.parsePhone("""{"categories":[{"id":"x","label":"X"}],"apps":[{"pkg":"${fleet.first()}","category":"x"},{"pkg":"org.schabi.newpipe","category":"x"}]}""")
        assertEquals(listOf("org.schabi.newpipe"), StoreRecs.phoneApps(withFleet, fleet).map { it.pkg })
    }

    @Test fun everyPhoneCategoryIsDeclaredAndEveryAppHasASource() {
        val recs = StoreRecs.parsePhone(repoFile("data/fleet/store-phone-recommendations.json").readText())
        val ids = recs.categories.map { it.id }.toSet()
        recs.apps.forEach { assertTrue(it.pkg + " category", it.category in ids); assertEquals(it.pkg, "fdroid", it.source); assertTrue(it.pkg, it.licence.isNotBlank()) }
        assertEquals(recs.apps.size, recs.apps.map { it.pkg }.toSet().size)
        assertTrue(StoreRecs.phoneByCategory(recs, recs.apps).all { it.second.isNotEmpty() })
    }

    @Test fun aBadOrMissingPhoneDatasetIsEmptyWithAReasonNeverACrash() {
        assertTrue(StoreRecs.parsePhone(null).apps.isEmpty() && StoreRecs.parsePhone(null).problems.isNotEmpty())
        assertTrue(StoreRecs.parsePhone("not json").problems.isNotEmpty())
        assertEquals(1, StoreRecs.parsePhone("""{"apps":[{"pkg":"a.b"},{"pkg":"a.b"},{"name":"nopkg"}]}""").apps.size)
    }

    /** The constellation-fleet packages out of the repo's own data (the same list the Store parses at runtime). */
    private fun fleetPackages(): Set<String> {
        val root = JSONObject(repoFile("aa_cloud-superapp/data/constellation-fleet.json").readText())
        val out = HashSet<String>()
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> { for (k in listOf("pkg", "package", "altId", "alt_id")) o.optString(k).takeIf { it.contains('.') }?.let(out::add); o.keys().forEach { walk(o.opt(it)) } }
                is org.json.JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
            }
        }
        walk(root)
        return out
    }

    // ── Torrent ───────────────────────────────────────────────────────
    @Test fun theTwelveCategoriesAreInTheOwnersOrder() {
        assertEquals(listOf("Movies", "TV Shows", "PC Games", "Anime", "Software & Applications", "E-Books", "Audiobooks", "Music", "Operating Systems",
            "Comics & Graphic Novels", "E-Learning & Video Courses", "Android Apps"), StoreRecs.TORRENT_CATEGORIES.map { it.label })
        assertEquals(12, StoreRecs.top(emptyList()).size)
    }

    @Test fun theRealCatalogueHasEveryCategoryAndEveryRecommendationIsLegit() {
        val root = JSONObject(repoFile("data/fleet/torrent-catalogue.json").readText())
        val a = root.getJSONArray("entries")
        val recs = (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            StoreRecs.Rec(o.getString("id"), o.getString("name"), o.getString("category"), o.optLong("size_bytes", -1), o.optString("licence"), o.optString("publisher"),
                o.optString("source_page"), o.optString("torrent_url").ifEmpty { null }, o.optString("magnet").ifEmpty { null }, o.optString("info_hash").ifEmpty { null })
        } }
        recs.forEach { assertEquals(it.id, null, StoreRecs.whyNot(it)) }
        val top = StoreRecs.top(recs)
        assertEquals(StoreRecs.TORRENT_CATEGORIES.map { it.id }, top.map { it.first.id })
        top.forEach { (c, rows) ->
            assertTrue("${c.id} has recommendations", rows.isNotEmpty())
            assertTrue("${c.id} at most ${StoreRecs.TOP}", rows.size <= StoreRecs.TOP)
            rows.forEach { assertTrue(it.id, StoreRecs.legit(it)); assertNotNull(it.licence); assertTrue(it.name.isNotBlank() && it.sizeBytes > 0) }
        }
        assertEquals("the Android label", "Android Apps", StoreRecs.TORRENT_CATEGORIES.last().label)
    }

    @Test fun theAllowlistRefusesPiratedCrackedAndCopyrightedEntries() {
        assertTrue(StoreRecs.legit(rec("ok")))
        assertFalse(StoreRecs.legit(rec("a", lic = "")))
        assertFalse(StoreRecs.legit(rec("b", lic = "All rights reserved")))
        assertFalse(StoreRecs.legit(rec("c", lic = "Proprietary")))
        assertFalse(StoreRecs.legit(rec("d", name = "Photoshop 2026 cracked + keygen")))
        assertFalse(StoreRecs.legit(rec("e", name = "Game FitGirl repack", lic = "GPL-3.0")))
        assertFalse("pre-activated", StoreRecs.legit(rec("f", name = "Windows pre-activated", lic = "CC BY 4.0")))
        assertFalse("a free-looking licence on an unlisted indexer host (the legal guard keeps denied domains out of this file)", StoreRecs.legit(rec("g", page = "https://tracker.example.net/torrent/1", url = null).let {
            StoreRecs.Rec(it.id, it.name, it.category, 1, "CC BY 4.0", "x", "https://tracker.example.net/torrent/1", null, "magnet:?xt=urn:btih:" + "a".repeat(40), "a".repeat(40)) }))
        assertFalse("an unknown host is refused", StoreRecs.legit(rec("h", url = "https://example.com/x.torrent")))
        assertFalse("no licence word match inside another word", StoreRecs.legit(rec("i", lic = "Permitted by studio")))
        assertTrue(StoreRecs.legit(rec("j", lic = "Public domain")))
        assertTrue(StoreRecs.legit(rec("k", lic = "GPL-3.0-or-later", page = "https://releases.ubuntu.com/x/", url = "https://releases.ubuntu.com/x/y.torrent")))
    }

    @Test fun topTenPerCategoryBySeedersAndAnIllegitEntryNeverAppears() {
        val h = { i: Int -> i.toString().padStart(40, '0') }
        val recs = (1..14).map { rec("m$it", hash = h(it)) } + rec("bad", name = "Crack pack", hash = h(99))
        val stats = (1..14).associate { h(it) to StoreRecs.Stat(it * 10, 1, "Internet Archive") } + (h(99) to StoreRecs.Stat(100000, 1, "x"))
        val movies = StoreRecs.top(recs, stats).first { it.first.id == "movies" }.second
        assertEquals(10, movies.size)
        assertEquals((14 downTo 5).map { "m$it" }, movies.map { it.id })
        assertTrue(movies.none { it.id == "bad" })
        // seeders unknown: still listed (after the known ones), by name
        val unknown = StoreRecs.top(listOf(rec("zz"), rec("aa"), rec("seeded", hash = h(5))), mapOf(h(5) to StoreRecs.Stat(3, 0, "")))
            .first { it.first.id == "movies" }.second
        assertEquals(listOf("seeded", "aa", "zz"), unknown.map { it.id })
    }
}
