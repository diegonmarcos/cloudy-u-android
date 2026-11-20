package com.diegonmarcos.cloudwiki

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * #1830 the Chronos pages at RUN TIME: the app clones diegonmarcos/lib-chronos itself, READ-ONLY, and serves that clone.
 *
 * Where the repo is, how page ids are derived, which CDN URLs are answered from the APK's vendored copies and the refresh modes are
 * one generated file, assets/chronos/runtime.json (tools/regen-chronos.py writes it from tools/chronos-pin.json; Kotlin spells no URL).
 *
 *  - CLONE: a shallow (depth 1), single-branch clone of `ref` through libs:git-core (JGit), into a scratch directory of the app's own
 *    storage, then swapped in whole, so a page is never served from a half-written tree. Nothing is ever committed, staged or pushed:
 *    the files are marked read-only and every refresh is a fresh clone.
 *  - REFRESH MODES (`refresh.modes`): Manual (the Refresh button only), every time Chronos opens, every 6 hours, once a day. The chosen
 *    mode, the commit and the time of the last refresh are kept in preferences.
 *  - FALLBACK: until the first clone lands (first run, no network), the copy baked into the APK at the pin is served; [tree] is then
 *    null and [Decl.chronos] answers the baked tree.
 */
object ChronosRepo {
    data class Mode(val id: String, val label: String, val maxAgeMinutes: Int)

    data class Runtime(
        val repo: String,
        val url: String,
        val ref: String,
        val sourceDir: String,
        val idPrefix: String,
        val icons: Map<String, String>,
        val defaultIcon: String,
        /** CDN URL -> the served path of its vendored copy, applied to every page of the clone. */
        val rewrites: List<Pair<String, String>>,
        val modes: List<Mode>,
        val defaultMode: String,
    )

    /** What the Chronos page shows: the clone's commit and time (null before the first clone), a refresh running, the last error. */
    data class State(
        val sha: String? = null,
        val at: Long = 0L,
        val busy: Boolean = false,
        val error: String? = null,
        val tree: ChronosTree? = null,
        val mode: String = "",
    )

    private const val PREFS = "chronos_repo"
    private const val K_SHA = "sha"
    private const val K_AT = "at"
    private const val K_MODE = "mode"
    private const val K_ERR = "error"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val lock = Mutex()
    @Volatile private var rt: Runtime? = null
    @Volatile private var started = false
    @Volatile private var openedThisRun = false
    @Volatile private var filesDir: File? = null

    /** The clone's working tree (the repo root; the pages are under [Runtime.sourceDir]). */
    fun repoDir(ctx: Context): File = File(ctx.filesDir, "chronos/repo")
    private fun scratchDir(ctx: Context): File = File(ctx.filesDir, "chronos/repo.new")
    private fun oldDir(ctx: Context): File = File(ctx.filesDir, "chronos/repo.old")

    fun runtime(ctx: Context): Runtime = rt ?: parseRuntime(ctx.assets.open(Chronos.ASSETS_DIR + "runtime.json").bufferedReader().use { it.readText() })
        .also { rt = it }

    /** The clone's source dir, when a clone is in place (what the web view serves first). */
    fun cloneSource(): File? {
        val r = rt ?: return null
        val root = filesDir ?: return null
        if (_state.value.tree == null) return null
        return File(root, "chronos/repo/" + r.sourceDir).takeIf { it.isDirectory }
    }

