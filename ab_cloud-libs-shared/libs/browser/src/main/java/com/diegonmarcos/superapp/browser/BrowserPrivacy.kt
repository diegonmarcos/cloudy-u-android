package com.diegonmarcos.superapp.browser

import com.diegonmarcos.cloudlib.web.CloudWeb
import android.content.Context
import com.diegonmarcos.cloudlib.browsercore.BrowserCore
import org.json.JSONArray
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.net.URI

/** One per-site permission the app declares (build.json::ui.browser.site_permissions[]). */
data class BrowserSitePerm(
    val id: String,
    val label: String,
    val default: String,
    /** Android runtime permissions the app must also hold before a page can be granted it. */
    val android: List<String> = emptyList(),
    /** What WebView asks with: PermissionRequest resources, or "geolocation" for the location prompt. */
    val webkit: List<String> = emptyList(),
    /** #1401 one of the most-used permissions: listed first. */
    val top: Boolean = false,
    /** #1401 can Android WebView enforce it (false = declared, shown as not supported in WebView). */
    val webview: Boolean = true,
    /** #1401 why it is not supported in WebView (empty when [webview]). */
    val why: String = "",
    /** #1401 the WebView hook that enforces it (empty when not [webview]). */
    val enforcedBy: String = "",
)

/**
 * #802 per-site decisions, pure: a rule for `example.org` also covers `a.example.org`,
 * the most specific host wins, and a host with no rule gets the permission's declared
 * default. Plus the one privacy rule for private tabs: they are never recorded.
 */
object BrowserSitePolicy {
    const val ALLOW = "allow"
    const val DENY = "deny"
    const val ASK = "ask"
    val VALUES = listOf(ALLOW, DENY, ASK)

    fun key(host: String, perm: String) = "${host.lowercase()}|$perm"

    fun hostOf(url: String?): String = runCatching { URI(url ?: "").host?.lowercase() }.getOrNull().orEmpty()

    /** [host] itself, then each parent domain: a.b.example.org → b.example.org → example.org → org. */
    fun suffixes(host: String): List<String> {
        val parts = host.lowercase().split('.').filter { it.isNotEmpty() }
        return parts.indices.map { parts.drop(it).joinToString(".") }
    }

    /** #1401 the host under which the user's global default of a permission is stored (Configs > Sites Permissions). */
    const val ANY_HOST = "*"

    /** The site's rule (most specific host first), else the user's global default, else the declared [default]. */
    fun resolve(rules: Map<String, String>, host: String, perm: String, default: String): String =
        BrowserCore.lists("perm_resolve", JSONObject().put("rules", JSONObject(rules)).put("host", host).put("perm", perm).put("default", default)).getString("value")

    /** The readable rule [resolve] (the browser core's `perm_resolve`) is held to. */
    fun resolveRules(rules: Map<String, String>, host: String, perm: String, default: String): String =
        suffixes(host).firstNotNullOfOrNull { rules[key(it, perm)] } ?: rules[key(ANY_HOST, perm)] ?: default

    /** #1401 the global default: the user's `*` rule, else the declared one. */
    fun defaultOf(rules: Map<String, String>, perm: BrowserSitePerm): String =
        BrowserCore.lists("perm_default", JSONObject().put("rules", JSONObject(rules)).put("perm", perm.id).put("default", perm.default)).getString("value")

    /** #1401 the Perms list order: the most-used first, then all the rest, each in declared order. */
    fun ordered(perms: List<BrowserSitePerm>): List<BrowserSitePerm> {
        val ids = BrowserCore.lists("perm_ordered", JSONObject().put("perms", JSONArray().also { a -> perms.forEach { a.put(JSONObject().put("id", it.id).put("top", it.top)) } })).getJSONArray("ids")
        val used = BooleanArray(perms.size)
        return (0 until ids.length()).mapNotNull { i -> perms.indices.firstOrNull { !used[it] && perms[it].id == ids.getString(i) }?.also { used[it] = true }?.let { perms[it] } }
    }

    private fun map(kind: String, state: String, gesture: Boolean = false) =
        BrowserCore.lists("perm_map", JSONObject().put("kind", kind).put("state", state).put("gesture", gesture)).get("value")

    /** #1401 the WebView settings a state turns into (the browser core's `perm_map`, JVM-tested). */
    fun autoplayNeedsGesture(state: String) = map("autoplay_needs_gesture", state) as Boolean
    /** WebSettings.MIXED_CONTENT_ALWAYS_ALLOW = 0, NEVER_ALLOW = 1, COMPATIBILITY_MODE = 2. */
    fun mixedContentMode(state: String) = (map("mixed_content", state) as Number).toInt()
    /** A window.open / target=_blank: allow opens it, block refuses it, ask opens it only on a user gesture. */
    fun popupAllowed(state: String, userGesture: Boolean) = map("popup_allowed", state, userGesture) as Boolean
    /** Storage, third-party cookies, fullscreen: on unless blocked (ask has no WebView prompt, so it behaves as allow). */
    fun onUnlessBlocked(state: String) = map("on_unless_blocked", state) as Boolean

