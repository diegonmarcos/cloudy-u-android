package com.diegonmarcos.cloudstore

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.diegonmarcos.superapp.fleetdata.FleetData
import com.diegonmarcos.superapp.torrent.Magnet
import com.diegonmarcos.superapp.torrent.TorrentEngine
import com.diegonmarcos.superapp.torrent.sentence
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Torrent tab's CATALOGUE (#1681): a declared, categorised list of legitimately distributable torrents (Linux ISOs, open
 * movies), each handed to the fleet's torrent engine (libs:torrent) or to the user's own torrent app.
 *
 * DATA, never baked: the declared list is the runtime dataset `shared/torrent-catalogue` (data/fleet/torrent-catalogue.json,
 * served by Cloud Store's fleet data provider). The APK carries the parser only; with nothing served yet the page says so and
 * still shows the user's own entries. The user's own entries live on the device (SharedPreferences [PREFS]), added in
 * Configs > Torrent.
 *
 * Every read is defensive: a malformed entry is dropped with its reason, a malformed document is an empty catalogue with the
 * reason, never a crash.
 */
object TorrentCatalogue {
    const val DATASET = "torrent-catalogue"
    const val PREFS = "store_torrent_catalogue"
    private const val KEY_USER = "user_entries"
    /** Where an entry with an unknown or missing category is filed. */
    const val OTHER = "other"
    /** A .torrent bigger than this is refused (a real one is a few hundred KB). */
    const val MAX_TORRENT_BYTES = 8 * 1024 * 1024

    data class Category(val id: String, val label: String)

    data class Entry(
        val id: String,
        val name: String,
        val category: String,
        val magnet: String?,
        val torrentUrl: String?,
        val sizeBytes: Long = -1,
        val publisher: String = "",
        val licence: String = "",
        val sourcePage: String = "",
        val user: Boolean = false,
    ) {
        /** The info-hash the magnet names, or null (a .torrent-only entry). */
        val infoHash: String? get() = magnet?.let { Magnet.infoHash(it) }
    }

    data class Catalogue(val categories: List<Category>, val entries: List<Entry>, val problems: List<String> = emptyList()) {
        /** Entries of [categoryId], or every entry for null. */
        fun inCategory(categoryId: String?): List<Entry> = if (categoryId == null) entries else entries.filter { it.category == categoryId }
        fun count(categoryId: String): Int = entries.count { it.category == categoryId }
    }

    val EMPTY = Catalogue(emptyList(), emptyList())

    /** Why an entry cannot be used, or null when it can: a name, and a valid magnet or an https .torrent address. */
    fun problem(name: String, magnet: String?, torrentUrl: String?): String? = when {
        name.isBlank() -> "needs a name"
        magnet.isNullOrBlank() && torrentUrl.isNullOrBlank() -> "needs a magnet link or a .torrent address"
        !magnet.isNullOrBlank() && Magnet.infoHash(magnet) == null -> "the magnet link has no valid xt=urn:btih info-hash"
        !torrentUrl.isNullOrBlank() && !torrentUrl.trim().startsWith("https://", ignoreCase = true) -> "the .torrent address must be https"
        else -> null
    }

    /** Parses the served dataset. Never throws: a bad document is an empty catalogue that says why. */
    fun parse(text: String): Catalogue {
        val root = runCatching { JSONObject(text) }.getOrElse { return Catalogue(emptyList(), emptyList(), listOf("catalogue is not JSON")) }
        val problems = mutableListOf<String>()
        val categories = mutableListOf<Category>()
        root.optJSONArray("categories")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isEmpty() || categories.any { it.id == id }) continue
                categories += Category(id, o.optString("label").ifBlank { id })
            }
        }
        val entries = readEntries(root.optJSONArray("entries"), user = false, problems)
        return normalise(Catalogue(categories, entries, problems))
    }

    private fun readEntries(a: JSONArray?, user: Boolean, problems: MutableList<String>): List<Entry> {
        val out = mutableListOf<Entry>()
        for (i in 0 until (a?.length() ?: 0)) {
            val o = a?.optJSONObject(i) ?: continue
            fun s(k: String) = if (o.isNull(k)) "" else o.optString(k).trim()
            val name = s("name")
            val magnet = s("magnet").ifEmpty { null }
            val url = s("torrent_url").ifEmpty { null }
            val why = problem(name, magnet, url)
            val id = s("id").ifEmpty { idFor(name) }
            if (why != null) { problems += "${id.ifEmpty { "entry ${i + 1}" }}: $why"; continue }
            if (out.any { it.id == id }) { problems += "$id: duplicate id"; continue }
            out += Entry(id, name, s("category").ifEmpty { OTHER }, magnet, url, o.optLong("size_bytes", -1L),
                s("publisher"), s("licence"), s("source_page"), user)
        }
        return out
    }

    /** Every entry's category is a declared one: an unknown category is added as itself, a missing one goes to [OTHER]. */
    private fun normalise(c: Catalogue): Catalogue {
        val cats = c.categories.toMutableList()
        c.entries.map { it.category }.distinct().filter { id -> cats.none { it.id == id } }.forEach { id ->
            cats += Category(id, if (id == OTHER) "Other" else id.replaceFirstChar { it.uppercase() })
        }
        return c.copy(categories = cats)
    }

    /** The declared catalogue plus the user's own entries (theirs win on an id clash, and are drawn first in a category). */
    fun merge(declared: Catalogue, user: List<Entry>): Catalogue {
        val ids = user.map { it.id }.toSet()
        return normalise(Catalogue(declared.categories, user + declared.entries.filter { it.id !in ids }, declared.problems))
    }

    fun idFor(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(64)

    // ── the user's own entries (Configs > Torrent), kept on the device ──

    fun userEntries(ctx: Context): List<Entry> = runCatching {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER, null) ?: return emptyList()
        readEntries(JSONArray(raw), user = true, mutableListOf())
    }.getOrDefault(emptyList())

    /** Adds (or replaces, same id) one entry of the user's; returns the reason when it is refused. */
    fun addUser(ctx: Context, name: String, category: String, magnet: String?, torrentUrl: String?): String? {
        val m = magnet?.trim()?.ifEmpty { null }; val u = torrentUrl?.trim()?.ifEmpty { null }
        problem(name, m, u)?.let { return it }
        val id = "user-" + idFor(name).ifEmpty { "entry" }
        val list = userEntries(ctx).filter { it.id != id } + Entry(id, name.trim(), category.ifBlank { OTHER }, m, u, user = true)
        save(ctx, list)
        return null
    }

    fun removeUser(ctx: Context, id: String) = save(ctx, userEntries(ctx).filter { it.id != id })

    private fun save(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        list.forEach { e ->
            a.put(JSONObject().put("id", e.id).put("name", e.name).put("category", e.category)
                .put("magnet", e.magnet ?: JSONObject.NULL).put("torrent_url", e.torrentUrl ?: JSONObject.NULL))
        }
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_USER, a.toString()).apply() }
    }

    // ── reading and handing off ──

    /** The served catalogue, or null while nothing has been served (a loading state, never a crash). Blocking: off the main thread. */
    fun served(ctx: Context): Catalogue? = runCatching { FleetData.get(ctx.applicationContext, DATASET) { parse(it) } }.getOrNull()

    /** Served + the user's own entries; [served] null still yields the user's entries. */
    fun load(ctx: Context): Pair<Catalogue, Boolean> {
        val s = served(ctx)
        return merge(s ?: EMPTY, userEntries(ctx)) to (s != null)
    }

    /** Hands [e] to the fleet torrent engine: its magnet when it has one, else its .torrent fetched over https. One sentence back. */
    fun download(engine: TorrentEngine, e: Entry, fetch: (String) -> ByteArray? = ::fetchTorrent): String {
        val m = e.magnet
        if (m != null && Magnet.infoHash(m) != null) return engine.addMagnet(m).sentence()
        val url = e.torrentUrl ?: return "${e.name}: no magnet link or .torrent address."
        val bytes = runCatching { fetch(url) }.getOrNull() ?: return "${e.name}: could not fetch the .torrent."
        return engine.addTorrentBytes(e.name + ".torrent", bytes).sentence()
    }

    /** The VIEW intent another torrent app takes: the magnet when there is one, else the .torrent address. Null when neither. */
    fun externalIntent(e: Entry): Intent? {
        val target = e.magnet ?: e.torrentUrl ?: return null
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Intent.createChooser(view, "Open with a torrent app").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Fetches a .torrent over https, capped at [MAX_TORRENT_BYTES]; null on any failure. */
    fun fetchTorrent(url: String): ByteArray? {
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); if (out.size() > MAX_TORRENT_BYTES) return null
                }
                out.toByteArray()
            }
        } catch (t: Throwable) { null } finally { c.disconnect() }
    }
}
