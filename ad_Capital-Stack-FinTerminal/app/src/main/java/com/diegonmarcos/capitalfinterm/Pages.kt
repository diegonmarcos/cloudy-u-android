package com.diegonmarcos.capitalfinterm

import org.json.JSONObject

/**
 * One declared entry of a section's page (build.json::ui.stacks[<section>]). The page is DATA: Kotlin names no title, no
 * description and no address. `kind` selects the renderer (the kinds below); an entry names at most ONE source for its target
 * (`site` a link of the SuperApp's linktree snapshot, `registry` a code of its ui.app_urls.registry_sites, `repo` + `path` a repo
 * under ui.app_urls.repo_base), or is `planned` and drawn greyed with its note.
 */
data class Entry(
    val kind: String,
    val title: String,
    val subtitle: String,
    val site: String,
    val registry: String,
    val repo: String,
    val path: String,
    val planned: Boolean,
    val note: String,
    /** The id of the dashboard (data/dashboards.json) a `dashboard` entry opens. */
    val dashboard: String,
    /** #1399 the sub-page id of a non-dashboard entry (`page`, e.g. MY-PM's holdings); a dashboard's page id is its slug. */
    val page: String = "",
    /** #1478 the table (data/pm.json key) a `pm_table` entry draws. */
    val table: String = "",
    /** #1478 a dashboard that is a detail page (a row of the PM Vol table opens it), not a sub-page of the one-row nav. */
    val detail: Boolean = false,
    /** The view of a `cb_table` entry (countries | groups | predictions): the CB page's table. */
    val view: String = "",
)

/** #1399 one card of a section's Homepage: the sub-page it opens, its declared label, icon and one-line description. */
data class HomeCard(val pageId: String, val title: String, val subtitle: String, val icon: String, val entry: Entry)

object Pages {
    /** The kinds the renderer draws; test-lnktree-app.sh holds them against the kinds build.json declares. */
    const val KIND_ENTRY = "entry"
    const val KIND_SECTION_TITLE = "section_title"
    const val KIND_HOLDINGS = "holdings"
    const val KIND_ABOUT = "about"
    const val KIND_DASHBOARD = "dashboard"
    const val KIND_SOURCES = "sources"
    const val KIND_PM_TABLE = "pm_table"
    const val KIND_CB_TABLE = "cb_table"

    /** The entries declared for [sectionId] in the stacks JSON (empty when the section declares none). */
    fun entries(stacks: String, sectionId: String): List<Entry> {
        val arr = runCatching { JSONObject(stacks).optJSONArray(sectionId) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            Entry(
                kind = o.optString("kind", KIND_ENTRY),
                title = o.optString("title"),
                subtitle = o.optString("subtitle"),
                site = o.optString("site"),
                registry = o.optString("registry"),
                repo = o.optString("repo"),
                path = o.optString("path"),
                planned = o.optBoolean("planned", false),
                note = o.optString("note"),
                dashboard = o.optString("dashboard"),
                page = o.optString("page"),
                table = o.optString("table"),
                detail = o.optBoolean("detail", false),
                view = o.optString("view"),
            )
        }
    }

    /**
     * Where [e] leads, read from the declared sources baked by app/build.gradle (UI_TARGETS_B64): holds no address itself.
     * A planned entry, an entry with no source, or a source that resolved to nothing answers null: the entry is greyed.
     */
    fun resolve(e: Entry, targets: String): String? {
        if (e.planned) return null
        val t = runCatching { JSONObject(targets) }.getOrNull() ?: return null
        if (e.site.isNotEmpty()) return t.optJSONObject("sites")?.optString(e.site)?.takeIf { it.isNotBlank() }
        if (e.registry.isNotEmpty()) return t.optJSONObject("registry_sites")?.optString(e.registry)?.takeIf { it.isNotBlank() }
        if (e.repo.isNotEmpty()) {
            val base = t.optString("repo_base")
            if (base.isBlank()) return null
            return base + e.repo + (if (e.path.isNotEmpty()) "/tree/main/" + e.path else "")
        }
        return null
    }

    /** #1399 the target word of ui.redirects for a section's Homepage. */
    const val PAGE_HOME = "home"

    /** #1399 every terminal section's first sub-page, the section's Homepage (page ids are unique per app: `<section>-home`). */
    fun homeId(sectionId: String): String = "$sectionId-home"

    fun isHome(sectionId: String, pageId: String): Boolean = pageId == PAGE_HOME || pageId == homeId(sectionId)

    /** The web slug of a dashboard id (scripts/gen-finterm.mjs slugOf): the dashboard's sub-page id. */
    fun slugOf(id: String): String = id.lowercase().replace('_', '-')

    /** The sub-page [e] is: its declared `page`, else its dashboard's slug; "" = not a sub-page (a section title, About, Sources, a `detail` dashboard). */
    fun pageIdOf(e: Entry): String = e.page.ifEmpty { if (e.kind == KIND_DASHBOARD && e.dashboard.isNotEmpty() && !e.detail) slugOf(e.dashboard) else "" }

    /**
     * The Homepage of a section: one card per declared sub-page after Home (ui.sections[].pages, in order), with the sub-page's
     * label and icon and its stack entry's one-line description. A declared page with no entry is left out (the guard rejects it).
     */
    fun homeCards(pages: List<Triple<String, String, String>>, entries: List<Entry>): List<HomeCard> =
        pages.drop(1).filter { !it.first.endsWith("-home") }.mapNotNull { (id, label, icon) ->
            val e = entries.firstOrNull { pageIdOf(it) == id } ?: return@mapNotNull null
            HomeCard(id, label.ifEmpty { e.title }, e.subtitle, icon, e)
        }

    /** The entry a sub-page id opens, or null (Home, or an unknown id). */
    fun entryOf(entries: List<Entry>, pageId: String): Entry? = entries.firstOrNull { pageIdOf(it) == pageId }

    /** #1399 ui.redirects: a removed dashboard id -> (tab, sub-page) its deep links open now; null = not redirected. */
    fun redirect(redirects: String, dashboard: String): Pair<String, String>? {
        val arr = runCatching { org.json.JSONArray(redirects) }.getOrNull() ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("from") == dashboard) return o.optString("tab") to o.optString("to", PAGE_HOME)
        }
        return null
    }
}
