package com.diegonmarcos.cloudlib.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.fixup.PDDocumentFixup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import java.io.ByteArrayOutputStream
import java.io.File

/** What an annotation on a page looks like to the caller. */
data class PdfAnnotationInfo(val subtype: String, val contents: String)

/** A fillable field: its full [name], the [kind] and the current [value]. */
data class PdfFormField(val name: String, val kind: Kind, val value: String) {
    enum class Kind { Text, Checkbox, Choice, Other }
}

/**
 * One PDF open for editing, in memory. Every operation changes the document; [save] writes it out. Annotations carry their
 * own appearance stream (ink as strokes, text as a Helvetica line) so any viewer shows them. Page numbers are 0-based.
 */
class PdfEditSession private constructor(private val doc: PDDocument) : AutoCloseable {

    val pageCount: Int get() = doc.numberOfPages

    fun pageSize(index: Int): PdfPageSize {
        val p = doc.getPage(index)
        val box = p.mediaBox
        return PdfPageSize(box.width, box.height, box.lowerLeftX, box.lowerLeftY, p.rotation)
    }

    fun annotations(index: Int): List<PdfAnnotationInfo> =
        doc.getPage(index).annotations.map { PdfAnnotationInfo(it.subtype.orEmpty(), it.contents.orEmpty()) }

    // ── annotations ─────────────────────────────────────────────────────────

    /** Draws [strokes] (each a polyline in PDF space) as an Ink annotation in [rgb] (0xRRGGBB) with a line of [width] units. */
    fun addInk(index: Int, strokes: List<List<PdfPoint>>, rgb: Int = DEFAULT_INK, width: Float = 2f) {
        val pts = strokes.filter { it.isNotEmpty() }
        if (pts.isEmpty()) return
        val all = pts.flatten()
        val pad = width + 1f
        val box = PDRectangle(all.minOf { it.x } - pad, all.minOf { it.y } - pad, all.maxOf { it.x } - all.minOf { it.x } + 2 * pad, all.maxOf { it.y } - all.minOf { it.y } + 2 * pad)
        val (r, g, b) = rgbFloats(rgb)
        val content = StringBuilder("${n(r)} ${n(g)} ${n(b)} RG ${n(width)} w 1 J 1 j\n")
        val ink = COSArray()
        for (s in pts) {
            val one = COSArray()
            s.forEachIndexed { i, p ->
                one.add(COSFloat(p.x)); one.add(COSFloat(p.y))
                content.append(n(p.x)).append(' ').append(n(p.y)).append(if (i == 0) " m\n" else " l\n")
            }
            if (s.size == 1) content.append(n(s[0].x)).append(' ').append(n(s[0].y)).append(" l\n")
            ink.add(one)
            content.append("S\n")
        }
        val d = annotationDict("Ink", box)
        d.setItem(COSName.getPDFName("InkList"), ink)
        d.setItem(COSName.C, floats(r, g, b))
        val bs = COSDictionary()
        bs.setFloat(COSName.W, width)
        d.setItem(COSName.BS, bs)
        attach(index, d, box, content.toString(), withFont = false)
    }

    /** A visible line of text at ([x], [y]) (the baseline's left end) as a FreeText annotation. */
    fun addText(index: Int, x: Float, y: Float, text: String, size: Float = 12f, rgb: Int = DEFAULT_TEXT) {
        if (text.isBlank()) return
        val safe = latin1(text)
        val box = PDRectangle(x, y - size * 0.3f, maxOf(size * 0.6f * safe.length, size) + 4f, size * 1.4f)
        val (r, g, b) = rgbFloats(rgb)
        val d = annotationDict("FreeText", box)
        d.setString(COSName.CONTENTS, text)
        d.setString(COSName.DA, "${n(r)} ${n(g)} ${n(b)} rg /Helv ${n(size)} Tf")
        attach(index, d, box, "BT\n/Helv ${n(size)} Tf\n${n(r)} ${n(g)} ${n(b)} rg\n2 ${n(size * 0.3f)} Td\n(${pdfString(safe)}) Tj\nET\n", withFont = true, local = true)
    }

