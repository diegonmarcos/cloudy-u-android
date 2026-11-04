package com.diegonmarcos.superapp.modelcatalogue

import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue.Kind
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue.Price
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue.PriceUnit
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue.RankBy
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue.Source
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogueRepository.Origin
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The shared model catalogue (Cloud Search's Chat › Search, Cloud Code's Chat) against a RECORDED OpenRouter (fixtures/openrouter-catalogue, read
 * 2026-10-10): the per-token → per-1M conversion and the floor, the order of every section
 * (Anthropic first, then ascending price), one row per provider, the daily cache and its offline
 * fallbacks, every curated id present in the catalogue that prices it, and the chat refusing what
 * is not a chat model. No test here touches the network.
 */
class ModelCatalogueTest {
    @get:Rule val tmp = TemporaryFolder()

    // The test task runs in this module's directory: the selection every consumer's assets merge.
    private val curatedText: String by lazy { File("src/main/assets/" + ModelCatalogue.ASSET).readText() }
    private val cat by lazy { ModelCatalogue.parse(curatedText) }
    private fun fx(name: String) = (javaClass.getResource("/fixtures/openrouter-catalogue/$name") ?: error("missing fixture $name")).readText(Charsets.UTF_8)
    private fun section(id: String) = cat.sections.first { it.id == id }

    // ── conversion and floor ─────────────────────────────────────────────

    @Test fun perTokenStringsBecomeDollarsPerMillion() {
        assertEquals(1.8, OpenRouterPrices.perMillion("0.0000018")!!, 0.0)
        assertEquals(1.28, OpenRouterPrices.perMillion("0.00000128")!!, 0.0)
        assertEquals(0.0126, OpenRouterPrices.perMillion("0.0000000126")!!, 0.0)
        assertEquals(0.0, OpenRouterPrices.perMillion("0")!!, 0.0)
        assertEquals(2.0, OpenRouterPrices.perMillion(0.000002)!!, 0.0)
        assertNull("a router's -1 is 'varies', not a price", OpenRouterPrices.perMillion("-1"))
        assertNull(OpenRouterPrices.perMillion(null))
        assertNull(OpenRouterPrices.perMillion("n/a"))
        assertNull(OpenRouterPrices.perMillion(" "))
        assertNull(OpenRouterPrices.perMillion(Double.NaN))
        assertNull(OpenRouterPrices.perMillion(true))
    }

    @Test fun theListPriceIsReadPerModel() {
        val list = OpenRouterPrices.list(fx("models.json"))
        assertEquals(Price(PriceUnit.TOKENS, 0.3, 1.0), list["qwen/qwen3-coder"])
        assertEquals(Price(PriceUnit.TOKENS, 0.0126, 1.28), list["deepseek/deepseek-v4-flash-0731"])
        val router = list.getValue("typesafe/jev-router")
        assertNull(router.input); assertNull(router.output)
        assertTrue(OpenRouterPrices.list("""{"nope":1}""").isEmpty())
    }

    @Test fun theFloorIsTheCheapestProvider() {
        assertEquals(0.0131 to 0.18, OpenRouterPrices.floor(fx("endpoints-deepseek_deepseek-v4-flash-0731.json")))
        assertEquals(0.22 to 1.0, OpenRouterPrices.floor(fx("endpoints-qwen_qwen3-coder.json")))
        assertEquals(null to null, OpenRouterPrices.floor("""{"data":{}}"""))
        assertEquals(null to null, OpenRouterPrices.floor("""{"data":{"endpoints":[{"pricing":{"prompt":"-1"}}]}}"""))
    }

    @Test fun aFloorNeverRaisesTheListPrice() {
        val list = Price(PriceUnit.TOKENS, 0.0126, 1.28)
        assertEquals(list.copy(floorInput = 0.0126, floorOutput = 0.18), OpenRouterPrices.withFloor(list, 0.0131 to 0.18))
        assertEquals(list, OpenRouterPrices.withFloor(list, null to null))
        assertEquals(Price(PriceUnit.TOKENS, null, null, 0.5, 0.7), OpenRouterPrices.withFloor(Price(PriceUnit.TOKENS, null, null), 0.5 to 0.7))
    }

