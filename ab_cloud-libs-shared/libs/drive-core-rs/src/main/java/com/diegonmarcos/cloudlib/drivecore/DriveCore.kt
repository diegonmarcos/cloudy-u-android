package com.diegonmarcos.cloudlib.drivecore

import java.util.concurrent.ConcurrentHashMap

/**
 * Cloud Drive's CPU-heavy engines behind ONE facade (#1729): file hashing, directory walking and sizing, file-tree diff.
 *
 *  - Each engine has a switch ([Engine] -> [Impl]); the default is the Rust core (`libdrive_core_rs.so`, ab_cloud-libs-shared/libs/
 *    drive-core-rs), declared by the host through [configure] (Cloud Drive: build.json::native_core). The benchmark flips it with
 *    [withImpl] to run Rust and Kotlin side by side.
 *  - Kotlin is the API surface and the fallback: the reference engines ([KotlinEngines]) return the same results. They answer when the
 *    switch says Kotlin, when the library did not load, when the core answered an error or panicked (the Rust boundary catches every
 *    panic), and for any path that is not a real file path (`content://` and every other URI stay Kotlin: Rust only ever sees `/...`).
 *  - It never throws for a native failure: the reason is logged once per distinct reason, and the Kotlin answer is returned.
 *  - Every call is one batch, never one JNI crossing per file.
 */
object DriveCore {
    const val TAG = "DriveCore"
    const val BLAKE3 = 1
    const val SHA256 = 2

    /** The host's log sink (Cloud Drive's logcat by default). */
    @Volatile var log: (String) -> Unit = { msg -> runCatching { android.util.Log.i(TAG, msg) } }

    /** Wraps each engine call so a profiler can time it: `op` is `drive.core.<engine>`, `impl` is `rust` or `kotlin`. The default only runs [block]. */
    interface Spans { fun <T> span(op: String, impl: String, block: () -> T): T }
    @Volatile var spans: Spans = object : Spans { override fun <T> span(op: String, impl: String, block: () -> T): T = block() }

    private val modes = ConcurrentHashMap<Engine, Impl>().apply { Engine.entries.forEach { put(it, Impl.RUST) } }
    private val reported = ConcurrentHashMap.newKeySet<String>()

    /** Parse a declared default like `hash=rust,walk=kotlin,diff=rust`; unknown names and values are ignored (the default stays Rust). */
    fun configure(declared: String) {
        for (part in declared.split(',')) {
            val kv = part.split('=', limit = 2).map { it.trim().lowercase() }
            if (kv.size != 2) continue
            val e = Engine.entries.firstOrNull { it.id == kv[0] } ?: continue
            val i = Impl.entries.firstOrNull { it.id == kv[1] } ?: continue
            modes[e] = i
        }
    }

    private val forced = ThreadLocal<HashMap<Engine, Impl>>()

    fun setImpl(engine: Engine, impl: Impl) { modes[engine] = impl }
    fun impl(engine: Engine): Impl = forced.get()?.get(engine) ?: modes[engine] ?: Impl.RUST

    /** Run [block] with [engine] on [impl] for THIS thread only, then put it back (the benchmark's A/B: other callers keep the declared switch). */
    fun <T> withImpl(engine: Engine, impl: Impl, block: () -> T): T {
        val m = forced.get() ?: HashMap<Engine, Impl>().also { forced.set(it) }
        val prev = m[engine]
        m[engine] = impl
        try { return block() } finally { if (prev == null) m.remove(engine) else m[engine] = prev }
    }

    /** Null when the Rust core is loaded, else why it is not. */
    fun nativeUnavailableReason(): String? = NativeBridge.unavailableReason()

    /** The core's version when it is loaded, else null. */
    fun nativeVersion(): String? = if (NativeBridge.unavailableReason() == null) runCatching { NativeBridge.nativeVersion() }.getOrNull() else null

    /** Proof that the boundary catches a panic: the `ERR` reason the core answers for a deliberate panic (null when the core is not loaded). */
    fun panicProbe(): String? = if (NativeBridge.unavailableReason() == null) runCatching { NativeBridge.nativePanicProbe() }.getOrNull() else null

