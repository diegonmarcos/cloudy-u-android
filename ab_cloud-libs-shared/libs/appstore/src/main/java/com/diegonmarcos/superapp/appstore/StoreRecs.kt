package com.diegonmarcos.superapp.appstore

import org.json.JSONObject
import java.net.URI

/**
 * Store > Search recommendations (#1749), the pure part: what each subpage suggests before a query is typed.
 *
 *  - Phone: curated open-source Android apps by category. The list is a runtime fleet dataset ([DATASET_PHONE], served by Cloud
 *    Store through [dataset]), never baked into an APK; every fleet package is removed from it ([phoneApps]).
 *  - Torrent: the declared catalogue of legitimately distributable torrents in the twelve [TORRENT_CATEGORIES], filtered by
 *    [legit] (licence + source allowlist) and cut to the top [TOP] per category by seeders ([top]). Nothing outside the declared,
 *    allowlisted catalogue is ever recommended; a typed search still shows whatever the user's sources return.
 */
object StoreRecs {
    const val DATASET_PHONE = "store-phone-recommendations"
    const val TOP = 10

    /** Reads a fleet dataset's JSON text by name (blocking, null = not served yet). Cloud Store sets it; an embedding host leaves it null. */
    @Volatile var dataset: ((String) -> String?)? = null

    // ── Phone ─────────────────────────────────────────────────────────

    class PhoneApp(val pkg: String, val name: String, val category: String, val source: String, val licence: String)
    class Category(val id: String, val label: String)
    class PhoneRecs(val categories: List<Category>, val apps: List<PhoneApp>, val problems: List<String> = emptyList())