    fun mode(ctx: Context): Mode {
        val r = runtime(ctx)
        val id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_MODE, null) ?: r.defaultMode
        return r.modes.firstOrNull { it.id == id } ?: r.modes.first { it.id == r.defaultMode }
    }

    fun setMode(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_MODE, id).apply()
        _state.value = _state.value.copy(mode = id)
    }

    /** Once per process: read what the last refresh left (commit, time, error) and the clone's page tree. */
    fun start(ctx: Context) {
        if (started) return
        started = true
        filesDir = ctx.filesDir
        val r = runtime(ctx)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val src = File(repoDir(ctx), r.sourceDir)
        val tree = if (File(src, "index.html").isFile) runCatching { scan(src, r) }.getOrNull() else null
        _state.value = State(p.getString(K_SHA, null).takeIf { tree != null }, p.getLong(K_AT, 0L), false, p.getString(K_ERR, null), tree, mode(ctx).id)
    }

    /** Is a refresh due under the chosen mode? [opening] = Chronos is being opened (the every-open mode counts once per run). */
    fun due(mode: Mode, cloned: Boolean, at: Long, now: Long, opening: Boolean): Boolean = when {
        mode.maxAgeMinutes < 0 -> false // Manual: only the Refresh button (the baked copy serves until then)
        mode.maxAgeMinutes == 0 -> opening || !cloned
        else -> !cloned || now - at >= mode.maxAgeMinutes * 60_000L
    }

    /** At app start and when Chronos is opened: refresh when the mode says it is due. */
    suspend fun refreshIfDue(ctx: Context, opening: Boolean) {
        start(ctx)
        val s = _state.value
        val open = opening && !openedThisRun
        if (opening) openedThisRun = true
        if (due(mode(ctx), s.tree != null, s.at, System.currentTimeMillis(), open)) refresh(ctx)
    }

    /** A fresh read-only shallow clone of the repo, swapped in whole. Never throws: a failure keeps the previous copy and says why. */
    suspend fun refresh(ctx: Context): Unit = withContext(Dispatchers.IO) {
        start(ctx)
        if (!lock.tryLock()) return@withContext
        try {
            _state.value = _state.value.copy(busy = true)
            val r = runtime(ctx)
            val scratch = scratchDir(ctx).also { it.deleteRecursively(); it.parentFile?.mkdirs() }
            val result = runCatching {
                com.diegonmarcos.cloudlib.gitcore.JGitEngine.clone(r.url, scratch, depth = 1, singleBranch = true, branch = r.ref).close()
                val sha = org.eclipse.jgit.api.Git.open(scratch).use { g -> g.repository.resolve("HEAD")?.name } ?: error("the clone has no HEAD")
                val src = File(scratch, r.sourceDir)
                check(File(src, "index.html").isFile) { "${r.repo}@${sha.take(8)} has no ${r.sourceDir}/index.html" }
                val tree = scan(src, r)
                readOnly(scratch)
                val live = repoDir(ctx)
                val old = oldDir(ctx).also { it.deleteRecursively() }
                if (live.exists() && !live.renameTo(old)) error("could not move the previous copy aside")
                if (!scratch.renameTo(live)) { old.renameTo(live); error("could not put the new copy in place") }
                old.deleteRecursively()
                sha to tree
            }
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            result.onSuccess { (sha, tree) ->
                val now = System.currentTimeMillis()
                p.putString(K_SHA, sha).putLong(K_AT, now).remove(K_ERR).apply()
                _state.value = State(sha, now, false, null, tree, mode(ctx).id)
            }.onFailure { e ->
                scratch.deleteRecursively()
                val why = (e.message ?: e.javaClass.simpleName).take(300)
                p.putString(K_ERR, why).apply()
                _state.value = _state.value.copy(busy = false, error = why)
            }
        } finally {
            lock.unlock()
        }
    }

    /** Files read-only (directories stay writable so the next refresh can remove the tree). */
    private fun readOnly(dir: File) {
        dir.walkTopDown().filter { it.isFile }.forEach { it.setWritable(false, false) }
    }

    /** The page served for a clone file: an HTML page with each pinned CDN URL replaced by its vendored copy (quoted, as the tool does). */
    fun rewrite(text: String, rewrites: List<Pair<String, String>>): String {
        var t = text
        for ((match, served) in rewrites) for (q in listOf("\"", "'")) t = t.replace(q + match + q, q + served + q)
        return t
    }

    // ── the page tree of a clone: the same derivation as tools/regen-chronos.py tree() ────────────────────────────

    fun scan(sourceDir: File, r: Runtime): ChronosTree {
        val out = ArrayList<ChronosPage>()
        fun node(rel: String, parent: String?): String {
            val d = if (rel.isEmpty()) sourceDir else File(sourceDir, rel)
            val label = titleOf(File(d, "index.html").readText()) ?: rel.substringAfterLast('/').ifEmpty { r.sourceDir }
            val id = r.idPrefix + if (rel.isEmpty()) "" else "." + segId(rel.substringAfterLast('/'))
            val slot = out.size
            out += ChronosPage(id, label, r.icons[rel] ?: r.defaultIcon, listOf(r.sourceDir, rel, "index.html").filter { it.isNotEmpty() }.joinToString("/"), parent, emptyList())
            val kids = (d.listFiles() ?: emptyArray())
                .filter { it.isDirectory && !it.name.startsWith(".") && File(it, "index.html").isFile }
                .sortedWith { a, b -> natural(a.name, b.name) }
                .map { node(listOf(rel, it.name).filter { s -> s.isNotEmpty() }.joinToString("/"), id) }
            out[slot] = out[slot].copy(children = kids)
            return id
        }
        node("", null)
        return ChronosTree(out)
    }

    fun segId(name: String): String =
        Regex("""^(\d+(?:\.\d+)*)\.""").find(name)?.groupValues?.get(1)
            ?: name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun titleOf(html: String): String? =
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)
            ?.let(::unescape)?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

    private fun unescape(s: String): String =
        Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> if (e.startsWith("#x")) e.drop(2).toInt(16).toChar().toString() else e.drop(1).toInt().toChar().toString()
            }
        }

    /** Python's natural key: digit runs compare as numbers, the rest case-blind. */
    fun natural(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]; val y = pb[i]
            val c = if (x is Long && y is Long) x.compareTo(y) else if (x is String && y is String) x.compareTo(y) else if (x is Long) -1 else 1
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }

    private fun parts(s: String): List<Any> {
        val out = ArrayList<Any>()
        var i = 0
        Regex("\\d+").findAll(s).forEach { m ->
            out += s.substring(i, m.range.first).lowercase()
            out += m.value.toLong()
            i = m.range.last + 1
        }
        out += s.substring(i).lowercase()
        return out
    }

    fun parseRuntime(json: String): Runtime {
        val o = JSONObject(json)
        val nav = o.getJSONObject("nav")
        val icons = nav.getJSONObject("icons").let { i -> i.keys().asSequence().associateWith { i.getString(it) } }
        val rw = o.getJSONArray("rewrites").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { x -> x.getString("match") to x.getString("served") } } }
        val refresh = o.getJSONObject("refresh")
        val modes = refresh.getJSONArray("modes").let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { m -> Mode(m.getString("id"), m.getString("label"), m.getInt("max_age_minutes")) } }
        }
        return Runtime(o.getString("repo"), o.getString("url"), o.getString("ref"), o.getString("source_dir"), nav.getString("id_prefix"),
            icons, nav.getString("default_icon"), rw, modes, refresh.getString("default"))
    }
}
