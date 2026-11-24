package com.diegonmarcos.cloudnav.maps.takeout

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.diegonmarcos.cloudnav.maps.MapsDb
import java.io.InputStream
import java.time.ZoneId

/** [SqlExec] over the app's SQLite, with compiled statements reused (an import runs millions of the same insert). */
class SqliteExec(private val db: SQLiteDatabase) : SqlExec {
    private val stmts = HashMap<String, SQLiteStatement>()
    private fun bind(st: SQLiteStatement, args: List<Any?>) {
        st.clearBindings()
        args.forEachIndexed { i, a ->
            val k = i + 1
            when (a) {
                null -> st.bindNull(k)
                is Long -> st.bindLong(k, a)
                is Int -> st.bindLong(k, a.toLong())
                is Boolean -> st.bindLong(k, if (a) 1 else 0)
                is Double -> st.bindDouble(k, a)
                is Float -> st.bindDouble(k, a.toDouble())
                else -> st.bindString(k, a.toString())
            }
        }
    }
    override fun exec(sql: String, args: List<Any?>) { val st = stmts.getOrPut(sql) { db.compileStatement(sql) }; bind(st, args); if (sql.trimStart().startsWith("INSERT", true)) st.executeInsert() else st.execute() }
    override fun rows(sql: String, args: List<Any?>): List<List<Any?>> {
        val out = ArrayList<List<Any?>>()
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            while (c.moveToNext()) out += (0 until c.columnCount).map { i ->
                when (c.getType(i)) { android.database.Cursor.FIELD_TYPE_NULL -> null; android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i); android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i); else -> c.getString(i) }
            }
        }
        return out
    }
    override fun begin() { db.beginTransaction() }
    override fun commit() { db.setTransactionSuccessful(); db.endTransaction() }
    override fun rollback() { db.endTransaction() }
}

/** The system picker's results (a zip or a JSON file from ACTION_OPEN_DOCUMENT, a folder from ACTION_OPEN_DOCUMENT_TREE) as a [TakeoutSource]. */
object TakeoutSources {
    fun fromUri(ctx: Context, uri: Uri): TakeoutSource {
        val cr = ctx.contentResolver
        if (uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) return TreeSource(ctx, uri)
        var name = uri.lastPathSegment ?: "export"; var size = -1L
        runCatching { cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) { c.getString(0)?.let { name = it }; size = c.getLong(1) } } }
        if (size < 0) size = runCatching { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        val open = { cr.openInputStream(uri) ?: error("cannot open $uri") }
        val zip = open().use { ins -> val m = ByteArray(4); ins.read(m) == 4 && m[0] == 'P'.code.toByte() && m[1] == 'K'.code.toByte() }
        return if (zip) ZipSource(name, size, open) else FileSource(name, size, open)
    }
}

/** A folder picked through ACTION_OPEN_DOCUMENT_TREE: every .json below it, found with DocumentsContract (no storage permission). */
class TreeSource(private val ctx: Context, private val tree: Uri) : TakeoutSource {
    override val label: String = tree.lastPathSegment ?: "folder"
    private class Doc(val uri: Uri, val path: String, val size: Long)
    private val docs: List<Doc> by lazy {
        val out = ArrayList<Doc>()
        fun walk(docId: String, path: String) {
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                val dirs = ArrayList<Pair<String, String>>()
                while (c.moveToNext()) {
                    val id = c.getString(0); val nm = c.getString(1) ?: continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) dirs += id to "$path$nm/"
                    else if (TakeoutImporter.accepts(nm)) out += Doc(DocumentsContract.buildDocumentUriUsingTree(tree, id), path + nm, if (c.isNull(3)) 0L else c.getLong(3))
                }
                dirs.forEach { walk(it.first, it.second) }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "")
        out.sortedBy { it.path }
    }
    override val totalBytes: Long get() = docs.sumOf { it.size }
    private var read = 0L
    override fun bytesRead() = read
    override fun entries(): Sequence<TakeoutEntry> = docs.asSequence().map { d ->
        TakeoutEntry(d.path, d.size) {
            val ins: InputStream = ctx.contentResolver.openInputStream(d.uri) ?: error("cannot open ${d.path}")
            object : java.io.FilterInputStream(ins) {
                override fun read(): Int = super.read().also { if (it >= 0) read++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
            }
        }
    }
}

