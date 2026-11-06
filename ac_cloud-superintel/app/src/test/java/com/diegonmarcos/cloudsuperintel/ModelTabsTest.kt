package com.diegonmarcos.cloudsuperintel

import com.diegonmarcos.superapp.ai.AiModelListCache
import com.diegonmarcos.superapp.ai.AiModelSearch
import com.diegonmarcos.superapp.ai.AiModelText
import com.diegonmarcos.superapp.modelcatalogue.CatalogueFetch
import com.diegonmarcos.superapp.modelcatalogue.CatalogueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Model Catalogue's tabs: the layout and order, no empty or placeholder tab, the live search filter and its TTL cache, over the real declared data. */
class ModelTabsTest {
    private val tabs = LibAi.tabs()

    @Test fun theTabsAreInThreeRowsUnderCoreInTheOwnersOrder() {
        assertEquals(1, tabs.layout.size)
        assertEquals("Core", tabs.layout[0].header)
        assertEquals(
            listOf(listOf("frontier", "text", "search"), listOf("audio", "image", "video"), listOf("specialist", "tasks", "medium", "small")),
            tabs.layout[0].rows,
        )
        assertEquals(
            listOf("Frontier", "Text", "Search", "Audio & Speech", "Image", "Video", "Specialist", "Tasks", "Medium", "Small"),
            tabs.order.map { tabs.labels.getValue(it) },
        )
    }

    @Test fun noTabIsEmptyAndEveryEntryIsComplete() {
        for (id in tabs.order) {
            val list = tabs.entries(id)
            assertTrue("tab $id is empty", list.isNotEmpty())
            assertEquals("tab $id repeats a model", list.size, list.map { it.id }.toSet().size)
            for (e in list) {
                assertTrue("${e.id} id", '/' in e.id)
                assertTrue("${e.id} name and provider", e.name.isNotBlank() && e.provider.isNotBlank())
                assertTrue("${e.id} modalities", e.modalitiesIn.isNotEmpty() && e.modalitiesOut.isNotEmpty())
                assertTrue("${e.id} price", e.price.input != null || e.price.output != null)
                assertTrue("${e.id} link", e.link.startsWith("https://") && e.id in e.link)
                assertTrue("${e.id} context", e.context > 0 || !e.chatCapable)
            }
        }
    }

    @Test fun noTabCarriesPlaceholderTbdOrSampleText() {
        val bad = Regex("(?i)\\b(tbd|tbc|todo|placeholder|lorem|sample|example|dummy|coming soon|xxx)\\b")
        for (id in tabs.order) for (e in tabs.entries(id)) {
            for (text in listOf(e.id, e.name, e.provider, e.note, e.domain)) assertFalse("${e.id}: $text", bad.containsMatchIn(text))
        }
    }

    @Test fun imageAndAudioTabsAreFilledFromTheModalitiesOpenRouterDeclares() {
        val image = tabs.entries("image")
        assertTrue(image.size >= 10)
        assertTrue(image.all { "image" in it.modalitiesOut })
        assertTrue("some image models are chat-capable (the image flow)", image.any { it.chatCapable && it.outputsImage })
        val audio = tabs.entries("audio")
        assertTrue(audio.size >= 5)
        assertTrue(audio.all { "audio" in it.modalitiesIn || "audio" in it.modalitiesOut })
        assertTrue(tabs.entries("video").all { "video" in it.modalitiesOut || "video" in it.modalitiesIn })
    }

    @Test fun everyTabHasItsDeclaredRules() {
        val rules = org.json.JSONObject(LibAi.asset("model-tabs.json")).getJSONObject("tabs")
        assertEquals(tabs.order.toSet(), rules.keys().asSequence().toSet())
        for (id in tabs.order) assertTrue("$id rules", rules.getJSONObject(id).getJSONArray("rules").length() > 0)
    }

    private val live = """{"data":[
      {"id":"acme/pix-draw","name":"Acme: Pix Draw","context_length":65536,"architecture":{"input_modalities":["text","image"],"output_modalities":["image","text"]},"pricing":{"prompt":"0.0000005","completion":"0.000003"}},
      {"id":"acme/chat-one","name":"Acme: Chat One","context_length":128000,"architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0.000001","completion":"0.000004"}},
      {"id":"other/voice-1","name":"Other: Voice 1","context_length":32000,"architecture":{"input_modalities":["text","audio"],"output_modalities":["text","audio"]},"pricing":{"prompt":"0.000002","completion":"0.00001"}},
      {"id":"openrouter/auto","name":"Auto Router","context_length":2000000,"architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"-1","completion":"-1"}}
    ]}"""

