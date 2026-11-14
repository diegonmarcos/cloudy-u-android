package com.diegonmarcos.clouddrive.files.convert

import java.io.OutputStream
import java.util.Locale

/**
 * A minimal PDF 1.4 WRITER for text: A4 pages, the standard Courier faces (no font is embedded, so
 * a character is exactly 0.6 em wide and line breaking needs no metrics), WinAnsi text. It exists
 * because the fleet keeps exactly ONE in-process PDF engine, the pdfium reader (PDF-ENGINE.md,
 * test-drive-pdf-engine.sh P2 forbids the platform's PDF classes), and writing a text PDF needs
 * none of it. Pure JVM; PdfWriterTest parses the file structure back.
 */
object PdfWriter {

    const val PAGE_W = 595
    const val PAGE_H = 842
    const val MARGIN = 48
    private const val CHAR_EM = 0.6f
    private const val LEADING = 1.3f

    class Line(val text: String, val size: Float = 10f, val bold: Boolean = false, val indent: Float = 0f)

    /** The content stream of every page, in order. Always at least one page. */
    fun pages(lines: List<Line>): List<String> {
        val pages = ArrayList<String>()
        var sb = StringBuilder()
        var y = (PAGE_H - MARGIN).toFloat()
        for (line in lines) {
            val perLine = maxOf(1, ((PAGE_W - 2 * MARGIN - line.indent) / (line.size * CHAR_EM)).toInt())
            val height = line.size * LEADING
            for (piece in wrap(line.text, perLine)) {
                if (y - height < MARGIN) { pages += sb.toString(); sb = StringBuilder(); y = (PAGE_H - MARGIN).toFloat() }
                y -= height
                if (piece.isNotBlank()) {
                    sb.append("BT /F").append(if (line.bold) 2 else 1).append(' ').append(num(line.size)).append(" Tf ")
                        .append(num(MARGIN + line.indent)).append(' ').append(num(y)).append(" Td (").append(escape(piece)).append(") Tj ET\n")
                }
            }
        }
        pages += sb.toString()
        return pages
    }

