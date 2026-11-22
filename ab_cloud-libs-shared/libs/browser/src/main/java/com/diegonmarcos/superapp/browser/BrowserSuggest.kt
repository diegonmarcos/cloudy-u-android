package com.diegonmarcos.superapp.browser

import com.diegonmarcos.cloudlib.browsercore.BrowserCore
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the address-bar dropdown offers while typing.
 *
 * EVERY SOURCE HERE IS LOCAL: the user's own open/pinned tabs and their
 * own on-device history, plus one row that runs the query through the
 * configured search engine when they pick it.
 *
 * There is deliberately NO remote suggestion feed. A keystroke-by-
 * keystroke stream of what is being typed is the single most revealing
 * thing a browser can emit, and the useful part of autocomplete — "take
 * me back to the thing I already visited" — is answered entirely from
 * local data. Not shipping it is why there is no toggle to get wrong.
 *
 * #886 THE REMOTE QUERY SUGGESTIONS ARE NOT IN THIS FILE. [sections] is handed whatever the
 * engine's suggestion feed answered ([BrowserRemoteSuggest], its own file, gated by the
 * `search_suggestions` setting) as a plain list of strings, so this object still contains no
 * network client and a test can hold it to that.
 *
 * Pure: ranking is asserted in a JVM unit test.
 */
object BrowserSuggest {

    enum class Source { TAB, HISTORY, SEARCH, FAV }

    data class Suggestion(val url: String, val label: String, val source: Source, val subtitle: String = "")

    /**
     * Ranked suggestions for [query].
     *
     * Tabs before history because a tab that is already open is the
     * cheapest destination and the likeliest intent. The search row is
     * always last and always present for a non-empty query, so the
     * dropdown never becomes a dead end when nothing matches locally.
     */
    fun suggest(
        query: String,
        tabs: List<BrowserTab>,
        history: List<BrowserVisit>,
        engine: BrowserSearchEngine,
        limit: Int = 8,
    ): List<Suggestion> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val needle = q.lowercase()

        val out = LinkedHashMap<String, Suggestion>()

        // #802 a private tab is never offered back as a suggestion.
        for (t in BrowserTabOrder.sort(tabs.filter { BrowserSitePolicy.shouldRecord(it) })) {
            if (t.url.lowercase().contains(needle) || t.title.lowercase().contains(needle)) {
                out.getOrPut(t.url) {
                    Suggestion(t.url, t.title.ifBlank { t.url }, Source.TAB)
                }
            }
        }
        for (v in history) {
            if (out.size >= limit) break
            if (v.url.lowercase().contains(needle) || v.title.lowercase().contains(needle)) {
                out.getOrPut(v.url) {
                    Suggestion(v.url, v.title.ifBlank { v.url }, Source.HISTORY)
                }
            }
        }

