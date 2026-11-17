package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.apps.PhoneTaxonomyLayout

/**
 * The decisions behind Store ▸ Phone ▸ Search, kept apart from the screen so they can be checked
 * on a plain JVM (ac_cloud-store/test/test-store-search-landing.sh compiles this file and
 * PhoneTaxonomyLayout.kt with kotlinc): no Android type is named here.
 *
 *  - [landing]: the page before any query. One shelf per sub-category (a `ui.phone_folders`
 *    folder) that holds at least one app of OUR list, grouped and ordered exactly as SuperApp ▸
 *    Phone lays the same taxonomy out, because both go through [PhoneTaxonomyLayout.bucket].
 *  - [merge]: every source's hits as ONE result per package, carrying every source's badge. A
 *    GitHub repository has no package name; it joins the package whose F-Droid / IzzyOnDroid
 *    `sourceCode` it is, and stays a link of its own otherwise.
 *  - [groups]: the results under the first declared source that found each, installed first.
 */
object StoreSuggest {

    // ── the landing ─────────────────────────────────────────────────────

    /** One app of our list as a card shows it. [badges] are source labels, in source order. */
    class Suggestion(val pkg: String, val label: String, val badges: List<String>, val installed: Boolean)

    /** A sub-category of the Phone taxonomy: [title] is the folder label without its section prefix. */
    class Shelf(val folderId: String, val title: String, val apps: List<Suggestion>)

    /** A section ([title] null = the folders no section prefix claims, drawn last as "Other"). */
    class Section(val title: String?, val shelves: List<Shelf>)

    /** A folder as the landing needs it: its id and its full label (prefix included). */
    class Folder(val id: String, val label: String)

    /**
     * [sections] (title, prefix) in `ui.phone_sections` order and [folders] in display order, both
     * as the shared taxonomy reads them; [ours] is our list; [folderOf] files one app under a
     * folder id. A folder no app of ours lands in gets no shelf, and a section with no shelf is
     * dropped. Within a shelf, the apps still to install come first, then the installed ones,
     * each by label. A package listed twice is shown once (its first entry wins).
     */
    fun landing(
        sections: List<Pair<String, String>>,
        folders: List<Folder>,
        ours: List<Suggestion>,
        folderOf: (Suggestion) -> String,
    ): List<Section> {
        val byFolder = HashMap<String, MutableList<Suggestion>>()
        val seen = HashSet<String>()
        for (s in ours) {
            if (s.pkg.isBlank() || !seen.add(s.pkg)) continue
            byFolder.getOrPut(folderOf(s)) { mutableListOf() }.add(s)
        }
        val shown = folders.filter { !byFolder[it.id].isNullOrEmpty() }
        return PhoneTaxonomyLayout.bucket(sections, shown) { it.label }.map { g ->
            Section(g.title, g.folders.map { f ->
                Shelf(f.id, f.label.removePrefix(g.prefix).trim().ifEmpty { f.label },
                    byFolder.getValue(f.id).sortedWith(SUGGESTION_ORDER))
            })
        }
    }

    val SUGGESTION_ORDER: Comparator<Suggestion> = compareBy({ it.installed }, { it.label.lowercase() })

    /** Package-name parts that name a platform, a TLD or a build flavour rather than the app. */
    private val GENERIC = setOf(
        "com", "org", "net", "io", "co", "app", "apps", "android", "mobile", "client", "lite", "free",
        "pro", "prod", "release", "main", "gov", "official", "ui",
    )

