package com.diegonmarcos.superapp.appstore

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1791 the ONE central catalogue (assets/store-catalogue.json): schema, parity with the three old per-page lists (snapshotted in
 * src/test/resources/store-catalogue-old-lists.json: every package, every field value), the owner's open-source games, and the icons.
 */
class StoreCatalogueTest {
    private fun asset(): File = listOf("src/main/assets/store-catalogue.json", "ab_cloud-libs-shared/libs/appstore/src/main/assets/store-catalogue.json").map(::File).first { it.isFile }
    private val raw: JSONObject by lazy { JSONObject(asset().readText()) }
    private val rows: Map<String, JSONObject> by lazy { raw.getJSONArray("apps").let { a -> (0 until a.length()).associate { a.getJSONObject(it).getString("pkg") to a.getJSONObject(it) } } }
    private val old: JSONObject by lazy { JSONObject(javaClass.classLoader!!.getResourceAsStream("store-catalogue-old-lists.json")!!.bufferedReader().readText()) }
    private fun list(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    private val cat by lazy { StoreCatalogue.parse(asset().readText()) }

    @Test fun parsesCleanWithOneRowPerPackage() {
        assertTrue(cat.problems.toString(), cat.problems.isEmpty())
        assertEquals(raw.getJSONArray("apps").length(), cat.entries.size)
        assertEquals(cat.entries.size, cat.entries.map { it.pkg }.toSet().size)
        assertEquals(5, cat.rows.size)
        assertEquals(listOf("category", "licence", "play_integrity", "game_dimension", "source"), cat.rows.map { it.field })
        assertEquals(listOf("tools", "services", "others", "games"), cat.rows[0].values.map { it.id })
        assertEquals(listOf("open", "closed"), cat.rows[1].values.map { it.id })
        assertEquals(listOf("none", "basic", "mid", "full"), cat.rows[2].values.map { it.id })
        assertEquals(listOf("2d", "3d"), cat.rows[3].values.map { it.id })
        assertEquals(listOf("fdroid", "aurora", "playstore", "other"), cat.rows[4].values.map { it.id })
        assertEquals(listOf("Tools", "Services", "Others", "Games"), cat.rows[0].values.map { it.label })
        assertEquals(listOf("No Play", "Play Basic", "Play Mid", "Play Full"), cat.rows[2].values.map { it.label })
        assertEquals(listOf("Games 2D", "Games 3D"), cat.rows[3].values.map { it.label })
        assertEquals(listOf("F-Droid", "Aurora", "Play Store", "Others"), cat.rows[4].values.map { it.label })
        assertEquals("All", cat.allLabel)
    }

    @Test fun everyRowCarriesTheFiveFieldsWithinTheirDeclaredValues() {
        for (e in cat.entries) {
            for (r in cat.rows) {
                val v = e.get(r.field)
                if (r.field == "game_dimension") assertTrue("${e.pkg} dimension only for games", if (e.get("category") == "games") v in r.values.map { it.id } else v.isEmpty())
                else assertTrue("${e.pkg} ${r.field}=$v", v in r.values.map { it.id })
            }
            assertTrue("${e.pkg} play_integrity_basis", e.get("play_integrity_basis").isNotBlank())
            assertTrue("${e.pkg} name", e.get("name").isNotBlank())
        }
    }

    @Test fun everyPackageOfTheThreeOldListsIsInTheCatalogue() {
        val r = list(old.getJSONArray("recommendations")); val g = list(old.getJSONArray("games")); val i = list(old.getJSONArray("integrity"))
        assertEquals(47, r.size); assertEquals(103, g.size); assertEquals(22, i.size)
        for (x in r + g + i) assertTrue("dropped: ${x.getString("pkg")}", x.getString("pkg") in rows)
        assertEquals((r + g + i).map { it.getString("pkg") }.toSet(), rows.keys)
        assertEquals(171, rows.size)
    }

    @Test fun noFieldValueOfAnOldRowIsLost() {
        fun same(o: JSONObject, old: String, n: String, x: JSONObject) = assertEquals("${x.getString("pkg")} $old -> $n", o.get(old).toString(), x.get(n).toString())
        for (o in list(old.getJSONArray("recommendations"))) {
            val x = rows.getValue(o.getString("pkg"))
            same(o, "name", "name", x); same(o, "category", "subcategory", x); same(o, "source", "source_listed", x); same(o, "licence", "licence_spdx", x)
            assertEquals("open", x.getString("licence"))
        }
        for (o in list(old.getJSONArray("games"))) {
            val x = rows.getValue(o.getString("pkg"))
            for (k in listOf("name", "developer", "desc", "icon", "store_url", "apk", "genre")) if (o.has(k)) same(o, k, k, x) else assertFalse("${o.getString("pkg")} invented $k", x.has(k))
            same(o, "category", "subcategory", x); same(o, "source", "source_listed", x); same(o, "dimension", "game_dimension", x)
            assertEquals(o.getString("pkg"), if (o.getBoolean("open_source")) "open" else "closed", x.getString("licence"))
            assertEquals("games", x.getString("category"))
            assertEquals(when (o.getString("source")) { "play" -> "aurora"; "fdroid" -> "fdroid"; else -> "other" }, x.getString("source"))
        }
        val tier = mapOf("basic" to "basic", "device" to "mid", "strong" to "full", "unknown" to "basic")
        for (o in list(old.getJSONArray("integrity"))) {
            val x = rows.getValue(o.getString("pkg"))
            same(o, "tier", "integrity_tier", x); same(o, "basis", "basis", x); same(o, "note", "note", x)
            assertEquals(tier.getValue(o.getString("tier")), x.getString("play_integrity"))
            assertEquals(o.getString("basis"), x.getString("play_integrity_basis"))
            assertTrue(o.getString("name") == x.getString("name") || o.getString("name") == x.optString("name_integrity_list"))
            assertEquals("a Play-listed integrity row", "play", x.getString("source_listed"))
        }
        // the old category lists are declared, ids unchanged
        val subs = list(raw.getJSONArray("subcategories")).map { it.getString("id") to it.getString("label") }
        for (c in list(old.getJSONArray("recommendation_categories")) + list(old.getJSONArray("game_categories"))) assertTrue(c.getString("id"), (c.getString("id") to c.getString("label")) in subs)
        for (x in rows.values) if (x.has("subcategory")) assertTrue(x.getString("subcategory"), x.getString("subcategory") in subs.map { it.first })
    }

    @Test fun anUnknownIntegrityTierIsFlooredToBasicAndNeverToNone() {
        for (o in list(old.getJSONArray("integrity")).filter { it.getString("tier") == "unknown" }) {
            val x = rows.getValue(o.getString("pkg"))
            assertEquals("basic", x.getString("play_integrity")); assertEquals("unknown", x.getString("integrity_tier")); assertEquals("unverified", x.getString("play_integrity_basis"))
        }
        assertTrue(raw.getString("_doc").contains("PLAY INTEGRITY MAPPING") && raw.getString("_doc").contains("SOURCE MAPPING"))
    }

    @Test fun theOpenSourceGamesOfTicket1776AreAllThereAsOpenGames() {
        val owners = listOf("io.anuke.mindustry", "com.unciv.app", "org.openttd.fdroid", "com.shatteredpixel.shatteredpixeldungeon", "com.cleverraven.cataclysmdda",
            "sh.ppy.osulazer", "org.lichess.mobileV2", "net.minetest.minetest", "org.supertuxkart.stk", "org.stjr.srb2")
        for (p in owners) { val x = rows[p] ?: error("$p missing"); assertEquals(p, "open", x.getString("licence")); assertEquals("games", x.getString("category")); assertTrue(x.has("genre")) }
        val oldOpen = list(old.getJSONArray("games")).filter { it.getBoolean("open_source") }.map { it.getString("pkg") }
        assertEquals(25, oldOpen.size)
        assertTrue(oldOpen.all { rows.getValue(it).getString("licence") == "open" && rows.getValue(it).getString("category") == "games" })
        assertEquals("other", rows.getValue("org.stjr.srb2").getString("source")); assertEquals("3d", rows.getValue("org.stjr.srb2").getString("game_dimension"))
        assertTrue(rows.getValue("sh.ppy.osulazer").getString("apk").endsWith("/sh.ppy.osulazer.apk"))
    }

    @Test fun everyEntryHasAWellFormedHttpsIconAndNoTwoRowsShareNothingBlank() {
        val bad = cat.entries.filter { e -> val u = e.icon; !(u.startsWith("https://") && u.length > 12 && !u.contains(" ") && runCatching { java.net.URI(u).host }.getOrNull().orEmpty().contains('.')) }
        assertTrue("blank or malformed icon: " + bad.map { it.pkg }, bad.isEmpty())
        assertTrue("every icon is the tile's image plan", cat.entries.all { StoreGrid.imagePlan(StoreGrid.Tile("k", "l", "", "", it.icon, 0, "A")) == it.icon })
        assertEquals("the letter tile that stands in for a failed icon is never blank", "M", StoreGrid.initial("Mindustry"))
        assertTrue(cat.entries.all { StoreGrid.initial(it.name).isNotBlank() })
    }

    @Test fun theStoreDeclaresTheCatalogueInItsBuildJson() {
        val b = listOf("ac_cloud-store/build.json", "../ac_cloud-store/build.json", "../../ac_cloud-store/build.json", "../../../ac_cloud-store/build.json", "../../../../ac_cloud-store/build.json").map(::File).first { it.isFile }
        val d = JSONObject(b.readText()).getJSONObject("store_catalogue")
        assertEquals("ab_cloud-libs-shared/libs/appstore/src/main/assets/store-catalogue.json", d.getString("asset"))
        assertTrue(d.getString("_doc").isNotBlank())
    }
}
