package com.diegonmarcos.cloudlib.scanner

import android.graphics.Bitmap
import com.diegonmarcos.cloudlib.pdf.PdfEditSession
import com.diegonmarcos.cloudlib.pdf.PdfPages
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * #1908 the PDF tools of the Tools hub, on bytes: merge, split, extract, rotate, reorder, delete, protect, compress and PDF to
 * images. Page edits that libs:pdf already has (rotate, reorder, delete) go through its PdfEditSession; PdfBox is used directly
 * only for what a session does not do (importing pages from several files, encryption, image recompression).
 * Page numbers are 0-based here; the screens show them 1-based.
 */
object PdfTools {
    fun pageCount(pdf: ByteArray, password: String = ""): Int = PDDocument.load(pdf, password).use { it.numberOfPages }

    /** The documents in [inputs] joined in order into one. */
    fun merge(inputs: List<ByteArray>): ByteArray {
        require(inputs.isNotEmpty()) { "nothing to merge" }
        val sources = inputs.map { PDDocument.load(it) }
        try {
            val dest = PDDocument()
            for (s in sources) for (i in 0 until s.numberOfPages) dest.importPage(s.getPage(i))
            return save(dest)
        } finally { sources.forEach { runCatching { it.close() } } }
    }

    /** One new document with just [pages] of [pdf], in the order given (extract, and reorder when it lists every page). */
    fun extract(pdf: ByteArray, pages: List<Int>): ByteArray {
        require(pages.isNotEmpty()) { "no pages chosen" }
        val src = PDDocument.load(pdf)
        try {
            val n = src.numberOfPages
            require(pages.all { it in 0 until n }) { "a page is out of range (document has $n)" }
            val dest = PDDocument()
            for (p in pages) dest.importPage(src.getPage(p))
            return save(dest)
        } finally { runCatching { src.close() } }
    }

    /** [pdf] cut into the page ranges of [ranges] (inclusive, 0-based): one document per range. */
    fun split(pdf: ByteArray, ranges: List<IntRange>): List<ByteArray> = ranges.map { r -> extract(pdf, r.toList()) }

    /** One document per page. */
    fun splitEveryPage(pdf: ByteArray): List<ByteArray> = split(pdf, (0 until pageCount(pdf)).map { it..it })

    /** Ranges from a typed list such as "1-3, 5, 7-9" (1-based), checked against [pageCount]; null when it does not parse or is out of range. */
    fun parseRanges(text: String, pageCount: Int): List<IntRange>? {
        val out = ArrayList<IntRange>()
        for (part in text.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val bits = part.split('-').map { it.trim() }
            val a = bits[0].toIntOrNull() ?: return null
            val b = if (bits.size == 1) a else if (bits.size == 2) (bits[1].toIntOrNull() ?: return null) else return null
            if (a < 1 || b < a || b > pageCount) return null
            out.add((a - 1)..(b - 1))
        }
        return out.ifEmpty { null }
    }

    /** [pages] turned by [degrees] (a multiple of 90, clockwise). Empty [pages] = every page. */
    fun rotate(pdf: ByteArray, pages: List<Int>, degrees: Int): ByteArray = PdfEditSession.open(pdf).use { s ->
        val targets = pages.ifEmpty { (0 until s.pageCount).toList() }
        for (p in targets) if (p in 0 until s.pageCount) s.rotatePage(p, degrees)
        s.save()
    }

    /** The pages in [order] (every page exactly once). */
    fun reorder(pdf: ByteArray, order: List<Int>): ByteArray = PdfEditSession.open(pdf).use { s -> s.reorder(order); s.save() }

    /** [pages] removed (a document keeps at least one page). */
    fun delete(pdf: ByteArray, pages: List<Int>): ByteArray = PdfEditSession.open(pdf).use { s ->
        for (p in pages.sortedDescending()) s.deletePage(p)
        s.save()
    }

    /** The document locked: [userPassword] opens it, [ownerPassword] (the user one when blank) keeps the permissions. 128-bit. */
    fun protect(pdf: ByteArray, userPassword: String, ownerPassword: String = ""): ByteArray {
        require(userPassword.isNotEmpty()) { "a password is needed" }
        val doc = PDDocument.load(pdf)
        try {
            val policy = StandardProtectionPolicy(ownerPassword.ifEmpty { userPassword }, userPassword, AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            return save(doc)
        } finally { runCatching { doc.close() } }
    }

    /** The password taken off a locked document. */
    fun unprotect(pdf: ByteArray, password: String): ByteArray {
        val doc = PDDocument.load(pdf, password)
        try {
            doc.isAllSecurityToBeRemoved = true
            return save(doc)
        } finally { runCatching { doc.close() } }
    }

    fun isEncrypted(pdf: ByteArray): Boolean = runCatching { PDDocument.load(pdf).use { it.isEncrypted } }.getOrElse { true }

    /** Every image in the document re-encoded as JPEG at [quality] (10..100) and no wider than [maxSide]; text and vectors are untouched. */
    fun compress(pdf: ByteArray, quality: Int, maxSide: Int): ByteArray {
        val doc = PDDocument.load(pdf)
        try {
            val q = quality.coerceIn(10, 100) / 100f
            for (page in doc.pages) {
                val res = page.resources ?: continue
                for (name in res.xObjectNames.toList()) {
                    val x = res.getXObject(name) as? PDImageXObject ?: continue
                    val src = x.image ?: continue
                    val scaled = if (maxOf(src.width, src.height) > maxSide) {
                        val f = maxSide.toDouble() / maxOf(src.width, src.height)
                        Bitmap.createScaledBitmap(src, maxOf(1, (src.width * f).toInt()), maxOf(1, (src.height * f).toInt()), true)
                    } else src
                    res.put(name, JPEGFactory.createFromImage(doc, scaled, q))
                }
            }
            return save(doc)
        } finally { runCatching { doc.close() } }
    }

    /** Every page drawn as a bitmap [widthPx] wide, through libs:pdf's PdfPages. The caller recycles them. */
    fun toImages(pdf: ByteArray, widthPx: Int, workDir: File, pages: List<Int> = emptyList()): List<Bitmap> {
        val tmp = File.createTempFile("pdf2img", ".pdf", workDir)
        try {
            tmp.writeBytes(pdf)
            return PdfPages(tmp).use { p ->
                val which = pages.ifEmpty { (0 until p.count).toList() }
                which.filter { it in 0 until p.count }.map { p.render(it, widthPx) }
            }
        } finally { tmp.delete() }
    }

    private fun save(doc: PDDocument): ByteArray = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
}
