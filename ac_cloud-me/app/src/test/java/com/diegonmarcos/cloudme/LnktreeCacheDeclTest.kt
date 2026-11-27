package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1323 the declaration: build.json::ui.lnktree_cache, parsed by the class the app uses, and the real page files it applies to. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LnktreeCacheDeclTest {
    private val decl = JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui").getJSONObject("lnktree_cache")
    private val cfg = LnktreeCacheConfig.parse(decl)

    @Test fun `the declared values are what the app reads`() {
        assertTrue(cfg.enabled)
        assertEquals("proj", cfg.section)   // #1901 the Lnktree section is Proj
        assertTrue(cfg.cachedSections().containsAll(listOf("pers", "prof", "proj", "lnktree")))   // #1951 all sections
        assertEquals(7 * 24 * 60 * 60 * 1000L, cfg.ttlMs)
        assertTrue(cfg.prefetchEnabled)
        assertEquals(3, cfg.prefetchConcurrency)
        assertEquals("connected", cfg.prefetchNetwork)   // #1951
        assertEquals(0, cfg.prefetchMaxPages)   // #1951 0 = every page
        assertEquals(0, cfg.maxSubresourcesPerPage)   // #1957 0 = every file of the page
        assertEquals(8, cfg.holderMaxWebViews)
        assertEquals("page", cfg.hardRefreshScope)
        assertEquals(512L * 1024 * 1024, cfg.maxBytes)   // #1957 every file of every page, cross-origin media included (#1951 measured 62 MB same-origin only)
        assertTrue(cfg.excludePages.isEmpty())
        assertTrue(decl.getString("_doc").isNotBlank())
    }

    @Test fun `every value is parsed from the declaration, not defaulted`() {
        val c = LnktreeCacheConfig.parse(JSONObject("""{"enabled":true,"section":"s","ttl_days":0.5,"max_cache_mb":1,"holder_max_webviews":2,
            "hard_refresh_scope":"all","exclude_pages":["a","b"],"prefetch":{"enabled":true,"concurrency":5,"network":"connected","max_pages":4,"max_subresources_per_page":9}}"""))
        assertEquals(12 * 60 * 60 * 1000L, c.ttlMs); assertEquals(1024L * 1024, c.maxBytes); assertEquals(2, c.holderMaxWebViews)
        assertEquals("all", c.hardRefreshScope); assertEquals(setOf("a", "b"), c.excludePages)
        assertEquals(5, c.prefetchConcurrency); assertEquals("connected", c.prefetchNetwork); assertEquals(4, c.prefetchMaxPages); assertEquals(9, c.maxSubresourcesPerPage)
        assertFalse(c.caches("a")); assertTrue(c.caches("c"))
    }

    @Test fun `an absent or empty declaration turns the cache off`() {
        assertFalse(LnktreeCacheConfig.parse(null).enabled)
        assertFalse(LnktreeCacheConfig.parse(JSONObject()).enabled)
        assertFalse(LnktreeCacheConfig.parse(JSONObject("""{"ttl_days":7}""")).caches("leafy"))
    }

    @Test fun `the pages the cache covers are the https web pages of the section`() {
        val files = mapOf("leafy" to "prof/leafy", "nexus" to "prof/nexus", "stark" to "prof/stark", "astro" to "pers/astro", "lt-lnktree-site" to "lnktree/lt-lnktree-site",
            "personal" to "pers/pers-profiles/personal", "ig0" to "pers/pers-profiles/ig0", "orkut" to "pers/pers-profiles/orkut",
            "health-profile" to "pers/health/health-profile", "dd" to "pers/dd", "pj-cg-omniverse" to "proj/pj-cg/pj-cg-omniverse", "lt-area-unix" to "lnktree/lt-area-unix")
        val got = files.mapNotNull { (id, f) -> LnktreePages.fromStack(id, JSONArray(BuroLlcTestData.text("data/ui/$f.json")), cfg) }
        assertEquals(setOf("leafy", "nexus", "stark", "astro", "lt-lnktree-site", "personal", "ig0", "orkut", "pj-cg-omniverse"), got.map { it.id }.toSet())
        assertTrue(got.all { it.url.startsWith("https://") })
        val optOut = cfg.copy(excludePages = setOf("stark"))
        assertNull(LnktreePages.fromStack("stark", JSONArray(BuroLlcTestData.text("data/ui/prof/stark.json")), optOut))
    }
}
