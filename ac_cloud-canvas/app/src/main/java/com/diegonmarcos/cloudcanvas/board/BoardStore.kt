package com.diegonmarcos.cloudcanvas.board

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** One saved whiteboard: its id (the file name), the name the owner gave it and when it last changed (epoch ms). */
data class BoardMeta(val id: String, val name: String, val updated: Long)

/**
 * The Board page's storage (#1787): every whiteboard is a tldraw store snapshot (`{store, schema}` JSON, the shape tldraw's
 * `store.getStoreSnapshot()` writes and `store.loadStoreSnapshot()` reads) in app storage, `<dir>/<id>.tldr`, with an index
 * `<dir>/boards.json` holding names, order and the open board. Files are written whole or not at all (temp file + rename), so a kill
 * mid-save keeps the previous version. Pure JVM: the page's JS bridge (BoardScreen) calls it, the unit suite drives it directly.
 */
class BoardStore(private val dir: File) {
    private val index = File(dir, "boards.json")

    init { dir.mkdirs() }

    @Synchronized fun list(): List<BoardMeta> = readIndex().first

    @Synchronized fun currentId(): String {
        val (boards, cur) = readIndex()
        if (cur != null && boards.any { it.id == cur }) return cur
        val first = boards.firstOrNull() ?: return create(DEFAULT_NAME).id
        writeIndex(boards, first.id)
        return first.id
    }

    @Synchronized fun open(id: String) {
        val (boards, _) = readIndex()
        require(boards.any { it.id == id }) { "no board $id" }
        writeIndex(boards, id)
    }

    /** A new empty board, made the open one. */
    @Synchronized fun create(name: String): BoardMeta {
        val (boards, _) = readIndex()
        val clean = cleanName(name).ifEmpty { DEFAULT_NAME }
        val m = BoardMeta(newId(boards.map { it.id }.toSet()), uniqueName(clean, boards.map { it.name }.toSet()), now())
        writeIndex(boards + m, m.id)
        return m
    }

    @Synchronized fun rename(id: String, name: String): BoardMeta {
        val (boards, cur) = readIndex()
        val old = boards.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no board $id")
        val clean = cleanName(name).ifEmpty { return old }
        val m = old.copy(name = uniqueName(clean, boards.filter { it.id != id }.map { it.name }.toSet()))
        writeIndex(boards.map { if (it.id == id) m else it }, cur)
        return m
    }

    /** Deletes a board and its snapshot; the store always keeps at least one board (an empty one is made when the last goes). */
    @Synchronized fun delete(id: String) {
        val (boards, cur) = readIndex()
        val rest = boards.filter { it.id != id }
        File(dir, "$id.tldr").delete()
        if (rest.isEmpty()) { writeIndex(emptyList(), null); create(DEFAULT_NAME); return }
        writeIndex(rest, if (cur == id) rest.first().id else cur)
    }

    /** The snapshot JSON of [id], or null for a board never saved (tldraw then starts it empty). */
    @Synchronized fun read(id: String): String? {
        require(ID.matches(id)) { "bad board id" }
        return File(dir, "$id.tldr").takeIf { it.isFile }?.readText()
    }

    /** Saves [snapshot] (it must be a JSON object with a `store`) for a known board and stamps its time. */
    @Synchronized fun write(id: String, snapshot: String) {
        require(ID.matches(id)) { "bad board id" }
        require(snapshot.length <= MAX_SNAPSHOT_CHARS) { "board too large" }
        val json = JSONObject(snapshot)
        require(json.has("store")) { "not a tldraw store snapshot" }
        val (boards, cur) = readIndex()
        require(boards.any { it.id == id }) { "no board $id" }
        atomicWrite(File(dir, "$id.tldr"), snapshot)
        writeIndex(boards.map { if (it.id == id) it.copy(updated = now()) else it }, cur)
    }

    private fun readIndex(): Pair<List<BoardMeta>, String?> {
        if (!index.isFile) return emptyList<BoardMeta>() to null
        return runCatching {
            val o = JSONObject(index.readText())
            val a = o.optJSONArray("boards") ?: JSONArray()
            val list = (0 until a.length()).map { a.getJSONObject(it) }
                .filter { ID.matches(it.optString("id")) }
                .map { BoardMeta(it.getString("id"), it.optString("name", DEFAULT_NAME), it.optLong("updated")) }
            list to o.optString("current").ifEmpty { null }
        }.getOrElse { emptyList<BoardMeta>() to null }
    }

    private fun writeIndex(boards: List<BoardMeta>, current: String?) {
        val a = JSONArray()
        boards.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("updated", it.updated)) }
        atomicWrite(index, JSONObject().put("version", 1).put("current", current ?: "").put("boards", a).toString())
    }

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) { "cannot write ${f.name}" } }
    }

    private var lastStamp = 0L
    private fun now(): Long { val t = maxOf(System.currentTimeMillis(), lastStamp + 1); lastStamp = t; return t }

    private fun newId(taken: Set<String>): String {
        var n = taken.size + 1
        while ("board-$n" in taken) n++
        return "board-$n"
    }

    companion object {
        const val DEFAULT_NAME = "Board"
        /** A board id: file-name safe, never a path. */
        val ID = Regex("board-[0-9]{1,6}")
        /** Snapshots above this are refused (a runaway page must not fill the phone): 32 M characters. */
        const val MAX_SNAPSHOT_CHARS = 32 * 1024 * 1024

        /** A name as typed, without control characters and at most 60 characters. */
        fun cleanName(name: String): String = name.filter { !it.isISOControl() }.trim().take(60)

        fun uniqueName(name: String, taken: Set<String>): String {
            if (name !in taken) return name
            var n = 2
            while ("$name $n" in taken) n++
            return "$name $n"
        }
    }
}
