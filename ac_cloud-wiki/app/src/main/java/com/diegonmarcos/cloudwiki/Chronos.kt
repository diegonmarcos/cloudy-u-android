package com.diegonmarcos.cloudwiki

import org.json.JSONArray
import org.json.JSONObject

/**
 * One Chronos page (#1789): an interactive HTML/JS simulator of diegonmarcos/lib-chronos, baked into the APK at a pinned commit
 * (tools/chronos-pin.json, tools/regen-chronos.py) under assets/chronos and shown in a web view. [asset] is its index.html relative to
 * assets/chronos; [parent] the page above it in the repo's hierarchy (null for the root); [children] the pages below it, in order.
 */
data class ChronosPage(val id: String, val label: String, val icon: String, val asset: String, val parent: String?, val children: List<String>) {
    /** The directory of [asset] (ends in `/`): the page is loaded by its directory so the repo's relative links resolve as on the web. */
    val dir: String get() = asset.substringBeforeLast("index.html")
}

/** The Chronos pages build.json::ui declares under wiki > chronos (nested `pages` carrying an `asset`), indexed by id. Pure. */
class ChronosTree(val pages: List<ChronosPage>) {
    private val byId = pages.associateBy { it.id }
    private val byDir = pages.associateBy { it.dir }

    fun page(id: String?): ChronosPage? = if (id == null) null else byId[id]

    /** The root page (the repo's Chronos/index.html), or null when nothing is declared. */
    val root: ChronosPage? get() = pages.firstOrNull { it.parent == null }

    /** The chain from the root down to [id], outermost first; empty when [id] is not a Chronos page. */
    fun path(id: String?): List<ChronosPage> = generateSequence(page(id)) { page(it.parent) }.toList().asReversed()

    /**
     * The page a URL path of the asset origin shows (`/chronos/Chronos/3.Quantum-Cycles/`, with or without `index.html`), or null when the
     * path is outside the Chronos pages: what the web view loaded after a link inside a page, so the side menu and the title follow it.
     */
    fun pageAt(path: String?): ChronosPage? {
        val p = path?.takeIf { it.startsWith(Chronos.SERVE_PATH) }?.removePrefix(Chronos.SERVE_PATH) ?: return null
        val dir = if (p.endsWith("/")) p else if (p.endsWith("index.html")) p.removeSuffix("index.html") else "$p/"
        return byDir[dir]
    }
}

object Chronos {
    /** Where assets/chronos is served on the asset origin; tools/chronos-pin.json::serve_path says the same (test-chronos.sh). */
    const val SERVE_PATH = "/chronos/"

    /** The assets directory the pages and their vendored files live in. */
    const val ASSETS_DIR = "chronos/"

    /** The side-menu page the Chronos pages hang under, and its section. */
    const val PARENT = "chronos"
    const val SECTION = "wiki"

    /** The path of [page] on the asset origin: its directory under [SERVE_PATH]. */
    fun path(page: ChronosPage): String = SERVE_PATH + page.dir

    /** The asset a request path names (a directory answers its index.html), or null outside [SERVE_PATH] or when it climbs out with `..`. */
    fun assetFor(path: String): String? {
        if (path.split('/').any { it == ".." }) return null
        val rel = path.removePrefix("/")
        return if (rel.isEmpty() || rel.endsWith("/")) rel + "index.html" else rel
    }

    /** The Chronos pages of a ui.sections JSON array (build.json::ui.sections, as BuildConfig bakes it). */
    fun parse(sectionsJson: String): ChronosTree {
        val sections = runCatching { JSONArray(sectionsJson) }.getOrNull() ?: return ChronosTree(emptyList())
        val wiki = objects(sections).firstOrNull { it.optString("id") == SECTION } ?: return ChronosTree(emptyList())
        val parent = objects(wiki.optJSONArray("pages")).firstOrNull { it.optString("id") == PARENT } ?: return ChronosTree(emptyList())
        val out = ArrayList<ChronosPage>()
        fun walk(a: JSONArray?, up: String?) {
            for (o in objects(a)) {
                val asset = o.optString("asset")
                if (asset.isBlank()) continue
                val kids = objects(o.optJSONArray("pages")).filter { it.optString("asset").isNotBlank() }.map { it.getString("id") }
                out += ChronosPage(o.getString("id"), o.optString("label", o.getString("id")), o.optString("icon"), asset, up, kids)
                walk(o.optJSONArray("pages"), o.getString("id"))
            }
        }
        walk(parent.optJSONArray("pages"), null)
        return ChronosTree(out)
    }

    private fun objects(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
}

/**
 * #1830 the side menu's wiki section with the Chronos pages of [tree] (the runtime clone, or the baked copy) under [Chronos.PARENT]:
 * the declaration's other pages and groups are untouched; only the Chronos page's nested pages follow the repo.
 */
fun withChronos(section: com.diegonmarcos.superapp.bottomnav.NavSection, tree: ChronosTree): com.diegonmarcos.superapp.bottomnav.NavSection {
    if (tree.root == null) return section
    fun nav(p: ChronosPage): com.diegonmarcos.superapp.bottomnav.NavPage =
        com.diegonmarcos.superapp.bottomnav.NavPage(p.id, p.label, p.icon, pages = p.children.mapNotNull { tree.page(it) }.map(::nav))
    // #1831 flat under the Chronos topic: every top-level page of the tree (the root first), deeper pages nested under their section
    val top = tree.pages.filter { it.parent == null }.map(::nav)
    return section.copy(pages = section.pages.map { if (it.id == Chronos.PARENT) it.copy(pages = top) else it })
}
