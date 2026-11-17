package com.diegonmarcos.superapp.appstore

import org.json.JSONObject

/**
 * Store > Search > Phone as four subpages (#1774): Open source, Non open source, Play Integrity, Games. Each has the search bar on
 * top and the icon grid (#1772) below. Pure part: which sources a subpage runs, which results it keeps, the Play Integrity tier
 * dataset and the games catalogue (both runtime fleet datasets, never baked into an APK), and the free / paid badge.
 *
 * Nothing is dropped by the split: the union of the subpages' sources is every Phone source, every result row keeps its verbs
 * (StoreSearchPage.ResultRow is the one row all four draw), and the fleet's own packages stay excluded.
 */
object StorePhoneSearch {
    const val OPEN = "phone-open"
    const val CLOSED = "phone-closed"
    const val INTEGRITY = "phone-integrity"
    const val GAMES = "phone-games"
    val SUBS = listOf(OPEN, CLOSED, INTEGRITY, GAMES)

    const val DATASET_INTEGRITY = "store-play-integrity"
    const val DATASET_GAMES = "games-catalogue"

    fun label(sub: String): String = when (sub) { OPEN -> "Open source"; CLOSED -> "Non open source"; INTEGRITY -> "Play Integrity"; else -> "Games" }
    fun tag(sub: String) = "store-search:phone-sub:$sub"

    fun blurb(sub: String): String = when (sub) {
        OPEN -> "Free-software apps: F-Droid, IzzyOnDroid and GitHub, plus the apps you declared that are open source. Not in the fleet."
        CLOSED -> "Apps that are not open source, free or paid, from Google Play through Aurora's anonymous session and your declared list. Each shows a Free or Paid badge when Play says so."
        INTEGRITY -> "Google Play apps grouped by the Play Integrity tier they need: Basic, Device or Strong. Tiers come from a fleet dataset; Unknown means nobody has recorded one."
        else -> "Games from the games catalogue and the same sources. Results install through the Store's install path."
    }

    /** The source ids (StoreSearchPage search sources) each subpage runs. Together they are every Phone source. */
    fun sources(sub: String): Set<String> = when (sub) {
        OPEN -> setOf(StoreSearchPage.DECLARED, SourceResolver.KIND_FDROID, SourceResolver.KIND_IZZY, StoreSearchPage.GITHUB)
        CLOSED -> setOf(StoreSearchPage.DECLARED, StoreSearchPage.PLAY)
        INTEGRITY -> setOf(StoreSearchPage.PLAY)
        else -> setOf(StoreSearchPage.DECLARED, SourceResolver.KIND_FDROID, SourceResolver.KIND_IZZY, StoreSearchPage.PLAY)
    }

    private val OPEN_SOURCES = setOf(SourceResolver.KIND_FDROID, SourceResolver.KIND_IZZY, StoreSearchPage.GITHUB)

    /** Is this result free software: any source said so, or it came from a free-software catalogue / a repository. */
    fun isOpen(r: StoreSuggest.Result): Boolean = r.foss || r.sources.any { it in OPEN_SOURCES }

    /** Whether [sub] shows [r]. Open: free software. Non open: not free software, from Play or the declared list. Integrity: a Google Play app. Games: a catalogue game. */
    fun keep(sub: String, r: StoreSuggest.Result, gamePkgs: Set<String>): Boolean = when (sub) {
        OPEN -> isOpen(r)
        CLOSED -> !isOpen(r) && r.pkg.isNotEmpty()
        INTEGRITY -> StoreSearchPage.PLAY in r.sources && r.pkg.isNotEmpty()
        else -> r.pkg in gamePkgs
    }

    /** The free / paid badge of a Play listing; "" = the listing did not say (no claim is made). */
    fun priceBadge(price: String): String = price.trim()

    /** The Non open source tile badge: price first, then the source label. */
    fun closedBadge(r: StoreSuggest.Result, sourceLabel: (String) -> String): String =
        listOf(priceBadge(r.price), sourceLabel(r.primary)).filter { it.isNotEmpty() }.joinToString(" · ")

    // ── Play Integrity tiers ──────────────────────────────────────────

    class Tier(val id: String, val label: String, val verdict: String, val meaning: String)
    class IntApp(val pkg: String, val name: String, val tier: String, val basis: String, val note: String)
    class Integrity(val tiers: List<Tier>, val apps: List<IntApp>, val problems: List<String> = emptyList())

    const val UNKNOWN = "unknown"
    /** The tiers the page always draws, in order, whatever the dataset declares (an undeclared one is added with its id as label). */
    val DEFAULT_TIERS = listOf(Tier("basic", "Basic", "MEETS_BASIC_INTEGRITY", ""), Tier("device", "Device", "MEETS_DEVICE_INTEGRITY", ""),
        Tier("strong", "Strong", "MEETS_STRONG_INTEGRITY", ""), Tier(UNKNOWN, "Unknown", "", ""))

