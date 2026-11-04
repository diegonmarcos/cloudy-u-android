package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.scraper.ApifyAccount
import com.diegonmarcos.superapp.scraper.ApifyCatalog
import com.diegonmarcos.superapp.scraper.ApifyClient
import com.diegonmarcos.superapp.scraper.ApifyExport
import com.diegonmarcos.superapp.scraper.ApifyStore
import com.diegonmarcos.superapp.scraper.MemoryTokenStore
import com.diegonmarcos.superapp.scraper.MusicHttp
import com.diegonmarcos.superapp.scraper.ScrapersPages
import com.diegonmarcos.superapp.scraper.SelfHostedCatalog
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1468 Apify: the Store parser (fixture), the declared list, token storage, the client, CSV/JSON export, and the Scrapers nav move. */
class ScraperApifyTest {
    private val fixture = """
{"data":{"total":2310,"count":4,"offset":0,"limit":24,"items":[
 {"id":"a1","name":"crawler-google-places","username":"compass","title":"Google Maps Scraper","userFullName":"Compass",
  "description":"Extract places","url":"https://apify.com/compass/crawler-google-places",
  "stats":{"totalRuns":24500000,"totalUsers":210000},
  "currentPricingInfo":{"pricingModel":"PRICE_PER_DATASET_ITEM","pricePerUnitUsd":0.004}},
 {"id":"a2","name":"instagram-scraper","username":"apify","title":"Instagram Scraper","stats":{"totalRuns":9800,"totalUsers":1234},
  "currentPricingInfo":{"pricingModel":"FLAT_PRICE_PER_MONTH","pricePerUnitUsd":49}},
 {"id":"a3","name":"free-one","username":"someone","stats":{"totalRuns":12,"totalUsers":3}},
 {"id":"a4","username":"nameless"}
]}}"""

    @Test fun `store url encodes the search and carries paging`() {
        assertEquals("https://api.apify.com/v2/store?search=google+maps&limit=24&offset=0", ApifyStore.url(" google maps "))
        assertEquals("https://api.apify.com/v2/store?search=a%26b&limit=5&offset=10", ApifyStore.url("a&b", 5, 10))
    }

    @Test fun `store parser reads name author runs users pricing and link`() {
        val p = ApifyStore.parse(fixture)
        assertEquals(2310, p.total)
        assertEquals(3, p.items.size)               // the row without a name is dropped
        val g = p.items[0]
        assertEquals("compass/crawler-google-places", g.id)
        assertEquals("Google Maps Scraper", g.title)
        assertEquals("Compass", g.author)
        assertEquals(24_500_000L, g.runs); assertEquals(210_000L, g.users)
        assertEquals("$4.00 / 1K results", g.pricing)
        assertEquals("https://apify.com/compass/crawler-google-places", g.url)
        assertEquals("$49.00 / month", p.items[1].pricing)
        assertEquals("https://apify.com/apify/instagram-scraper", p.items[1].url)
        val f = p.items[2]
        assertEquals("free-one", f.title); assertEquals("someone", f.author); assertEquals("Free", f.pricing)
    }

    @Test fun `store parser survives an empty or odd answer`() {
        assertEquals(0, ApifyStore.parse("""{"data":{"items":[]}}""").items.size)
        assertEquals(0, ApifyStore.parse("""{}""").items.size)
    }

    @Test fun `numbers are compacted`() {
        assertEquals("—", ApifyStore.compact(-1)); assertEquals("999", ApifyStore.compact(999))
        assertEquals("1.2K", ApifyStore.compact(1234)); assertEquals("24.5M", ApifyStore.compact(24_500_000))
        assertEquals("a~b", ApifyStore.apiId("a/b"))
    }

