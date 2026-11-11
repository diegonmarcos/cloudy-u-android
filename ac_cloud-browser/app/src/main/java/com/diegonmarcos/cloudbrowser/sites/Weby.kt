package com.diegonmarcos.cloudbrowser.sites

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One site tile. [url] null = the declaration knows no web address for it: the tile is drawn greyed and does nothing. */
data class WebyTile(val id: String, val label: String, val url: String?, val pkg: String? = null)

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
            WebyTile(pkg, label, web[pkg]?.second, pkg)
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

/** An installed, launchable app as the phone reports it. */
data class WebyInstalled(val pkg: String, val label: String)

/**
 * #1634 the FULL Phone-apps catalogue, pure (JVM-tested in WebyCatalogueTest): every section, every folder and every app of the
 * SuperApp's Phone > Apps page, beside the Quickmarks groups (WebySeed), never instead of them. The sections (ui.phone_sections) and
 * the folders (ui.phone_folders: label prefix char -> section, match_keywords -> members) come from the SuperApp's declaration;
 * the fleet's own packages join the folder ui.external_apps names. Members = every package a folder declares (so the whole
 * catalogue is there, installed or not) plus every launchable app on this phone, sorted into its folder by the same keyword rules
 * as libs:appstore PhoneAppClassifier (first folder in declared order wins; an app nothing claims falls to the sink folder).
 * [decl] = ui.weby.catalogue + webapps (phone-webapps.json groups, the web address of an app).
 */
object WebyCatalogue {
    /** Title of the top-level group that holds a section's folders. */
    const val ROOT = "Phone apps"
    const val NO_SECTION = "Other"

    private data class Folder(val id: String, val order: String, val label: String, val keywords: List<String>, val pin: Boolean, val sink: Boolean)

    private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).mapNotNull { a?.optString(it)?.takeIf { s -> s.isNotEmpty() } }

    private fun folders(cat: JSONObject): List<Folder> {
        val ext = HashMap<String, MutableList<String>>()
        val e = cat.optJSONArray("external") ?: JSONArray()
        for (i in 0 until e.length()) e.optJSONObject(i)?.let { o -> strings(o.optJSONArray("packages")).forEach { ext.getOrPut(o.optString("folder")) { ArrayList() }.add("pkg:$it") } }
        val a = cat.optJSONArray("folders") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Folder(o.optString("id"), o.optString("order", "99"), o.optString("label"),
                (strings(o.optJSONArray("match_keywords")) + (ext[o.optString("id")] ?: emptyList())).map { it.lowercase() }, o.optBoolean("pin"), o.optBoolean("sink"))
        }.sortedWith(compareBy({ it.order }, { it.id }))
    }

    private fun matches(kw: String, pkg: String, lbl: String): Boolean = when {
        kw.startsWith("pkg:") -> kw.length > 4 && pkg == kw.substring(4)
        kw.startsWith("pkg^") -> kw.length > 4 && pkg.startsWith(kw.substring(4))
        kw.startsWith("lbl:") -> kw.length > 4 && lbl == kw.substring(4)
        kw.startsWith("lbl~") -> kw.length - 4 >= 3 && lbl.contains(kw.substring(4))
        else -> kw.length >= 4 && "$pkg $lbl".contains(kw)
    }

    private fun classify(pkg: String, label: String, fs: List<Folder>, sink: String?): String? {
        val p = pkg.lowercase(); val l = label.lowercase()
        for (f in fs) for (k in f.keywords) if (matches(k, p, l)) return f.id
        return sink
    }

    /** Every package a folder names with a `pkg:` keyword, in declared folder order (the catalogue's declared members). */
    fun declaredPackages(decl: JSONObject): List<String> =
        folders(decl.optJSONObject("catalogue") ?: JSONObject()).flatMap { f -> f.keywords.filter { it.startsWith("pkg:") }.map { it.substring(4) } }.distinct()

    fun derive(decl: JSONObject, installed: List<WebyInstalled>): List<WebyFolder> {
        val cat = decl.optJSONObject("catalogue") ?: return emptyList()
        val fs = folders(cat)
        if (fs.isEmpty()) return emptyList()
        val sink = fs.firstOrNull { it.sink }?.id ?: fs.firstOrNull { it.keywords.isEmpty() }?.id
        val web = HashMap<String, Pair<String, String?>>()
        val wg = decl.optJSONArray("webapps") ?: JSONArray()
        for (i in 0 until wg.length()) {
            val apps = wg.optJSONObject(i)?.optJSONArray("apps") ?: continue
            for (j in 0 until apps.length()) {
                val a = apps.optJSONObject(j) ?: continue
                web.putIfAbsent(a.optString("pkg"), a.optString("label") to a.optString("url").takeIf { it.isNotBlank() && it != "null" })
            }
        }
        val label = HashMap<String, String>()
        installed.forEach { label[it.pkg] = it.label }
        val members = LinkedHashMap<String, LinkedHashSet<String>>()
        fun put(pkg: String) { classify(pkg, label[pkg] ?: web[pkg]?.first ?: pkg, fs, sink)?.let { members.getOrPut(it) { LinkedHashSet() }.add(pkg) } }
        fs.flatMap { f -> f.keywords.filter { it.startsWith("pkg:") }.map { it.substring(4) } }.distinct().forEach { put(it) }
        installed.forEach { put(it.pkg) }
        val secs = cat.optJSONArray("sections") ?: JSONArray()
        val sections = (0 until secs.length()).mapNotNull { secs.optJSONObject(it) }.map { it.optString("prefix") to it.optString("title") }
        val out = LinkedHashMap<String, MutableList<WebyFolder>>()
        sections.forEach { out[it.second] = ArrayList() }
        for (f in fs) {
            val m = members[f.id].orEmpty()
            if (m.isEmpty() && !f.pin) continue
            val first = f.label.firstOrNull()
            val sec = sections.firstOrNull { it.first.isNotEmpty() && first != null && it.first[0] == first }
            val title = if (sec != null) f.label.drop(1) else f.label
            val tiles = m.map { pkg -> WebyTile(pkg, label[pkg] ?: web[pkg]?.first ?: pkg, web[pkg]?.second, pkg) }.sortedBy { it.label.lowercase() }
            out.getOrPut(sec?.second ?: NO_SECTION) { ArrayList() }.add(WebyFolder(title.replace('/', '-'), tiles))
        }
        return out.filterValues { it.isNotEmpty() }.map { (t, l) -> WebyFolder(prefix(t), emptyList(), l) }
    }

    /** A catalogue section's top-level title; the page tells these (derived, never saved or edited) from the user's own folders by it. */
    fun prefix(section: String) = "$ROOT › ${section.replace('/', '-')}"
    fun isCatalogue(path: String) = path == ROOT || path.startsWith("$ROOT › ")
}