    /** Parses the served dataset; never throws, a bad document is an empty list that says why. */
    fun parseIntegrity(text: String?): Integrity {
        if (text.isNullOrBlank()) return Integrity(DEFAULT_TIERS, emptyList(), listOf("not served yet"))
        val root = runCatching { JSONObject(text) }.getOrElse { return Integrity(DEFAULT_TIERS, emptyList(), listOf("not JSON")) }
        val tiers = ArrayList<Tier>(); val apps = ArrayList<IntApp>(); val problems = ArrayList<String>()
        root.optJSONArray("tiers")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
            val id = o.optString("id").trim()
            if (id.isNotEmpty() && tiers.none { it.id == id }) tiers += Tier(id, o.optString("label").ifBlank { id }, o.optString("verdict"), o.optString("meaning"))
        } }
        root.optJSONArray("apps")?.let { a -> for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val pkg = o.optString("pkg").trim()
            if (pkg.isEmpty() || apps.any { it.pkg == pkg }) { problems += "entry ${i + 1}: no package or duplicate"; continue }
            apps += IntApp(pkg, o.optString("name").ifBlank { pkg }, o.optString("tier").ifBlank { UNKNOWN }, o.optString("basis").ifBlank { "unverified" }, o.optString("note"))
        } }
        val declared = if (tiers.isEmpty()) DEFAULT_TIERS else tiers
        return Integrity(declared, apps, problems)
    }

    /** The tier of [pkg]: its dataset row's tier when that is a declared tier, else [UNKNOWN] (an app not listed is unknown, never a guess). */
    fun tierOf(data: Integrity?, pkg: String): String {
        val t = data?.apps?.firstOrNull { it.pkg == pkg }?.tier ?: return UNKNOWN
        return if (data?.tiers?.any { it.id == t } == true) t else UNKNOWN
    }

    /** [items] grouped by tier in the dataset's declared order, every declared tier kept (empty ones included), an unknown tier last. */
    fun <T> groupByTier(data: Integrity?, items: List<T>, pkgOf: (T) -> String): List<Pair<Tier, List<T>>> {
        val tiers = (data?.tiers ?: DEFAULT_TIERS).let { ts -> if (ts.any { it.id == UNKNOWN }) ts else ts + Tier(UNKNOWN, "Unknown", "", "") }
        val ordered = tiers.filter { it.id != UNKNOWN } + tiers.filter { it.id == UNKNOWN }
        return ordered.map { t -> t to items.filter { tierOf(data, pkgOf(it)) == t.id } }
    }

    /** The dataset's apps as Play results (never fleet apps) for the pre-query view. */
    fun integrityResults(data: Integrity, fleetPackages: Set<String>): List<StoreSuggest.Result> =
        data.apps.filter { it.pkg !in fleetPackages }.map { StoreSuggest.Result("int:${it.pkg}", it.pkg, it.name, "", listOf(StoreSearchPage.PLAY), false, "", "") }

    /** The detail line of an app's tier: what the dataset says and how sure it is. */
    fun tierLine(data: Integrity?, pkg: String): String {
        val a = data?.apps?.firstOrNull { it.pkg == pkg }
        val t = tierOf(data, pkg)
        val label = data?.tiers?.firstOrNull { it.id == t }?.label ?: DEFAULT_TIERS.first { it.id == t }.label
        return if (a == null) "Play Integrity: $label (not in the dataset)" else "Play Integrity: $label (${a.basis})" + a.note.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
    }

    // ── Games ─────────────────────────────────────────────────────────

    class Game(val pkg: String, val name: String, val developer: String, val desc: String, val category: String, val source: String, val icon: String)
    class Games(val categories: List<StoreRecs.Category>, val games: List<Game>, val problems: List<String> = emptyList())

    fun parseGames(text: String?): Games {
        if (text.isNullOrBlank()) return Games(emptyList(), emptyList(), listOf("not served yet"))
        val root = runCatching { JSONObject(text) }.getOrElse { return Games(emptyList(), emptyList(), listOf("not JSON")) }
        val cats = ArrayList<StoreRecs.Category>(); val games = ArrayList<Game>(); val problems = ArrayList<String>()
        root.optJSONArray("categories")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
            val id = o.optString("id").trim(); if (id.isNotEmpty() && cats.none { it.id == id }) cats += StoreRecs.Category(id, o.optString("label").ifBlank { id })
        } }
        root.optJSONArray("games")?.let { a -> for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val pkg = o.optString("pkg").trim()
            if (pkg.isEmpty() || games.any { it.pkg == pkg }) { problems += "entry ${i + 1}: no package or duplicate"; continue }
            games += Game(pkg, o.optString("name").ifBlank { pkg }, o.optString("developer"), o.optString("desc"), o.optString("category").ifBlank { "other" },
                o.optString("source").ifBlank { StoreSearchPage.PLAY }, o.optString("icon"))
        } }
        return Games(cats, games, problems)
    }

    /** The games a phone shows: fleet packages dropped. */
    fun games(g: Games, fleetPackages: Set<String>): List<Game> = g.games.filter { it.pkg !in fleetPackages }

    /** Games grouped by declared category (an undeclared category last, under its own id). */
    fun gamesByCategory(g: Games, list: List<Game>): List<Pair<StoreRecs.Category, List<Game>>> {
        val ids = g.categories.map { it.id } + list.map { it.category }.filter { c -> g.categories.none { it.id == c } }.distinct()
        return ids.mapNotNull { id ->
            val l = list.filter { it.category == id }
            if (l.isEmpty()) null else (g.categories.firstOrNull { it.id == id } ?: StoreRecs.Category(id, id.replaceFirstChar { it.uppercase() })) to l
        }
    }

    /** A catalogue game as a result: its catalogue source (play | fdroid) with the store icon. */
    fun gameResult(g: Game): StoreSuggest.Result =
        StoreSuggest.Result("game:${g.pkg}", g.pkg, g.name, g.developer, listOf(g.source), g.source == SourceResolver.KIND_FDROID, "", "", g.icon)

    /** Catalogue games whose name, developer or package matches [query] (case-insensitive). */
    fun matchGames(list: List<Game>, query: String): List<Game> {
        val q = query.trim().lowercase()
        return if (q.isEmpty()) emptyList() else list.filter { q in it.name.lowercase() || q in it.developer.lowercase() || q in it.pkg.lowercase() }
    }
}