    @Test fun theLiveListIsParsedAndRoutersAreSkipped() {
        val all = AiModelSearch.parseLive(live)
        assertEquals(listOf("acme/pix-draw", "acme/chat-one", "other/voice-1"), all.map { it.id })
        val pix = all.first()
        assertEquals("Pix Draw", pix.name)
        assertEquals("Acme", pix.provider)
        assertEquals(0.5, pix.price.input!!, 1e-9)
        assertEquals(3.0, pix.price.output!!, 1e-9)
        assertTrue(pix.outputsImage && pix.chatCapable)
        assertEquals("text+image->image+text", pix.modality)
    }

    @Test fun theSearchFiltersByNameIdProviderAndModality() {
        val all = AiModelSearch.parseLive(live)
        assertEquals(listOf("acme/chat-one"), AiModelSearch.filter(all, "chat", 10).map { it.id })
        assertEquals(listOf("acme/chat-one", "acme/pix-draw"), AiModelSearch.filter(all, "acme", 10).map { it.id })
        assertEquals(listOf("other/voice-1"), AiModelSearch.filter(all, "audio", 10).map { it.id })
        assertEquals(listOf("acme/pix-draw"), AiModelSearch.filter(all, "out:image", 10).map { it.id })
        assertEquals(listOf("acme/pix-draw"), AiModelSearch.filter(all, "in:image acme", 10).map { it.id })
        assertEquals(listOf("other/voice-1"), AiModelSearch.filter(all, "OTHER voice", 10).map { it.id })
        assertTrue(AiModelSearch.filter(all, "nothing-like-this", 10).isEmpty())
        assertTrue(AiModelSearch.filter(all, "   ", 10).isEmpty())
        assertEquals(1, AiModelSearch.filter(all, "acme", 1).size)
    }

    @Test fun theSearchRunsOverTheBakedTabsToo() {
        val hits = AiModelSearch.filter(tabs.all.values.toList(), "out:image", 100)
        assertTrue(hits.isNotEmpty() && hits.all { it.outputsImage })
    }

    private class Store : CatalogueStore {
        val map = HashMap<String, CatalogueStore.Entry>()
        override fun get(key: String) = map[key]
        override fun put(key: String, body: String, at: Long) { map[key] = CatalogueStore.Entry(body, at) }
    }

    @Test fun theListIsFetchedOnDemandOncePerTtlAndStaleOnFailure() {
        var now = 1_000L
        var calls = 0
        var up = true
        val fetch = CatalogueFetch { calls++; if (up) live else null }
        val cache = AiModelListCache("https://x/models", 60_000, fetch, Store()) { now }
        assertEquals("nothing is fetched until asked", 0, calls)
        assertEquals(3, cache.get()!!.size)
        assertEquals(1, calls)
        now += 59_000
        cache.get()
        assertEquals("within the TTL nothing is asked", 1, calls)
        now += 2_000
        up = false
        assertEquals("a failed refresh answers the stale copy", 3, cache.get()!!.size)
        assertEquals(2, calls)
        up = true
        cache.get()
        assertEquals(3, calls)
    }

    @Test fun noCopyAndNoListMeansNull() {
        val cache = AiModelListCache("https://x/models", 60_000, CatalogueFetch { null }, Store())
        assertNull(cache.get())
    }

    @Test fun theSearchIsDeclaredDebouncedAndCachedWithNoPolling() {
        val s = tabs.search
        assertEquals("https://openrouter.ai/api/v1/models", s.url)
        assertTrue(s.debounceMs in 200..1000)
        assertTrue(s.ttlMs >= 60_000)
        assertTrue(s.minChars >= 2 && s.maxResults in 10..100)
    }

    @Test fun aRowSaysItsContextPriceAndModalities() {
        val e = AiModelSearch.parseLive(live).first()
        assertEquals("65K ctx · in \$0.50 · out \$3.00/1M · text+image->image+text", AiModelText.spec(e))
        assertEquals("1M", AiModelText.context(1_048_576))
        assertEquals("-", AiModelText.context(0))
        assertNotNull(tabs.entries("frontier").firstOrNull())
    }
}