    /** A sticky note at ([x], [y]) holding [text]; viewers draw their own note icon. */
    fun addNote(index: Int, x: Float, y: Float, text: String) {
        val box = PDRectangle(x, y, NOTE_SIZE, NOTE_SIZE)
        val d = annotationDict("Text", box)
        d.setString(COSName.CONTENTS, text)
        d.setName(COSName.NAME, "Note")
        val page = doc.getPage(index)
        val list = page.annotations
        list.add(PDAnnotation.createAnnotation(d))
        page.annotations = list
    }

    // ── forms ───────────────────────────────────────────────────────────────

    fun formFields(): List<PdfFormField> {
        val form = acroForm() ?: return emptyList()
        return form.fieldTree.mapNotNull { f ->
            val type = f.fieldType ?: return@mapNotNull null
            val kind = when (type) {
                "Tx" -> PdfFormField.Kind.Text
                "Btn" -> PdfFormField.Kind.Checkbox
                "Ch" -> PdfFormField.Kind.Choice
                else -> PdfFormField.Kind.Other
            }
            PdfFormField(f.fullyQualifiedName.orEmpty(), kind, runCatching { f.valueAsString.orEmpty() }.getOrDefault(""))
        }
    }

    /**
     * Fills the text fields named in [values] and draws each value into the field's widget; a checkbox takes "Yes" or "Off".
     * Returns the names that were set. The values are written to the field dictionary directly, so no font has to be resolved.
     */
    fun fillForm(values: Map<String, String>): List<String> {
        val form = acroForm() ?: return emptyList()
        val done = ArrayList<String>()
        for ((name, value) in values) {
            val field = form.getField(name) ?: continue
            when (field.fieldType) {
                "Tx" -> {
                    field.cosObject.setString(COSName.V, value)
                    for (w in field.widgets) {
                        val rect = w.rectangle ?: continue
                        val box = PDRectangle(rect.width, rect.height)
                        val size = (rect.height * 0.6f).coerceIn(6f, 14f)
                        val content = "/Tx BMC\nq\nBT\n/Helv ${n(size)} Tf\n0 g\n2 ${n((rect.height - size) / 2 + size * 0.2f)} Td\n(${pdfString(latin1(value))}) Tj\nET\nQ\nEMC\n"
                        w.appearance = appearance(box, content, withFont = true)
                    }
                    done += name
                }
                "Btn" -> {
                    val on = value.equals("Yes", true) || value.equals("true", true) || value == "1"
                    val state = if (on) checkedState(field.widgets.firstOrNull()) else "Off"
                    field.cosObject.setName(COSName.V, state)
                    for (w in field.widgets) w.cosObject.setName(COSName.AS, state)
                    done += name
                }
            }
        }
        if (done.isNotEmpty()) form.cosObject.setBoolean(COSName.getPDFName("NeedAppearances"), false)
        return done
    }

    /** The form without the catalog's default fixup, which loads the standard fonts (needs the Android resource loader). */
    private fun acroForm() = doc.documentCatalog.getAcroForm(null as PDDocumentFixup?)

    // ── pages ───────────────────────────────────────────────────────────────

    fun deletePage(index: Int) {
        if (index in 0 until pageCount && pageCount > 1) doc.removePage(index)
    }

    /** Puts the pages in [order] (indexes of the current pages; each exactly once). */
    fun reorder(order: List<Int>) {
        require(order.sorted() == (0 until pageCount).toList()) { "order must hold every page exactly once" }
        val pages = (0 until pageCount).map { doc.getPage(it) }
        pages.forEach { doc.removePage(it) }
        order.forEach { doc.addPage(pages[it]) }
    }

    fun movePage(from: Int, to: Int) = reorder(PdfGeometry.moved(pageCount, from, to))

    fun rotatePage(index: Int, degrees: Int = 90) {
        val p = doc.getPage(index)
        p.rotation = (((p.rotation + degrees) % 360) + 360) % 360
    }