/**
 * #1531 the home-screen layout spec, pure (JVM-tested): the SuperApp Phone > Apps grid's launcher look at 8 columns by 8 rows
 * (KitLauncher, the shared ui-kit constants). A top-level folder is a titled section; its cells are its own apps first, then its
 * folders as folder tiles, exactly as the phone page lays a group out; a folder tile opens a popup with its own grid.
 */
object WebyLayout {
    const val COLUMNS = com.diegonmarcos.superapp.uikit.KitLauncher.COLUMNS
    const val ROWS = com.diegonmarcos.superapp.uikit.KitLauncher.ROWS
    const val PAGE = COLUMNS * ROWS
    const val POPUP_COLUMNS = com.diegonmarcos.superapp.uikit.KitLauncher.POPUP_COLUMNS

    sealed interface Cell {
        data class App(val tile: WebyTile) : Cell
        data class Folder(val folder: WebyFolder) : Cell
    }

    fun cells(f: WebyFolder): List<Cell> = f.tiles.map { Cell.App(it) } + f.folders.map { Cell.Folder(it) }

    /** The apps a folder tile previews: its own, then its folders' (so a folder of folders still shows something). */
    fun previewTiles(f: WebyFolder): List<WebyTile> {
        val all = allTiles(f)
        return all.take(com.diegonmarcos.superapp.uikit.KitLauncher.previewCount(all.size))
    }

    private fun allTiles(f: WebyFolder): List<WebyTile> = f.tiles + f.folders.flatMap { allTiles(it) }

    /** Grid rows [n] cells take at [cols] columns. */
    fun rows(n: Int, cols: Int = COLUMNS) = com.diegonmarcos.superapp.uikit.KitLauncher.rows(n, cols)
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
                .put("tiles", JSONArray().also { t -> f.tiles.forEach { x -> t.put(JSONObject().put("id", x.id).put("label", x.label).put("url", x.url ?: JSONObject.NULL).also { o -> x.pkg?.let { o.put("pkg", it) } }) } })
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
            t.optJSONObject(j)?.let { WebyTile(it.optString("id"), it.optString("label"), if (it.isNull("url")) null else it.optString("url").takeIf { u -> u.isNotBlank() }, it.optString("pkg").takeIf { p -> p.isNotBlank() }) }
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