    /** History, previews and suggestions never see a private tab. */
    fun shouldRecord(tab: BrowserTab?): Boolean = tab?.isPrivate != true

    /** #900 the per-site rule ids that are not allow/deny/ask permissions but a stored value. */
    const val ZOOM = "zoom"
    const val ZOOM_MIN = 50
    const val ZOOM_MAX = 300

    /** The text zoom (percent) set for [host] (or a parent domain), else null = use the global one. */
    fun zoomOf(rules: Map<String, String>, host: String): Int? =
        BrowserCore.lists("perm_zoom", JSONObject().put("rules", JSONObject(rules)).put("host", host)).let { if (it.isNull("zoom")) null else it.getInt("zoom") }

    /** Hosts that hold at least one rule, with their rules: the Sites page of Configs (the browser core's `perm_hosts`). */
    fun hostsWithRules(rules: Map<String, String>): Map<String, Map<String, String>> {
        val a = BrowserCore.lists("perm_hosts", JSONObject().put("rules", JSONObject(rules))).getJSONArray("hosts")
        val out = LinkedHashMap<String, Map<String, String>>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i); val r = o.getJSONObject("rules")
            out[o.getString("host")] = r.keys().asSequence().associateWith { r.getString(it) }
        }
        return out
    }

    /** The readable rule [hostsWithRules] is held to. */
    fun hostsWithRulesRules(rules: Map<String, String>): Map<String, Map<String, String>> =
        rules.entries.mapNotNull { (k, v) -> k.substringBefore('|', "").takeIf { it.isNotEmpty() }?.let { h -> Triple(h, k.substringAfter('|'), v) } }
            .filter { it.first != ANY_HOST }
            .groupBy({ it.first }, { it.second to it.third }).mapValues { it.value.toMap() }.toSortedMap()

    fun parsePerms(arr: org.json.JSONArray?): List<BrowserSitePerm> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            BrowserSitePerm(o.optString("id"), o.optString("label", o.optString("id")), o.optString("default", ASK),
                list("android"), list("webkit"), top = o.optBoolean("top", false), webview = o.optBoolean("webview", true),
                why = o.optString("why"), enforcedBy = o.optString("enforced_by"))
        }.filter { it.id.isNotEmpty() }
}

/** #802 the per-site rules, in the declared `browser_site_permissions` prefs (config: they move with the phone). */
class BrowserSitePermissions(context: Context) {
    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("browser_site_permissions", Context.MODE_PRIVATE)

    /**
     * #1841 the rules' logic (resolution, zoom, the Sites page's hosts, set, forget) is the browser core's (`perm_*`; its Kotlin twin without
     * the library). The prefs file and its `host|perm` keys are unchanged; [BrowserListMigration.ensureMap] moves an upper-case host key to
     * its canonical one once, after a backup of the prefs.
     */
    fun rules(): Map<String, String> {
        BrowserListMigration.ensureMap(ctx, "browser_site_permissions", "perm_migrate", null)
        return sp.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }

    fun resolve(host: String, perm: BrowserSitePerm) = BrowserSitePolicy.resolve(rules(), host, perm.id, perm.default)

    /** #1401 the global default of [perm] (the user's, else the declared one). */
    fun defaultOf(perm: BrowserSitePerm) = BrowserSitePolicy.defaultOf(rules(), perm)

    /** #1401 set the global default of [perm] for every site with no rule of its own. */
    fun setDefault(perm: String, value: String): String? = set(BrowserSitePolicy.ANY_HOST, perm, value)

    /** #1401 does [host] hold a rule of its own for [perm]? */
    fun hasOwn(host: String, perm: String) = rules().containsKey(BrowserSitePolicy.key(host, perm))

    /** #1401 drop [host]'s own rule for [perm]: it follows the global default again. */
    fun reset(host: String, perm: String) { sp.edit().remove(BrowserSitePolicy.key(host, perm)).apply() }

    /** #900 this site's text zoom in percent, or null. */
    fun zoom(host: String): Int? = BrowserSitePolicy.zoomOf(rules(), host)

