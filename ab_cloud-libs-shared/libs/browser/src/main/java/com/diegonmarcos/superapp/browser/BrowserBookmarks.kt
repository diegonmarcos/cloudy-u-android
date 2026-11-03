package com.diegonmarcos.superapp.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One bookmark. [folder] is a slash path ("search/engines"), "" for the top level. */
data class BrowserBookmark(val url: String, val title: String, val folder: String = "", val ts: Long = 0L)

/**
 * #802 the bookmark rules, pure: one bookmark per URL (adding again updates it),
 * folders are slash paths, and a folder op moves or removes its whole subtree.
 * JVM-tested; [BrowserBookmarks] only persists what these return.
 */
object BrowserBookmarkOps {

    fun normFolder(f: String): String = f.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")

    /** Add, or update the title/folder of the bookmark already there for [b].url. */
    fun add(list: List<BrowserBookmark>, b: BrowserBookmark): List<BrowserBookmark> =
        listOf(b.copy(folder = normFolder(b.folder))) + list.filterNot { it.url == b.url }

    fun remove(list: List<BrowserBookmark>, url: String) = list.filterNot { it.url == url }

    /** Every folder in use, parents included, sorted. */
    fun folders(list: List<BrowserBookmark>): List<String> = list.flatMap { b ->
        val parts = b.folder.split('/').filter { it.isNotEmpty() }
        parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
    }.distinct().sorted()

    private fun inFolder(f: String, folder: String) = f == folder || f.startsWith("$folder/")

    /** Rename/move [from] (and everything under it) to [to]. */
    fun moveFolder(list: List<BrowserBookmark>, from: String, to: String): List<BrowserBookmark> {
        val a = normFolder(from); val b = normFolder(to)
        if (a.isEmpty()) return list
        return list.map { if (inFolder(it.folder, a)) it.copy(folder = normFolder(b + it.folder.removePrefix(a))) else it }
    }

    /** Delete [folder] and every bookmark in it or under it. */
    fun deleteFolder(list: List<BrowserBookmark>, folder: String): List<BrowserBookmark> {
        val a = normFolder(folder)
        return if (a.isEmpty()) list else list.filterNot { inFolder(it.folder, a) }
    }

    fun toJson(list: List<BrowserBookmark>): JSONArray = JSONArray().also { arr ->
        list.forEach { arr.put(JSONObject().put("url", it.url).put("title", it.title).put("folder", it.folder).put("ts", it.ts)) }
    }

    fun fromJson(raw: String?): List<BrowserBookmark> {
        val arr = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("url")
            if (u.isBlank()) null else BrowserBookmark(u, o.optString("title", u), o.optString("folder"), o.optLong("ts"))
        }
    }
}

/** #802 bookmarks, persisted in the `browser_bookmarks` prefs (declared, class config: they move to a new phone). */
class BrowserBookmarks(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("browser_bookmarks", Context.MODE_PRIVATE)

    fun all(): List<BrowserBookmark> = BrowserBookmarkOps.fromJson(sp.getString(KEY, "[]"))
    fun has(url: String) = all().any { it.url == url }
    fun add(url: String, title: String, folder: String = "") =
        save(BrowserBookmarkOps.add(all(), BrowserBookmark(url, title.ifBlank { url }, folder, System.currentTimeMillis())))
    fun remove(url: String) = save(BrowserBookmarkOps.remove(all(), url))
    fun folders() = BrowserBookmarkOps.folders(all())
    fun moveFolder(from: String, to: String) = save(BrowserBookmarkOps.moveFolder(all(), from, to))
    fun deleteFolder(folder: String) = save(BrowserBookmarkOps.deleteFolder(all(), folder))

    /** #893 apply the app's seed once per entry; what was applied is remembered, so a deleted seed link stays deleted. */
    fun applySeed(seed: FavSeed) {
        if (seed.items.isEmpty()) return
        val seen = sp.getStringSet(SEEN, emptySet()).orEmpty()
        val (list, nowSeen) = BrowserFavourites.merge(all(), seed, seen, System.currentTimeMillis())
        if (nowSeen == seen && sp.getInt(SEED_VERSION, 0) >= seed.version) return
        sp.edit().putString(KEY, BrowserBookmarkOps.toJson(list).toString()).putStringSet(SEEN, nowSeen).putInt(SEED_VERSION, seed.version).apply()
    }

    /** #893 list | grid, persisted. */
    fun viewMode(): String = BrowserFavourites.normView(sp.getString(VIEW, null))
    fun setViewMode(v: String) { sp.edit().putString(VIEW, BrowserFavourites.normView(v)).apply() }

    /** #1422 the collapsed Favourites groups (folder names), persisted. */
    fun collapsed(): Set<String> = sp.getStringSet(COLLAPSED, emptySet()).orEmpty().toSet()
    fun toggleCollapsed(folder: String) { sp.edit().putStringSet(COLLAPSED, BrowserFavourites.toggle(collapsed(), folder)).apply() }

    /** #893 long-press ▸ edit / move: the title and folder of the bookmark at [url]. */
    fun edit(url: String, title: String, folder: String) {
        val cur = all().firstOrNull { it.url == url } ?: return
        save(all().map { if (it.url == url) cur.copy(title = title.ifBlank { url }, folder = BrowserBookmarkOps.normFolder(folder)) else it })
    }

    private fun save(list: List<BrowserBookmark>) {
        sp.edit().putString(KEY, BrowserBookmarkOps.toJson(list).toString()).apply()
    }

    private companion object {
        const val KEY = "bookmarks_json"; const val SEEN = "seed_seen"; const val SEED_VERSION = "seed_version"; const val VIEW = "view"; const val COLLAPSED = "collapsed_groups"
    }
}
