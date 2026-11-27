package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1911 every Pers/Prof/Proj tab embeds its OWN site in the page WebView, the URL taken from the declared data (me-links.json = the
 * page file's `url`), and the Open button and Links panel of #1906 are gone. test/test-me-links.sh proves each check red by mutation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeLinksEmbedTest {
    private val entries = JSONObject(BuroLlcTestData.text("data/web/me-links.json")).getJSONArray("entries").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun block(e: JSONObject): JSONObject {
        val p = (0 until e.getJSONArray("path").length()).joinToString("/") { e.getJSONArray("path").getString(it) }
        return JSONArray(BuroLlcTestData.text("data/ui/${e.getString("section")}/$p.json")).getJSONObject(0)
    }
    private val tabs get() = entries.filter { it.has("path") }

    @Test fun `every tab embeds the URL its me-links entry declares`() {
        assertTrue(tabs.size >= 40)
        for (e in tabs) assertEquals(e.getString("id"), e.getString("url"), block(e).optString("url"))
    }

    @Test fun `Leaf-y Nexus Stark Personal Social Instagram LinkedIn and Lnktree embed their real sites`() {
        val want = mapOf("prof-leafy" to "https://diegonmarcos.github.io/leafy/", "prof-nexus" to "https://diegonmarcos.github.io/nexus/", "prof-stark" to "https://diegonmarcos.github.io/stark/",
            "pers-personal" to "https://diegonmarcos.github.io/mySocials/", "prof-professional" to "https://diegonmarcos.com/", "prof-portfolio" to "https://diegonmarcos.github.io/cv_web/", "prof-developer" to "https://github.com/diegonmarcos", "prof-cv" to "https://diegonmarcos.github.io/cv_pdf/", "pers-ig0" to "https://diegonmarcos.github.io/mySocials/instagram-diegocnmarcos_.html",
            "lnktree-default" to "https://linktree.diegonmarcos.com/", "proj-cg-omniverse" to "https://diegonmarcos.github.io/galaxy/", "proj-unix-cloudy" to "https://github.com/diegonmarcos/cloudy-___")
        for ((id, url) in want) assertEquals(id, url, block(tabs.first { it.getString("id") == id }).optString("url"))
    }

    @Test fun `no two Pers Prof Proj tabs embed the same page`() {
        val urls = tabs.filter { !it.has("mirror_of") }.map { block(it).optString("url") }   // #1918 Prof > LinkedIn declares mirror_of the Pers one
        assertEquals(urls.toString(), urls.size, urls.toSet().size)
        assertTrue(urls.none { it.isBlank() })
    }

    @Test fun `no tab embeds a bundled mirror of another tab or an appassets page`() {
        for (e in tabs) assertTrue(e.getString("id"), block(e).optString("url").startsWith("https://") && !block(e).optString("url").contains("appassets"))
    }

    @Test fun `the Open button and the Links panel are gone`() {
        val ss = BuroLlcTestData.text("app/src/main/java/com/diegonmarcos/cloudme/SectionScreen.kt")
        assertFalse(ss.contains("MeLinkedWeb"))
        // #1932 the "web" page is still the bare WebPage for every entry that is not a repo with declared views; a repo entry hosts the viewer, whose GH view is that same WebPage
        assertTrue(ss.contains("\"web\" ->") && ss.contains("else WebPage(single, modifier, pageId = page.id)"))
        val k = BuroLlcTestData.text("app/src/main/java/com/diegonmarcos/cloudme/MeLinks.kt")
        for (gone in listOf("Open \$", "\"Links\"", "FilledTonalButton", "ACTION_VIEW", "MeLinkedWeb")) assertFalse(gone, k.contains(gone))
    }

    @Test fun `the page WebView falls back to the asset loader and is keyed by its page`() {
        val w = BuroLlcTestData.text("app/src/main/java/com/diegonmarcos/cloudme/WebPage.kt")
        assertTrue(w.contains("?: super.shouldInterceptRequest(view, request)"))
        assertTrue(w.contains("key(generation, url, pageId)"))
        assertTrue(w.contains("MAINFRAME_ERROR"))
        assertTrue(w.contains("IMG_CHECK") && w.contains("naturalWidth==0"))
    }
}
