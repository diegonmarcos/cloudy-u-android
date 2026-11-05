package com.diegonmarcos.cloudbrowser.sites

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1493 Weby: derived from the SuperApp phone-apps declaration with its folder structure, and the user's edits. */
class WebyTest {
    private val superapp = File("../../aa_cloud-superapp")
    private val phone = JSONObject(File(superapp, "build.json").readText()).getJSONObject("ui").getJSONArray("sections")
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == "phone" } }
    private val groups = phone.getJSONArray("phone_app_groups")
    private val webapps = JSONObject(File(superapp, "data/phone-webapps.json").readText()).getJSONArray("groups")
    private fun seed() = WebySeed.derive(JSONObject().put("groups", groups).put("webapps", webapps))

    private fun pkgs(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { val p = a!!.get(it); if (p is JSONObject) p.getString("pkg") else p as String }

    @Test
    fun `the folders are the declared groups and their folders, in order, with the same packages`() {
        val s = seed()
        assertEquals((0 until groups.length()).map { groups.getJSONObject(it).getString("title") }, s.map { it.title })
        for ((i, f) in s.withIndex()) {
            val g = groups.getJSONObject(i)
            assertEquals("tiles of ${f.title}", pkgs(g.optJSONArray("packages")), f.tiles.map { it.id })
            val subs = g.optJSONArray("folders") ?: JSONArray()
            assertEquals("folders of ${f.title}", (0 until subs.length()).map { subs.getJSONObject(it).getString("label") }, f.folders.map { it.title })
            for ((k, sub) in f.folders.withIndex()) assertEquals(pkgs(subs.getJSONObject(k).getJSONArray("packages")), sub.tiles.map { it.id })
        }
    }

    @Test
    fun `a tile carries the web address the phone-webapps declaration gives its package, null when it has none`() {
        val urls = HashMap<String, String?>()
        for (i in 0 until webapps.length()) { val a = webapps.getJSONObject(i).getJSONArray("apps"); for (j in 0 until a.length()) a.getJSONObject(j).let { urls.putIfAbsent(it.getString("pkg"), if (it.isNull("url")) null else it.getString("url")) } }
        val tiles = WebyOps.flatten(seed()).flatMap { it.second.tiles }
        assertTrue(tiles.isNotEmpty())
        tiles.forEach { assertEquals(it.id, urls[it.id], it.url) }
        assertTrue("some app has a web address", tiles.any { it.url != null })
        assertTrue("some app has none", tiles.any { it.url == null })
    }

    private val base = listOf(WebyFolder("A", listOf(WebyTile("x", "X", "https://x.org")), listOf(WebyFolder("Sub", emptyList()))))

    @Test
    fun `a bookmark is added to a folder, created on the way, and removed again`() {
        val n = WebyOps.addBookmark(base, "A/Sub", "Site", "site.org")
        assertEquals("https://site.org", WebyOps.flatten(n).first { it.first == "A/Sub" }.second.tiles.single().url)
        val made = WebyOps.addBookmark(n, "New/Deep", "", "https://d.io/p")
        assertEquals("d.io", WebyOps.flatten(made).first { it.first == "New/Deep" }.second.tiles.single().label)
        val gone = WebyOps.removeBookmark(made, "A/Sub", "u:https://site.org")
        assertTrue(WebyOps.flatten(gone).first { it.first == "A/Sub" }.second.tiles.isEmpty())
        assertEquals(n, WebyOps.addBookmark(n, "A/Sub", "Site", "https://site.org"))
    }

    @Test
    fun `an unusable address or an empty folder changes nothing`() {
        assertNull(WebyOps.normalizeUrl("not a url"))
        assertNull(WebyOps.normalizeUrl("ftp://x.org"))
        assertEquals(base, WebyOps.addBookmark(base, "A", "t", "   "))
        assertEquals(base, WebyOps.addBookmark(base, "", "t", "x.org"))
    }

    @Test
    fun `folders are added, not duplicated, and removed with what is in them`() {
        val n = WebyOps.addFolder(base, "A/Other")
        assertEquals(listOf("A", "A/Sub", "A/Other"), WebyOps.flatten(n).map { it.first })
        assertEquals(n, WebyOps.addFolder(n, "A/Other"))
        assertEquals(listOf("A", "A/Sub"), WebyOps.flatten(WebyOps.removeFolder(n, "A/Other")).map { it.first })
        assertTrue(WebyOps.removeFolder(n, "A").isEmpty())
    }

    @Test
    fun `the edits survive a save and a load, and no saved copy means the seed`() {
        val n = WebyOps.addBookmark(seed(), "AI/Others", "Mine", "mine.example")
        assertEquals(n, WebyOps.fromJson(WebyOps.toJson(n).toString()))
        assertNull(WebyOps.fromJson(null))
        assertNull(WebyOps.fromJson("not json"))
    }
}
