package com.diegonmarcos.superapp.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** #1468 one Apify actor as a Store row: [id] is `username/name`. Runs and users are -1 when unknown (the declared list has no stats). */
data class ApifyActor(
    val id: String, val title: String, val author: String, val description: String = "",
    val runs: Long = -1, val users: Long = -1, val pricing: String = "", val url: String = "",
)

/** One page of a Store search. */
data class StorePage(val total: Long, val items: List<ApifyActor>)

data class ApifyRun(val id: String, val actorId: String, val status: String, val startedAt: String, val datasetId: String)
data class ApifyDataset(val id: String, val name: String, val itemCount: Long, val modifiedAt: String)

/** #1468 the declared list of the most-used actors (data, not code paths). */
object ApifyCatalog {
    const val STORE = "https://api.apify.com/v2/store"
    const val API = "https://api.apify.com/v2"
    const val NOTICE = "Apify actors run in Apify's cloud and bill your Apify account. Paste your own API token (console.apify.com > Settings > Integrations); it is kept encrypted on this phone."

    val DEFAULT_JSON = """
{"actors":[
 {"id":"compass/crawler-google-places","label":"Google Maps Scraper","about":"Places, reviews, contacts from Google Maps"},
 {"id":"apify/instagram-scraper","label":"Instagram Scraper","about":"Posts, profiles, hashtags, comments"},
 {"id":"clockworks/tiktok-scraper","label":"TikTok Scraper","about":"Videos, profiles, hashtags, comments"},
 {"id":"apify/website-content-crawler","label":"Website Content Crawler","about":"Crawl a site into clean text for AI and search"},
 {"id":"apify/google-search-scraper","label":"Google Search Results","about":"Organic results, ads, related queries"},
 {"id":"junglee/amazon-crawler","label":"Amazon Product Scraper","about":"Product title, price, rating, reviews"},
 {"id":"streamers/youtube-scraper","label":"YouTube Scraper","about":"Videos, channels, playlists, search"},
 {"id":"apidojo/tweet-scraper","label":"Twitter / X Scraper","about":"Tweets, profiles, search results"},
 {"id":"curious_coder/linkedin-jobs-scraper","label":"LinkedIn Jobs Scraper","about":"Job listings from LinkedIn search"},
 {"id":"apify/facebook-pages-scraper","label":"Facebook Pages Scraper","about":"Page details, followers, contacts"},
 {"id":"apify/facebook-posts-scraper","label":"Facebook Posts Scraper","about":"Posts of public pages"},
 {"id":"trudax/reddit-scraper-lite","label":"Reddit Scraper","about":"Posts, comments, communities"},
 {"id":"apify/google-trends-scraper","label":"Google Trends Scraper","about":"Interest over time and related queries"},
 {"id":"apify/web-scraper","label":"Web Scraper","about":"Generic crawler driven by a JavaScript page function"},
 {"id":"apify/cheerio-scraper","label":"Cheerio Scraper","about":"Fast generic crawler for static HTML"}
]}"""

    fun parse(json: String): List<ApifyActor> {
        val a = JSONObject(json).getJSONArray("actors")
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val id = o.getString("id")
            ApifyActor(id, o.getString("label"), id.substringBefore('/'), o.optString("about"), url = ApifyStore.pageUrl(id))
        }
    }

    val actors: List<ApifyActor> by lazy { parse(DEFAULT_JSON) }
}

/** #1468 the public Store API: the search URL and the parser of its answer. */
object ApifyStore {
    fun pageUrl(id: String) = "https://apify.com/$id"

    /** The API path form of an actor id (`user/name` becomes `user~name`). */
    fun apiId(id: String) = id.trim().replace('/', '~')

    fun url(search: String, limit: Int = 24, offset: Int = 0): String =
        ApifyCatalog.STORE + "?search=" + MusicHttp.enc(search.trim()) + "&limit=$limit&offset=$offset"

    /** 12345 -> 12.3K, 1500000 -> 1.5M; -1 (unknown) -> an em dash. */
    fun compact(n: Long): String = when {
        n < 0 -> "—"
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }

    /** The pricing text of an actor's currentPricingInfo (absent or FREE is Free). */
    fun pricing(p: JSONObject?): String {
        if (p == null) return "Free"
        val usd = p.optDouble("pricePerUnitUsd", Double.NaN)
        return when (p.optString("pricingModel")) {
            "PRICE_PER_DATASET_ITEM" -> if (usd.isNaN()) "Per result" else String.format(Locale.US, "$%.2f / 1K results", usd * 1000)
            "FLAT_PRICE_PER_MONTH" -> if (usd.isNaN()) "Monthly rental" else String.format(Locale.US, "$%.2f / month", usd)
            "PAY_PER_EVENT" -> "Pay per event"
            "", "FREE" -> "Free"
            else -> p.optString("pricingModel").lowercase(Locale.US).replace('_', ' ')
        }
    }

