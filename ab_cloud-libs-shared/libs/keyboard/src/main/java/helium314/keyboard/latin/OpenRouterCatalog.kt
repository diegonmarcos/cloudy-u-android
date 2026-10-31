// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Configs ▸ AI Routing ▸ Refresh: the WHOLE OpenRouter model list (GET /api/v1/models, public, no key),
 * cached with the time it was fetched. [AiRouter.refreshPricing] keeps only the registry's few models'
 * prices; this keeps every model, because the per-task tables let the user pick any of them.
 *
 * Parsing, the cache codec, filtering and the refresh decision are pure (JVM-tested in
 * OpenRouterCatalogTest); the only I/O is [httpGet] and the [KeyValueStore] the caller hands in.
 */
data class CatalogModel(
    val id: String,
    val name: String,
    val provider: String,
    val contextLength: Int?,
    /** US dollars per million input tokens; null when the catalog did not say. */
    val inPerM: Double?,
    /** US dollars per million output tokens; null when the catalog did not say. */
    val outPerM: Double?,
) {
    /** OpenRouter marks free variants with a ":free" id suffix; a model priced 0/0 is free too. */
    val free: Boolean get() = id.endsWith(":free") || (inPerM == 0.0 && outPerM == 0.0)
}

data class Catalog(val fetchedAt: Long, val models: List<CatalogModel>)

/** The prefs-shaped store the catalog and the per-task choices live in (SharedPreferences on a phone, a map in tests). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

object OpenRouterCatalog {
    const val URL = "https://openrouter.ai/api/v1/models"
    const val CACHE_KEY = "ai_catalog_openrouter"

    sealed class RefreshResult {
        data class Ok(val catalog: Catalog) : RefreshResult()
        /** No network / host unreachable. The previous cache (if any) is kept untouched. */
        data class Offline(val message: String, val kept: Catalog?) : RefreshResult()
        /** The catalog answered but not with something usable. The previous cache is kept. */
        data class Failed(val message: String, val kept: Catalog?) : RefreshResult()
    }

    /** OpenRouter publishes dollars per TOKEN as strings; the tables show dollars per MILLION. */
    private fun perMillion(o: JSONObject?, key: String): Double? =
        o?.optString(key, "")?.takeIf { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { it * 1_000_000.0 }

    /** `{"data":[{"id","name","context_length","pricing":{"prompt","completion"}}]}` → models; entries without an id are skipped. */
    fun parse(body: String): List<CatalogModel> {
        val data = JSONObject(body).getJSONArray("data")
        val out = ArrayList<CatalogModel>(data.length())
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id", "").takeIf { it.isNotBlank() } ?: continue
            val provider = id.substringBefore('/', "")
            // OpenRouter names read "Provider: Model"; the provider has its own column, so drop the prefix.
            val name = m.optString("name", "").ifBlank { id }.substringAfter(": ")
            val ctx = m.optInt("context_length", 0).takeIf { it > 0 }
            val pr = m.optJSONObject("pricing")
            out += CatalogModel(id, name, provider, ctx, perMillion(pr, "prompt"), perMillion(pr, "completion"))
        }
        return out
    }

    fun encode(c: Catalog): String {
        val a = JSONArray()
        c.models.forEach { m ->
            a.put(JSONArray().put(m.id).put(m.name).put(m.provider).put(m.contextLength ?: -1)
                .put(m.inPerM ?: -1.0).put(m.outPerM ?: -1.0))
        }
        return JSONObject().put("fetched", c.fetchedAt).put("models", a).toString()
    }

    fun decode(raw: String?): Catalog? = raw?.let {
        runCatching {
            val o = JSONObject(it)
            val a = o.getJSONArray("models")
            Catalog(o.getLong("fetched"), (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                CatalogModel(r.getString(0), r.getString(1), r.getString(2),
                    r.getInt(3).takeIf { v -> v > 0 }, r.getDouble(4).takeIf { v -> v >= 0 }, r.getDouble(5).takeIf { v -> v >= 0 })
            })
        }.getOrNull()
    }

    fun load(store: KeyValueStore): Catalog? = decode(store.get(CACHE_KEY))

    /**
     * Fetch, parse, cache. Any failure keeps the previous cache and says which kind of failure it was:
     * an unreachable network reads "offline", anything else (HTTP error, unreadable body, empty list) "failed".
     */
    fun refresh(store: KeyValueStore, now: Long, fetch: () -> String): RefreshResult {
        val kept = load(store)
        val body = try { fetch() } catch (e: Exception) {
            return if (isOffline(e)) RefreshResult.Offline(e.message ?: e.javaClass.simpleName, kept)
            else RefreshResult.Failed(e.message ?: e.javaClass.simpleName, kept)
        }
        val models = try { parse(body) } catch (e: Exception) {
            return RefreshResult.Failed("unreadable model list: ${e.message}", kept)
        }
        if (models.isEmpty()) return RefreshResult.Failed("the model list is empty", kept)
        val c = Catalog(now, models)
        store.put(CACHE_KEY, encode(c))
        return RefreshResult.Ok(c)
    }

    fun isOffline(e: Throwable): Boolean =
        e is UnknownHostException || e is ConnectException || e is NoRouteToHostException || e is SocketTimeoutException

    /**
     * Search and filter: [query] matches name, id or provider (case-insensitive, every word must match);
     * [freeOnly] keeps free models. The order is the catalog's own (OpenRouter lists newest first).
     */
    fun filter(models: List<CatalogModel>, query: String, freeOnly: Boolean): List<CatalogModel> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return models.filter { m ->
            (!freeOnly || m.free) && words.all { w ->
                m.name.lowercase().contains(w) || m.id.lowercase().contains(w) || m.provider.lowercase().contains(w)
            }
        }
    }

    /** Blocking GET. Never call on the main thread. */
    fun httpGet(url: String = URL, timeoutMs: Int = 15_000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = timeoutMs; instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode != 200) throw IOException("OpenRouter model list HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
}
