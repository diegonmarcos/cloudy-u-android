package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.fleetdata.FleetData
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/**
 * #642 — THE STORE'S READ-ONLY FEEDS: commits, and CI-CD runs.
 *
 * The Store already answers "what is installed and what can I install". It
 * could not answer "what changed, and did it build" — the two questions you
 * actually have while looking at a version number. Those are the same shape as
 * each other (a list of dated entries with a state and a link) and a different
 * shape from everything else in here, so they are ONE reader over a
 * declaration rather than two screens.
 *
 * ONE TAB PER DECLARED FEED, and nothing in this file names a repository, an
 * endpoint, a JSON field or a status word. All of that is
 * assets/appstore-feeds.json, the same way [PhoneAppActions.SOURCES_FILE]
 * owns the install sources: a third feed is an entry there and no edit here.
 * That is also what makes the derivation testable — [labels] can be handed a
 * declaration a test invented, and has to draw it.
 *
 * ONE REQUEST PATH. Everything goes through [SourceResolver.getBody], which is
 * the store's one fetch with the store's one timeout and redirect policy. This
 * file opens no connection of its own.
 *
 * A READER, not an actor. The only thing a row does is open the link the feed
 * itself supplied, so there is no install or write anywhere in here. The one
 * credential is the fleet bearer (#841), sent only to the fleet git-proxy so
 * reads stop spending the anonymous GitHub quota; the public url is still read
 * without one, as the fallback.
 */
object FeedViewer {

    /** #1566 the feed declaration names the repositories the Cloud page reads: a fleet-private tier-2 dataset served by Cloud Account's private route; [FEEDS_FILE] is its name in the private repo and in CI's fleet-private-data. */
    const val DATASET = "fleet/appstore-feeds"
    const val FEEDS_FILE = "appstore-feeds.json"
    /** UNIT TESTS ONLY: the CI-materialised declaration a test puts where the reader looks first; production never sets it. */
    @Volatile var testDecl: String? = null

    /** How one url's body is read into rows: which array, and the templates.
     *  The fleet git-proxy (#841) answers a REDUCED shape (`.commits[]` with a
     *  flat `message`/`author`, `.runs[]` not `.workflow_runs[]`), so the proxy
     *  leg carries a shape of its own and the public leg keeps GitHub's. */
    class Shape(
        val items: String?,
        val ref: String,
        val title: String,
        val subtitle: String,
        val link: String,
        val state: String?,
    )

    /** One declared feed. [items] is null when the response IS the array.
     *  [proxy] is null until the fleet serves this feed; when set it is tried
     *  FIRST, with the fleet bearer, and [url] stays the public fallback (#668,
     *  #841). [proxyShape] is how the proxy's body is read; it inherits every
     *  field the declaration's `proxy` object leaves out. */
    class Feed(
        val id: String,
        val label: String,
        val blurb: String,
        val url: String,
        val proxy: String?,
        val items: String?,
        val ref: String,
        val title: String,
        val subtitle: String,
        val link: String,
        val state: String?,
        val ok: Set<String>,
        val bad: Set<String>,
        proxyShape: Shape? = null,
    ) {
        /** The public leg's shape: the feed's own fields. */
        val shape = Shape(items, ref, title, subtitle, link, state)
        val proxyShape: Shape = proxyShape ?: shape
    }

    /**
     * #841 THE FLEET BEARER, handed in by the host - this library cannot read
     * the host's credential store and must not grow a second one. The SuperApp
     * sets it to the same Authelia bearer its other fleet calls send
     * (libs:ops DaguPrefs, as OpsClient/ContainerSheet do). Read per request so
     * a token pasted after launch is used without a restart. Blank = send no
     * Authorization; the proxy then answers 401 and the read falls through to
     * the public url. Never logged, never put in a message, never sent to [Feed.url].
     */
    @Volatile var fleetBearer: () -> String = { "" }

    /** One row, already rendered down to strings by the templates. */
    class Entry(val ref: String, val title: String, val subtitle: String, val link: String, val state: String)

    // ── the declaration ──────────────────────────────────────────────────────

    /** Every declared feed, in declared order. An unreadable or absent asset is
     *  NO feeds and therefore no tabs — never a half-built one. */
    fun feeds(ctx: Context): List<Feed> = runCatching {
        parse(testDecl?.let { JSONObject(it) } ?: FleetData.get(ctx.applicationContext, DATASET) { JSONObject(it) } ?: return@runCatching emptyList())
    }.getOrDefault(emptyList())

