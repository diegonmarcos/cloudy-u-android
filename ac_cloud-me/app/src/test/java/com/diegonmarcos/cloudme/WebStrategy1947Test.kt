package com.diegonmarcos.cloudme

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1947 the strategy declaration (org.json needs Robolectric): network_first for Cloud Me, stale_while_revalidate still supported. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebStrategy1947Test {
    @get:Rule val tmp = TemporaryFolder()
    private val page = "https://p.example/"
    private val cfg = LnktreeCacheConfig(
        enabled = true, section = "pers", ttlMs = 7 * LnktreeCacheConfig.DAY_MS, prefetchEnabled = true, prefetchConcurrency = 1,
        prefetchNetwork = "unmetered", prefetchMaxPages = 3, maxSubresourcesPerPage = 5, maxBytes = 1_000_000,
        holderMaxWebViews = 8, hardRefreshScope = "page", excludePages = emptySet(),
    )
    private fun store() = LnktreeCacheStore(tmp.newFolder("c"), { 1_000_000_000_000L })

    @Test fun `stale_while_revalidate is still supported and parsed`() {
        val c = LnktreeCacheConfig.parse(org.json.JSONObject("""{"enabled":true,"section":"s","strategy":"stale_while_revalidate"}"""))
        assertFalse(c.networkFirst)
        assertTrue(LnktreeCacheConfig.parse(org.json.JSONObject("""{"enabled":true,"section":"s","strategy":"network_first"}""")).networkFirst)
        val s = store(); s.put("pers", page, "text/html", "utf-8", 200, "old".toByteArray())
        assertEquals("old", LnktreeResolver.serve("pers", page, cfg.copy(strategy = LnktreeCacheConfig.STALE_WHILE_REVALIDATE), s, { error("cache first") }, {}, page)!!.body.readText())
    }

    @Test fun `the Cloud Me declaration documents network_first and is cache_ttl since 1957`() {
        val d = org.json.JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui").getJSONObject("lnktree_cache")
        assertEquals("cache_ttl", d.getString("strategy")); assertTrue(d.getString("_doc").contains("network_first"))   // #1957 cache_ttl since the owner policy
    }

    @Test fun `a released page keeps its offline copy under network_first and drops it under stale_while_revalidate`() {
        assertFalse(cfg.copy(strategy = LnktreeCacheConfig.NETWORK_FIRST).releaseDropsCache)
        assertTrue(cfg.copy(strategy = LnktreeCacheConfig.STALE_WHILE_REVALIDATE).releaseDropsCache)
    }
}