    fun save(): ByteArray {
        val out = ByteArrayOutputStream()
        doc.save(out)
        return out.toByteArray()
    }

    override fun close() = doc.close()

    // ── internals ───────────────────────────────────────────────────────────

    private fun annotationDict(subtype: String, box: PDRectangle): COSDictionary {
        val d = COSDictionary()
        d.setItem(COSName.TYPE, COSName.ANNOT)
        d.setName(COSName.SUBTYPE, subtype)
        d.setInt(COSName.F, PRINT_FLAG)
        d.setItem(COSName.RECT, floats(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY))
        return d
    }

    /** [local]: the appearance is drawn from its own origin (text); otherwise in page space (ink). */
    private fun attach(index: Int, d: COSDictionary, box: PDRectangle, content: String, withFont: Boolean, local: Boolean = false) {
        val annotation = PDAnnotation.createAnnotation(d)
        annotation.appearance = appearance(if (local) PDRectangle(box.width, box.height) else box, content, withFont)
        val page = doc.getPage(index)
        val list = page.annotations
        list.add(annotation)
        page.annotations = list
    }

    private fun appearance(box: PDRectangle, content: String, withFont: Boolean): PDAppearanceDictionary {
        val form = PDAppearanceStream(doc)
        form.bBox = box
        val resources = PDResources()
        if (withFont) {
            val font = COSDictionary()
            font.setItem(COSName.TYPE, COSName.FONT)
            font.setName(COSName.SUBTYPE, "Type1")
            font.setName(COSName.BASE_FONT, "Helvetica")
            font.setName(COSName.ENCODING, "WinAnsiEncoding")
            val fonts = COSDictionary()
            fonts.setItem(COSName.getPDFName("Helv"), font)
            fonts.setItem(COSName.getPDFName("Tx"), font)
            resources.cosObject.setItem(COSName.FONT, fonts)
        }
        form.resources = resources
        form.contentStream.createOutputStream().use { it.write(content.toByteArray(Charsets.ISO_8859_1)) }
        val ap = PDAppearanceDictionary()
        ap.setNormalAppearance(form)
        return ap
    }

    private fun checkedState(widget: com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget?): String {
        val normal = widget?.cosObject?.getDictionaryObject(COSName.AP) as? COSDictionary
        val n = normal?.getDictionaryObject(COSName.N) as? COSDictionary
        return n?.keySet()?.map { it.name }?.firstOrNull { it != "Off" } ?: "Yes"
    }

    companion object {
        const val DEFAULT_INK = 0xD32F2F
        const val DEFAULT_TEXT = 0x000000
        private const val PRINT_FLAG = 4
        private const val NOTE_SIZE = 20f

        fun open(bytes: ByteArray): PdfEditSession = PdfEditSession(PDDocument.load(bytes))

        fun open(file: File): PdfEditSession = open(file.readBytes())

        /** Opens [file], runs [block], writes the file back. The caller reloads its view. */
        fun edit(file: File, block: (PdfEditSession) -> Unit) {
            open(file).use { s ->
                block(s)
                file.writeBytes(s.save())
            }
        }

        /** A one-page-per-size PDF for tests and "new" documents: each pair is a page's width and height. */
        fun blank(pageSizes: List<Pair<Float, Float>>): ByteArray {
            val d = PDDocument()
            for ((w, h) in pageSizes) d.addPage(com.tom_roush.pdfbox.pdmodel.PDPage(PDRectangle(w, h)))
            return ByteArrayOutputStream().also { d.save(it); d.close() }.toByteArray()
        }

        /** A PDF number: plain decimal, never an exponent. */
        private fun n(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)

        private fun rgbFloats(rgb: Int) = Triple(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f)

        private fun floats(vararg v: Float): COSArray = COSArray().also { a -> v.forEach { a.add(COSFloat(it)) } }

        /** WinAnsi-safe: anything outside Latin-1 becomes '?'. */
        private fun latin1(s: String): String = s.map { if (it.code in 32..255) it else '?' }.joinToString("")

        private fun pdfString(s: String): String = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    }
}