    /** Parses the served dataset; never throws, a bad document is an empty list that says why. */
    fun parsePhone(text: String?): PhoneRecs {
        if (text.isNullOrBlank()) return PhoneRecs(emptyList(), emptyList(), listOf("not served yet"))
        val root = runCatching { JSONObject(text) }.getOrElse { return PhoneRecs(emptyList(), emptyList(), listOf("not JSON")) }
        val cats = ArrayList<Category>(); val apps = ArrayList<PhoneApp>(); val problems = ArrayList<String>()
        root.optJSONArray("categories")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
            val id = o.optString("id").trim(); if (id.isNotEmpty() && cats.none { it.id == id }) cats += Category(id, o.optString("label").ifBlank { id })
        } }
        root.optJSONArray("apps")?.let { a -> for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val pkg = o.optString("pkg").trim()
            if (pkg.isEmpty() || apps.any { it.pkg == pkg }) { problems += "entry ${i + 1}: no package or duplicate"; continue }
            apps += PhoneApp(pkg, o.optString("name").ifBlank { pkg }, o.optString("category").ifBlank { "other" }, o.optString("source").ifBlank { "fdroid" }, o.optString("licence"))
        } }
        return PhoneRecs(cats, apps, problems)
    }

    /** The Phone page recommends non-fleet apps only: every package in [fleetPackages] is dropped. */
    fun phoneApps(recs: PhoneRecs, fleetPackages: Set<String>): List<PhoneApp> = recs.apps.filter { it.pkg !in fleetPackages }

    /** The apps grouped by declared category, in declared order; an undeclared category goes last under its own id. */
    fun phoneByCategory(recs: PhoneRecs, apps: List<PhoneApp>): List<Pair<Category, List<PhoneApp>>> {
        val ids = recs.categories.map { it.id } + apps.map { it.category }.filter { c -> recs.categories.none { it.id == c } }.distinct()
        return ids.mapNotNull { id ->
            val list = apps.filter { it.category == id }
            if (list.isEmpty()) null else (recs.categories.firstOrNull { it.id == id } ?: Category(id, id.replaceFirstChar { it.uppercase() })) to list
        }
    }

    // ── Torrent ───────────────────────────────────────────────────────

    /** The owner's twelve categories, in display order ("android" keeps its id; the label is Android Apps). */
    val TORRENT_CATEGORIES: List<Category> = listOf(
        Category("movies", "Movies"), Category("tv", "TV Shows"), Category("games", "PC Games"), Category("anime", "Anime"),
        Category("software", "Software & Applications"), Category("ebooks", "E-Books"), Category("audiobooks", "Audiobooks"),
        Category("music", "Music"), Category("os", "Operating Systems"), Category("comics", "Comics & Graphic Novels"),
        Category("courses", "E-Learning & Video Courses"), Category("android", "Android Apps"),
    )

    /** One recommended torrent. [key] is the host's handle for the download. */
    class Rec(
        val id: String, val name: String, val category: String, val sizeBytes: Long, val licence: String, val publisher: String,
        val sourcePage: String, val torrentUrl: String?, val magnet: String?, val infoHash: String?, val key: Any? = null,
        /** Cover image URL from the catalogue (#1772), loaded at run time; empty = the generated category tile. */
        val image: String = "",
    )

    /** Seeders / leechers read live from a source; -1 = no source said. */
    class Stat(val seeders: Int, val leechers: Int, val sourceLabel: String)

    /** Licences a recommendation may carry: public domain, Creative Commons and open-source / free-software licences. */
    private val LICENCE_OK = listOf("public domain", "cc0", "cc by", "creative commons", "gpl", "lgpl", "agpl", "apache", "mpl", "mit", "bsd",
        "isc", "free and open source", "dfsg", "open source", "free software", "free licence", "openly licensed", "official free")
    /** Words that disqualify an entry whatever else its licence says. */
    private val LICENCE_BAD = listOf("crack", "keygen", "repack", "pre-activated", "preactivated", "warez", "all rights reserved", "proprietary", "trial", "activat", "leaked", "nulled")

    /** Hosts a recommendation's .torrent and source page may come from: publishers' own sites and open archives. */
    val SOURCE_HOSTS: List<String> = listOf(
        "archive.org", "gutenberg.org", "standardebooks.org", "librivox.org", "jamendo.com", "freemusicarchive.org", "blender.org",
        "ocw.mit.edu", "cs50.harvard.edu", "cs50.io", "f-droid.org", "documentfoundation.org", "videolan.org", "gimp.org",
        "ubuntu.com", "debian.org", "fedoraproject.org", "linuxmint.com", "archlinux.org", "kali.org", "opensuse.org", "freebsd.org",
        "openbsd.org", "netbsd.org", "tails.net", "manjaro.org", "pop-os.org", "elementary.io", "gentoo.org", "alpinelinux.org",
        "almalinux.org", "rockylinux.org", "kde.org", "gnome.org", "mozilla.org", "peppercarrot.com", "wikimedia.org", "kiwix.org",
    )

    private fun has(text: String, phrase: String, whole: Boolean = false) =
        Regex("(^|[^a-z0-9])" + Regex.escape(phrase) + if (whole) "($|[^a-z0-9])" else "").containsMatchIn(text)

    private fun hostOf(url: String): String? = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()
    private fun hostAllowed(h: String) = SOURCE_HOSTS.any { h == it || h.endsWith(".$it") }

    /** Why [r] is not recommendable, or null when it is: a licence on the allowlist, no disqualifying word, only allowlisted source hosts. */
    fun whyNot(r: Rec): String? {
        val lic = r.licence.lowercase().trim()
        if (lic.isEmpty()) return "no licence declared"
        LICENCE_BAD.firstOrNull { has(lic, it) || has(r.name.lowercase(), it) }?.let { return "disqualifying word '$it'" }
        if (LICENCE_OK.none { has(lic, it, whole = true) }) return "licence '${r.licence}' is not on the allowlist"
        val hosts = listOfNotNull(r.torrentUrl, r.sourcePage.ifBlank { null }).map { it to hostOf(it) }
        if (hosts.isEmpty()) return "no source declared"
        hosts.firstOrNull { it.second == null || !hostAllowed(it.second!!) }?.let { return "source ${it.second ?: it.first} is not on the allowlist" }
        if (r.torrentUrl == null && r.magnet == null) return "no download"
        return null
    }

    fun legit(r: Rec): Boolean = whyNot(r) == null

    /**
     * The recommendations: [recs] filtered by [legit], grouped under the twelve [TORRENT_CATEGORIES] (an empty category is kept, so the
     * page can say so), each sorted by live seeders (unknown last, then larger first by name) and cut to [TOP]. [stats] is keyed by info-hash.
     */
    fun top(recs: List<Rec>, stats: Map<String, Stat> = emptyMap(), n: Int = TOP): List<Pair<Category, List<Rec>>> {
        val ok = recs.filter(::legit)
        fun seeds(r: Rec) = r.infoHash?.let { stats[it.lowercase()]?.seeders } ?: -1
        return TORRENT_CATEGORIES.map { c ->
            c to ok.filter { it.category == c.id }.sortedWith(compareByDescending<Rec> { seeds(it) }.thenBy { it.name.lowercase() }).take(n)
        }
    }
}
