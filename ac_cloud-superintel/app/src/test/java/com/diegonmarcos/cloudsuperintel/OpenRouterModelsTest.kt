package com.diegonmarcos.cloudsuperintel

import com.diegonmarcos.superapp.ai.AiModelEntry
import com.diegonmarcos.superapp.ai.AiModelPrice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #1854 The OpenRouter metadata parser, the words of each cell, the join with a Core, and the daily disk cache, on a fixture of the API's real shape. */
class OpenRouterModelsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fixture = File("src/test/resources/openrouter-models.json").readText()
    private val models = OpenRouterModels.parse(fixture)

    @Test fun parsesEveryFieldOfTheFullMetadata() {
        val m = models.first { it.id == "anthropic/claude-sonnet-4.5" }
        assertEquals("Anthropic: Claude Sonnet 4.5", m.name)
        assertEquals(1_000_000L, m.contextLength)
        assertEquals("0.000003", m.pricePrompt)
        assertEquals("0.000015", m.priceCompletion)
        assertEquals("0", m.priceRequest)
        assertEquals("0.0048", m.priceImage)
        assertEquals("text+image->text", m.modality)
        assertEquals("Claude", m.tokenizer)
        assertEquals(64_000L, m.maxCompletionTokens)
        assertEquals(true, m.isModerated)
        assertEquals(listOf("include_reasoning", "max_tokens", "reasoning", "stop", "temperature", "tool_choice", "tools", "top_p"), m.supportedParameters)
        assertEquals(1759161676L, m.created)
        assertTrue(m.description.startsWith("Claude Sonnet 4.5 is"))
    }

    @Test fun anEntryWithoutIdIsSkippedAndAMissingFieldStaysEmpty() {
        assertEquals(listOf("anthropic/claude-sonnet-4.5", "meta-llama/llama-3.3-70b-instruct:free", "openrouter/auto", "acme/bare-model"), models.map { it.id })
        val bare = models.last()
        assertEquals("acme/bare-model", bare.name)
        assertEquals(0L, bare.contextLength)
        assertNull(bare.pricePrompt); assertNull(bare.maxCompletionTokens); assertNull(bare.isModerated)
        assertEquals("", bare.modality); assertEquals("", bare.tokenizer); assertTrue(bare.supportedParameters.isEmpty())
        assertNull(models.first { it.id.startsWith("meta-llama") }.maxCompletionTokens)
    }

    @Test fun garbageParsesToNothing() {
        assertTrue(OpenRouterModels.parse("").isEmpty())
        assertTrue(OpenRouterModels.parse("not json").isEmpty())
        assertTrue(OpenRouterModels.parse("{\"data\": 3}").isEmpty())
        assertTrue(OpenRouterModels.parse("{}").isEmpty())
    }

    @Test fun cellsSayEveryFieldInTheTablesWords() {
        val m = models.first { it.id == "anthropic/claude-sonnet-4.5" }
        val cells = OpenRouterModels.COLUMNS.associateWith { OpenRouterModels.cell(m, it) }
        assertEquals("anthropic/claude-sonnet-4.5", cells["id"])
        assertEquals("1M", cells["context_length"])
        assertEquals("$3", cells["prompt"])
        assertEquals("$15", cells["completion"])
        assertEquals("-", cells["request"])
        assertEquals("$0.0048", cells["image"])
        assertEquals("text+image->text", cells["modality"])
        assertEquals("Claude", cells["tokenizer"])
        assertEquals("64K", cells["max_completion_tokens"])
        assertEquals("yes", cells["is_moderated"])
        assertTrue(cells.getValue("supported_parameters").startsWith("include_reasoning, max_tokens"))
        assertEquals("2025-09-29", cells["created"])
        // The free model says free, a router's negative price says var, a missing price says "-".
        val free = models.first { it.id.endsWith(":free") }
        assertEquals("free", OpenRouterModels.cell(free, "prompt"))
        assertEquals("no", OpenRouterModels.cell(free, "is_moderated"))
        assertEquals("var", OpenRouterModels.cell(models.first { it.id == "openrouter/auto" }, "prompt"))
        assertEquals("-", OpenRouterModels.cell(models.last(), "prompt"))
        assertEquals("-", OpenRouterModels.cell(models.last(), "created"))
    }

    @Test fun theColumnsAreTheTicketsFullMetadata() {
        assertEquals(
            listOf("id", "name", "context_length", "prompt", "completion", "request", "image", "modality", "tokenizer", "max_completion_tokens", "is_moderated", "supported_parameters", "created", "description"),
            OpenRouterModels.COLUMNS,
        )
    }

    @Test fun priceWordsAreExactForSmallPrices() {
        assertEquals("$0.15", OpenRouterModels.perMillion("0.00000015"))
        assertEquals("$0.075", OpenRouterModels.perMillion("0.000000075"))
        assertEquals("-", OpenRouterModels.perMillion(null))
        assertEquals("-", OpenRouterModels.perMillion("abc"))
        assertEquals("128K", OpenRouterModels.tokens(128_000))
        assertEquals("1.5M", OpenRouterModels.tokens(1_500_000))
        assertEquals("512", OpenRouterModels.tokens(512))
    }

    private fun entry(id: String) = AiModelEntry(id, "Baked " + id, "p", listOf("text"), listOf("text"), 8000, AiModelPrice("tokens", 1.0, 2.0), "l", "chat", note = "baked note")

    @Test fun joinKeepsTheCoresOrderAndMarksWhatTheLiveListLacks() {
        val rows = OpenRouterModels.join(listOf(entry("openrouter/auto"), entry("gone/model"), entry("anthropic/claude-sonnet-4.5")), models)
        assertEquals(listOf("openrouter/auto", "gone/model", "anthropic/claude-sonnet-4.5"), rows.map { it.model.id })
        assertEquals(listOf(true, false, true), rows.map { it.live })
        assertEquals("Auto Router", rows[0].model.name)
        assertEquals("Baked gone/model", rows[1].model.name)
        assertEquals(8000L, rows[1].model.contextLength)
        assertEquals("text->text", rows[1].model.modality)
        assertNull(rows[1].model.pricePrompt)
    }

    private fun store(fetch: (String) -> String?, clock: () -> Long) = OpenRouterStore(File(tmp.root, "or"), fetch, clock)

    @Test fun anEmptyCacheIsStaleAndTheFirstGetFetchesAndStores() {
        var now = 1_000L
        var calls = 0
        val s = store({ url -> calls++; assertEquals(OpenRouterModels.URL, url); fixture }, { now })
        assertNull(s.cached()); assertTrue(s.stale())
        val got = s.get()
        assertNotNull(got); assertEquals(4, got!!.models.size); assertEquals(1_000L, got.at)
        assertEquals(1, calls)
        // A second store over the same directory (a new process) reads the cache from disk.
        val again = store({ error("no network") }, { now })
        assertEquals(4, again.cached()!!.models.size)
        assertFalse(again.stale())
    }

    @Test fun refreshesOnceADayNotBefore() {
        var now = 0L
        var calls = 0
        val s = store({ calls++; fixture }, { now })
        s.get(); assertEquals(1, calls)
        now = OpenRouterModels.MAX_AGE_MS - 1; s.get(); assertEquals("under a day: the cache", 1, calls)
        now = OpenRouterModels.MAX_AGE_MS; s.get(); assertEquals("a day: refreshed", 2, calls)
        assertEquals(OpenRouterModels.MAX_AGE_MS, s.cached()!!.at)
        now = OpenRouterModels.MAX_AGE_MS + 5; s.get(); assertEquals("just refreshed", 2, calls)
    }

    @Test fun theRefreshButtonForcesAFetchAtOnce() {
        var now = 0L
        var calls = 0
        val s = store({ calls++; fixture }, { now })
        s.get(); now = 10
        s.get(force = true)
        assertEquals(2, calls)
        assertEquals(10L, s.cached()!!.at)
    }

    @Test fun aFailedOrEmptyFetchKeepsTheOldCache() {
        var now = 0L
        var body: String? = fixture
        val s = store({ body }, { now })
        s.get()
        now = OpenRouterModels.MAX_AGE_MS + 1
        body = null
        val kept = s.get()
        assertEquals("the stale cache answers", 4, kept!!.models.size)
        assertEquals(0L, kept.at)
        body = "{\"data\": []}"
        assertNull(s.refresh())
        assertEquals(0L, s.cached()!!.at)
        body = "garbage"
        assertNull(s.refresh())
        assertEquals(4, s.cached()!!.models.size)
    }

    @Test fun aClockSetBackCountsAsStale() {
        var now = 5_000_000L
        val s = store({ fixture }, { now })
        s.get()
        now = 1_000L
        assertTrue(s.stale())
    }

    @Test fun noCacheAndNoNetworkIsNull() {
        assertNull(store({ null }, { 0 }).get())
    }
}
