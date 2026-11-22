package com.diegonmarcos.superapp.browser

import android.content.Context
import com.diegonmarcos.cloudlib.browsercore.BrowserCore
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

    fun fromJson(raw: String?): List<BrowserBookmark> = fromArray(runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray()))

    /** #1841 the rows of a core answer (`bookmarks`, a section's `items`). */
    fun fromArray(arr: JSONArray?): List<BrowserBookmark> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("url")
            if (u.isBlank()) null else BrowserBookmark(u, o.optString("title", u), o.optString("folder"), o.optLong("ts"))
        }
    }
}

/**
 * #802 bookmarks, persisted in the `browser_bookmarks` prefs (declared, class config: they move to a new phone).
 * #1841 the list logic (one per URL, folder paths, folder moves and deletes, the seed, sections, filter, import / export) is the browser
 * core's (libs:browser-core-rs lists.rs; its Kotlin twin KtLists when the library is not loaded): this class reads the stored text, hands it
 * to one core op and stores what comes back. The text keeps its key and schema; [BrowserListMigration] normalises it once, with a backup.
 * [BrowserBookmarkOps] above stays as the readable rules and the parity reference.
 */
class BrowserBookmarks(context: Context) {
    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("browser_bookmarks", Context.MODE_PRIVATE)

    private fun raw(): String {
        BrowserListMigration.ensure(ctx, "bookmarks", "browser_bookmarks", KEY)
        return sp.getString(KEY, "[]") ?: "[]"
    }

    /** One core op over the stored list. */
    private fun op(name: String, args: JSONObject.() -> Unit = {}): JSONObject = BrowserCore.lists(name, JSONObject().put("raw", raw()).apply(args))

    fun all(): List<BrowserBookmark> = BrowserBookmarkOps.fromArray(op("bm_load").optJSONArray("bookmarks"))
    fun has(url: String) = op("bm_has") { put("url", url) }.optBoolean("has")
    fun add(url: String, title: String, folder: String = "") = save(op("bm_add") { put("url", url).put("title", title).put("folder", folder).put("now", System.currentTimeMillis()) })
    fun remove(url: String) = save(op("bm_remove") { put("url", url) })
    fun folders(): List<String> = op("bm_folders").optJSONArray("folders").let { a -> if (a == null) emptyList<String>() else (0 until a.length()).map { a.optString(it) } }
    fun moveFolder(from: String, to: String) = save(op("bm_move_folder") { put("from", from).put("to", to) })
    fun deleteFolder(folder: String) = save(op("bm_delete_folder") { put("folder", folder) })

    /** Folder -> bookmarks, the top level first (the Fav page's sections), from the core. */
    fun sections(): List<Pair<String, List<BrowserBookmark>>> = BrowserFavourites.decodeSections(op("bm_sections").optJSONArray("sections"))

    /** #1841 the bookmarks as a JSON document (format, version, count, bookmarks); [importJson] reads it (or a bare array) back. */
    fun exportJson(): String = op("bm_export").getJSONObject("doc").toString(2)

    /** Merge a document into the list: a newer row replaces, new rows are added. The counts, or `{"error": code}` (nothing is stored). */
    fun importJson(text: String): JSONObject {
        val r = op("bm_import") { put("text", text) }
        if (!r.has("error")) save(r)
        return r
    }

    /** #893 apply the app's seed once per entry; what was applied is remembered, so a deleted seed link stays deleted. */
    fun applySeed(seed: FavSeed) {
        if (seed.items.isEmpty()) return
        val seen = sp.getStringSet(SEEN, emptySet()).orEmpty()
        val r = op("bm_merge_seed") {
            put("seed", JSONObject().put("version", seed.version)
                .put("items", JSONArray().also { a -> seed.items.forEach { a.put(JSONObject().put("title", it.title).put("url", it.url).put("group", it.group)) } })
                .put("legacyGroups", JSONArray(seed.legacyGroups.toList())))
            put("seen", JSONArray(seen.toList())).put("now", System.currentTimeMillis()).put("stored_version", sp.getInt(SEED_VERSION, 0))
        }
        if (!r.optBoolean("changed")) return
        val nowSeen = r.optJSONArray("seen").let { a -> if (a == null) emptySet<String>() else (0 until a.length()).map { a.optString(it) }.toSet() }
        sp.edit().putString(KEY, r.getString("raw")).putStringSet(SEEN, nowSeen).putInt(SEED_VERSION, seed.version).apply()
    }

    /** #893 list | grid, persisted. */
    fun viewMode(): String = BrowserFavourites.normView(sp.getString(VIEW, null))
    fun setViewMode(v: String) { sp.edit().putString(VIEW, BrowserFavourites.normView(v)).apply() }

    /** #1422 the collapsed Favourites groups (folder names), persisted. */
    fun collapsed(): Set<String> = sp.getStringSet(COLLAPSED, emptySet()).orEmpty().toSet()
    fun toggleCollapsed(folder: String) { sp.edit().putStringSet(COLLAPSED, BrowserFavourites.toggle(collapsed(), folder)).apply() }

    /** #893 long-press ▸ edit / move: the title and folder of the bookmark at [url]. */
    fun edit(url: String, title: String, folder: String) = save(op("bm_edit") { put("url", url).put("title", title).put("folder", folder) })

    private fun save(answer: JSONObject) {
        sp.edit().putString(KEY, answer.getString("raw")).apply()
    }

    private companion object {
        const val KEY = "bookmarks_json"; const val SEEN = "seed_seen"; const val SEED_VERSION = "seed_version"; const val VIEW = "view"; const val COLLAPSED = "collapsed_groups"
    }
}
