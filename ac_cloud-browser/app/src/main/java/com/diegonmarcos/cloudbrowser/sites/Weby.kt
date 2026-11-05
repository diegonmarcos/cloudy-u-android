package com.diegonmarcos.cloudbrowser.sites

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One site tile. [url] null = the declaration knows no web address for it: the tile is drawn greyed and does nothing. */
data class WebyTile(val id: String, val label: String, val url: String?)

/** A launcher folder: tiles, then sub-folders. */
data class WebyFolder(val title: String, val tiles: List<WebyTile>, val folders: List<WebyFolder> = emptyList())

/**
 * #1493 Weby, pure (JVM-tested in WebyTest). The seed is DERIVED from the Cloud SuperApp phone-apps declaration
 * (build.json ui.sections[phone].phone_app_groups: groups, their packages and their folders) joined, by package, with the
 * web address each app has in data/phone-webapps.json (#1466). Group = folder, a group's folder = a sub-folder, in the
 * declared order; nothing is typed here.
 */
object WebySeed {
    /** [decl] = {groups: phone_app_groups, webapps: phone-webapps.json's groups}. */
    fun derive(decl: JSONObject): List<WebyFolder> {
        val web = HashMap<String, Pair<String, String?>>()
        val wg = decl.optJSONArray("webapps") ?: JSONArray()
        for (i in 0 until wg.length()) {
            val apps = wg.optJSONObject(i)?.optJSONArray("apps") ?: continue
            for (j in 0 until apps.length()) {
                val a = apps.optJSONObject(j) ?: continue
                web.putIfAbsent(a.optString("pkg"), a.optString("label") to a.optString("url").takeIf { it.isNotBlank() && it != "null" })
            }
        }
        fun tiles(arr: JSONArray?): List<WebyTile> = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val p = arr?.opt(i)
            val pkg = if (p is JSONObject) p.optString("pkg") else (p as? String).orEmpty()
            if (pkg.isBlank()) return@mapNotNull null
            val label = (p as? JSONObject)?.optString("label")?.takeIf { it.isNotBlank() } ?: web[pkg]?.first ?: pkg
            WebyTile(pkg, label, web[pkg]?.second)
        }
        val groups = decl.optJSONArray("groups") ?: JSONArray()
        return (0 until groups.length()).mapNotNull { i ->
            val g = groups.optJSONObject(i) ?: return@mapNotNull null
            val subs = g.optJSONArray("folders")
            WebyFolder(g.optString("title"), tiles(g.optJSONArray("packages")),
                (0 until (subs?.length() ?: 0)).mapNotNull { k -> subs?.optJSONObject(k)?.let { WebyFolder(it.optString("label"), tiles(it.optJSONArray("packages"))) } })
        }
    }
}

/** The user's edits, pure: add / remove a bookmark or a folder, addressed by a "/"-joined folder path. */
object WebyOps {
    fun normFolder(path: String) = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }

    /** "example.org" -> https://example.org ; blank or hostless -> null. */
    fun normalizeUrl(input: String): String? {
        val s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val full = if ("://" in s) s else "https://$s"
        val host = runCatching { java.net.URI(full).host }.getOrNull()
        return full.takeIf { !host.isNullOrBlank() && (full.startsWith("http://") || full.startsWith("https://")) }
    }

    private fun edit(list: List<WebyFolder>, path: List<String>, create: Boolean, f: (WebyFolder) -> WebyFolder): List<WebyFolder> {
        if (path.isEmpty()) return list
        val head = path.first()
        val rest = path.drop(1)
        val idx = list.indexOfFirst { it.title == head }
        if (idx < 0) {
            if (!create) return list
            val fresh = WebyFolder(head, emptyList())
            return list + if (rest.isEmpty()) f(fresh) else fresh.copy(folders = edit(emptyList(), rest, true, f))
        }
        return list.mapIndexed { i, x -> if (i != idx) x else if (rest.isEmpty()) f(x) else x.copy(folders = edit(x.folders, rest, create, f)) }
    }

    /** Adds (or replaces, same address) a bookmark in [path], creating the folders on the way; an unusable address changes nothing. */
    fun addBookmark(list: List<WebyFolder>, path: String, label: String, url: String): List<WebyFolder> {
        val u = normalizeUrl(url) ?: return list
        val p = normFolder(path).ifEmpty { return list }
        val tile = WebyTile("u:$u", label.trim().ifEmpty { u.substringAfter("://").substringBefore('/') }, u)
        return edit(list, p, true) { it.copy(tiles = it.tiles.filterNot { t -> t.id == tile.id } + tile) }
    }

    fun removeBookmark(list: List<WebyFolder>, path: String, id: String): List<WebyFolder> =
        edit(list, normFolder(path), false) { it.copy(tiles = it.tiles.filterNot { t -> t.id == id }) }

    fun addFolder(list: List<WebyFolder>, path: String): List<WebyFolder> = edit(list, normFolder(path), true) { it }

    fun removeFolder(list: List<WebyFolder>, path: String): List<WebyFolder> {
        val p = normFolder(path)
        if (p.isEmpty()) return list
        if (p.size == 1) return list.filterNot { it.title == p[0] }
        return edit(list, p.dropLast(1), false) { it.copy(folders = it.folders.filterNot { f -> f.title == p.last() }) }
    }

    /** Every folder as (path, folder), depth first: what the grid draws section by section. */
    fun flatten(list: List<WebyFolder>, prefix: String = ""): List<Pair<String, WebyFolder>> =
        list.flatMap { f -> val path = if (prefix.isEmpty()) f.title else "$prefix/${f.title}"; listOf(path to f) + flatten(f.folders, path) }

    fun toJson(list: List<WebyFolder>): JSONArray = JSONArray().also { a ->
        list.forEach { f ->
            a.put(JSONObject().put("title", f.title)
                .put("tiles", JSONArray().also { t -> f.tiles.forEach { x -> t.put(JSONObject().put("id", x.id).put("label", x.label).put("url", x.url ?: JSONObject.NULL)) } })
                .put("folders", toJson(f.folders)))
        }
    }

    fun fromJson(raw: String?): List<WebyFolder>? {
        val a = runCatching { JSONArray(raw ?: return null) }.getOrNull() ?: return null
        return parse(a)
    }

    private fun parse(a: JSONArray): List<WebyFolder> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val t = o.optJSONArray("tiles") ?: JSONArray()
        WebyFolder(o.optString("title"), (0 until t.length()).mapNotNull { j ->
            t.optJSONObject(j)?.let { WebyTile(it.optString("id"), it.optString("label"), if (it.isNull("url")) null else it.optString("url").takeIf { u -> u.isNotBlank() }) }
        }, parse(o.optJSONArray("folders") ?: JSONArray()))
    }
}

/** The bookmarks as the user left them (`weby_bookmarks` prefs: config, they move with the phone); the seed until the first edit. */
class WebyStore(context: Context, private val seed: List<WebyFolder>) {
    private val sp = context.applicationContext.getSharedPreferences("weby_bookmarks", Context.MODE_PRIVATE)
    fun load(): List<WebyFolder> = WebyOps.fromJson(sp.getString(KEY, null)) ?: seed
    fun save(list: List<WebyFolder>) { sp.edit().putString(KEY, WebyOps.toJson(list).toString()).apply() }
    private companion object { const val KEY = "folders_json" }
}