    fun write(lines: List<Line>, out: OutputStream, cancelled: () -> Boolean = { false }) {
        val streams = pages(lines)
        val total = 4 + 2 * streams.size // objects 1..total
        val offsets = LongArray(total + 1)
        var pos = 0L
        fun put(s: String) { val b = s.toByteArray(Charsets.ISO_8859_1); out.write(b); pos += b.size }
        fun obj(n: Int, body: String) { offsets[n] = pos; put("$n 0 obj\n$body\nendobj\n") }

        put("%PDF-1.4\n")
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Count ${streams.size} /Kids [" + streams.indices.joinToString(" ") { "${5 + 2 * it} 0 R" } + "] >>")
        obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>")
        obj(4, "<< /Type /Font /Subtype /Type1 /BaseFont /Courier-Bold /Encoding /WinAnsiEncoding >>")
        streams.forEachIndexed { i, content ->
            if (cancelled()) throw java.io.IOException("cancelled")
            obj(5 + 2 * i, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $PAGE_W $PAGE_H] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents ${6 + 2 * i} 0 R >>")
            obj(6 + 2 * i, "<< /Length ${content.length} >>\nstream\n$content\nendstream")
        }
        val xref = pos
        put("xref\n0 ${total + 1}\n0000000000 65535 f \n")
        for (n in 1..total) put(String.format(Locale.US, "%010d 00000 n \n", offsets[n]))
        put("trailer\n<< /Size ${total + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.flush()
    }


    /** One raster page: [jpeg] is a complete JPEG file of [width] x [height] pixels (embedded as is, DCTDecode). */
    class ImagePage(val jpeg: ByteArray, val width: Int, val height: Int)

    /** The A4 page (portrait, or landscape for a wide image) and the placement that fits [width] x [height] inside a 24 pt margin. */
    internal fun fit(width: Int, height: Int): FloatArray {
        val landscape = width > height
        val pw = (if (landscape) PAGE_H else PAGE_W).toFloat()
        val ph = (if (landscape) PAGE_W else PAGE_H).toFloat()
        val box = 24f
        val scale = minOf((pw - 2 * box) / maxOf(1, width), (ph - 2 * box) / maxOf(1, height))
        val w = width * scale; val h = height * scale
        return floatArrayOf(pw, ph, (pw - w) / 2, (ph - h) / 2, w, h)
    }

    /** Images to a PDF, one page each (no platform PDF class: the fleet keeps its ONE engine). Always at least one page is required. */
    fun writeImages(pages: List<ImagePage>, out: OutputStream, cancelled: () -> Boolean = { false }) {
        require(pages.isNotEmpty()) { "no image to write" }
        val total = 2 + 3 * pages.size
        val offsets = LongArray(total + 1)
        var pos = 0L
        fun put(b: ByteArray) { out.write(b); pos += b.size }
        fun put(s: String) = put(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(n: Int, body: String) { offsets[n] = pos; put("$n 0 obj\n$body\nendobj\n") }
        put("%PDF-1.4\n")
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Count ${pages.size} /Kids [" + pages.indices.joinToString(" ") { "${3 + 3 * it} 0 R" } + "] >>")
        pages.forEachIndexed { i, pg ->
            if (cancelled()) throw java.io.IOException("cancelled")
            val f = fit(pg.width, pg.height)
            val base = 3 + 3 * i
            obj(base, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f[0].toInt()} ${f[1].toInt()}] /Resources << /XObject << /Im0 ${base + 1} 0 R >> >> /Contents ${base + 2} 0 R >>")
            offsets[base + 1] = pos
            put("${base + 1} 0 obj\n<< /Type /XObject /Subtype /Image /Width ${pg.width} /Height ${pg.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${pg.jpeg.size} >>\nstream\n")
            put(pg.jpeg); put("\nendstream\nendobj\n")
            val content = "q ${num(f[4])} 0 0 ${num(f[5])} ${num(f[2])} ${num(f[3])} cm /Im0 Do Q"
            obj(base + 2, "<< /Length ${content.length} >>\nstream\n$content\nendstream")
        }
        val xref = pos
        put("xref\n0 ${total + 1}\n0000000000 65535 f \n")
        for (n in 1..total) put(String.format(Locale.US, "%010d 00000 n \n", offsets[n]))
        put("trailer\n<< /Size ${total + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.flush()
    }

    private fun num(f: Float): String = String.format(Locale.US, "%.2f", f)

    /** [text] cut into pieces of at most [width] characters, at a space where there is one. An empty line stays one empty piece. */
    internal fun wrap(text: String, width: Int): List<String> {
        if (text.length <= width) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > width) {
            val space = rest.lastIndexOf(' ', width)
            val cut = if (space > 0) space else width
            out += rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    private val WIN_ANSI_EXTRA = mapOf(
        '€' to 0x80, '‚' to 0x82, '„' to 0x84, '…' to 0x85, '†' to 0x86, '‡' to 0x87, '‰' to 0x89, '‹' to 0x8B,
        '‘' to 0x91, '’' to 0x92, '“' to 0x93, '”' to 0x94, '•' to 0x95, '–' to 0x96, '—' to 0x97, '™' to 0x99, '›' to 0x9B,
    )

    /** PDF literal-string body for [s]: ASCII as is, `\`, `(` and `)` escaped, Latin-1 and the WinAnsi extras as octal, the rest `?`. */
    internal fun escape(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            val code = c.code
            when {
                c == '\\' || c == '(' || c == ')' -> append('\\').append(c)
                code in 0x20..0x7E -> append(c)
                code in 0xA0..0xFF -> append('\\').append(String.format(Locale.US, "%03o", code))
                WIN_ANSI_EXTRA.containsKey(c) -> append('\\').append(String.format(Locale.US, "%03o", WIN_ANSI_EXTRA.getValue(c)))
                c == '\t' -> append("    ")
                else -> append('?')
            }
        }
    }
}