    private fun isRealPath(p: String): Boolean = p.startsWith("/") && !p.contains("://")

    private fun note(reason: String) { if (reported.add(reason)) log("fallback to Kotlin: $reason") }

    /** Run the Rust side when the switch, the library and [eligible] allow; any failure is logged and returns null (the caller then runs Kotlin). */
    private fun <T> viaRust(engine: Engine, eligible: Boolean, why: String, call: () -> T): T? {
        if (impl(engine) != Impl.RUST) return null
        if (!eligible) { note("${engine.id}: $why"); return null }
        NativeBridge.unavailableReason()?.let { note("${engine.id}: $it"); return null }
        return try { call() } catch (t: Throwable) { note("${engine.id}: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(200)}"); null }
    }

    // ── 1. hashing ─────────────────────────────────────────────────────────

    /** BLAKE3 and/or SHA-256 ([algos]) of each file, results in input order. [limit] hashes only that many leading bytes (-1: the whole file). */
    fun hashFiles(paths: List<String>, algos: Int = BLAKE3 or SHA256, threads: Int = 4, limit: Long = -1L): List<FileHash> {
        if (paths.isEmpty()) return emptyList()
        val real = paths.indices.filter { isRealPath(paths[it]) }
        val out = arrayOfNulls<FileHash>(paths.size)
        if (real.isNotEmpty()) {
            val rustTry = viaRust(Engine.HASH, true, "") {
                val sub = real.map { paths[it] }
                val res = spans.span("drive.core.hash", "rust") {
                    val recs = Wire.body(NativeBridge.nativeHash(sub.joinToString("\n") { Wire.esc(it) }, algos, threads, limit)).getOrThrow()
                    recs.map { r -> Wire.fields(r).let { f -> FileHash(f[1], f[2].toLong(), f[3], f[4], f.getOrElse(5) { "" }) } }
                }
                check(res.size == sub.size) { "the core hashed ${res.size} of ${sub.size} files" }
                real.forEachIndexed { i, idx -> out[idx] = res[i] }
                log("engine=hash impl=rust core=${nativeVersion()} files=${sub.size} algos=$algos")
                true
            }
            if (rustTry == null) {
                val sub = real.map { paths[it] }
                val res = spans.span("drive.core.hash", "kotlin") { KotlinEngines.hash(sub, algos, limit) }
                real.forEachIndexed { i, idx -> out[idx] = res[i] }
            }
        }
        val other = paths.indices.filter { !isRealPath(paths[it]) }
        if (other.isNotEmpty()) {
            if (impl(Engine.HASH) == Impl.RUST) note("hash: not a real file path (content:// and other URIs stay Kotlin)")
            // A URI has no file to open here: the host reads it through its own resolver. The answer names why, it never throws.
            other.forEach { out[it] = FileHash(paths[it], 0, "", "", "not a real file path: the host's content resolver reads it") }
        }
        return out.map { it!! }
    }

    /** The lowercase hex SHA-256 of one real file, or null when it cannot be read. */
    fun sha256Hex(path: String): String? = hashFiles(listOf(path), SHA256, 1).first().let { if (it.error.isEmpty()) it.sha256 else null }

    // ── 2. walk and sizing ─────────────────────────────────────────────────

    /** Walk [root] (a real folder path) and aggregate in one batch. Throws only when the root itself cannot be read. */
    fun walk(root: String, opts: WalkOpts = WalkOpts()): WalkAgg {
        val clean = java.io.File(root).path
        val rust = viaRust(Engine.WALK, isRealPath(clean), "not a real file path (content:// and other URIs stay Kotlin)") {
            spans.span("drive.core.walk", "rust") {
                val agg = parseWalk(Wire.body(NativeBridge.nativeWalk(clean, walkOptsText(opts))).getOrThrow())
                log("engine=walk impl=rust core=${nativeVersion()} files=${agg.files} dirs=${agg.dirs} bytes=${agg.bytes}")
                agg
            }
        }
        return rust ?: spans.span("drive.core.walk", "kotlin") { KotlinEngines.walk(clean, opts) }
    }