    @Test fun imagesAreBilledPerImageMegapixelOrToken() {
        assertEquals(Price(PriceUnit.IMAGE, 0.003, 0.03, 0.003, 0.03), OpenRouterPrices.image(fx("image-endpoints-qwen_qwen-image-3.json")))
        assertEquals(Price(PriceUnit.MEGAPIXEL, null, 0.014, null, 0.014), OpenRouterPrices.image(fx("image-endpoints-black-forest-labs_flux.2-klein-4b.json")))
        assertEquals(Price(PriceUnit.TOKENS, null, 1.6, null, 1.6), OpenRouterPrices.image(fx("image-endpoints-tencent_hy-image-v3.5-preview.json")))
        assertNull(OpenRouterPrices.image("""{"endpoints":[]}"""))
        assertNull(OpenRouterPrices.image("""{"x":1}"""))
        assertNull("an unknown unit is not guessed", OpenRouterPrices.image("""{"endpoints":[{"pricing":[{"billable":"output_image","unit":"request","cost_usd":1}]}]}"""))
        val two = """{"endpoints":[{"pricing":[{"billable":"output_image","unit":"image","cost_usd":0.05},{"billable":"output_image","unit":"image","cost_usd":0.04}]},
            {"pricing":[{"billable":"output_image","unit":"image","cost_usd":0.02},{"billable":"input_image","unit":"image","cost_usd":0.001}]}]}"""
        assertEquals("list = the first provider's cheapest variant, floor = the cheapest anywhere",
            Price(PriceUnit.IMAGE, null, 0.04, 0.001, 0.02), OpenRouterPrices.image(two))
    }

    @Test fun videosArePricedPerSecondInCentsOrDollars() {
        val v = OpenRouterPrices.videos(fx("videos-models.json"))
        assertEquals(Price(PriceUnit.SECOND, null, 0.05), v["alibaba/wan-3.0"])
        assertEquals(Price(PriceUnit.SECOND, null, 0.02), v["x-ai/grok-imagine-video-1.5-lite"])
        assertTrue(OpenRouterPrices.videos("""{"data":[{"id":"a/b"},{"id":"c/d","pricing_skus":{"cents_per_image_input":"1"}}]}""").isEmpty())
    }

    // ── what a cell says ─────────────────────────────────────────────────

    @Test fun dollarsReadAsAPersonWritesThem() {
        assertEquals("$1.28", ModelCatalogue.usd(1.28))
        assertEquals("$50.00", ModelCatalogue.usd(50.0))
        assertEquals("$0.90", ModelCatalogue.usd(0.9))
        assertEquals("$0.18", ModelCatalogue.usd(0.18))
        assertEquals("$0.0126", ModelCatalogue.usd(0.0126))
        assertEquals("$0.657", ModelCatalogue.usd(0.65736))
        assertEquals("$0.005", ModelCatalogue.usd(0.005))
        assertEquals("$0.10", ModelCatalogue.usd(0.1))
        assertEquals(ModelCatalogue.FREE, ModelCatalogue.usd(0.0))
        assertEquals(ModelCatalogue.DASH, ModelCatalogue.usd(null))
        assertEquals(ModelCatalogue.DASH, ModelCatalogue.usd(-1.0))
    }

    @Test fun theOutputCellCarriesTheFloorWhenAProviderIsCheaper() {
        val code = section("A0")
        assertEquals("$1.28 (Floor ~$0.18)", ModelCatalogue.outputCell(code, Price(PriceUnit.TOKENS, 0.0126, 1.28, 0.0126, 0.18)))
        assertEquals("a floor within 1 % is the same price", "$1.00", ModelCatalogue.outputCell(code, Price(PriceUnit.TOKENS, 0.3, 1.0, 0.22, 0.995)))
        assertEquals("$0.30", ModelCatalogue.inputCell(code, Price(PriceUnit.TOKENS, 0.3, 1.0, 0.22, 1.0)))
        assertEquals(ModelCatalogue.FREE, ModelCatalogue.outputCell(code, Price(PriceUnit.TOKENS, 0.0, 0.0)))
        assertEquals(ModelCatalogue.DASH, ModelCatalogue.outputCell(code, null))
        assertEquals(ModelCatalogue.DASH, ModelCatalogue.outputCell(code, Price(PriceUnit.TOKENS, null, -1.0)))
        val emb = section("B0")
        assertEquals("embeddings bill no output", ModelCatalogue.DASH, ModelCatalogue.outputCell(emb, Price(PriceUnit.TOKENS, 0.02, 0.0)))
        assertEquals("$0.02 (Floor ~$0.01)", ModelCatalogue.inputCell(emb, Price(PriceUnit.TOKENS, 0.02, 0.0, 0.01, 0.0)))
        val media = section("D0")
        assertEquals("$0.03/img", ModelCatalogue.outputCell(media, Price(PriceUnit.IMAGE, 0.003, 0.03)))
        assertEquals("$0.014/MP", ModelCatalogue.outputCell(media, Price(PriceUnit.MEGAPIXEL, null, 0.014)))
        assertEquals("$0.05/s", ModelCatalogue.outputCell(media, Price(PriceUnit.SECOND, null, 0.05)))
        assertEquals("$1.60", ModelCatalogue.outputCell(media, Price(PriceUnit.TOKENS, null, 1.6)))
    }

