package com.diegonmarcos.cloudbrowser.sites

import com.diegonmarcos.cloudlib.browsercore.BrowserCore
import com.diegonmarcos.superapp.browser.BrowserSitePolicy
import com.diegonmarcos.superapp.browser.BrowserVisit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * #1493 Domains, the pure half (JVM-tested in DomainsTest): the visit log, History per day, Visited, and the per-site actions.
 * The browser's own history keeps one row per page and at most 500 of them; [VisitLog] keeps every (page, time) it was shown,
 * so "every site ever visited" outlives that cap.
 */
object VisitLog {
    const val CAP = 20000

    /** [history] folded into [log]: rows already there (same page and time) are not repeated; newest first, capped. */
    fun merge(log: List<BrowserVisit>, history: List<BrowserVisit>, cap: Int = CAP): List<BrowserVisit> {
        val seen = log.mapTo(HashSet()) { it.url to it.ts }
        val fresh = history.filter { seen.add(it.url to it.ts) }
        return (fresh + log).sortedByDescending { it.ts }.take(cap)
    }

    fun toJson(list: List<BrowserVisit>): String = JSONArray().also { a -> list.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title).put("s", it.ts)) } }.toString()

    fun fromJson(raw: String?): List<BrowserVisit> {
        val a = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> o.optString("u").takeIf { it.isNotBlank() }?.let { BrowserVisit(it, o.optString("t", it), o.optLong("s")) } } }
    }

    /** The domain of a page: its host without a leading www.; blank for what is not a web address. */
    fun domainOf(url: String): String = BrowserSitePolicy.hostOf(url).removePrefix("www.")
}

/** History per day: pages, or the domains those pages belong to. */
object HistoryDays {
    data class PageDay(val day: String, val pages: List<BrowserVisit>)
    data class DomainCount(val domain: String, val pages: Int, val last: Long)
    data class DomainDay(val day: String, val domains: List<DomainCount>)

    /** `yyyy-MM-dd` of [ts] in [zone]. */
    fun dayOf(ts: Long, zone: ZoneId): String = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().toString()

    /** The visits as the core reads them: url, title, time and the page's domain (parsed here, by java.net.URI, the platform's own parser). */
    private fun rows(visits: List<BrowserVisit>) = JSONArray().also { a ->
        visits.forEach { a.put(JSONObject().put("url", it.url).put("title", it.title).put("ts", it.ts).put("domain", VisitLog.domainOf(it.url))) }
    }

    /** The zone's UTC offsets over the span of [visits], `[[from_ms, offset_ms], ...]`: the core has no time-zone database, it applies these. */
    fun zoneOffsets(visits: List<BrowserVisit>, zone: ZoneId): JSONArray {
        val out = JSONArray()
        if (visits.isEmpty()) return out
        val lo = visits.minOf { it.ts }; val hi = visits.maxOf { it.ts }
        val rules = zone.rules
        out.put(JSONArray().put(lo).put(rules.getOffset(Instant.ofEpochMilli(lo)).totalSeconds * 1000L))
        var t = rules.nextTransition(Instant.ofEpochMilli(lo))
        while (t != null && t.instant.toEpochMilli() <= hi) {
            out.put(JSONArray().put(t.instant.toEpochMilli()).put(t.offsetAfter.totalSeconds * 1000L))
            t = rules.nextTransition(t.instant)
        }
        return out
    }

    private fun visit(o: JSONObject) = BrowserVisit(o.optString("url"), o.optString("title"), o.optLong("ts"))

    /** Pages per day, the newest day first and the newest page first within it (#1841: the browser core's `view_pages_by_day`). */
    fun pagesByDay(visits: List<BrowserVisit>, zone: ZoneId): List<PageDay> {
        val a = BrowserCore.lists("view_pages_by_day", JSONObject().put("visits", rows(visits)).put("offsets", zoneOffsets(visits, zone))).optJSONArray("days") ?: return emptyList()
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { d -> PageDay(d.getString("day"), d.getJSONArray("pages").let { p -> (0 until p.length()).map { visit(p.getJSONObject(it)) } }) } }
    }

    /** Domains per day with how many pages each had that day: most pages first, then name (the core's `view_domains_by_day`). */
    fun domainsByDay(visits: List<BrowserVisit>, zone: ZoneId): List<DomainDay> {
        val a = BrowserCore.lists("view_domains_by_day", JSONObject().put("visits", rows(visits)).put("offsets", zoneOffsets(visits, zone))).optJSONArray("days") ?: return emptyList()
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { d ->
            DomainDay(d.getString("day"), d.getJSONArray("domains").let { c -> (0 until c.length()).map { c.getJSONObject(it).let { o -> DomainCount(o.getString("domain"), o.getInt("pages"), o.getLong("last")) } } })
        } }
    }

    /** The readable rules [pagesByDay] and [domainsByDay] are held to. */
    fun pagesByDayRules(visits: List<BrowserVisit>, zone: ZoneId): List<PageDay> =
        visits.sortedByDescending { it.ts }.groupBy { dayOf(it.ts, zone) }.map { (d, v) -> PageDay(d, v) }.sortedByDescending { it.day }

    fun domainsByDayRules(visits: List<BrowserVisit>, zone: ZoneId): List<DomainDay> =
        visits.filter { VisitLog.domainOf(it.url).isNotEmpty() }.groupBy { dayOf(it.ts, zone) }.map { (d, v) ->
            DomainDay(d, v.groupBy { VisitLog.domainOf(it.url) }.map { (dom, p) -> DomainCount(dom, p.size, p.maxOf { it.ts }) }
                .sortedWith(compareByDescending<DomainCount> { it.pages }.thenBy { it.domain }))
        }.sortedByDescending { it.day }
}

