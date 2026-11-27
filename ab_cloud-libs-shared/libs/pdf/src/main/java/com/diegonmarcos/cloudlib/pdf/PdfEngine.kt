package com.diegonmarcos.cloudlib.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.Bookmark
import io.legere.pdfiumandroid.api.FindFlags
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * THE one in-process PDF engine of the fleet (#577, constraint #463).
 *
 * pdfium — the engine inside Chrome — through io.legere:pdfiumandroid (Apache-2.0, pinned in
 * build.json::pdf.engine), reached by every other app through the manifest, never copied in.
 * This is the only file that names the library; PdfReaderView, PdfReaderActivity and
 * PdfConversion.convertPdf all go through it, so swapping the engine again is a one-file change
 * and one-pdf-engine-guard has exactly one place to look.
 *
 * It replaced pdf.js-in-a-WebView, which cost a base64 copy of the whole file over the bridge
 * (a 24 MB ceiling and seconds of parse), drew one page at a time on a single canvas, and ran
 * the PDF worker on the UI thread. pdfium opens from a file descriptor and reads only what a
 * page needs, so open time no longer depends on file size.
 *
 * Thread model: pdfiumandroid serialises native calls behind one global lock, so any method
 * here may be called from any thread. Callers keep long work (render, search, extraction) off
 * the UI thread themselves.
 */
class PdfEngine private constructor(
    private val document: PdfDocument,
    private val descriptor: ParcelFileDescriptor,
    private val spooled: File?
) : Closeable {

    val pageCount: Int = document.getPageCount()

    @Volatile
    private var closed = false

    /** A page's size in PDF points, or null if the page will not load. */
    fun pageSize(index: Int): FloatArray? {
        if (closed) return null
        val page = document.openPage(index) ?: return null
        return page.use { floatArrayOf(it.getPageWidthPoint().toFloat(), it.getPageHeightPoint().toFloat()) }
    }

    /**
     * Draws page [index] into [bitmap]: the whole page is scaled to [drawW] x [drawH] and its
     * top-left lands at ([startX], [startY]) in the bitmap, so a NEGATIVE start renders just a
     * window of a page too large to hold whole. That window is how zoom stays sharp at 6x
     * without a 100 MB bitmap. The canvas is filled white, not pdfium's default grey, so a
     * one-pixel rounding gap at a page edge is not a visible seam.
     */
    fun render(index: Int, bitmap: Bitmap, startX: Int, startY: Int, drawW: Int, drawH: Int): Boolean {
        if (closed) return false
        val page = document.openPage(index) ?: return false
        page.use {
            it.renderPageBitmap(
                bitmap, startX, startY, drawW, drawH,
                renderAnnot = true,
                canvasColor = WHITE,
                pageBackgroundColor = WHITE
            )
        }
        return true
    }

    /** The whole text layer of one page; empty for an image-only page (a scan). */
    fun pageText(index: Int): String {
        if (closed) return ""
        val page = document.openPage(index) ?: return ""
        return page.use { p ->
            p.openTextPage().use { t ->
                val n = t.textPageCountChars()
                if (n <= 0) "" else (t.textPageGetText(0, n) ?: "")
            }
        }
    }

    /** Every match of [query] on one page as (first char index, char count) pairs. */
    fun findOnPage(index: Int, query: String, matchCase: Boolean): List<IntArray> {
        val hits = ArrayList<IntArray>()
        if (closed || query.isEmpty()) return hits
        val page = document.openPage(index) ?: return hits
        page.use { p ->
            p.openTextPage().use { t ->
                val flags: Set<FindFlags> = if (matchCase) setOf(FindFlags.MatchCase) else emptySet()
                val find = t.findStart(query, flags, 0) ?: return hits
                find.use { f ->
                    while (f.findNext()) hits.add(intArrayOf(f.getSchResultIndex(), f.getSchCount()))
                }
            }
        }
        return hits
    }

    /**
     * Rectangles covering chars [start, start+count) of a page, as FRACTIONS of the page (0..1,
     * y down). A fraction survives every zoom and relayout, so a highlight is computed once and
     * never again.
     */
    fun rangeRects(index: Int, start: Int, count: Int): List<RectF> {
        val out = ArrayList<RectF>()
        if (closed) return out
        val page = document.openPage(index) ?: return out
        page.use { p ->
            p.openTextPage().use { t ->
                val rects = t.textPageGetRectsForRanges(intArrayOf(start, count)) ?: return out
                for (r in rects) out.add(toFraction(p.mapRectToDevice(0, 0, FRACTION_BASE, FRACTION_BASE, 0, r.rect)))
            }
        }
        return out
    }

    /** The char under a page-fraction point, or -1. */
    fun charAt(index: Int, fx: Float, fy: Float): Int {
        if (closed) return -1
        val page = document.openPage(index) ?: return -1
        return page.use { p ->
            p.openTextPage().use { t ->
                val at = p.mapDeviceCoordsToPage(
                    0, 0, FRACTION_BASE, FRACTION_BASE, 0,
                    (fx * FRACTION_BASE).toInt(), (fy * FRACTION_BASE).toInt()
                )
                t.textPageGetCharIndexAtPos(at.x.toDouble(), at.y.toDouble(), TOLERANCE_PT, TOLERANCE_PT)
            }
        }
    }

    /** A tappable link on a page: [rect] is a page fraction; a link goes to a page or a URL. */
    class PageLink(val rect: RectF, val page: Int?, val uri: String?)

    fun links(index: Int): List<PageLink> {
        val out = ArrayList<PageLink>()
        if (closed) return out
        val page = document.openPage(index) ?: return out
        page.use { p ->
            for (l in p.getPageLinks()) {
                val dest = l.destPageIdx?.takeIf { it >= 0 }
                if (dest == null && l.uri.isNullOrEmpty()) continue
                out.add(PageLink(toFraction(p.mapRectToDevice(0, 0, FRACTION_BASE, FRACTION_BASE, 0, l.bounds)), dest, l.uri))
            }
        }
        return out
    }

    class OutlineItem(val title: String, val page: Int, val depth: Int)

    /** The document outline, flattened depth-first. Empty when the PDF has none. */
    fun outline(): List<OutlineItem> {
        val out = ArrayList<OutlineItem>()
        if (closed) return out
        flatten(document.getTableOfContents(), 0, out)
        return out
    }

    private fun flatten(nodes: List<Bookmark>, depth: Int, out: MutableList<OutlineItem>) {
        if (depth > OUTLINE_DEPTH_CEILING) return
        for (b in nodes) {
            out.add(OutlineItem(b.title?.trim().orEmpty().ifEmpty { "—" }, b.pageIdx.toInt(), depth))
            flatten(b.children, depth + 1, out)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { document.close() } catch (error: Exception) { /* already gone */ }
        try { descriptor.close() } catch (error: IOException) { /* already gone */ }
        spooled?.delete()
    }

    private fun toFraction(r: android.graphics.Rect): RectF = RectF(
        Math.min(r.left, r.right).toFloat() / FRACTION_BASE,
        Math.min(r.top, r.bottom).toFloat() / FRACTION_BASE,
        Math.max(r.left, r.right).toFloat() / FRACTION_BASE,
        Math.max(r.top, r.bottom).toFloat() / FRACTION_BASE
    )

    /** Thrown for a document that needs a password the caller has not supplied (or got wrong). */
    class PasswordRequired : IOException("password required")

    companion object {
        /** 0xFFFFFFFF as an Int (opaque white). */
        private const val WHITE = -1

        /** Virtual page size that page-fraction mapping is done at; ints keep pdfium's mapper exact enough. */
        private const val FRACTION_BASE = 100_000

        /** Hit-test slop around a press, in PDF points (a fingertip is not a pixel). */
        private const val TOLERANCE_PT = 12.0

        private const val OUTLINE_DEPTH_CEILING = 32

        /**
         * Opens [uri] (file:// or content://) without reading it into memory.
         *
         * A content provider that hands over a pipe instead of a seekable file cannot be opened
         * by pdfium, which seeks; that one case is spooled to the cache directory and opened
         * from there, and the copy is deleted on [close].
         *
         * @throws PasswordRequired when the document is encrypted and [password] is missing or wrong
         * @throws IOException for anything else that stops the document opening
         */
        @Throws(IOException::class)
        fun open(context: Context, uri: Uri, password: String?): PdfEngine {
            val core = PdfiumCore(context)
            var spooled: File? = null
            var pfd = descriptorFor(context, uri)
            try {
                val doc = try {
                    core.newDocument(pfd, password)
                } catch (error: io.legere.pdfiumandroid.api.PdfPasswordException) {
                    throw PasswordRequired()
                } catch (error: IOException) {
                    // Not a password problem and not seekable: retry from a private copy once.
                    if (uri.scheme != "content") throw error
                    pfd.close()
                    val copy = spool(context, uri)
                    spooled = copy
                    pfd = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
                    try {
                        core.newDocument(pfd, password)
                    } catch (again: io.legere.pdfiumandroid.api.PdfPasswordException) {
                        throw PasswordRequired()
                    }
                }
                return PdfEngine(doc, pfd, spooled)
            } catch (error: Throwable) {
                try { pfd.close() } catch (ignored: IOException) { /* nothing to release */ }
                spooled?.delete()
                throw error
            }
        }

        private fun descriptorFor(context: Context, uri: Uri): ParcelFileDescriptor =
            if (uri.scheme == "content") {
                context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("the document cannot be opened")
            } else {
                val path = uri.path ?: throw IOException("the document has no path")
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            }

        /** A private, readable copy of a content:// document, also what Share hands to other apps. */
        @Throws(IOException::class)
        fun spool(context: Context, uri: Uri): File {
            val dir = File(context.cacheDir, "pdf-spool").apply { mkdirs() }
            val copy = File.createTempFile("doc-", ".pdf", dir)
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("the document cannot be opened")
            input.use { src -> copy.outputStream().use { dst -> src.copyTo(dst) } }
            return copy
        }
    }
}
