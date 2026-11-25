package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1906 every entry of me-links.json is bound to a clickable action: a row of its section's menu opening its URL, and each tab's own link is the "Open <site>" action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeLinksActionsTest {
    private val all = MeLinks.parse(BuroLlcTestData.text("data/links/me-links.json"))

    @Test fun `every entry is a clickable row of its section menu with its own URL`() {
        assertTrue(all.size >= 18)
        for (e in all) {
            val row = MeLinks.menuFor(all, e.section).firstOrNull { it.id == e.id }
            assertNotNull("${e.id} is in no menu", row)
            assertEquals(e.id, e.url, row!!.url)
        }
    }

    @Test fun `every page with a link has its Open action naming its entry`() {
        val raw = org.json.JSONObject(BuroLlcTestData.text("data/links/me-links.json")).getJSONArray("entries")
        var tabs = 0
        for (i in 0 until raw.length()) {
            val e = raw.getJSONObject(i); if (!e.has("path")) continue
            val p = (0 until e.getJSONArray("path").length()).joinToString("/") { e.getJSONArray("path").getString(it) }
            val link = JSONArray(BuroLlcTestData.text("data/ui/${e.getString("section")}/$p.json")).getJSONObject(0).getString("link")
            assertEquals(e.getString("id"), MeLinks.barFor(all, link)?.id)
            tabs++
        }
        assertEquals(8, tabs)
    }

    @Test fun `the web page is drawn with the linked wrapper`() {
        assertTrue(BuroLlcTestData.text("app/src/main/java/com/diegonmarcos/cloudme/SectionScreen.kt").contains("MeLinkedWeb(single, section.id, page.id, modifier)"))
        assertTrue(BuroLlcTestData.text("app/src/main/java/com/diegonmarcos/cloudme/MeLinks.kt").contains("MeLinks.open(ctx, e.url)"))
    }
}