    @Test fun anEstimateSaysSo() {
        assertEquals("MoE 400B / 17B est.", ModelCatalogue.est("MoE 400B / 17B", true))
        assertEquals("Apache-2.0", ModelCatalogue.est("Apache-2.0", false))
    }

    // ── order ────────────────────────────────────────────────────────────

    @Test fun anthropicLeadsThenTheRestRiseInPrice() {
        val live = OpenRouterPrices.list(fx("models.json"))
        for (s in cat.sections.filter { it.source == Source.CHAT }) {
            val rows = ModelCatalogue.ordered(s, live, emptySet())
            val firstOther = rows.indexOfFirst { !it.row.anthropic }
            assertTrue("${s.id}: an Anthropic row after another provider's", rows.drop(maxOf(firstOther, 0)).none { it.row.anthropic })
            assertEquals("${s.id}: Anthropic rows keep their curated order", s.rows.filter { it.anthropic }.map { it.id }, rows.takeWhile { it.row.anthropic }.map { it.row.id })
            val ranked = rows.filterNot { it.row.anthropic }.map { ModelCatalogue.rank(it, s.rankBy) ?: Double.MAX_VALUE }
            assertEquals("${s.id}: not low to high", ranked.sorted(), ranked)
        }
    }

    @Test fun rankingFollowsTheSectionsPriceAndUnpricedRowsSinkToTheEnd() {
        val s = small("""
          {"provider":"Anthropic","id":"anthropic/a","name":"A","params":"p","params_est":false,"license":"l","license_est":false,"snapshot":{"unit":"tokens","in":5,"out":25}},
          {"provider":"P1","id":"p1/x","name":"X","params":"p","params_est":false,"license":"l","license_est":false,"snapshot":{"unit":"tokens","in":0.1,"out":3}},
          {"provider":"P2","id":"p2/y","name":"Y","params":"p","params_est":false,"license":"l","license_est":false,"snapshot":{"unit":"tokens","in":0.9,"out":1}},
          {"provider":"P3","id":"p3/z","name":"Z","params":"p","params_est":false,"license":"l","license_est":false,"snapshot":{"unit":"tokens","in":-1,"out":-1}},
          {"provider":"P4","id":"p4/w","name":"W","params":"p","params_est":false,"license":"l","license_est":false,"snapshot":{"unit":"tokens","in":0.5}}""")
        assertEquals(listOf("anthropic/a", "p4/w", "p2/y", "p1/x", "p3/z"), ModelCatalogue.ordered(s, null, emptySet()).map { it.row.id })
        val byInput = s.copy(rankBy = RankBy.INPUT)
        assertEquals(listOf("anthropic/a", "p1/x", "p4/w", "p2/y", "p3/z"), ModelCatalogue.ordered(byInput, null, emptySet()).map { it.row.id })
        val live = mapOf("p1/x" to Price(PriceUnit.TOKENS, 0.1, 0.2))
        assertEquals("a live price reorders the section", "p1/x", ModelCatalogue.ordered(s, live, emptySet())[1].row.id)
        val mixed = s.copy(rows = s.rows.drop(1).take(2).let { (a, b) -> listOf(a.copy(snapshot = Price(PriceUnit.SECOND, null, 0.01)), b.copy(snapshot = Price(PriceUnit.IMAGE, null, 9.0))) })
        assertEquals("units rank apart: per image before per second", listOf("p2/y", "p1/x"), ModelCatalogue.ordered(mixed, null, emptySet()).map { it.row.id })
        assertNull(ModelCatalogue.rank(ModelCatalogue.Shown(s.rows[1], null, true, true), RankBy.OUTPUT))
    }