    fun parse(json: String): StorePage {
        val data = JSONObject(json).optJSONObject("data") ?: return StorePage(0, emptyList())
        val arr = data.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val user = o.optString("username"); val name = o.optString("name")
            if (name.isBlank()) return@mapNotNull null
            val id = if (user.isBlank()) name else "$user/$name"
            val stats = o.optJSONObject("stats")
            ApifyActor(
                id = id, title = o.optString("title").ifBlank { name }, author = o.optString("userFullName").ifBlank { user },
                description = o.optString("description"),
                runs = stats?.optLong("totalRuns", -1) ?: -1, users = stats?.optLong("totalUsers", -1) ?: -1,
                pricing = pricing(o.optJSONObject("currentPricingInfo")), url = o.optString("url").ifBlank { pageUrl(id) },
            )
        }
        return StorePage(data.optLong("total", items.size.toLong()), items)
    }
}

/** #1468 where actor runs go: Apify Cloud (the default) or the fleet's Apify-compatible Cloudy Crawlee runner (opt-in). The Store search always uses the public Apify Store. */
enum class ApifyBackend(val label: String, val api: String, val tokenKey: String) {
    CLOUD("Apify Cloud", ApifyCatalog.API, "apify.token"),
    CRAWLEE("Self-hosted Cloudy Crawlee", "https://crawlee-cloud.mesh.diegonmarcos.com/v2", "crawlee.token"),
}

/** #1468 the Apify tokens' only store: SafeEncryptedPrefs file apify_tokens. */
class ApifyTokenStore(ctx: android.content.Context) : TokenStore {
    private val sp = com.diegonmarcos.superapp.crash.SafeEncryptedPrefs.prefs(ctx.applicationContext, "apify_tokens")
    override fun get(key: String): String? = sp.getString(key, null)
    override fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }
    override fun remove(key: String) { sp.edit().remove(key).apply() }
}

/** #1468 the user's Apify token: only in encrypted storage (EncryptedTokenStore with its own prefs file), never plaintext. */
class ApifyAccount(private val store: TokenStore) {
    /** The chosen backend; Apify Cloud until the user opts in to Crawlee. */
    var backend: ApifyBackend
        get() = ApifyBackend.entries.firstOrNull { it.name == store.get(BACKEND) } ?: ApifyBackend.CLOUD
        set(v) { store.put(BACKEND, v.name) }

    private val key get() = backend.tokenKey
    fun token(): String = store.get(key).orEmpty()
    fun connected(): Boolean = token().isNotEmpty()

    /** False (nothing stored) for a blank token or one with spaces inside. */
    fun save(raw: String): Boolean {
        val t = raw.trim()
        if (t.length < 8 || t.any { it.isWhitespace() }) return false
        store.put(key, t); return true
    }

    fun clear() = store.remove(key)

    /** Shown in place of the token: first 4 and last 2 characters. */
    fun masked(): String = token().let { if (it.length <= 8) "••••" else it.take(4) + "••••" + it.takeLast(2) }

    companion object { const val BACKEND = "apify.backend" }
}

/** #1468 the token-authenticated Apify calls (run an actor, list runs, datasets and their items). */
class ApifyClient(private val token: String, private val http: MusicHttp = MusicHttp(), private val api: String = ApifyCatalog.API) {
    private val auth get() = mapOf("Authorization" to "Bearer $token")

    fun run(actorId: String, input: String): ApifyRun {
        val body = JSONObject(input.ifBlank { "{}" })
        return runOf(JSONObject(http.postJson("$api/acts/${ApifyStore.apiId(actorId)}/runs", body, auth)).getJSONObject("data"))
    }

    fun runs(limit: Int = 25): List<ApifyRun> = items("$api/actor-runs?limit=$limit&desc=1").map(::runOf)

    fun datasets(limit: Int = 25): List<ApifyDataset> = items("$api/datasets?limit=$limit&desc=1").map {
        ApifyDataset(it.optString("id"), it.optString("name"), it.optLong("itemCount", 0), it.optString("modifiedAt"))
    }

    fun datasetItems(datasetId: String, limit: Int = 1000): JSONArray =
        JSONArray(http.get("$api/datasets/${MusicHttp.enc(datasetId)}/items?format=json&clean=1&limit=$limit", auth))

    private fun items(url: String): List<JSONObject> {
        val a = JSONObject(http.get(url, auth)).optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun runOf(o: JSONObject) = ApifyRun(o.optString("id"), o.optString("actId"), o.optString("status"), o.optString("startedAt"), o.optString("defaultDatasetId"))
}

/** #1468 CSV and JSON export of dataset items (objects of any shape). */
object ApifyExport {
    /** Columns in first-seen order across all items. */
    fun columns(items: JSONArray): List<String> {
        val cols = LinkedHashSet<String>()
        for (i in 0 until items.length()) items.optJSONObject(i)?.keys()?.forEach { cols.add(it) }
        return cols.toList()
    }

    private fun text(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is JSONObject, is JSONArray -> v.toString()
        else -> v.toString()
    }

    fun csv(items: JSONArray): String {
        val cols = columns(items)
        val sb = StringBuilder(cols.joinToString(",") { MusicExport.cell(it) }).append("\r\n")
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            sb.append(cols.joinToString(",") { MusicExport.cell(text(o.opt(it))) }).append("\r\n")
        }
        return sb.toString()
    }

    fun json(items: JSONArray): String = items.toString(2)
}