    @Test fun `declared list holds the most used actors`() {
        val ids = ApifyCatalog.actors.map { it.id }
        for (want in listOf("compass/crawler-google-places", "apify/instagram-scraper", "clockworks/tiktok-scraper", "apify/website-content-crawler",
            "apify/google-search-scraper", "streamers/youtube-scraper", "apidojo/tweet-scraper", "apify/facebook-pages-scraper")) assertTrue(want, want in ids)
        assertTrue(ApifyCatalog.actors.size >= 10)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ApifyCatalog.actors.all { it.url == "https://apify.com/${it.id}" && it.author.isNotBlank() })
    }

    @Test fun `token is kept in the store, trimmed, masked, and validated`() {
        val store = MemoryTokenStore()
        val a = ApifyAccount(store)
        assertFalse(a.connected())
        assertFalse(a.save("short")); assertFalse(a.save("has space inside token"))
        assertFalse(a.connected())
        assertTrue(a.save("  apify_api_ABCDEFGHIJ  "))
        assertEquals("apify_api_ABCDEFGHIJ", ApifyAccount(store).token())      // a new account over the same store sees it
        assertEquals("apif••••IJ", a.masked())
        assertFalse(a.masked().contains("ABCDEFG"))
        a.clear()
        assertFalse(a.connected()); assertEquals("", a.token())
    }

    @Test fun `client sends the bearer token to run an actor and lists runs and datasets`() {
        MockWebServer().use { srv ->
            srv.enqueue(MockResponse().setBody("""{"data":{"id":"R1","actId":"A1","status":"READY","startedAt":"2026-11-04T10:00:00Z","defaultDatasetId":"D1"}}"""))
            srv.enqueue(MockResponse().setBody("""{"data":{"items":[{"id":"R1","actId":"A1","status":"SUCCEEDED","startedAt":"2026-11-04T10:00:00Z","defaultDatasetId":"D1"}]}}"""))
            srv.enqueue(MockResponse().setBody("""{"data":{"items":[{"id":"D1","name":"maps","itemCount":7,"modifiedAt":"2026-11-04T10:05:00Z"}]}}"""))
            srv.enqueue(MockResponse().setBody("""[{"a":1},{"a":2}]"""))
            val api = srv.url("/v2").toString().trimEnd('/')
            val c = ApifyClient("tok_12345678", MusicHttp(), api)
            val r = c.run("compass/crawler-google-places", """{"q":"x"}""")
            assertEquals("D1", r.datasetId)
            val req = srv.takeRequest()
            assertEquals("/v2/acts/compass~crawler-google-places/runs", req.path)
            assertEquals("Bearer tok_12345678", req.getHeader("Authorization"))
            assertEquals("""{"q":"x"}""", req.body.readUtf8())
            assertEquals("SUCCEEDED", c.runs().single().status)
            assertTrue(srv.takeRequest().path!!.startsWith("/v2/actor-runs?"))
            assertEquals(7L, c.datasets().single().itemCount)
            assertEquals(2, c.datasetItems("D1").length())
        }
    }

    @Test fun `export writes CSV with a union of columns and JSON`() {
        val items = JSONArray("""[{"title":"A, B","n":1,"tags":["x","y"]},{"title":"=cmd","extra":null,"o":{"k":1}}]""")
        assertEquals(listOf("title", "n", "tags", "extra", "o"), ApifyExport.columns(items))
        val lines = ApifyExport.csv(items).split("\r\n").filter { it.isNotEmpty() }
        assertEquals("title,n,tags,extra,o", lines[0])
        assertEquals("\"A, B\",1,\"[\"\"x\"\",\"\"y\"\"]\",,", lines[1])
        assertTrue(lines[2].startsWith("'=cmd,"))          // never a live formula
        assertEquals(2, JSONArray(ApifyExport.json(items)).length())
    }

    // ---- the nav move
    @Test fun `Scrapers pages are exactly Site Files, Apify, SelfHosted and Music is a SelfHosted section`() {
        val secs = JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONArray("sections")
        val s = (0 until secs.length()).map { secs.getJSONObject(it) }.first { it.getString("id") == "scrapers" }
        val pages = s.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals(listOf("site_files", "apify", "self_hosted"), pages.map { it.getString("id") })
        assertEquals(listOf("Site Files", "Apify", "SelfHosted"), pages.map { it.getString("label") })
        assertEquals(listOf(ScrapersPages.SITE_FILES, ScrapersPages.APIFY, ScrapersPages.SELF_HOSTED), pages.map { it.getString("id") })
        assertEquals(listOf("music", "youtube", "scrappers_api"), SelfHostedCatalog.sections.map { it.id })
        assertEquals(SelfHostedCatalog.MUSIC, SelfHostedCatalog.sections.first().id)
    }
}