    // ── the selection file ───────────────────────────────────────────────

    @Test fun theCuratedCatalogueHasTheOwnersShape() {
        assertEquals(listOf("A", "B", "C", "D"), cat.groups.map { it.id })
        assertEquals(listOf("Text", "Search", "Audio & Speech", "Visual Media"), cat.groups.map { it.label })
        assertEquals(listOf("A0", "A1", "A2", "A3", "B0", "B1", "B2", "C0", "D0"), cat.sections.map { it.id })
        assertEquals(listOf("Code", "Agentic", "Base", "Reasoning"), cat.groups[0].sections.map { it.label })
        assertEquals(listOf("Embeddings", "Decisions", "Rerank"), cat.groups[1].sections.map { it.label })
        assertEquals(24, cat.refreshHours)
        for (s in cat.sections) {
            val others = s.rows.filterNot { it.anthropic }
            assertEquals("${s.id}: one row per provider", others.size, others.map { it.provider }.toSet().size)
            assertEquals("${s.id}: only a chat section holds Anthropic's reference", !s.chat || s.rows.none { it.anthropic }, s.needsReferenceRow)
            if (s.chat) assertTrue("${s.id}: no Anthropic reference row", s.rows.first().anthropic)
            else assertTrue("${s.id}: non-chat sections get the greyed reference row", s.needsReferenceRow)
        }
        assertEquals(setOf(Kind.EMBEDDINGS, Kind.RERANK, Kind.AUDIO, Kind.IMAGE), cat.sections.filterNot { it.chat }.map { it.kind }.toSet())
    }

    @Test fun everyCuratedIdIsInTheCatalogueThatPricesIt() {
        val chat = OpenRouterPrices.ids(fx("models.json"))
        val emb = OpenRouterPrices.ids(fx("embeddings-models.json"))
        val media = OpenRouterPrices.ids(fx("images-models.json")) + OpenRouterPrices.ids(fx("videos-models.json"))
        val absent = cat.sections.flatMap { s ->
            val listed = when (s.source) { Source.CHAT -> chat; Source.EMBEDDINGS -> emb; Source.IMAGES, Source.VIDEOS -> media; Source.NONE -> emptySet() }
            s.rows.map { it.id }.filterNot { it in listed }.map { "${s.id} $it" }
        }
        assertEquals("curated ids OpenRouter does not list", emptyList<String>(), absent)
        // The owner's draft named Llama 3.1 405B Instruct for Code: OpenRouter no longer lists it (Nous' fine-tune is all that is left).
        assertFalse("meta-llama/llama-3.1-405b-instruct" in chat)
        assertTrue("nousresearch/hermes-3-llama-3.1-405b" in chat)
    }

    @Test fun aSelectionThatWouldDrawAWrongTableIsRefused() {
        fun bad(edit: (JSONObject) -> Unit) = assertThrows(IllegalArgumentException::class.java) {
            ModelCatalogue.parse(JSONObject(curatedText).also(edit).toString())
        }
        fun sec(o: JSONObject, id: String) = (0 until o.getJSONArray("groups").length()).flatMap { g ->
            val a = o.getJSONArray("groups").getJSONObject(g).getJSONArray("sections"); (0 until a.length()).map { a.getJSONObject(it) }
        }.first { it.getString("id") == id }
        bad { o -> sec(o, "A0").getJSONArray("rows").getJSONObject(3).put("provider", "Mistral") }
        bad { o -> sec(o, "A0").getJSONArray("rows").getJSONObject(3).put("id", "mistralai/codestral-2508").put("provider", "Other") }
        bad { o -> sec(o, "B0").getJSONArray("rows").put(JSONObject(sec(o, "A0").getJSONArray("rows").getJSONObject(0).toString())) }
        bad { o -> sec(o, "B2").getJSONArray("rows").put(JSONObject(sec(o, "B0").getJSONArray("rows").getJSONObject(0).toString())) }
        bad { o -> sec(o, "A2").getJSONArray("rows").getJSONObject(2).remove("snapshot") }
        bad { o -> sec(o, "A2").getJSONArray("rows").getJSONObject(2).put("id", "nomodel") }
        bad { o -> sec(o, "A2").getJSONArray("rows").getJSONObject(2).put("provider", "Anthropic") }
        bad { o -> sec(o, "A2").getJSONArray("rows").getJSONObject(2).put("provider", " ") }
        bad { o -> sec(o, "A2").put("kind", "speech") }
        bad { o -> sec(o, "A2").put("id", "A1") }
        bad { o -> o.getJSONObject("urls").put("chat", "http://openrouter.ai/api/v1/models") }
        bad { o -> o.getJSONObject("urls").put("model_endpoints", "https://openrouter.ai/api/v1/models/endpoints") }
        bad { o -> o.put("refresh_hours", 0) }
        bad { o -> o.put("pricing_as_of", "10/10/2026") }
        bad { o -> o.put("groups", org.json.JSONArray()) }
    }