    /** The recursive byte count of [root], links not followed (a clone's size on disk). */
    fun dirBytes(root: String, threads: Int = 4): Long =
        walk(root, WalkOpts(maxEntries = Long.MAX_VALUE / 4, budgetMs = Long.MAX_VALUE / 4, topFiles = 0, topFolders = 1, threads = threads)).bytes

    /** The files of [root] as (path, size, mtime), sorted by path: the snapshot [diff] compares. */
    fun snapshot(root: String, threads: Int = 4): List<ListedFile> =
        walk(root, WalkOpts(maxEntries = Long.MAX_VALUE / 4, budgetMs = Long.MAX_VALUE / 4, topFiles = 0, topFolders = 1, listFiles = true, threads = threads)).listing

    // ── 3. tree diff ───────────────────────────────────────────────────────

    /** The delta between two snapshots: ADDED (only in [right]), DELETED (only in [left]), MODIFIED (size, hash when both have one, else mtime). */
    fun diff(left: List<ListedFile>, right: List<ListedFile>): List<Delta> {
        val rust = viaRust(Engine.DIFF, (left.asSequence() + right.asSequence()).all { isRealPath(it.path) }, "not a real file path (content:// and other URIs stay Kotlin)") {
            spans.span("drive.core.diff", "rust") {
                val recs = Wire.body(NativeBridge.nativeDiff(diffText(left), diffText(right))).getOrThrow()
                val d = recs.map { r -> Wire.fields(r).let { f -> Delta(f[0][0], f[1]) } }
                log("engine=diff impl=rust core=${nativeVersion()} left=${left.size} right=${right.size} delta=${d.size}")
                d
            }
        }
        return rust ?: spans.span("drive.core.diff", "kotlin") { KotlinEngines.diff(left, right) }
    }

    private fun diffText(l: List<ListedFile>): String =
        l.joinToString("\n") { "${Wire.esc(it.path)}\t${it.bytes}\t${it.mtimeMs}\t${it.hash}" }

    internal fun walkOptsText(o: WalkOpts): String = buildString {
        o.skipNames.forEach { append("skip=").append(Wire.esc(it)).append('\n') }
        o.skipRel.forEach { append("skip_rel=").append(Wire.esc(it)).append('\n') }
        append("max_entries=").append(o.maxEntries).append('\n')
        append("budget_ms=").append(o.budgetMs).append('\n')
        append("top_files=").append(o.topFiles).append('\n')
        append("top_folders=").append(o.topFolders).append('\n')
        append("list=").append(if (o.listFiles) 1 else 0).append('\n')
        append("threads=").append(o.threads).append('\n')
        append("loose=").append(Wire.esc(o.looseLabel)).append('\n')
    }

    internal fun parseWalk(records: List<String>): WalkAgg {
        var files = 0L; var dirs = 0L; var bytes = 0L; var entries = 0L; var partial = ""
        val folders = ArrayList<Pair<String, Long>>(); val top = ArrayList<SizedPath>(); val ext = java.util.TreeMap<String, ExtTotal>(); val listing = ArrayList<ListedFile>()
        for (r in records) {
            val f = Wire.fields(r)
            when (f[0]) {
                "S" -> { files = f[1].toLong(); dirs = f[2].toLong(); bytes = f[3].toLong(); entries = f[4].toLong() }
                "P" -> partial = f.getOrElse(1) { "" }
                "F" -> folders += f[1] to f[2].toLong()
                "T" -> top += SizedPath(f[1].toLong(), f[2])
                "X" -> ext[f[1]] = ExtTotal(f[2].toLong(), f[3].toLong())
                "L" -> listing += ListedFile(f[1], f[2].toLong(), f[3].toLong())
            }
        }
        return WalkAgg(files, dirs, bytes, entries, partial, folders, top, ext, listing)
    }
}
