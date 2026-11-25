package com.diegonmarcos.cloudlib.scanner

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale

/** One page ready to be written: its plan and the JPEG of the (filtered) page image. */
class PdfPageData(val plan: PlannedPage, val jpeg: ByteArray, val imgW: Int, val imgH: Int)

/**
 * #1839 a minimal multi-page searchable PDF (1.4), pure: each page is a full-page JPEG (DCTDecode) with the OCR words
 * laid over it as invisible text (render mode 3, Helvetica), so the page looks like the scan and can be searched and copied.
 * Text is WinAnsi; a character outside Latin-1 is written as '?' (the .txt export keeps the full text).
 */
object PdfWriter {
    fun write(out: OutputStream, pages: List<PdfPageData>, title: String = "") {
        require(pages.isNotEmpty()) { "a PDF needs a page" }
        val buf = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun obj(body: ByteArray) {
            offsets.add(buf.size())
            buf.write("${offsets.size} 0 obj\n".toByteArray(Charsets.ISO_8859_1)); buf.write(body); buf.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        fun s(t: String) = t.toByteArray(Charsets.ISO_8859_1)
        buf.write(s("%PDF-1.4\n%âãÏÓ\n"))
        // 1 catalog, 2 pages, 3 font, 4 info; then three objects per page: page, content, image.
        val kids = pages.indices.joinToString(" ") { "${5 + 3 * it} 0 R" }
        obj(s("<< /Type /Catalog /Pages 2 0 R >>"))
        obj(s("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>"))
        obj(s("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"))
        obj(s("<< /Title (${escape(title)}) /Producer (Cloud Camera Scanner) >>"))
        pages.forEachIndexed { i, p ->
            val pl = p.plan.placement
            val content = StringBuilder()
            content.append("q ${n(pl.w)} 0 0 ${n(pl.h)} ${n(pl.x)} ${n(pl.pageH - pl.y - pl.h)} cm /Im0 Do Q\n")
            if (p.plan.words.isNotEmpty()) {
                content.append("BT 3 Tr\n")
                for (w in p.plan.words) content.append("/F1 ${n(w.fontSize)} Tf ${n(w.hScale)} Tz 1 0 0 1 ${n(w.x)} ${n(w.y)} Tm (${escape(w.text)}) Tj\n")
                content.append("ET\n")
            }
            val cbytes = s(content.toString())
            obj(s("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(pl.pageW)} ${n(pl.pageH)}] /Resources << /Font << /F1 3 0 R >> /XObject << /Im0 ${7 + 3 * i} 0 R >> >> /Contents ${6 + 3 * i} 0 R >>"))
            obj(s("<< /Length ${cbytes.size} >>\nstream\n") + cbytes + s("endstream"))
            obj(s("<< /Type /XObject /Subtype /Image /Width ${p.imgW} /Height ${p.imgH} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${p.jpeg.size} >>\nstream\n") + p.jpeg + s("\nendstream"))
        }
        val xref = buf.size()
        buf.write(s("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n"))
        for (o in offsets) buf.write(s(String.format(Locale.ROOT, "%010d 00000 n \n", o)))
        buf.write(s("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 4 0 R >>\nstartxref\n$xref\n%%EOF\n"))
        out.write(buf.toByteArray())
    }

    private fun n(v: Double): String = String.format(Locale.ROOT, "%.2f", v)

    /** A PDF literal string body: Latin-1 only, with ( ) \ escaped and control characters as spaces. */
    fun escape(t: String): String = buildString {
        for (c in t) when {
            c == '(' || c == ')' || c == '\\' -> append('\\').append(c)
            c < ' ' -> append(' ')
            c.code > 255 -> append('?')
            else -> append(c)
        }
    }
}
