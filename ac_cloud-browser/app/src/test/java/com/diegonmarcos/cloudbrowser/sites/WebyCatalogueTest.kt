package com.diegonmarcos.cloudbrowser.sites

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1634 Weby's full Phone-apps catalogue: coverage / field parity against the SuperApp's declaration (every section, every folder,
 * every declared package appears, with its web address), and the Quickmarks groups are still all there (nothing removed).
 */
class WebyCatalogueTest {
    private val superapp = File("../../aa_cloud-superapp")
    private val ui = JSONObject(File(superapp, "build.json").readText()).getJSONObject("ui")
    private val webapps = JSONObject(File(superapp, "data/phone-webapps.json").readText()).getJSONArray("groups")

    private fun objs(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }

    /** What app/build.gradle bakes into ui.weby.catalogue, derived the same way. */
    private fun catalogue(): JSONObject {
        val ext = JSONArray()
        for (e in objs(ui.getJSONArray("external_apps"))) {
            val folder = e.optString("folder")
            if (folder.isEmpty()) continue
            val pk = LinkedHashSet<String>()
            listOf("hub_package", "alt_package", "install_package").forEach { k -> e.optString(k).takeIf { it.isNotEmpty() }?.let { pk += it } }
            e.optJSONObject("forks")?.let { f -> f.keys().forEach { k -> f.optString(k).takeIf { it.isNotEmpty() }?.let { pk += it } } }
            ext.put(JSONObject().put("folder", folder).put("packages", JSONArray(pk.toList())))
        }
        val folders = JSONArray()
        for (f in objs(ui.getJSONArray("phone_folders")))
            folders.put(JSONObject().put("id", f.getString("id")).put("order", f.optString("order", "99")).put("label", f.getString("label"))
                .put("match_keywords", f.optJSONArray("match_keywords") ?: JSONArray()).put("pin", f.optBoolean("pin")).put("sink", f.optBoolean("sink")))
        val sections = JSONArray()
        for (s in objs(ui.getJSONArray("phone_sections"))) sections.put(JSONObject().put("title", s.getString("title")).put("prefix", s.getString("prefix")))
        return JSONObject().put("sections", sections).put("folders", folders).put("external", ext)
    }

    private val decl get() = JSONObject().put("catalogue", catalogue()).put("webapps", webapps)

    private fun allTiles(l: List<WebyFolder>) = WebyOps.flatten(l).flatMap { it.second.tiles }

    @Test
    fun `every declared phone folder shows, grouped under its declared section`() {
        val cat = WebyCatalogue.derive(decl, emptyList())
        val titles = cat.map { it.title }
        assertTrue(titles.all { WebyCatalogue.isCatalogue(it) })
        for (s in objs(ui.getJSONArray("phone_sections"))) {
            val owns = objs(ui.getJSONArray("phone_folders")).any { it.getString("label").startsWith(s.getString("prefix")) && (it.optJSONArray("match_keywords")?.length() ?: 0) > 0 }
            if (owns) assertTrue("section ${s.getString("title")}", WebyCatalogue.prefix(s.getString("title")) in titles)
        }
        val shown = cat.flatMap { sec -> sec.folders.map { it.title } }.toSet()
        for (f in objs(ui.getJSONArray("phone_folders"))) {
            if ((f.optJSONArray("match_keywords")?.length() ?: 0) == 0) continue
            val label = f.getString("label")
            val sec = objs(ui.getJSONArray("phone_sections")).firstOrNull { label.startsWith(it.getString("prefix")) }
            assertTrue("folder $label", (if (sec != null) label.drop(1) else label).replace('/', '-') in shown)
        }
    }

    @Test
    fun `every package a phone folder declares is a tile exactly once, with the web address phone-webapps gives it`() {
        val tiles = allTiles(WebyCatalogue.derive(decl, emptyList()))
        val declared = WebyCatalogue.declaredPackages(decl)
        assertTrue("a real catalogue", declared.size > 100)
        assertEquals(declared.toSet(), tiles.map { it.id }.toSet())
        assertEquals("one tile per package", tiles.size, tiles.map { it.id }.toSet().size)
        val urls = HashMap<String, String?>()
        for (g in objs(webapps)) for (a in objs(g.getJSONArray("apps"))) urls.putIfAbsent(a.getString("pkg"), if (a.isNull("url")) null else a.getString("url"))
        tiles.filter { it.id in urls }.forEach { assertEquals(it.id, urls[it.id], it.url) }
        assertTrue("some tile has a web address", tiles.any { it.url != null })
        assertTrue("every tile can launch its app", tiles.all { it.pkg == it.id })
    }

    @Test
    fun `installed apps join their folder by the same rules and unclaimed ones fall to the sink`() {
        val base = allTiles(WebyCatalogue.derive(decl, emptyList())).size
        val cat = WebyCatalogue.derive(decl, listOf(WebyInstalled("br.gov.example.app", "Gov"), WebyInstalled("zz.nobody.knows", "Mystery")))
        val tiles = allTiles(cat)
        assertEquals(base + 2, tiles.size)
        assertEquals("Mystery", tiles.first { it.id == "zz.nobody.knows" }.label)
        val gov = cat.flatMap { it.folders }.first { f -> f.tiles.any { it.id == "br.gov.example.app" } }
        assertEquals("Gov & ID", gov.title)
        val sinkLabel = objs(ui.getJSONArray("phone_folders")).first { it.optBoolean("sink") }.getString("label")
        assertTrue(cat.flatMap { it.folders }.first { f -> f.tiles.any { it.id == "zz.nobody.knows" } }.title == sinkLabel.trimStart { !it.isLetterOrDigit() })
    }

    @Test
    fun `the Quickmarks groups are untouched and a saved copy keeps each tile's app`() {
        val phone = objs(ui.getJSONArray("sections")).first { it.getString("id") == "phone" }
        val groups = phone.getJSONArray("phone_app_groups")
        val seed = WebySeed.derive(JSONObject().put("groups", groups).put("webapps", webapps))
        assertEquals(groups.length(), seed.size)
        assertTrue(allTiles(seed).all { it.pkg == it.id })
        assertEquals(seed, WebyOps.fromJson(WebyOps.toJson(seed).toString()))
        assertTrue("the catalogue is a separate, derived layer", seed.none { WebyCatalogue.isCatalogue(it.title) })
    }
}
