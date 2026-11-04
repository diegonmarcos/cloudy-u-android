package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.FavFeed
import com.diegonmarcos.superapp.browser.FavSeed
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1458 the runtime GH Pages feed: pages.json (front-html) -> seed items. */
class FavFeedTest {
    private val json = """{"owner":"diegonmarcos","pages":[
        {"repo":"diegonmarcos.github.io","url":"https://diegonmarcos.github.io/","source":{"branch":"main","path":"/"},"public":true},
        {"repo":"HardTech-Chronos","url":"https://diegonmarcos.github.io/HardTech-Chronos/","public":true},
        {"repo":"HardTech-Chronos","url":"https://diegonmarcos.github.io/HardTech-Chronos/","public":true},
        {"repo":"secret","url":"https://x.github.io/secret/","public":false},
        {"repo":"plain","url":"http://x.github.io/plain/","public":true},
        {"repo":"","url":"https://x.github.io/norepo/","public":true}]}"""

    @Test
    fun `only public https sites become items, one per url, filed under the chip and the repo`() {
        val items = FavFeed.parse(json, "GH Pages")
        assertEquals(listOf("https://diegonmarcos.github.io/", "https://diegonmarcos.github.io/HardTech-Chronos/"), items.map { it.url })
        assertEquals(listOf("GH Pages/diegonmarcos.github.io", "GH Pages/HardTech-Chronos"), items.map { it.group })
        assertEquals("HardTech-Chronos", items[1].title)
    }

    @Test
    fun `garbage, null and an empty list give no items so the cache stays`() {
        assertTrue(FavFeed.parse(null, "GH Pages").isEmpty())
        assertTrue(FavFeed.parse("<html>404</html>", "GH Pages").isEmpty())
        assertTrue(FavFeed.parse("""{"pages":[]}""", "GH Pages").isEmpty())
        assertTrue(FavFeed.parse("""{"owner":"x"}""", "GH Pages").isEmpty())
    }

    @Test
    fun `a catalog filter carries its feed url`() {
        val s = FavSeed.parse(JSONObject("""{"version":4,"items":[{"title":"a","url":"https://a.b/","group":"g"}],
            "filters":[{"id":"gh_pages","label":"GH Pages","feed":"https://raw.githubusercontent.com/diegonmarcos/front-html/main/pages.json"},{"id":"w","label":"W"}]}"""))
        assertEquals("https://raw.githubusercontent.com/diegonmarcos/front-html/main/pages.json", s.filters[0].feed)
        assertEquals(null, s.filters[1].feed)
    }
}
