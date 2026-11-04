package com.diegonmarcos.superapp.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * #1458 a Favourites chip fed at RUNTIME by a pages.json (build.json::ui.browser.favourites.filters[].from = pages_hub, `feed` = the raw URL
 * in diegonmarcos/front-html). The last good copy is cached, so an offline start still seeds; a bad download never replaces the cache.
 * The rows join the bookmarks through the same once-per-entry seed merge as the baked ones (edits and deletions stand).
 */
object FavFeed {
    /** pages.json -> one seed item per PUBLIC site: title = repo, folder = "<chip label>/<repo>". Anything malformed gives an empty list. */
    fun parse(json: String?, label: String): List<FavSeedItem> {
        val arr = runCatching { JSONObject(json ?: return emptyList()).optJSONArray("pages") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = e.optString("url").trim(); val repo = e.optString("repo").trim()
            if (!e.optBoolean("public", false) || !url.startsWith("https://") || repo.isEmpty()) null else FavSeedItem(repo, url, "$label/$repo")
        }.distinctBy { it.url }
    }

    fun start(ctx: Context, seed: FavSeed, bookmarks: BrowserBookmarks) {
        val app = ctx.applicationContext
        val sp = app.getSharedPreferences("browser_fav_feed", Context.MODE_PRIVATE)
        val main = Handler(Looper.getMainLooper())
        for (f in seed.filters) {
            val url = f.feed ?: continue
            val cacheKey = "feed_${f.id}"
            fun apply(text: String?) {
                val rows = parse(text, f.label)
                if (rows.isNotEmpty()) bookmarks.applySeed(seed.copy(items = rows))
            }
            apply(sp.getString(cacheKey, null))   // offline-safe: the last good copy, at once
            Thread {
                val text = runCatching { download(url) }.getOrNull()
                if (parse(text, f.label).isNotEmpty()) {
                    sp.edit().putString(cacheKey, text).apply()
                    main.post { apply(text) }
                }
            }.apply { isDaemon = true; name = "fav-feed-${f.id}" }.start()
        }
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
