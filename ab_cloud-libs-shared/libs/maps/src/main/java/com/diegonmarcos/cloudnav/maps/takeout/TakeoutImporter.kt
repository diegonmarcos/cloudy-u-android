package com.diegonmarcos.cloudnav.maps.takeout

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.time.ZoneId
import java.util.zip.ZipInputStream

/** One file of an export. [open] is valid only until the next entry of the same source is asked for (zip streams are sequential). */
class TakeoutEntry(val name: String, val size: Long, val open: () -> InputStream)

/** A zip, a folder or a single file; [bytesRead] / [totalBytes] drive the progress bar. */
interface TakeoutSource {
    val label: String
    val totalBytes: Long
    fun bytesRead(): Long
    fun entries(): Sequence<TakeoutEntry>
    fun close() {}
}

private class CountingStream(inp: InputStream, val onRead: (Int) -> Unit) : FilterInputStream(inp) {
    override fun read(): Int = super.read().also { if (it >= 0) onRead(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onRead(it) }
}

private class NonClosing(inp: InputStream) : FilterInputStream(inp) { override fun close() {} }

/** A Takeout zip streamed entry by entry (never unpacked to disk, never held in memory); progress counts the compressed bytes. */
class ZipSource(override val label: String, override val totalBytes: Long, private val open: () -> InputStream) : TakeoutSource {
    private var read = 0L
    override fun bytesRead() = read
    override fun entries(): Sequence<TakeoutEntry> = sequence {
        ZipInputStream(CountingStream(open()) { read += it }).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) continue
                yield(TakeoutEntry(e.name, e.size) { NonClosing(zis) })
            }
        }
    }
}

/** One loose JSON file (Records.json, Timeline.json, one month of Semantic Location History). */
class FileSource(override val label: String, override val totalBytes: Long, private val open: () -> InputStream) : TakeoutSource {
    private var read = 0L
    override fun bytesRead() = read
    override fun entries(): Sequence<TakeoutEntry> = sequenceOf(TakeoutEntry(label, totalBytes) { CountingStream(open()) { read += it } })
}

/** A folder of an extracted export (plain files; the app's SAF folder source has the same shape). */
class DirSource(private val root: File) : TakeoutSource {
    override val label = root.name
    private val files: List<File> by lazy { root.walkTopDown().filter { it.isFile && TakeoutImporter.accepts(it.name) }.sortedBy { it.path }.toList() }
    override val totalBytes: Long get() = files.sumOf { it.length() }
    private var read = 0L
    override fun bytesRead() = read
    override fun entries(): Sequence<TakeoutEntry> = files.asSequence().map { f -> TakeoutEntry(f.relativeTo(root).path, f.length()) { CountingStream(f.inputStream()) { read += it } } }
}

class ImportProgress(val bytesDone: Long, val bytesTotal: Long, val file: String, val filesDone: Int, val fixes: Long, val visits: Long, val activities: Long, val signals: Long) {
    val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0).toFloat()
}

class ImportSummary(val files: Int, val days: Int, val visits: Long, val activities: Long, val places: Int, val fixes: Long, val signals: Long, val skipped: Int,
                    val skipReasons: Map<String, Int>, val failedFiles: List<String>, val formats: Set<TakeoutFormat>, val firstMs: Long?, val lastMs: Long?, val cancelled: Boolean) {
    val found: Boolean get() = visits + activities + fixes + signals > 0
}

/**
 * #1853 the import engine: every .json of the source is streamed through [TakeoutParsers] (the content, not the file name, says which
 * of the three formats it is; #1887 a .kml / .xml, or any content starting with `<`, through [TakeoutKml]) into a [TakeoutWriter]. Idempotent: rows carry stable keys, so the same export imported twice leaves the
 * same rows. Cancel is checked per record; what was written before it stays and a re-import completes it. The rebuild of segments,
 * rollups and aggregates is the caller's next step ([TakeoutRebuild]).
 */
object TakeoutImporter {
    /** #1887 the files an import reads: JSON (the three Google formats) and KML (`.kml`, `.kml.xml`, `.xml`; a KMZ's doc.kml). The content decides the parser. */
    fun accepts(name: String): Boolean = name.endsWith(".json", true) || name.endsWith(".kml", true) || name.endsWith(".xml", true)

    /** #1887 the stream starts with `<` (after a BOM or blanks): KML, else JSON. The reader is left where it was. */
    internal fun isXml(r: java.io.BufferedReader): Boolean {
        r.mark(4096)
        try {
            repeat(4096) {
                val c = r.read()
                if (c < 0) return false
                val ch = c.toChar()
                if (!ch.isWhitespace() && ch != '\uFEFF') return ch == '<'
            }
            return false
        } finally { r.reset() }
    }

    fun run(source: TakeoutSource, sql: SqlExec, importId: Long, zone: ZoneId = ZoneId.systemDefault(), progress: (ImportProgress) -> Unit = {},
            cancelled: () -> Boolean = { false }): ImportSummary {
        val w = TakeoutWriter(sql, importId, zone)
        var files = 0; var current = ""
        val failed = ArrayList<String>(); val formats = linkedSetOf<TakeoutFormat>()
        var wasCancelled = false
        var lastTick = 0L
        fun tick(force: Boolean = false) {
            val now = System.nanoTime()
            if (!force && now - lastTick < 150_000_000L) return
            lastTick = now
            val c = w.counts
            progress(ImportProgress(source.bytesRead(), source.totalBytes, current, files, c[TkTable.FIXES] ?: 0, c[TkTable.VISITS] ?: 0, c[TkTable.ACTIVITIES] ?: 0, c[TkTable.SIGNALS] ?: 0))
        }
        w.begin()
        try {
            for (e in source.entries()) {
                if (cancelled()) { wasCancelled = true; break }
                if (source !is FileSource && !accepts(e.name)) continue   // #1887 a single picked file is read whatever its name; its content decides
                current = e.name; files++
                try {
                    e.open().use { ins ->
                        val r = InputStreamReader(ins, Charsets.UTF_8).buffered(64 * 1024)
                        formats += if (isXml(r)) TakeoutKml.parse(r, w, onProgress = { tick() }, cancelled = cancelled)
                        else TakeoutParsers.parse(r, w, onProgress = { tick() }, cancelled = cancelled)
                    }
                } catch (c: ImportCancelled) { wasCancelled = true; break }
                catch (x: Exception) { failed += "${e.name}: ${x.message?.take(120)}" }
                tick(true)
            }
            w.finish(true)
        } catch (x: Throwable) { w.finish(false); throw x }
        tick(true)
        val c = w.counts
        return ImportSummary(files, w.days, c[TkTable.VISITS] ?: 0, c[TkTable.ACTIVITIES] ?: 0, w.places, c[TkTable.FIXES] ?: 0, c[TkTable.SIGNALS] ?: 0, w.skippedCount, w.skipReasons, failed, formats,
            w.firstMs.takeIf { it != Long.MAX_VALUE }, w.lastMs.takeIf { it != Long.MIN_VALUE }, wasCancelled)
    }
}
