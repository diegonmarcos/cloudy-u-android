package com.diegonmarcos.cloudbrowser.sites

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1531 the Tabs page's History link opens the official Domains > History page, the one history UI. */
class SitesRouteTest {
    private val ui = JSONObject(File("../build.json").readText()).getJSONObject("ui")

    @Test
    fun `Tabs History routes to Domains History when the nav declares it, else nowhere`() {
        assertEquals("domains" to "history", SitesRoute.history { s, p -> s == "domains" && p == "history" })
        assertNull(SitesRoute.history { _, _ -> false })
    }

    @Test
    fun `the route target is a declared page of the Domains section`() {
        val sections = ui.getJSONArray("sections")
        val domains = (0 until sections.length()).map { sections.getJSONObject(it) }.first { it.getString("id") == SitesRoute.HISTORY_SECTION }.getJSONArray("pages")
        assertTrue((0 until domains.length()).any { domains.getJSONObject(it).getString("id") == SitesRoute.HISTORY_PAGE })
        assertEquals("History", (0 until domains.length()).map { domains.getJSONObject(it) }.first { it.getString("id") == SitesRoute.HISTORY_PAGE }.getString("label"))
    }

    @Test
    fun `the shell sends the host's history link there and the library keeps no second route when the hook is set`() {
        val main = File("src/main/java/com/diegonmarcos/cloudbrowser/MainActivity.kt").readText()
        assertTrue("f.onHistory = { openHistory() }" in main)
        assertTrue("SitesRoute.history" in main)
        val lib = File("../../ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/BrowserHostFragment.kt").readText()
        assertTrue("onHistory?.let { it(); return }" in lib)
    }
}
