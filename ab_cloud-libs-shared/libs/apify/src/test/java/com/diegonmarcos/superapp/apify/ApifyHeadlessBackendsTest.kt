package com.diegonmarcos.superapp.apify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** #1498 Phone Browser and Cloudy Headless next to Apify / Crawlee / Cloudflare in the shared scraper backend list. */
class ApifyHeadlessBackendsTest {
    private class Mem : ApifyKv {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun set(key: String, value: String?) { if (value.isNullOrBlank()) m.remove(key) else m[key] = value }
    }

    @Test fun declaredListCarriesBothNewBackendsAndKeepsTheDefault() {
        val ids = ApifyBackends.DECLARED.backends.map { it.id }
        assertEquals(listOf("cloud", "selfhosted", "cloudflare", "phone", "headless"), ids)
        assertEquals("cloud", ApifyBackends.DECLARED.default)
        assertEquals(1, ApifyBackends.DECLARED.backends.count { it.api == null }) // selfUrl() still finds the one self-hosted
        assertTrue(ApifyBackends.DECLARED.byId("phone").headless)
        assertFalse(ApifyBackends.DECLARED.byId("cloudflare").headless)
    }

    @Test fun parsedDeclarationsKeepTheKind() {
        val b = ApifyBackends.parse("""{"default_backend":"phone","backends":[{"id":"phone","label":"Phone Browser","kind":"phone","api":"http://127.0.0.1:8765"},{"id":"cloud","label":"A","api":"https://api.apify.com/v2"}]}""")
        assertEquals("phone", b.default)
        assertTrue(b.byId("phone").headless)
        assertFalse(b.byId("cloud").headless)
    }

    @Test fun storeResolvesPhoneAndHeadlessWithTheirOwnKeys() {
        val kv = Mem(); val st = ApifyBackendStore(kv)
        val phone = st.decl.byId("phone"); val hl = st.decl.byId("headless")
        assertNull(st.resolve(phone)); assertFalse(st.hasToken(hl))
        assertTrue(st.setToken(phone, "ptok")); assertTrue(st.setToken(hl, "htok"))
        assertEquals("http://127.0.0.1:8765" to "ptok", st.resolve(phone))
        assertEquals("https://mcp.diegonmarcos.com/cloudy-browser-headless" to "htok", st.resolve(hl))
        assertEquals("phone.browser.token", st.tokenKey(phone)); assertEquals("headless.cloudy.token", st.tokenKey(hl))
        kv.set("headless.cloudy.url", "https://other.example/x/")
        assertEquals("https://other.example/x", st.resolve(hl)!!.first)
        kv.set("headless.cloudy.url", "http://insecure.example")
        assertEquals("https://mcp.diegonmarcos.com/cloudy-browser-headless", st.resolve(hl)!!.first)
        st.setBackend("headless"); assertEquals("headless", st.backend().id)
    }

    @Test fun runBodyAndMarkdownFollowTheSharedApi() {
        val o = JSONObject(ApifyBackends.headlessRunBody(" https://a.test/x "))
        val steps = o.getJSONArray("steps")
        assertEquals(listOf("open", "wait", "readable", "close"), (0 until steps.length()).map { steps.getJSONObject(it).getString("cmd") })
        assertEquals("https://a.test/x", steps.getJSONObject(0).getString("url"))
        val ok = """{"ok":true,"steps":[{"ok":true},{"ok":true},{"ok":true,"result":{"title":"t","markdown":"# Hi"}},{"ok":true}]}"""
        assertEquals("# Hi", ApifyBackends.headlessMarkdown(ok))
        val bad = """{"ok":false,"steps":[{"ok":false,"error":{"code":"timeout","message":"open timed out"}}]}"""
        try { ApifyBackends.headlessMarkdown(bad); throw AssertionError("expected IOException") } catch (e: IOException) { assertEquals("open timed out", e.message) }
        try { ApifyBackends.headlessMarkdown("""{"ok":false,"error":{"code":"unauthorized","message":"bearer required"}}"""); throw AssertionError("expected IOException") } catch (e: IOException) { assertEquals("bearer required", e.message) }
    }
}