    // ── the chat picks only what can chat ────────────────────────────────

    @Test fun onlyAChatModelCanBePicked() {
        var picked: String? = null
        val code = section("A0")
        val row = ModelCatalogue.ordered(code, null, emptySet()).first { !it.row.anthropic }
        assertTrue(ModelCatalogue.choose(code, row) { picked = it })
        assertEquals(row.row.id, picked)
        picked = null
        for (s in cat.sections.filterNot { it.chat }) for (r in ModelCatalogue.ordered(s, null, emptySet())) {
            assertFalse("${s.id} ${r.row.id} is selectable", r.selectable)
            assertFalse(ModelCatalogue.choose(s, r) { picked = it })
        }
        assertNull("an embeddings / audio / image row reached the chat", picked)
        val gone = ModelCatalogue.ordered(code, null, setOf(row.row.id)).first { it.row.id == row.row.id }
        assertFalse("a model OpenRouter stopped listing is not offered", gone.selectable || gone.listed)
        assertFalse(ModelCatalogue.choose(code, gone) { picked = it })
        assertFalse("a row of another section", ModelCatalogue.choose(section("A1"), row.copy(row = code.rows.last())) { picked = it })
        assertNull(picked)
    }

    @Test fun aPickPersistsThroughTheStoreTheChatReads() {
        val store = mutableMapOf<String, String>()
        val code = section("A2")
        val row = ModelCatalogue.ordered(code, null, emptySet()).first { it.row.id == "meta-llama/llama-3.3-70b-instruct" }
        assertTrue(ModelCatalogue.choose(code, row) { store["model"] = it })
        assertEquals("meta-llama/llama-3.3-70b-instruct", store["model"])
    }

    // ── prices: daily, cached, offline ───────────────────────────────────

    private inner class Recorded(var down: Boolean = false) : CatalogueFetch {
        val asked = mutableListOf<String>()
        override fun get(url: String): String? {
            asked += url
            if (down) return null
            val name = when {
                url == cat.urls.chat -> "models.json"
                url == cat.urls.embeddings -> "embeddings-models.json"
                url == cat.urls.images -> "images-models.json"
                url == cat.urls.videos -> "videos-models.json"
                url.startsWith("https://openrouter.ai/api/v1/images/models/") -> "image-endpoints-" + url.removePrefix("https://openrouter.ai/api/v1/images/models/").removeSuffix("/endpoints").replace("/", "_") + ".json"
                else -> "endpoints-" + url.removePrefix("https://openrouter.ai/api/v1/models/").removeSuffix("/endpoints").replace("/", "_") + ".json"
            }
            return javaClass.getResource("/fixtures/openrouter-catalogue/$name")?.readText()
        }
    }

    private var now = 1_791_640_000_000L // 2026-10-10
    private fun repo(http: CatalogueFetch, c: ModelCatalogue.Catalogue = cat) = ModelCatalogueRepository(c, http, FileCatalogueStore(tmp.root)) { now }