        val local = out.values.take(limit - 1)
        return local + Suggestion(
            url = BrowserSearch.searchUrl(q, engine),
            label = "Search ${engine.label} for “$q”",
            source = Source.SEARCH,
        )
    }

    /**
     * The dropdown's three LABELLED sections, always in this order, each capped:
     *  - [Sections.search]: Web search, the default engine's own section: a "Search <engine> for q" row
     *    first, then the engine's query suggestions ([remote], already fetched or empty), each opening a search.
     *  - [Sections.history]: pages he has been to that match, open tabs first, then history ranked by
     *    [score] (how well it matches, then how recent), each with title and URL.
     *  - [Sections.favourites]: matching Fav entries (the bookmarks, seeded sections such as GitHub Pages
     *    included), best match first, never a URL the History section already shows.
     * A private tab is never offered, as in [suggest].
     */
    data class Sections(val search: List<Suggestion>, val history: List<Suggestion>, val favourites: List<Suggestion> = emptyList()) {
        val isEmpty: Boolean get() = search.isEmpty() && history.isEmpty() && favourites.isEmpty()
        val flat: List<Suggestion> get() = search + history + favourites
    }

    fun sections(
        query: String,
        tabs: List<BrowserTab>,
        history: List<BrowserVisit>,
        engine: BrowserSearchEngine,
        remote: List<String> = emptyList(),
        now: Long = System.currentTimeMillis(),
        searchLimit: Int = SEARCH_LIMIT,
        historyLimit: Int = HISTORY_LIMIT,
        favourites: List<BrowserBookmark> = emptyList(),
        favLimit: Int = FAV_LIMIT,
    ): Sections {
        val q = query.trim()
        if (q.isEmpty()) return Sections(emptyList(), emptyList())
        val needle = q.lowercase()

        val search = ArrayList<Suggestion>()
        search.add(Suggestion(BrowserSearch.searchUrl(q, engine), "Search ${engine.label} for “$q”", Source.SEARCH, q))
        for (r in remote) {
            val t = r.trim()
            if (t.isEmpty() || t.equals(q, ignoreCase = true) || search.any { it.label == t }) continue
            if (search.size >= searchLimit) break
            search.add(Suggestion(BrowserSearch.searchUrl(t, engine), t, Source.SEARCH, t))
        }

        val ranked = rank(needle, tabs, history, now, historyLimit)
        val shown = ranked.map { it.url }.toSet()
        val favs = BrowserFavourites.suggest(favourites, q, favLimit, shown)
            .map { Suggestion(it.url, it.title.ifBlank { it.url }, Source.FAV, it.url) }
        return Sections(search, ranked, favs)
    }

    /**
     * #1841 the History section's ranking is the browser core's (`suggest_rank`; its Kotlin twin without the library): open tabs first (a private
     * tab never), then history by [matchScore] plus [recency], one row per url, at most [limit]. [rankRules] below is the readable rule it is held to.
     */
    fun rank(needle: String, tabs: List<BrowserTab>, history: List<BrowserVisit>, now: Long, limit: Int): List<Suggestion> {
        val a = JSONObject().put("needle", needle).put("now", now).put("limit", limit)
            .put("tabs", JSONArray().also { arr -> BrowserTabOrder.sort(tabs.filter { BrowserSitePolicy.shouldRecord(it) }).forEach { arr.put(JSONObject().put("url", it.url).put("title", it.title)) } })
            .put("history", JSONArray().also { arr -> history.forEach { arr.put(JSONObject().put("url", it.url).put("title", it.title).put("ts", it.ts)) } })
        val rows = BrowserCore.lists("suggest_rank", a).optJSONArray("rows") ?: return emptyList()
        return (0 until rows.length()).map { rows.getJSONObject(it).let { o -> Suggestion(o.getString("url"), o.getString("label"), Source.valueOf(o.getString("source")), o.getString("url")) } }
    }

    fun rankRules(needle: String, tabs: List<BrowserTab>, history: List<BrowserVisit>, now: Long, limit: Int): List<Suggestion> {
        val out = LinkedHashMap<String, Pair<Double, Suggestion>>()
        for (t in BrowserTabOrder.sort(tabs.filter { BrowserSitePolicy.shouldRecord(it) })) {
            val m = matchScore(needle, t.url, t.title) ?: continue
            out.getOrPut(t.url) { (m + 1000.0) to Suggestion(t.url, t.title.ifBlank { t.url }, Source.TAB, t.url) }
        }
        for (v in history) {
            val m = matchScore(needle, v.url, v.title) ?: continue
            out.getOrPut(v.url) { (m + recency(v.ts, now)) to Suggestion(v.url, v.title.ifBlank { v.url }, Source.HISTORY, v.url) }
        }
        return out.values.sortedByDescending { it.first }.take(limit).map { it.second }
    }

    /** Dense: five rows a section at most (the search section counts its own "Search ..." row). */
    const val SEARCH_LIMIT = 5
    const val HISTORY_LIMIT = 5
    const val FAV_LIMIT = 5

    /**
     * How well [needle] (lower-case) matches a page: the host starting with it beats a title word
     * starting with it, which beats a substring of the url, which beats one of the title. Null = no match.
     */
    fun matchScore(needle: String, url: String, title: String): Double? {
        val u = url.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val t = title.lowercase()
        return when {
            u.startsWith(needle) -> 100.0
            t.startsWith(needle) -> 90.0
            t.split(' ', '-', '_', '|', '/', '.').any { it.isNotEmpty() && it.startsWith(needle) } -> 70.0
            u.contains(needle) -> 50.0
            t.contains(needle) -> 40.0
            else -> null
        }
    }

    /** 0..30: a visit from this minute is worth 30, one from a month ago about 1. */
    fun recency(ts: Long, now: Long): Double {
        val days = ((now - ts).coerceAtLeast(0L)) / 86_400_000.0
        return 30.0 / (1.0 + days)
    }
}
