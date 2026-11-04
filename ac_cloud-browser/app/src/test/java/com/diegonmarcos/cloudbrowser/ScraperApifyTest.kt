package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.apify.ApifyBackendStore
import com.diegonmarcos.superapp.apify.ApifyBackends
import com.diegonmarcos.superapp.apify.ApifyKv
import com.diegonmarcos.superapp.scraper.ApifyCatalog
import com.diegonmarcos.superapp.scraper.ApifyClient
import com.diegonmarcos.superapp.scraper.ApifyExport
import com.diegonmarcos.superapp.scraper.ApifyStore
import com.diegonmarcos.superapp.scraper.CfEndpoint
import com.diegonmarcos.superapp.scraper.CloudflareClient
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

    private class MapKv : ApifyKv {
        val m = mutableMapOf<String, String>()
        override fun get(key: String) = m[key]
        override fun set(key: String, value: String?) { if (value.isNullOrBlank()) m.remove(key) else m[key] = value.trim() }
    }

    @Test fun `token is kept in the shared store per backend, validated, and defaults to Apify Cloud`() {
        val kv = MapKv()
        val s = ApifyBackendStore(kv)
        assertEquals(ApifyBackends.CLOUD, s.backend().id)                    // Apify Cloud unless the user opts in
        assertEquals("https://api.apify.com/v2", s.resolve()?.first ?: ApifyBackends.CLOUD_API)
        assertFalse(s.hasToken())
        assertFalse(s.setToken(s.backend(), "has space inside"))
        assertTrue(s.setToken(s.backend(), "  apify_api_ABCDEFGHIJ  "))
        assertEquals("https://api.apify.com/v2" to "apify_api_ABCDEFGHIJ", ApifyBackendStore(kv).resolve())
        s.setBackend(ApifyBackends.SELF_HOSTED)
        assertEquals(ApifyBackends.SELF_HOSTED, ApifyBackendStore(kv).backend().id)
        assertFalse(s.hasToken())                                            // the cloud token is not sent to the self-hosted runner
        assertTrue(s.setToken(s.backend(), "crawlee_bearer_1234"))
        assertEquals("https://crawlee-cloud.mesh.diegonmarcos.com/v2" to "crawlee_bearer_1234", s.resolve())
        s.setBackend(ApifyBackends.CLOUD)
        s.setToken(s.backend(), "")
        assertFalse(s.hasToken())
    }

    @Test fun `Cloudflare Browser Run is the third backend, Apify stays the default, Worker URL is the default mode and REST keeps an account id`() {
        val kv = MapKv()
        val s = ApifyBackendStore(kv)
        assertEquals(listOf("cloud", "selfhosted", "cloudflare"), s.decl.backends.map { it.id })
        assertEquals(ApifyBackends.CLOUD, s.backend().id)
        val cf = s.decl.byId(ApifyBackends.CLOUDFLARE)
        assertTrue(cf.cloudflare); assertFalse(s.decl.byId(ApifyBackends.CLOUD).cloudflare)
        assertEquals(ApifyBackends.CF_WORKER, s.cfMode())
        // Worker URL mode (default): an https Worker URL + the Worker bearer
        assertFalse(s.setWorkerUrl("http://insecure.example"))
        assertTrue(s.setToken(cf, "  wk_token_1234  "))
        assertFalse(s.hasToken(cf))                                          // the Worker URL is missing
        assertTrue(s.setWorkerUrl(" https://edge-md-scraper.example.workers.dev/ "))
        assertTrue(s.hasToken(cf))
        assertEquals("wk_token_1234", kv.get("cloudflare.worker_token"))
        s.setBackend(ApifyBackends.CLOUDFLARE)
        assertEquals("https://edge-md-scraper.example.workers.dev/?url=https%3A%2F%2Fa.example%2Fx%3Fq%3D1", ApifyBackends.workerUrl(s.resolve()!!.first, "https://a.example/x?q=1"))
        assertEquals("wk_token_1234", s.resolve()!!.second)
        assertEquals(null, ApifyBackends.workerUrl("http://x", "https://a"))
        // REST API mode: the account id + the API token, separate keys
        s.setCfMode(ApifyBackends.CF_REST)
        val acct = "0123456789abcdef0123456789ABCDEF"
        assertFalse(s.hasToken(cf))
        assertFalse(s.setAccountId("not-an-id"))
        assertTrue(s.setToken(cf, "cf_token_1234")); assertTrue(s.setAccountId(acct))
        assertTrue(s.hasToken(cf))
        assertEquals("cf_token_1234", kv.get("cloudflare.api_token")); assertEquals(acct, kv.get("cloudflare.account_id"))
        assertEquals("https://api.cloudflare.com/client/v4/accounts/$acct/browser-rendering" to "cf_token_1234", s.resolve())
        assertEquals("https://x/accounts/$acct/browser-rendering/markdown", ApifyBackends.cloudflareUrl(ApifyBackends.cloudflareBase("https://x", acct)!!))
        assertEquals(null, ApifyBackends.cloudflareBase("https://x", "zz"))
        assertFalse(s.hasToken(s.decl.byId(ApifyBackends.CLOUD)))            // Apify Cloud's own token is separate
        val parsed = ApifyBackends.parse("""{"default_backend":"cloud","backends":[{"id":"cloud","api":"https://api.apify.com/v2"},{"id":"cloudflare","kind":"cloudflare","api":"https://api.cloudflare.com/client/v4"}]}""")
        assertTrue(parsed.byId("cloudflare").cloudflare)
    }

    @Test fun `Cloudflare client reads Markdown from the Worker (default) or the REST API, bearer on both`() {
        MockWebServer().use { srv ->
            srv.enqueue(MockResponse().setBody("---\ntitle: \"T\"\n---\n\n# Title"))
            srv.enqueue(MockResponse().setBody("""{"success":true,"result":"# Title"}"""))
            srv.enqueue(MockResponse().setBody("""{"success":true,"result":["https://a","https://b"]}"""))
            srv.enqueue(MockResponse().setBody("""{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}"""))
            val w = CloudflareClient(ApifyBackends.CF_WORKER, srv.url("/").toString().replace("http://", "https://").trimEnd('/'), "wk_tok", MusicHttp(open = { java.net.URL(it.toString().replace("https://", "http://")).openConnection() as java.net.HttpURLConnection }))
            assertTrue(w.render(CfEndpoint.MARKDOWN, "https://example.com/p?x=1").endsWith("# Title"))
            val wr = srv.takeRequest()
            assertEquals("/?url=https%3A%2F%2Fexample.com%2Fp%3Fx%3D1", wr.path); assertEquals("Bearer wk_tok", wr.getHeader("Authorization"))
            val base = srv.url("/client/v4/accounts/A/browser-rendering").toString().trimEnd('/')
            val c = CloudflareClient(ApifyBackends.CF_REST, base, "cf_tok", MusicHttp())
            assertEquals("# Title", c.render(CfEndpoint.MARKDOWN, " https://example.com "))
            val req = srv.takeRequest()
            assertEquals("/client/v4/accounts/A/browser-rendering/markdown", req.path)
            assertEquals("Bearer cf_tok", req.getHeader("Authorization"))
            assertEquals("""{"url":"https://example.com"}""", req.body.readUtf8())
            assertEquals("https://a\nhttps://b", c.render(CfEndpoint.LINKS, "https://example.com"))
            assertTrue(runCatching { c.render(CfEndpoint.CONTENT, "https://example.com") }.exceptionOrNull()!!.message!!.contains("Authentication error"))
        }
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
    @Test fun `Scrapers pages are exactly Site Files, Apify, Cloudflare, SelfHosted and Music is a SelfHosted section`() {
        val secs = JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONArray("sections")
        val s = (0 until secs.length()).map { secs.getJSONObject(it) }.first { it.getString("id") == "scrapers" }
        val pages = s.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals(listOf("site_files", "apify", "cloudflare", "self_hosted"), pages.map { it.getString("id") })
        assertEquals(listOf("Site Files", "Apify", "Cloudflare", "SelfHosted"), pages.map { it.getString("label") })
        assertEquals(listOf(ScrapersPages.SITE_FILES, ScrapersPages.APIFY, ScrapersPages.CLOUDFLARE, ScrapersPages.SELF_HOSTED), pages.map { it.getString("id") })
        assertEquals(listOf("music", "youtube", "scrappers_api"), SelfHostedCatalog.sections.map { it.id })
        assertEquals(SelfHostedCatalog.MUSIC, SelfHostedCatalog.sections.first().id)
    }
}