    @Test fun aRefreshPricesEveryRowFromTheLiveCatalogues() {
        val http = Recorded()
        val p = repo(http).load()
        assertEquals(Origin.LIVE, p.origin)
        val prices = p.prices!!
        assertEquals(Price(PriceUnit.TOKENS, 0.0126, 1.28, 0.0126, 0.18), prices["deepseek/deepseek-v4-flash-0731"])
        assertEquals(Price(PriceUnit.TOKENS, 0.3, 1.0, 0.22, 1.0), prices["qwen/qwen3-coder"])
        assertEquals("no endpoints recorded: the floor is the list price", Price(PriceUnit.TOKENS, 0.06, 0.25), prices["ibm-granite/granite-4.2-8b"])
        assertEquals(0.01, prices.getValue("baai/bge-m3").floorInput!!, 0.0)
        assertEquals(Price(PriceUnit.IMAGE, 0.003, 0.03, 0.003, 0.03), prices["qwen/qwen-image-3"])
        assertEquals(Price(PriceUnit.SECOND, null, 0.05), prices["alibaba/wan-3.0"])
        assertTrue(p.missing.isEmpty())
        assertEquals("2026-10-10", repo(http).asOf(p))
        val once = http.asked.count { "/endpoints" in it && "gpt-oss-120b" in it }
        assertEquals("a model in two sections is asked once", 1, once)
        assertTrue("rerank has no catalogue to ask", http.asked.none { "rerank" in it })
    }

    @Test fun aFreshCacheIsNotRefetchedAndSurvivesARestart() {
        val http = Recorded()
        repo(http).load()
        val n = http.asked.size
        now += 23 * 3_600_000L
        val again = repo(http).load()
        assertEquals(Origin.CACHED, again.origin)
        assertEquals("prices refresh at most daily", n, http.asked.size)
        assertEquals(1.28, again.prices!!.getValue("deepseek/deepseek-v4-flash-0731").output!!, 0.0)
        assertEquals(0.18, again.prices!!.getValue("deepseek/deepseek-v4-flash-0731").floorOutput!!, 0.0)
    }

    @Test fun offlineAfterADayKeepsTheLastPricesMarkedStale() {
        val http = Recorded()
        repo(http).load()
        now += 25 * 3_600_000L
        http.down = true
        val stale = repo(http).load()
        assertEquals(Origin.STALE, stale.origin)
        assertEquals(1.28, stale.prices!!.getValue("deepseek/deepseek-v4-flash-0731").output!!, 0.0)
        assertEquals("the date on screen is the fetch's, not today's", "2026-10-10", repo(http).asOf(stale))
        http.down = false
        assertEquals("back online, a stale cache refreshes", Origin.LIVE, repo(http).load().origin)
    }

    @Test fun neverFetchedAndOfflineIsTheBundledSnapshot() {
        val http = Recorded(down = true)
        val r = repo(http)
        val p = r.load()
        assertEquals(Origin.SNAPSHOT, p.origin)
        assertNull(p.prices)
        assertEquals(cat.pricingAsOf, r.asOf(p))
        assertEquals(Origin.SNAPSHOT, r.stored().origin)
        val shown = ModelCatalogue.ordered(section("A0"), p.prices, p.missing)
        assertTrue("every row still has a price offline", shown.all { it.price != null })
        assertEquals(Origin.SNAPSHOT, ModelCatalogueRepository(cat, http, FileCatalogueStore(File(tmp.root, "x")).also { File(tmp.root, "x").mkdirs() }) { now }.stored().origin)
    }

    @Test fun anUnreadableCacheFallsBackToTheSnapshot() {
        FileCatalogueStore(tmp.root).put(ModelCatalogueRepository.KEY, "not json", now)
        assertEquals(Origin.SNAPSHOT, repo(Recorded(down = true)).load().origin)
    }

    @Test fun aCuratedIdOpenRouterDropsIsFlaggedNotShippedAsSelectable() {
        val o = JSONObject(curatedText)
        val a0 = o.getJSONArray("groups").getJSONObject(0).getJSONArray("sections").getJSONObject(0)
        a0.getJSONArray("rows").getJSONObject(6).put("id", "meta-llama/llama-3.1-405b-instruct")
        val d0 = o.getJSONArray("groups").getJSONObject(3).getJSONArray("sections").getJSONObject(0)
        d0.getJSONArray("rows").getJSONObject(3).put("id", "runway/gen-3")
        val c = ModelCatalogue.parse(o.toString())
        val p = repo(Recorded(), c).load()
        assertEquals(setOf("meta-llama/llama-3.1-405b-instruct", "runway/gen-3"), p.missing)
        val row = ModelCatalogue.ordered(c.sections[0], p.prices, p.missing).first { it.row.id == "meta-llama/llama-3.1-405b-instruct" }
        assertFalse(row.listed || row.selectable)
        assertEquals("its snapshot is what is shown", row.row.snapshot, row.price)
    }

