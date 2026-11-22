package com.diegonmarcos.cloudbrowser.sites

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1842 Cloudy > WebApps: Weby's folders in three sections, the Cloud Store's categories, the app-links addresses. */
class CloudyWebAppsTest {
    private val decl = JSONObject("""{"categories":[
        {"id":"primary","label":"Primary","members":[{"id":"mail","label":"Mail","link_id":"cloud-mail","icon":"ic_mail"},{"id":"dialer","label":"Dialer","link_id":"cloud-dialer","icon":""}]},
        {"id":"empty","label":"Empty","members":[]}]}""")

    @Test fun `three sections WebApps GitHub Pages Local Webserver, the Store's categories as folders, an empty category is not shown`() {
        val out = CloudyWebApps.derive(decl, mapOf("cloud-mail" to "https://webmail.example/"), listOf(CloudyEntry("repo", "https://o.github.io/repo/")), listOf(CloudyEntry("Local", "http://emulated.app/", port = 8002)))
        assertEquals(listOf("WebApps", "GitHub Pages", "Local Webserver"), out.map { it.title })
        assertEquals(listOf("Primary"), out[0].folders.map { it.title })
        val mail = out[0].folders[0].tiles
        assertEquals(listOf("mail", "dialer"), mail.map { it.id })
        assertEquals("https://webmail.example/", mail[0].url); assertEquals("ic_mail", mail[0].icon)
        assertNull("a phone-only app has no address and is greyed, never dropped", mail[1].url); assertNull(mail[1].icon)
        assertEquals(listOf("https://o.github.io/repo/"), out[1].tiles.map { it.url })
        assertEquals(listOf("http://emulated.app/"), out[2].tiles.map { it.url })
    }

    @Test fun `#1856 WebApps is the Cloud rows, one top-level section per Store category, GH Pages and Local have their own sections`() {
        val rows = CloudyWebApps.webApps(decl, mapOf("cloud-mail" to "https://webmail.example/"))
        assertEquals(listOf("Primary"), rows.map { it.title })
        assertEquals(listOf("mail", "dialer"), rows[0].tiles.map { it.id })
        val gh = CloudyWebApps.ghPages(listOf(CloudyEntry("repo", "https://o.github.io/repo/")), listOf("Lnktree" to listOf(CloudyEntry("Linktree", "https://o.github.io/linktree/"))), "diegonmarcos.github.io")
        assertEquals(listOf("Repos' Pages", "diegonmarcos.github.io › Lnktree"), gh.map { it.title })
        val local = CloudyWebApps.local(listOf(CloudyEntry("Local", "http://emulated.app/", port = 8002)), listOf(CloudyEntry("Drive", "http://emulated.app/p/drive")))
        assertEquals("File Explorers first, then Pages", listOf("File Explorers", "Pages"), local.map { it.title })
        assertEquals(listOf("http://emulated.app/"), local[0].tiles.map { it.url })
    }

    @Test fun `links come from app-links json and a missing or null href is absent`() {
        val m = CloudyWebApps.links("""{"links":[{"id":"cloud-mail","href":"https://w/"},{"id":"cloud-dialer","href":null},{"id":"x"}]}""")
        assertEquals(mapOf("cloud-mail" to "https://w/"), m)
        assertTrue(CloudyWebApps.links(null).isEmpty()); assertTrue(CloudyWebApps.links("nope").isEmpty())
    }

    @Test fun `the declared categories are the Cloud Store's and every member app keeps its place`() {
        val fleet = JSONObject(java.io.File("../../aa_cloud-superapp/data/constellation-fleet.json").readText())
        val cats = fleet.getJSONArray("app_categories")
        val members = (0 until cats.length()).flatMap { i -> val m = cats.getJSONObject(i).getJSONArray("members"); (0 until m.length()).map { m.getString(it) } }
        assertTrue(members.size > 30)
        val gradle = java.io.File("build.gradle").readText()
        assertTrue("app_categories" in gradle && "webappCategories" in gradle && "pathData" in gradle && "tile_groups" in gradle)
        val links = JSONObject(java.io.File("../build.json").readText()).getJSONObject("ui").getJSONObject("cloudy").getJSONObject("webapps").getString("links_url")
        assertTrue("cloudy-h-webapps-mob" in links && links.endsWith("app-links.json"))
    }
}
