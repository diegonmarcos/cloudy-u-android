package com.diegonmarcos.hardtechcad

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
    /** #1404 a `library` entry: the id of ui.libraries it opens. */
    val library: String = "",
)

object Pages {
    /** The kinds the renderer draws; test-lnktree-app.sh holds them against the kinds build.json declares. */
    const val KIND_ENTRY = "entry"
    const val KIND_SECTION_TITLE = "section_title"
    const val KIND_HOLDINGS = "holdings"
    const val KIND_ABOUT = "about"
    /** #1404 a model-library sub-page (ui.libraries[library]). */
    const val KIND_LIBRARY = "library"

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
                library = o.optString("library"),
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
}