    /**
     * A readable name for a package nobody has labelled yet and that is not installed (so the
     * PackageManager has no label to give): its last meaningful part, capitalised.
     * `com.google.android.apps.photos` → "Photos", `ai.perplexity.app.android` → "Perplexity".
     */
    fun prettyLabel(pkg: String): String {
        val parts = pkg.split('.').filter { it.isNotBlank() }
        val pick = parts.lastOrNull { it.lowercase() !in GENERIC && it.length > 1 } ?: parts.lastOrNull() ?: pkg
        return pick.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    // ── search ──────────────────────────────────────────────────────────

    /**
     * One source's answer. [pkg] is blank for a hit that has no package name (a GitHub
     * repository); [repo] is the source repository URL when the source knows it (F-Droid and
     * IzzyOnDroid's `sourceCode`, GitHub's own page); [link] is the page to open instead of
     * installing.
     */
    class Hit(
        val source: String, val pkg: String, val title: String, val by: String = "",
        val foss: Boolean = false, val repo: String = "", val link: String = "",
    )

    /** One result per package (or per unmatched repository): [sources] in declared order. */
    class Result(
        val key: String, val pkg: String, val title: String, val by: String,
        val sources: List<String>, val foss: Boolean, val repo: String, val link: String,
    ) {
        val primary: String get() = sources.first()
    }

    /** `https://github.com/A/B.git/` and `http://www.github.com/a/b` are the same repository. */
    fun normaliseRepo(url: String): String = url.trim().lowercase()
        .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        .trimEnd('/').removeSuffix(".git").trimEnd('/')

    /**
     * Every hit of every source in [order], merged by package name: one [Result] per package,
     * its title and author from the first source (in [order]) that has one, FOSS when any source
     * says so. A repository-only hit joins the package result whose [Result.repo] is the same
     * repository; otherwise it is a result of its own keyed `repo:<url>`. Hits from a source not
     * in [order] are ignored — a source must be declared to be shown.
     */
    fun merge(order: List<String>, hits: List<Hit>): List<Result> {
        val rank = order.withIndex().associate { it.value to it.index }
        val ranked = hits.filter { it.source in rank }.sortedBy { rank.getValue(it.source) }
        val byPkg = LinkedHashMap<String, MutableList<Hit>>()
        val repoOnly = mutableListOf<Hit>()
        for (h in ranked) {
            val p = h.pkg.trim()
            if (p.isNotEmpty()) byPkg.getOrPut(p) { mutableListOf() }.add(h) else if (h.repo.isNotBlank()) repoOnly.add(h)
        }
        fun build(key: String, pkg: String, hs: List<Hit>) = Result(
            key = key, pkg = pkg,
            title = hs.firstOrNull { it.title.isNotBlank() }?.title ?: pkg,
            by = hs.firstOrNull { it.by.isNotBlank() }?.by.orEmpty(),
            sources = hs.map { it.source }.distinct().sortedBy { rank.getValue(it) },
            foss = hs.any { it.foss },
            repo = hs.firstOrNull { it.repo.isNotBlank() }?.repo.orEmpty(),
            link = hs.firstOrNull { it.link.isNotBlank() }?.link.orEmpty(),
        )
        val merged = LinkedHashMap<String, MutableList<Hit>>()
        byPkg.forEach { (p, hs) -> merged[p] = hs }
        val byRepo = HashMap<String, String>()
        byPkg.forEach { (p, hs) -> hs.firstOrNull { it.repo.isNotBlank() }?.let { byRepo.putIfAbsent(normaliseRepo(it.repo), p) } }
        val loose = LinkedHashMap<String, MutableList<Hit>>()
        for (h in repoOnly) {
            val r = normaliseRepo(h.repo)
            val p = byRepo[r]
            if (p != null) merged.getValue(p).add(h) else loose.getOrPut(r) { mutableListOf() }.add(h)
        }
        return merged.map { (p, hs) -> build(p, p, hs.sortedBy { rank.getValue(it.source) }) } +
            loose.map { (r, hs) -> build("repo:$r", "", hs) }
    }

    /**
     * [results] under the first source in [order] that found each, every source of [order] keeping
     * its place even when nothing landed under it (the page still shows that source's state).
     * Within a group the installed apps come first — status first — and otherwise the sources'
     * own ranking is kept.
     */
    fun groups(order: List<String>, results: List<Result>, installed: (String) -> Boolean): List<Pair<String, List<Result>>> =
        order.map { src ->
            src to results.filter { it.primary == src }
                .sortedByDescending { it.pkg.isNotEmpty() && installed(it.pkg) }
        }
}
