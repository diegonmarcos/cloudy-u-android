package com.diegonmarcos.cloudlib.browsercore

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cloud Browser's native core (#1841): the session / tab snapshot store, the history and bookmark search and the content-blocking filter
 * of libbrowser_core.so, as Kotlin calls. When the library is not loaded (no .so for the ABI) or a call fails, the same call runs in
 * Kotlin ([KtSessionStore], [KtSearch], [KtFilter]) and [onFallback] says why once per reason, so the logs show which engine answered.
 */
object BrowserCore {
    /** Called with (job, reason) the first time a job falls back to Kotlin; the browser logs it under CBrowser/Core. */
    @Volatile var onFallback: ((String, String) -> Unit)? = null
    private val told = HashSet<String>()

    /** Null when the core is usable in this process, otherwise why not. */
    fun unavailableReason(): String? = runCatching { NativeBridge.unavailableReason() }.getOrElse { "JNI not available: ${it.javaClass.simpleName}" }

    val native: Boolean get() = unavailableReason() == null

    fun version(): String? = if (native) runCatching { NativeBridge.nativeVersion() }.getOrNull() else null

    private fun fellBack(job: String, why: String) {
        val key = "$job:$why"
        val first = synchronized(told) { told.add(key) }
        if (first) onFallback?.invoke(job, why)
    }

    /** `OK\n<json>` -> the json; anything else (ERR, null) -> null. */
    fun decode(answer: String?): String? = if (answer != null && answer.startsWith("OK\n")) answer.substring(3) else null

    private inline fun <T> viaCore(job: String, native: () -> T?): T? {
        unavailableReason()?.let { fellBack(job, it); return null }
        val r = try { native() } catch (t: Throwable) { fellBack(job, "${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}"); null }
        if (r == null) fellBack(job, "the core answered an error")
        return r
    }

    // ── the session store ────────────────────────────────────────────────────────────────────────────────────────────

    /** The snapshot store at [path] (a SQLite file with the core, a JSON file next to it without). */
    class SessionStore(private val path: String) {
        private val kt by lazy { KtSessionStore(File("$path.json")) }
        /** True while the Rust store is answering (false after a fall back to Kotlin). */
        @Volatile var usingCore: Boolean = true
            private set

        private fun call(op: String, arg: String): String? {
            if (!usingCore) return null
            val r = viaCore("store") { decode(NativeBridge.nativeStore(path, op, arg)) }
            if (r == null) usingCore = false
            return r
        }

        fun put(s: TabSnapshot): Boolean {
            call("put", s.toJson().toString())?.let { return true }
            return kt.put(s)
        }

        fun get(key: String): TabSnapshot? {
            call("get", key)?.let { j -> return JSONObject(j).optJSONObject("snapshot")?.let { TabSnapshot.fromJson(it) } }
            return kt.get(key)
        }

        fun list(): List<TabSnapshot> {
            call("list", "")?.let { j ->
                val a = JSONObject(j).optJSONArray("snapshots") ?: JSONArray()
                return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> TabSnapshot.fromJson(o) } }
            }
            return kt.list()
        }

        fun delete(key: String): Boolean {
            call("delete", key)?.let { return JSONObject(it).optBoolean("deleted") }
            return kt.delete(key)
        }

        fun prune(keep: Collection<String>): Int {
            call("prune", JSONArray(keep.toList()).toString())?.let { return JSONObject(it).optInt("pruned") }
            return kt.prune(keep)
        }

        fun count(): Int {
            call("count", "")?.let { return JSONObject(it).optInt("count") }
            return kt.count()
        }
    }

    // ── search ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Indices into [entries] that match [query]. [ranked] false: the History page's substring filter, input order kept; true: every
     * word must match, best first. A blank query keeps the first [limit] entries.
     */
    fun search(entries: List<SearchEntry>, query: String, limit: Int = Int.MAX_VALUE, ranked: Boolean = false): List<Int> {
        if (entries.isEmpty()) return emptyList()
        val lim = if (limit > 1_000_000) 1_000_000 else limit
        val viaNative = viaCore("search") {
            val arr = JSONArray()
            for (e in entries) arr.put(JSONObject().put("t", e.title).put("u", e.url).put("ts", e.ts))
            decode(NativeBridge.nativeSearch(arr.toString(), query, lim, ranked))
        }
        if (viaNative != null) {
            val a = JSONArray(viaNative)
            return (0 until a.length()).map { a.getInt(it) }
        }
        return KtSearch.search(entries, query, lim, ranked)
    }

    // ── content blocking ─────────────────────────────────────────────────────────────────────────────────────────────

    /** A compiled rule set (one rule per line: `||host^`, `host`, `@@||host^`). Close it when the owner goes. */
    class Filter(rules: String) : AutoCloseable {
        private val handle: Long = viaCore("filter") { NativeBridge.nativeFilterNew(rules).takeIf { it != 0L } } ?: 0L
        private val kt: KtFilter? = if (handle == 0L) KtFilter(rules) else null

        val usingCore: Boolean get() = handle != 0L

        fun shouldBlock(requestHost: String, pageHost: String, mainFrame: Boolean): Boolean =
            if (handle != 0L) runCatching { NativeBridge.nativeFilterBlocks(handle, requestHost, pageHost, mainFrame) }.getOrDefault(false)
            else kt!!.shouldBlock(requestHost, pageHost, mainFrame)

        fun isTracker(host: String): Boolean = if (handle != 0L) runCatching { NativeBridge.nativeFilterBlocks(handle, host, "", false) }.getOrDefault(false) else kt!!.isTracker(host)

        override fun close() { if (handle != 0L) runCatching { NativeBridge.nativeFilterFree(handle) } }
    }

    /** Proves the boundary: answers null when the core is absent, else the `panic:` reason. */
    fun panicProbe(): String? = if (native) runCatching { NativeBridge.nativePanicProbe() }.getOrNull()?.removePrefix("ERR\t") else null
}
