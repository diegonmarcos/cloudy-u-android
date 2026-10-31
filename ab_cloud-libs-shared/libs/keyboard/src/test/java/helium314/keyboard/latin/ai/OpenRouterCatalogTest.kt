// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import helium314.keyboard.latin.Catalog
import helium314.keyboard.latin.CatalogModel
import helium314.keyboard.latin.KeyValueStore
import helium314.keyboard.latin.OpenRouterCatalog
import helium314.keyboard.latin.OpenRouterCatalog.RefreshResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class MapStore : KeyValueStore {
    val map = HashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/** Configs ▸ AI Routing ▸ Refresh: the OpenRouter model list is parsed, cached with its time, and kept on failure. */
class OpenRouterCatalogTest {
    private val body = """
        {"data":[
          {"id":"google/gemini-2.5-flash","name":"Google: Gemini 2.5 Flash","context_length":1048576,
           "pricing":{"prompt":"0.0000003","completion":"0.0000025"}},
          {"id":"meta-llama/llama-3.3-70b-instruct:free","name":"Meta: Llama 3.3 70B Instruct (free)","context_length":131072,
           "pricing":{"prompt":"0","completion":"0"}},
          {"id":"acme/zero","name":"Acme: Zero","context_length":8000,"pricing":{"prompt":"0","completion":"0"}},
          {"id":"acme/noprice","name":"","context_length":0},
          {"name":"no id, skipped"}
        ]}
    """.trimIndent()

    @Test fun parsesNameProviderContextAndPricePerMillion() {
        val ms = OpenRouterCatalog.parse(body)
        assertEquals(4, ms.size)
        val g = ms[0]
        assertEquals("google/gemini-2.5-flash", g.id)
        assertEquals("Gemini 2.5 Flash", g.name)          // "Google: " prefix dropped
        assertEquals("google", g.provider)
        assertEquals(1048576, g.contextLength)
        assertEquals(0.3, g.inPerM!!, 1e-9)               // $/token * 1e6
        assertEquals(2.5, g.outPerM!!, 1e-9)
        assertFalse(g.free)
    }

    @Test fun freeBadgeFromSuffixOrZeroPrice() {
        val ms = OpenRouterCatalog.parse(body).associateBy { it.id }
        assertTrue(ms.getValue("meta-llama/llama-3.3-70b-instruct:free").free)
        assertTrue(ms.getValue("acme/zero").free)
        val np = ms.getValue("acme/noprice")
        assertFalse(np.free)                               // unknown price is not "free"
        assertNull(np.inPerM); assertNull(np.contextLength)
        assertEquals("acme/noprice", np.name)              // blank name falls back to the id
    }

    @Test fun cacheRoundTripKeepsEveryField() {
        val c = Catalog(1_700_000_000_000L, OpenRouterCatalog.parse(body))
        assertEquals(c, OpenRouterCatalog.decode(OpenRouterCatalog.encode(c)))
        assertNull(OpenRouterCatalog.decode("not json"))
        assertNull(OpenRouterCatalog.decode(null))
    }

    @Test fun refreshCachesWithTimestamp() {
        val store = MapStore()
        val r = OpenRouterCatalog.refresh(store, 42L) { body }
        assertTrue(r is RefreshResult.Ok)
        val cached = OpenRouterCatalog.load(store)!!
        assertEquals(42L, cached.fetchedAt)
        assertEquals(4, cached.models.size)
    }

    @Test fun offlineKeepsThePreviousCacheAndSaysOffline() {
        val store = MapStore()
        OpenRouterCatalog.refresh(store, 1L) { body }
        val r = OpenRouterCatalog.refresh(store, 2L) { throw UnknownHostException("openrouter.ai") }
        assertTrue(r is RefreshResult.Offline)
        assertEquals(1L, (r as RefreshResult.Offline).kept!!.fetchedAt)
        assertEquals(1L, OpenRouterCatalog.load(store)!!.fetchedAt) // not overwritten
    }

    @Test fun httpErrorAndEmptyListAreFailuresNotOffline() {
        val store = MapStore()
        assertTrue(OpenRouterCatalog.refresh(store, 1L) { throw IOException("HTTP 503") } is RefreshResult.Failed)
        assertTrue(OpenRouterCatalog.refresh(store, 1L) { """{"data":[]}""" } is RefreshResult.Failed)
        assertTrue(OpenRouterCatalog.refresh(store, 1L) { "<html>" } is RefreshResult.Failed)
        assertNull(OpenRouterCatalog.load(store))
    }

    @Test fun searchMatchesEveryWordAcrossNameIdProviderAndFreeFilter() {
        val ms = OpenRouterCatalog.parse(body)
        assertEquals(listOf("google/gemini-2.5-flash"), OpenRouterCatalog.filter(ms, "gemini flash", false).map { it.id })
        assertEquals(listOf("google/gemini-2.5-flash"), OpenRouterCatalog.filter(ms, "GOOGLE", false).map { it.id })
        assertEquals(emptyList<String>(), OpenRouterCatalog.filter(ms, "gemini llama", false).map { it.id })
        assertEquals(listOf("meta-llama/llama-3.3-70b-instruct:free", "acme/zero"),
            OpenRouterCatalog.filter(ms, "", true).map { it.id })
        assertEquals(listOf("acme/zero"), OpenRouterCatalog.filter(ms, "acme", true).map { it.id })
        assertEquals(4, OpenRouterCatalog.filter(ms, "  ", false).size)
    }

    @Test fun offlineClassification() {
        assertTrue(OpenRouterCatalog.isOffline(UnknownHostException()))
        assertTrue(OpenRouterCatalog.isOffline(java.net.ConnectException()))
        assertTrue(OpenRouterCatalog.isOffline(java.net.SocketTimeoutException()))
        assertFalse(OpenRouterCatalog.isOffline(IOException("HTTP 500")))
    }

    @Suppress("unused") private fun model(id: String) = CatalogModel(id, id, "p", null, null, null)
}