    @Test fun aCatalogueThatFailsKeepsItsSectionsOnTheSnapshotWithoutCallingThemMissing() {
        val inner = Recorded()
        val partial = CatalogueFetch { url -> if (url == cat.urls.embeddings || url == cat.urls.images) null else inner.get(url) }
        val p = repo(partial).load()
        assertEquals(Origin.LIVE, p.origin)
        assertNull(p.prices!!["baai/bge-m3"])
        assertTrue(p.missing.isEmpty())
        assertEquals("a video still prices from its own list", Price(PriceUnit.SECOND, null, 0.05), p.prices!!["alibaba/wan-3.0"])
        assertNull(p.prices!!["qwen/qwen-image-3"])
    }

    @Test fun freshnessIsADayAndNeverTheFuture() {
        val r = repo(Recorded())
        assertTrue(r.fresh(now, now))
        assertTrue(r.fresh(now - 24 * 3_600_000L + 1, now))
        assertFalse(r.fresh(now - 24 * 3_600_000L, now))
        assertFalse("a clock set back does not freeze the prices", r.fresh(now + 1, now))
    }

    @Test fun theCacheRoundTrips() {
        val p = ModelCatalogueRepository.Priced(mapOf("a/b" to Price(PriceUnit.MEGAPIXEL, null, 0.014, null, 0.01)), setOf("c/d"), null, Origin.LIVE)
        assertEquals(p, ModelCatalogueRepository.decode(ModelCatalogueRepository.encode(p)))
    }

    // ── the section filter (Cloud Code shows A0 Code only) ───────────────

    @Test fun theSectionFilterKeepsOnlyTheAskedSections() {
        val code = cat.only(setOf("A0"))
        assertEquals(listOf("A0"), code.sections.map { it.id })
        assertEquals("the group of a kept section stays, the empty ones go", listOf("A"), code.groups.map { it.id })
        assertEquals(section("A0").rows, code.sections.single().rows)
        assertTrue("A0 is a chat section: every listed row can be picked", ModelCatalogue.ordered(code.sections.single(), null, emptySet()).all { it.selectable })
        assertEquals("null = every section (Cloud Search)", cat, cat.only(null))
        assertEquals(listOf("A0", "B1"), cat.only(setOf("B1", "A0")).sections.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { cat.only(setOf("A0", "Z9")) }
    }

    @Test fun theWebExportIsWhatThePageDraws() {
        val code = cat.only(setOf("A0"))
        val live = OpenRouterPrices.list(fx("models.json"))
        val priced = ModelCatalogueRepository.Priced(live, emptySet(), now, Origin.LIVE)
        val o = CatalogueJson.shown(code, priced, "2026-10-10")
        assertEquals("2026-10-10", o.getString("as_of"))
        assertEquals("live", o.getString("origin"))
        val groups = o.getJSONArray("groups")
        assertEquals(1, groups.length())
        val s = groups.getJSONObject(0).getJSONArray("sections").getJSONObject(0)
        assertEquals("A0", s.getString("id"))
        val rows = s.getJSONArray("rows")
        val shown = ModelCatalogue.ordered(code.sections.single(), live, emptySet())
        assertEquals(shown.size, rows.length())
        shown.forEachIndexed { i, r ->
            val j = rows.getJSONObject(i)
            assertEquals(r.row.id, j.getString("id"))
            assertEquals(ModelCatalogue.outputCell(code.sections.single(), r.price), j.getString("output"))
            assertEquals(r.selectable, j.getBoolean("selectable"))
        }
        assertEquals("nothing fetched yet = the snapshot", "snapshot", CatalogueJson.shown(code, null, cat.pricingAsOf).getString("origin"))
    }

    private fun small(rows: String) = ModelCatalogue.parse(
        JSONObject(curatedText).put("groups", org.json.JSONArray("""[{"id":"T","label":"T","sections":[
          {"id":"T0","label":"t","kind":"chat","catalog":"chat","rank_by":"output","note":"","rows":[$rows]}]}]""")).toString(),
    ).sections.single().also { assertNotNull(it) }
}