/** Visited: every domain with its first and last visit, sortable. */
object Visited {
    data class Site(val domain: String, val first: Long, val last: Long, val pages: Int)

    const val BY_NAME = "name"
    const val BY_LAST = "last"

    /** #1841 the browser core's `view_sites` (first and last visit, distinct pages per domain). */
    fun sites(visits: List<BrowserVisit>): List<Site> {
        val rows = JSONArray().also { a -> visits.forEach { a.put(JSONObject().put("url", it.url).put("title", it.title).put("ts", it.ts).put("domain", VisitLog.domainOf(it.url))) } }
        return siteList(BrowserCore.lists("view_sites", JSONObject().put("visits", rows)).optJSONArray("sites"))
    }

    private fun siteList(a: JSONArray?): List<Site> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it).let { o -> Site(o.getString("domain"), o.getLong("first"), o.getLong("last"), o.getInt("pages")) } }

    fun sitesRules(visits: List<BrowserVisit>): List<Site> =
        visits.filter { VisitLog.domainOf(it.url).isNotEmpty() }.groupBy { VisitLog.domainOf(it.url) }
            .map { (d, v) -> Site(d, v.minOf { it.ts }, v.maxOf { it.ts }, v.map { it.url }.distinct().size) }

    /** `name` = A to Z (ignoring case); `last` = most recently visited first, ties by name; anything else = name (the core's `view_sites_sort`). */
    fun sort(sites: List<Site>, by: String): List<Site> = siteList(BrowserCore.lists("view_sites_sort", JSONObject().put("by", by)
        .put("sites", JSONArray().also { a -> sites.forEach { a.put(JSONObject().put("domain", it.domain).put("first", it.first).put("last", it.last).put("pages", it.pages)) } })).optJSONArray("sites"))

    fun sortRules(sites: List<Site>, by: String): List<Site> = when (by) {
        BY_LAST -> sites.sortedWith(compareByDescending<Site> { it.last }.thenBy { it.domain })
        else -> sites.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.domain })
    }

    /** Every site that has ever been seen: the visited ones and the ones holding a permission rule, A to Z (the core's `view_ever_seen`). */
    fun everSeen(visited: List<Site>, ruleHosts: Collection<String>): List<String> =
        BrowserCore.lists("view_ever_seen", JSONObject().put("visited", JSONArray(visited.map { it.domain })).put("rule_hosts", JSONArray(ruleHosts.toList()))).getJSONArray("hosts")
            .let { a -> (0 until a.length()).map { a.getString(it) } }
}

/** One cached file as the actions see it. */
data class CachedFile(val host: String, val path: String, val mime: String)

/** What a site action does, as steps; [SiteActions.run] hands each to an executor. */
sealed interface SiteOp {
    data class DeleteFiles(val paths: List<String>) : SiteOp
    data class Cookies(val host: String) : SiteOp
    data class Storage(val host: String) : SiteOp
    data class ResetPerms(val host: String) : SiteOp
}

interface SiteExec {
    fun deleteFiles(paths: List<String>)
    fun cookies(host: String)
    fun storage(host: String)
    fun resetPerms(host: String)
}

/** The per-site actions (ui.domains.actions ids) as pure plans over the site's cached files. */
object SiteActions {
    const val COOKIES = "cookies"
    const val MEDIA = "media"
    const val CACHE = "cache"
    const val STORAGE = "storage"
    const val RESET_PERMS = "reset_perms"

    private fun own(host: String, files: List<CachedFile>) = files.filter { it.host == host }

    /** null = not an action. [mediaPrefixes] are the content types that count as media (ui.domains.media_mime_prefixes). */
    fun plan(id: String, host: String, files: List<CachedFile>, mediaPrefixes: List<String>): List<SiteOp>? = when (id) {
        COOKIES -> listOf(SiteOp.Cookies(host))
        STORAGE -> listOf(SiteOp.Storage(host))
        RESET_PERMS -> listOf(SiteOp.ResetPerms(host))
        CACHE -> listOf(SiteOp.DeleteFiles(own(host, files).map { it.path }))
        MEDIA -> listOf(SiteOp.DeleteFiles(own(host, files).filter { f -> mediaPrefixes.any { f.mime.startsWith(it) } }.map { it.path }))
        else -> null
    }

    fun run(ops: List<SiteOp>, exec: SiteExec) = ops.forEach {
        when (it) {
            is SiteOp.DeleteFiles -> exec.deleteFiles(it.paths)
            is SiteOp.Cookies -> exec.cookies(it.host)
            is SiteOp.Storage -> exec.storage(it.host)
            is SiteOp.ResetPerms -> exec.resetPerms(it.host)
        }
    }
}
