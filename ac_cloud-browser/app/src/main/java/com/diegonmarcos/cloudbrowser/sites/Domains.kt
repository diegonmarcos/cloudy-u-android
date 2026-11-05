package com.diegonmarcos.cloudbrowser.sites

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

    /** Pages per day, the newest day first and the newest page first within it. */
    fun pagesByDay(visits: List<BrowserVisit>, zone: ZoneId): List<PageDay> =
        visits.sortedByDescending { it.ts }.groupBy { dayOf(it.ts, zone) }.map { (d, v) -> PageDay(d, v) }.sortedByDescending { it.day }

    /** Domains per day with how many pages each had that day: most pages first, then name. */
    fun domainsByDay(visits: List<BrowserVisit>, zone: ZoneId): List<DomainDay> =
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

    fun sites(visits: List<BrowserVisit>): List<Site> =
        visits.filter { VisitLog.domainOf(it.url).isNotEmpty() }.groupBy { VisitLog.domainOf(it.url) }
            .map { (d, v) -> Site(d, v.minOf { it.ts }, v.maxOf { it.ts }, v.map { it.url }.distinct().size) }

    /** `name` = A to Z (ignoring case); `last` = most recently visited first, ties by name; anything else = name. */
    fun sort(sites: List<Site>, by: String): List<Site> = when (by) {
        BY_LAST -> sites.sortedWith(compareByDescending<Site> { it.last }.thenBy { it.domain })
        else -> sites.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.domain })
    }

    /** Every site that has ever been seen: the visited ones and the ones holding a permission rule, A to Z. */
    fun everSeen(visited: List<Site>, ruleHosts: Collection<String>): List<String> =
        (visited.map { it.domain } + ruleHosts.map { it.removePrefix("www.") }.filter { it.isNotEmpty() && it != BrowserSitePolicy.ANY_HOST }).distinct().sortedBy { it.lowercase() }
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
