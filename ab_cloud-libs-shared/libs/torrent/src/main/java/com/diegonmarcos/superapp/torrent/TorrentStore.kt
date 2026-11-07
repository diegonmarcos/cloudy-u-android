package com.diegonmarcos.superapp.torrent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the engine remembers about a torrent between launches: where it came from and where it goes. Runtime data in the
 *  app's private storage, never baked. */
data class TorrentEntry(
    val id: String,
    val name: String,
    /** The magnet uri, or null for a .torrent file. */
    val magnet: String?,
    /** The kept copy of the .torrent file, or null for a magnet. */
    val torrentFile: String?,
    val savePath: String,
    val addedAt: Long,
    val paused: Boolean,
    val seed: Boolean,
    /** Set once the torrent has finished; a finished torrent that is not seeding stays paused. */
    val completed: Boolean,
)

interface TorrentStore {
    fun load(): List<TorrentEntry>
    fun save(entries: List<TorrentEntry>)
}

class MemoryStore : TorrentStore {
    private var list: List<TorrentEntry> = emptyList()
    override fun load() = list
    override fun save(entries: List<TorrentEntry>) { list = entries }
}

/** One JSON file. An unreadable file is an empty list, never a crash (a torn write must not take the page down). */
class FileTorrentStore(private val file: File) : TorrentStore {
    override fun load(): List<TorrentEntry> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            TorrentEntry(id, o.optString("name"), o.optString("magnet").takeIf { it.isNotEmpty() }, o.optString("file").takeIf { it.isNotEmpty() },
                o.optString("save"), o.optLong("added"), o.optBoolean("paused"), o.optBoolean("seed", true), o.optBoolean("completed"))
        }
    }.getOrDefault(emptyList())

    override fun save(entries: List<TorrentEntry>) {
        val a = JSONArray()
        entries.forEach { e ->
            a.put(JSONObject().put("id", e.id).put("name", e.name).put("magnet", e.magnet ?: "").put("file", e.torrentFile ?: "")
                .put("save", e.savePath).put("added", e.addedAt).put("paused", e.paused).put("seed", e.seed).put("completed", e.completed))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.writeText(a.toString()); tmp.delete() }
    }
}
