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

    @Test
    fun `the home grid is 8 columns by 8 rows with launcher folder tiles and a popup grid of its own`() {
        assertEquals(8, WebyLayout.COLUMNS)
        assertEquals(8, WebyLayout.ROWS)
        assertEquals(64, WebyLayout.PAGE)
        assertTrue(WebyLayout.POPUP_COLUMNS in 2..WebyLayout.COLUMNS)
        assertEquals(1, WebyLayout.rows(8))
        assertEquals(2, WebyLayout.rows(9))
        assertEquals(8, WebyLayout.rows(64))
        // a folder previews 2x2 up to four members, 3x3 from five, never more than nine
        assertEquals(2, com.diegonmarcos.superapp.uikit.KitLauncher.previewEdge(1))
        assertEquals(2, com.diegonmarcos.superapp.uikit.KitLauncher.previewEdge(4))
        assertEquals(3, com.diegonmarcos.superapp.uikit.KitLauncher.previewEdge(5))
        assertEquals(9, com.diegonmarcos.superapp.uikit.KitLauncher.previewCount(30))
        assertEquals(3, com.diegonmarcos.superapp.uikit.KitLauncher.previewCount(3))
        // the SuperApp phone grid's tile: 52 dp icon, 11 sp one-line label, 6 dp padding, 16 dp folder corner
        assertEquals(52, com.diegonmarcos.superapp.uikit.KitLauncher.ICON_DP)
        assertEquals(11, com.diegonmarcos.superapp.uikit.KitLauncher.LABEL_SP)
        assertEquals(6, com.diegonmarcos.superapp.uikit.KitLauncher.CELL_PAD_DP)
        assertEquals(16, com.diegonmarcos.superapp.uikit.KitLauncher.FOLDER_CORNER_DP)
        assertEquals(33f, com.diegonmarcos.superapp.uikit.KitLauncher.iconDp(45f), 0.001f)
        assertEquals(52f, com.diegonmarcos.superapp.uikit.KitLauncher.iconDp(200f), 0.001f)
    }

    @Test
    fun `a section lays its apps out first and its folders after them, as the phone page does`() {
        val f = WebyFolder("G", listOf(WebyTile("a", "A", null), WebyTile("b", "B", null)), listOf(WebyFolder("Sub", listOf(WebyTile("c", "C", null)))))
        val cells = WebyLayout.cells(f)
        assertEquals(listOf("a", "b"), cells.filterIsInstance<WebyLayout.Cell.App>().map { it.tile.id })
        assertTrue(cells[2] is WebyLayout.Cell.Folder)
        assertEquals(listOf("a", "b", "c"), WebyLayout.previewTiles(f).map { it.id })
        // every seeded group lays out exactly its declared apps and folders
        for ((i, folder) in seed().withIndex()) {
            val g = groups.getJSONObject(i)
            assertEquals(pkgs(g.optJSONArray("packages")).size + (g.optJSONArray("folders")?.length() ?: 0), WebyLayout.cells(folder).size)
        }
    }
}