/** What the UI shows of an import. */
sealed class ImportState {
    object Idle : ImportState()
    class Running(val source: String, val progress: ImportProgress?, val phase: String) : ImportState()
    class Done(val summary: ImportSummary, val segments: Int) : ImportState()
    class Failed(val message: String) : ImportState()
}

/**
 * #1853 one import at a time (the thread is the host's: Cloud Nav's foreground service, or [start]'s plain thread): parse and store ([TakeoutImporter]), then rebuild the segments and rollups
 * ([TakeoutRebuild]) and call [afterImport] (Cloud Nav rebuilds its aggregates there). Cancel stops at the next record.
 */
object ImportController {
    @Volatile var state: ImportState = ImportState.Idle
        private set
    @Volatile private var cancel = false
    /** The host's hook once segments and rollups are rebuilt: Cloud Nav's aggregates (Trips, Places, Cities, World, Insights). */
    @Volatile var afterImport: ((Context) -> Unit)? = null
    /** #1887 the host's offline gazetteer: names the imported places that came without an address (a Timeline KML or export: coordinates only). */
    @Volatile var gazetteer: ((Context) -> Gazetteer?)? = null

    val running: Boolean get() = state is ImportState.Running

    fun cancel() { cancel = true }

    /** Takes the one import slot for [uri] (state Running at once, so the UI shows it); false when one is already running. */
    fun claim(uri: Uri): Boolean = synchronized(this) { if (running) false else { state = ImportState.Running(uri.lastPathSegment ?: "", null, "reading"); cancel = false; true } }

    /**
     * Starts the import of [uri] on a plain thread; false when one is already running. Cloud Nav runs imports in its foreground
     * service (TakeoutImportService, #1853) and falls back here only when the service cannot be held.
     */
    fun start(ctx: Context, uri: Uri, zone: ZoneId = ZoneId.systemDefault(), onFinished: () -> Unit = {}): Boolean {
        if (!claim(uri)) return false
        startClaimed(ctx, uri, zone, onFinished)
        return true
    }

    /** The thread half of [start], for a caller that already holds the slot from [claim]. */
    fun startClaimed(ctx: Context, uri: Uri, zone: ZoneId = ZoneId.systemDefault(), onFinished: () -> Unit = {}) {
        val app = ctx.applicationContext
        Thread({ runSync(app, uri, zone); onFinished() }, "takeout-import").apply { isDaemon = true; start() }
    }

    /** The whole import on the calling thread (the thread of [start], and the emulator test). */
    fun runSync(app: Context, uri: Uri, zone: ZoneId = ZoneId.systemDefault()): ImportState {
        val db = MapsDb.get(app)
        val sql = db.sqlExec()
        var id = -1L
        try {
            val source = TakeoutSources.fromUri(app, uri)
            state = ImportState.Running(source.label, null, "reading")
            id = (sql.rows("SELECT COALESCE(MAX(id), 0) + 1 FROM imports;")[0][0] as Long)
            sql.exec("INSERT INTO imports (id, name, started_ms, status) VALUES (?, ?, ?, 'running');", listOf(id, source.label, System.currentTimeMillis()))
            val summary = TakeoutImporter.run(source, sql, id, zone, { p -> state = ImportState.Running(source.label, p, "reading") }, { cancel })
            state = ImportState.Running(source.label, null, "rebuilding")
            val geo = if (summary.found) runCatching { gazetteer?.invoke(app) }.getOrNull() else null
            val segs = if (summary.found) TakeoutRebuild.run(sql, zone, cancelled = { false }, geo = geo) else 0
            if (summary.found) runCatching { afterImport?.invoke(app) }
            sql.exec("UPDATE imports SET finished_ms=?, status=?, files=?, days=?, visits=?, activities=?, places=?, fixes=?, signals=?, skipped=?, first_ms=?, last_ms=? WHERE id=?;",
                listOf(System.currentTimeMillis(), if (summary.cancelled) "cancelled" else "done", summary.files, summary.days, summary.visits, summary.activities, summary.places, summary.fixes,
                    summary.signals, summary.skipped, summary.firstMs, summary.lastMs, id))
            return ImportState.Done(summary, segs).also { state = it }
        } catch (x: Throwable) {
            if (id >= 0) runCatching { sql.exec("UPDATE imports SET finished_ms=?, status='failed', error=? WHERE id=?;", listOf(System.currentTimeMillis(), x.message?.take(300), id)) }
            return ImportState.Failed(x.message ?: x.javaClass.simpleName).also { state = it }
        }
    }

    fun reset() { if (!running) state = ImportState.Idle }
}
