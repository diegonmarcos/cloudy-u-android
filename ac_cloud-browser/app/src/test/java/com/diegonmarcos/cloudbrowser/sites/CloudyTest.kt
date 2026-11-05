package com.diegonmarcos.cloudbrowser.sites

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1493 Cloudy: the public Pages and the pages the Cloud-Webserver serves. */
class CloudyTest {
    @Test
    fun `the Pages list is the declared ones merged with the live public ones, by address`() {
        val decl = JSONObject("""{"gh_pages":[{"name":"b-repo","url":"https://o.github.io/b-repo/"},{"name":"o.github.io","url":"https://o.github.io/"}]}""")
        val live = """{"pages":[{"repo":"a-repo","url":"https://o.github.io/a-repo/","public":true},{"repo":"b-repo","url":"https://o.github.io/b-repo","public":true},{"repo":"hid","url":"https://o.github.io/hid/","public":false}]}"""
        val out = Cloudy.merge(Cloudy.seedPages(decl), live)
        assertEquals(listOf("a-repo", "b-repo", "o.github.io"), out.map { it.title })
        assertEquals(Cloudy.seedPages(decl).sortedBy { it.title }, Cloudy.merge(Cloudy.seedPages(decl), null))
        assertTrue(Cloudy.merge(emptyList(), "garbage").isEmpty())
    }

    private val ws = JSONObject("""{"base_role":"drive","endpoints":[
        {"id":"e","title":"Local","name":"emulated.app","port":8002,"role":"drive","summary":"the drive"},
        {"id":"c","title":"Cloud","name":"cloud.app","port":8003,"role":"diag","summary":"diag"}],
        "pages":[{"id":"drive","label":"Drive","route":"/__app__/api/sub/pages-drive","tab":"pages"}]}""")

    @Test
    fun `the declared Webserver pages are its endpoints and its Pages sub-pages on the drive endpoint`() {
        val out = Cloudy.declaredWebserver(ws)
        assertEquals(listOf("Local", "Cloud", "Drive"), out.map { it.title })
        assertEquals("http://emulated.app/__app__/api/sub/pages-drive", out.last().url)
        assertEquals(8002, out.last().port)
        assertTrue(Cloudy.declaredWebserver(JSONObject()).isEmpty())
    }

    @Test
    fun `the Repos store adds each running site on its loopback port, and a missing or bad store adds nothing`() {
        val store = """{"entries":[{"id":"1","label":"Site","repo":"r","source":"emulated","start":"","exposure":"mesh","port":8100},{"id":"2","label":"Bad","repo":"r","port":0}]}"""
        val out = Cloudy.storeEntries(store)
        assertEquals(1, out.size)
        assertEquals("http://127.0.0.1:8100/", out[0].url)
        assertEquals("r · mesh", out[0].detail)
        assertTrue(Cloudy.storeEntries(null).isEmpty())
        assertTrue(Cloudy.storeEntries("nope").isEmpty())
    }
}