    /** #900 set (50..300) or clear (null) this site's zoom. */
    fun setZoom(host: String, percent: Int?) {
        val a = BrowserCore.lists("perm_set_zoom", JSONObject().put("host", host).put("percent", percent ?: JSONObject.NULL))
        when (a.optString("action")) {
            "remove" -> sp.edit().remove(a.getString("key")).apply()
            "put" -> sp.edit().putString(a.getString("key"), a.getString("value")).apply()
        }
    }

    /** #900 forget every rule of [host] (the Sites page). */
    fun forget(host: String) {
        val a = BrowserCore.lists("perm_forget", JSONObject().put("host", host).put("keys", JSONArray(sp.all.keys.toList()))).getJSONArray("remove")
        val ed = sp.edit()
        for (i in 0 until a.length()) ed.remove(a.getString(i))
        ed.apply()
    }

    /** Store a decision; [value] must be allow/deny/ask. @return the refusal in words, or null. */
    fun set(host: String, perm: String, value: String): String? {
        val a = BrowserCore.lists("perm_set", JSONObject().put("host", host).put("perm", perm).put("value", value))
        if (a.has("error")) return a.getString("error")
        sp.edit().putString(a.getString("key"), a.getString("value")).apply()
        return null
    }
}

/**
 * #900 Block trackers, pure: a subresource request to a declared tracker host (or a subdomain) is
 * answered empty, unless it is the page's own site or the main frame. The hosts are the app's
 * content (build.json::ui.browser.tracker_hosts); this class ships no list.
 */
class BrowserTrackers(hosts: Collection<String>, extraLists: List<String> = emptyList()) {
    // #1841 the host matcher is the browser core's (Rust: libbrowser_core.so, Kotlin twin when it is not loaded): one rule per line;
    // the declared hosts and any further rule lists are merged by the core (comments and duplicates dropped) into the one rule text
    private val filter = com.diegonmarcos.cloudlib.browsercore.BrowserCore.Filter(
        com.diegonmarcos.cloudlib.browsercore.BrowserCore.mergeLists(listOf(hosts.joinToString("\n")) + extraLists))

    /** [host] is a tracker host or a subdomain of one. A bare TLD never matches. */
    fun isTracker(host: String): Boolean = filter.isTracker(host)

    /** Answer this request empty? [pageHost] is the page it was made from. The page IS the tracker's own site (hotjar.com opened on purpose): its own requests are not blocked. */
    fun shouldBlock(requestHost: String, pageHost: String, mainFrame: Boolean): Boolean = filter.shouldBlock(requestHost, pageHost, mainFrame)

    /** [shouldBlock] plus the declared `/segment` path rules (an adblock-style list line), by the request's whole url. */
    fun shouldBlockUrl(url: String, requestHost: String, pageHost: String, mainFrame: Boolean): Boolean = filter.shouldBlockUrl(url, requestHost, pageHost, mainFrame)
}

/**
 * #802 "Clear browsing data": each declared box (build.json::ui.browser.clear_data) is
 * ONE call here. Runs on the main thread (WebView and WebStorage refuse others) and
 * answers once the asynchronous cookie removal has finished.
 */
object BrowserClearData {
    fun clear(ctx: Context, boxes: Set<String>, probeUrl: String?, done: (JSONObject) -> Unit) {
        val out = JSONObject().put("ok", true).put("cleared", org.json.JSONArray(boxes.sorted()))
        for (b in boxes) when (b) {
            "history" -> BrowserHistory(ctx).clear()
            "cache" -> CloudWeb.clearCache(ctx)
            "storage" -> WebStorage.getInstance().deleteAllData()
            "previews" -> { TabThumbnails.sharedOrNull()?.clear(); File(ctx.cacheDir, TabThumbnails.DIR).deleteRecursively(); BrowserTabPrefs(ctx).clearPreviews() }
            "downloads" -> BrowserDownloads(ctx).clear()
            "cookies" -> Unit   // asynchronous: below
            else -> out.put("ok", false).put("error", "$b: not a clear-data box")
        }
        val finish = {
            if (!probeUrl.isNullOrBlank()) out.put("cookies_after", CookieManager.getInstance().getCookie(probeUrl) ?: JSONObject.NULL)
            done(out)
        }
        if ("cookies" in boxes) CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); finish() }
        else finish()
    }

    /**
     * The last private tab closed: drop the site storage of every origin it visited, and the
     * session cookies too when no normal tab is open to lose them. Cookies are process-wide in
     * Android WebView, so a private tab beside normal ones is best-effort; the UI says so.
     */
    fun endPrivateSession(origins: Collection<String>, normalTabsOpen: Boolean) {
        origins.forEach { WebStorage.getInstance().deleteOrigin(it) }
        if (!normalTabsOpen) CookieManager.getInstance().removeSessionCookies(null)
    }
}