    /** Split from [feeds] so a test can hand it a declaration of its own
     *  invention: a hardcoded strip passes a test that only ever sees today's
     *  two feeds, and fails the moment it is handed three. */
    fun parse(decl: JSONObject): List<Feed> {
        val array = decl.optJSONArray("feeds") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val f = array.optJSONObject(i) ?: return@mapNotNull null
            val id = f.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val url = f.optString("url").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val items = f.optString("items").takeIf { it.isNotEmpty() }
            val ref = f.optString("ref"); val title = f.optString("title")
            val subtitle = f.optString("subtitle"); val link = f.optString("link")
            val state = f.optString("state").takeIf { it.isNotEmpty() }
            // `proxy` is either a bare url (same shape as the public one) or an
            // object {url, items, ref, title, subtitle, link, state} whose
            // absent fields inherit the feed's.
            val po = f.optJSONObject("proxy")
            val proxy = (po?.optString("url") ?: f.optString("proxy")).takeIf { it.isNotEmpty() && it != "null" }
            val proxyShape = po?.let {
                Shape(items = if (it.has("items")) it.optString("items").takeIf { s -> s.isNotEmpty() } else items,
                    ref = it.optString("ref", ref), title = it.optString("title", title),
                    subtitle = it.optString("subtitle", subtitle), link = it.optString("link", link),
                    state = if (it.has("state")) it.optString("state").takeIf { s -> s.isNotEmpty() } else state)
            }
            Feed(id = id, label = f.optString("label", id), blurb = f.optString("blurb"),
                url = url, proxy = proxy, items = items, ref = ref, title = title,
                subtitle = subtitle, link = link, state = state,
                ok = words(f.optJSONArray("ok")), bad = words(f.optJSONArray("bad")),
                proxyShape = proxyShape)
        }
    }

    private fun words(a: JSONArray?): Set<String> =
        (0 until (a?.length() ?: 0)).mapNotNull { a?.optString(it)?.takeIf { s -> s.isNotEmpty() } }.toSet()

    // ── the fetch, and the templates ─────────────────────────────────────────

    /**
     * [feed]'s entries, newest first as the endpoint returns them. Blocking:
     * called on a worker thread by [render].
     */
    fun load(feed: Feed): List<Entry> {
        // #668 the fleet proxy is an OPTIMISATION, never a dependency: public
        // data must stay readable with the fleet down, so any proxy failure
        // falls through to the public url, and is kept (suppressed) so the
        // sentence can still say the proxy failed too.
        // The bearer goes ONLY to the proxy: the public url is GitHub's, and the
        // fleet credential must never leave the fleet.
        val proxy = feed.proxy ?: return try {
            read(feed.shape, feed.url, emptyMap()).also { note(feed, LEG_PUBLIC, it.size, null, null) }
        } catch (direct: Exception) { note(feed, LEG_FAILED, 0, null, explain(direct)); throw direct }
        var proxyWhy: String? = null
        val got = try {
            val bearer = runCatching { fleetBearer() }.getOrDefault("").trim()
            read(feed.proxyShape, proxy, if (bearer.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $bearer"))
        } catch (viaProxy: Exception) {
            try { read(feed.shape, feed.url, emptyMap()).also { proxyWhy = explain(viaProxy) } } catch (direct: Exception) {
                direct.addSuppressed(viaProxy); note(feed, LEG_FAILED, 0, explain(viaProxy), explain(direct)); throw direct
            }
        }
        note(feed, if (proxyWhy == null) LEG_PROXY else LEG_FALLBACK, got.size, proxyWhy, null)
        return got
    }

    // ── #841 which leg served each feed, for /api/store/feeds ───────────────

    const val LEG_PROXY = "proxy"
    const val LEG_FALLBACK = "github-fallback"
    const val LEG_PUBLIC = "github"
    const val LEG_FAILED = "failed"

    /** The last [load] of one feed: the leg that answered, and why the proxy
     *  was passed over. Messages come from [explain], which never carries the
     *  bearer (it is only ever a request header). */
    class Served(val leg: String, val entries: Int, val proxyError: String?, val error: String?, val at: Long)

    private val served = java.util.concurrent.ConcurrentHashMap<String, Served>()

    private fun note(feed: Feed, leg: String, n: Int, proxyError: String?, error: String?) {
        served[feed.id] = Served(leg, n, proxyError, error, System.currentTimeMillis())
    }

    fun lastServed(id: String): Served? = served[id]

    /** Every declared feed with its two urls and the last leg that served it
     *  (null = not loaded since launch). Pure over [feeds] + [served]. */
    fun servedJson(feeds: List<Feed>): org.json.JSONObject {
        val arr = JSONArray()
        for (f in feeds) {
            val s = served[f.id]
            arr.put(org.json.JSONObject().put("id", f.id).put("proxy", f.proxy ?: org.json.JSONObject.NULL)
                .put("url", f.url)
                .put("bearerSet", runCatching { fleetBearer() }.getOrDefault("").isNotBlank())
                .put("last", s?.let {
                    org.json.JSONObject().put("leg", it.leg).put("entries", it.entries)
                        .put("proxyError", it.proxyError ?: org.json.JSONObject.NULL)
                        .put("error", it.error ?: org.json.JSONObject.NULL).put("at", it.at)
                } ?: org.json.JSONObject.NULL))
        }
        return org.json.JSONObject().put("ok", true).put("feeds", arr)
    }

    /** One url's entries. There is NO path from a failed read to an empty
     *  list: a 404 or a body without the declared array THROWS, so the only
     *  way to draw "nothing in this feed" is a 2xx that carried an empty one. */
    private fun read(feed: Shape, url: String, headers: Map<String, String>): List<Entry> {
        val body = SourceResolver.getBody(url, headers) ?: throw SourceResolver.HttpStatus(404, url, "HTTP 404 from $url")
        val root = JSONTokener(body).nextValue()
        val array = when {
            feed.items != null -> (root as? JSONObject)?.optJSONArray(feed.items)
            else -> root as? JSONArray
        } ?: error("$url answered without the declared ${feed.items ?: "top-level list"}")
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            Entry(ref = fill(feed.ref, item), title = fill(feed.title, item),
                subtitle = fill(feed.subtitle, item), link = fill(feed.link, item),
                state = feed.state?.let { path(item, it) } ?: "")
        }
    }

    /**
     * #668 WHICH failure, in words. Quota, unreachable, refused and wrong-shape
     * lead to different places (wait / check the network / check the url / the
     * endpoint changed), so they must not share one sentence - and none of them
     * may read as an empty feed. Pure, so it is testable without a device.
     */
    fun explain(t: Throwable): String {
        val why = when {
            t is SourceResolver.HttpStatus && t.quota ->
                "Rate limit reached — not an empty feed. ${t.message}. It refills by itself" +
                    (t.resetEpoch?.let { " at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it * 1000)) } ?: "") + "."
            t is SourceResolver.HttpStatus && t.code == 404 -> "Not found — not an empty feed. ${t.message}"
            t is SourceResolver.HttpStatus -> "Refused by the server — ${t.message}"
            t is IOException -> "Unreachable — nothing was read. ${t.javaClass.simpleName}: ${t.message.orEmpty()}"
            else -> "The feed answered, but not in the shape its declaration describes — ${t.message}"
        }
        return why + t.suppressed.joinToString("") { " (Fleet proxy tried first: ${explain(it)})" }
    }

    /**
     * Substitute every `{a.b.c}` in [template] with that dotted path out of
     * [item]. A path the item does not carry substitutes EMPTY rather than
     * throwing: a feed that changes shape must degrade to a thinner row, not
     * take the tab down — the row is a reader, and a missing author name is
     * not a reason to show nothing at all.
     */
    fun fill(template: String, item: JSONObject): String =
        TEMPLATE.replace(template) { path(item, it.groupValues[1]) }.trim()

    /** `commit.author.name` out of nested objects. Empty when absent. */
    private fun path(item: JSONObject, dotted: String): String {
        var node: JSONObject = item
        val parts = dotted.split('.')
        for (p in parts.dropLast(1)) node = node.optJSONObject(p) ?: return ""
        return node.opt(parts.last())?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
    }

    private val TEMPLATE = Regex("\\{([A-Za-z0-9_.]+)\\}")

    // ── the link ─────────────────────────────────────────────────────────────

    /** The one place a row's link is opened. Handed in by the host fragment so
     *  this object needs no Activity of its own. */
    fun opener(ctx: Context): (String) -> Unit = { url ->
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
