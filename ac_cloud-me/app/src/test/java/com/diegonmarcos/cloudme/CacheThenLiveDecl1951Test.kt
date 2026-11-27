package com.diegonmarcos.cloudme

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1951 the strategy declaration (org.json needs Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CacheThenLiveDecl1951Test {
    @Test fun `cache_then_live is a declared strategy next to the two others`() {
        val o = { x: String -> org.json.JSONObject("""{"enabled":true,"section":"s","strategy":"$x","intercept_timeout_ms":2000,"prefetch":{"refresh_hours":6,"start_delay_s":20}}""") }
        val a = LnktreeCacheConfig.parse(o("cache_then_live"))
        assertTrue(a.cacheThenLive); assertFalse(a.networkFirst); assertFalse(a.releaseDropsCache)
        assertEquals(2000, a.interceptTimeoutMs); assertEquals(6, a.prefetchRefreshHours); assertEquals(20, a.prefetchStartDelaySeconds)
        assertTrue(LnktreeCacheConfig.parse(o("network_first")).networkFirst)
        assertFalse(LnktreeCacheConfig.parse(o("stale_while_revalidate")).cacheThenLive)
        val d = org.json.JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui").getJSONObject("lnktree_cache")
        assertEquals("cache_ttl", d.getString("strategy")); assertTrue(d.getString("_doc").contains("cache_then_live"))   // #1957 the owner policy replaced it as the default; the value stays available
    }
}
