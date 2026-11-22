package com.diegonmarcos.superapp.browser

import android.content.Context
import com.diegonmarcos.cloudlib.browsercore.BrowserCore
import org.json.JSONArray
import org.json.JSONObject

/** One recorded page visit. Pure data. */
data class BrowserVisit(val url: String, val title: String, val ts: Long)

/**
 * Local browsing history.
 *
 * ON-DEVICE ONLY, AND THAT IS A REQUIREMENT RATHER THAN AN OVERSIGHT.
 * This class has no network code, no sink, no analytics call and no
 * exporter, and it must never grow one: it is a personal phone's
 * browsing history. The diagnostics sink configured in build.json
 * (BuildConfig.LOG_SINK_URL) is for crash/debug traces and nothing in
 * this file is routed to it.
 *
 * SharedPreferences-backed for the same reason [BrowserTabPrefs] is —
 * a few hundred rows do not justify a database dependency.
 */
class BrowserHistory(context: Context) {

    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("browser_history", Context.MODE_PRIVATE)

    /**
     * #1841 the list is the browser core's (libs:browser-core-rs lists.rs: model, dedupe, cap, sort, frecency, search; its Kotlin twin when
     * the library is not loaded). The stored text keeps its prefs file, key and schema ([BrowserListMigration] normalises it once, keeping a
     * backup). There is still no exporter here: the core can write and read a history document, this class does not offer it.
     */
    fun rawJson(): String {
        BrowserListMigration.ensure(ctx, "history", PREFS, KEY)
        return sp.getString(KEY, "[]") ?: "[]"
    }

    private fun visits(a: JSONObject): List<BrowserVisit> {
        val arr = a.optJSONArray("visits") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { BrowserVisit(it.optString("url"), it.optString("title"), it.optLong("ts")) }
    }

    /** Newest first. */
    fun all(): List<BrowserVisit> = visits(BrowserCore.lists("history_load", JSONObject().put("raw", rawJson())))

    /**
     * The visits whose title or address contain [query] (blank = all), ordered by [sort] ("input" = newest first as stored, "recent",
     * "oldest", "frecency", "title", "host"); [ranked] = the address bar's ranking instead (best match first).
     */
    fun query(query: String, sort: String = "input", limit: Int = Int.MAX_VALUE, ranked: Boolean = false): List<BrowserVisit> =
        visits(BrowserCore.lists("history_query", JSONObject().put("raw", rawJson()).put("query", query).put("sort", sort)
            .put("limit", limit).put("mode", if (ranked) "ranked" else "filter").put("now", System.currentTimeMillis())))

    /**
     * Record a visit. Re-visiting a URL moves it up rather than adding a
     * duplicate row, so the list reads as "pages", not "page loads" —
     * which is what makes it useful as a suggestion source.
     */
    fun record(url: String, title: String) {
        synchronized(LOCK) {
            val a = BrowserCore.lists("history_record", JSONObject().put("raw", rawJson()).put("url", url).put("title", title)
                .put("now", System.currentTimeMillis()).put("cap", CAP))
            if (a.optBoolean("recorded")) save(a.getString("raw"))
        }
    }

    fun clear() = synchronized(LOCK) { save("[]") }

    private fun save(raw: String) {
        sp.edit().putString(KEY, raw).apply()
    }

    companion object {
        private const val PREFS = "browser_history"
        private const val KEY = "history_json"
        private val LOCK = Any()
        /** ponytail: a flat cap, not time-based expiry. Add expiry if he asks for it. */
        private const val CAP = 500
    }
}
