package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * THE BOUNDARY BETWEEN SHARED MECHANISM AND PER-APP CONTENT.
 *
 * libs:browser is shared by reference — ac_cloud-browser/build.json and
 * ac_cloud-vault/build.json both name this directory, and anything
 * hardcoded here reaches every app that ever links it. So the mechanism
 * for pinning, grouping, history, suggestion and searching lives in this
 * module, and the CONTENT — which tabs are pinned on a fresh install,
 * which search engines exist and which is default — arrives as JSON
 * from the consuming app.
 *
 * The rule this enforces came out of #209: shared code is fine, but one
 * app's configuration must never turn up inside another app.
 *
 * [DEFAULT] is therefore deliberately EMPTY of tabs. An app that links
 * libs:browser and supplies no config gets a browser with no pinned
 * tabs at all — never somebody else's homepage set.
 *
 * ac_cloud-browser supplies its own via build.json::ui.browser, which
 * app/build.gradle bakes into BuildConfig.UI_BROWSER_CONFIG_B64 and
 * MainActivity hands to BrowserHostFragment.newInstance.
 */
data class BrowserConfig(
    /** Pinned on FIRST RUN ONLY. Never re-seeded — see [BrowserTabPrefs.seedOnce]. */
    val defaultPinnedTabs: List<String>,
    /**
     * Custom names for pinned tabs, url → title. A `default_pinned_tabs` entry is either a plain
     * URL string (no name: the tab shows the page's own title) or `{"url": ..., "title": ...}`.
     */
    val defaultPinnedTitles: Map<String, String> = emptyMap(),
    val engines: List<BrowserSearchEngine>,
    val defaultEngineId: String,
    /** #802 the user settings this app offers; empty = WebView defaults, nothing offered. */
    val settings: BrowserSettingsCatalogue = BrowserSettingsCatalogue.EMPTY,
    /** #802 the overflow menu, as the app declares it. */
    val menu: BrowserMenu = BrowserMenu.EMPTY,
    /** #802 `mobile` / `desktop` user-agent strings; absent = WebView's own. */
    val userAgents: Map<String, String> = emptyMap(),
    /** #802 the colour roles of the Compose surfaces (KitPalette's seven), ARGB. */
    val palette: Map<String, Int> = emptyMap(),
    /** #802 the stylesheet reader mode renders the extracted article with. */
    val readerCss: String = "",
    /** #802 the per-site permissions the Site settings page offers, with their defaults. */
    val sitePerms: List<BrowserSitePerm> = emptyList(),
    /** #802 the boxes of "Clear browsing data", id → label. */
    val clearData: List<Pair<String, String>> = emptyList(),
    /** #802 the add-ons; their menu rows are already merged into [menu]. */
    val addons: BrowserAddons = BrowserAddons.EMPTY,
    /** #886 Configs' topic sections, in order: id → title. A setting's `section` names one of these ids. */
    val settingsSections: List<Pair<String, String>> = emptyList(),
    /** #893 the favourites seeded on first run (and per new entry); expanded from build.json at build time. */
    val favourites: FavSeed = FavSeed.EMPTY,
    /** #900 the Configs pages: the top tabs, each page's global rows and native blocks. */
    val configs: BrowserConfigPages = BrowserConfigPages.EMPTY,
    /** #900 hosts whose subresources "Block trackers" answers empty (this app's content, not the library's). */
    val trackerHosts: List<String> = emptyList(),
    /** The app's private names (exact hosts that stand for loopback servers, answered by the app's own DNS); NONE = no such names. */
    val privateNames: PrivateNames = PrivateNames.NONE,
    /** Tab-switcher snapshots: capture scale, cache bounds, incognito policy (build.json::ui.browser.tab_thumbnails). */
    val tabThumbs: TabThumbConfig = TabThumbConfig(),
) {

    /** The configured default, or the first engine, or Qwant. Never null. */
    fun defaultEngine(): BrowserSearchEngine =
        engines.firstOrNull { it.id == defaultEngineId }
            ?: engines.firstOrNull()
            ?: QWANT

    /** [engines] entry by id, falling back to [defaultEngine]. */
    fun engine(id: String?): BrowserSearchEngine =
        engines.firstOrNull { it.id == id } ?: defaultEngine()

    companion object {

        /**
         * Qwant is the fallback engine because the owner asked for it and
         * because a browser with no engine at all cannot answer a typed
         * query. It is an ENGINE default, not a content default — the
         * shipped engine list still comes from build.json.
         */
        val QWANT = BrowserSearchEngine(
            id = "qwant",
            label = "Qwant",
            template = "https://www.qwant.com/?q=${BrowserSearchEngine.QUERY}",
        )

        /**
         * What a consuming app gets when it supplies no configuration.
         * ZERO pinned tabs — this is the assertion that keeps one app's
         * homepage set out of every other app that links this module.
         */
        val DEFAULT = BrowserConfig(
            defaultPinnedTabs = emptyList(),
            engines = listOf(QWANT),
            defaultEngineId = QWANT.id,
        )

        /**
         * Parse the per-app block. Any malformed or absent field falls
         * back to [DEFAULT]'s value rather than throwing — a bad config
         * must not brick the browser, and an EMPTY pin list is the safe
         * direction to fail in.
         */
        fun parse(json: String?): BrowserConfig {
            if (json.isNullOrBlank()) return DEFAULT
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return DEFAULT

            val pins = ArrayList<String>()
            val pinTitles = LinkedHashMap<String, String>()
            (o.optJSONArray("default_pinned_tabs") ?: JSONArray()).let { arr ->
                for (i in 0 until arr.length()) {
                    val p = parsePin(arr.opt(i)) ?: continue
                    pins.add(p.first)
                    p.second?.let { pinTitles[p.first] = it }
                }
            }

            val engines = ArrayList<BrowserSearchEngine>()
            (o.optJSONArray("search_engines") ?: JSONArray()).let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val id = e.optString("id").trim()
                    val tpl = e.optString("template").trim()
                    if (id.isEmpty() || !tpl.contains(BrowserSearchEngine.QUERY)) continue
                    engines.add(BrowserSearchEngine(id, e.optString("label", id), tpl, e.optString("suggest").trim().ifEmpty { null }))
                }
            }

            val finalEngines = if (engines.isEmpty()) DEFAULT.engines else engines
            val defaultId = o.optString("default_engine", DEFAULT.defaultEngineId)
            val addons = BrowserAddons.parse(o.optJSONArray("addons"))
            val menu = BrowserMenu.parse(o.optJSONObject("menu")).let { m ->
                // An add-on's rows join the Page Tools section (when the menu declares one).
                if (m.sections.none { it.id == "page_tools" }) m
                else BrowserMenu(m.sections, m.items + addons.all.flatMap { it.menu }, m.whys)
            }
            val sections = o.optJSONArray("settings_sections").let { a ->
                if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { it.optString("id") to it.optString("label", it.optString("id")) }
                }.filter { it.first.isNotEmpty() }
            }
            return BrowserConfig(
                defaultPinnedTabs = pins,
                defaultPinnedTitles = pinTitles,
                engines = finalEngines,
                defaultEngineId = defaultId,
                settings = BrowserSettingsCatalogue.parse(
                    o.optJSONArray("settings"), finalEngines.map { it.id }, defaultId,
                    addonIds = addons.all.map { it.id }, addonDefaults = addons.all.filter { it.defaultEnabled }.map { it.id }.toSet(),
                    engineLabels = finalEngines.associate { it.id to it.label }),
                menu = menu,
                addons = addons,
                userAgents = o.optJSONObject("user_agents").strings(),
                palette = o.optJSONObject("palette").strings().mapNotNull { (k, v) ->
                    parseColor(v)?.let { k to it }
                }.toMap(),
                readerCss = o.optString("reader_css"),
                favourites = FavSeed.parse(o.optJSONObject("favourites")),
                sitePerms = BrowserSitePolicy.parsePerms(o.optJSONArray("site_permissions")),
                settingsSections = sections,
                configs = BrowserConfigPages.parse(o.optJSONObject("configs"), sections, addons),
                privateNames = PrivateNames.parse(o.optJSONObject("private_names")),
                tabThumbs = TabThumbConfig.parse(o.optJSONObject("tab_thumbnails")),
                trackerHosts = o.optJSONArray("tracker_hosts").let { a ->
                    if (a == null) emptyList() else (0 until a.length()).map { i -> a.optString(i).trim().lowercase() }.filter { it.isNotEmpty() }
                },
                clearData = o.optJSONArray("clear_data").let { a ->
                    if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
                        a.optJSONObject(i)?.let { it.optString("id") to it.optString("label", it.optString("id")) }
                    }
                },
            )
        }

        /** One pin: a URL string, or `{url, title}`. Returns (url, title-or-null); null when there is no URL. */
        internal fun parsePin(v: Any?): Pair<String, String?>? {
            val (url, title) = when (v) {
                is JSONObject -> v.optString("url").trim() to v.optString("title").trim()
                is String -> v.trim() to ""
                else -> return null
            }
            if (url.isEmpty()) return null
            return url to title.ifEmpty { null }
        }

        private fun JSONObject?.strings(): Map<String, String> =
            if (this == null) emptyMap() else keys().asSequence().filterNot { it.startsWith("_") }
                .associateWith { optString(it) }

        /** `#RRGGBB` or `#AARRGGBB` → ARGB; null for anything else. Pure, unlike android.graphics.Color. */
        internal fun parseColor(v: String): Int? {
            val h = v.removePrefix("#")
            val n = h.toLongOrNull(16) ?: return null
            return when (h.length) { 6 -> (0xFF000000 or n).toInt(); 8 -> n.toInt(); else -> null }
        }

        /** Same, for the base64 BuildConfig field the gradle script bakes. */
        fun parseBase64(b64: String?): BrowserConfig {
            if (b64.isNullOrBlank()) return DEFAULT
            val json = runCatching {
                String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
            }.getOrNull()
            return parse(json)
        }
    }
}
