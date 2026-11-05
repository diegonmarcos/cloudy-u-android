package com.diegonmarcos.cloudlib.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The edit round-trip on a tiny generated fixture: edit, save, reload, read it back. */
class PdfEditSessionTest {
    /** Three pages told apart by width: 200, 300, 400. */
    private fun fixture(): ByteArray = PdfEditSession.blank(listOf(200f to 300f, 300f to 200f, 400f to 400f))

    private fun widths(bytes: ByteArray) = PdfEditSession.open(bytes).use { s -> (0 until s.pageCount).map { s.pageSize(it).width } }

    private fun roundTrip(bytes: ByteArray, edit: (PdfEditSession) -> Unit): ByteArray = PdfEditSession.open(bytes).use { s -> edit(s); s.save() }

    @Test fun `the fixture has three pages in order`() {
        assertEquals(listOf(200f, 300f, 400f), widths(fixture()))
    }

    @Test fun `ink survives a save and reload with its strokes`() {
        val out = roundTrip(fixture()) { it.addInk(1, listOf(listOf(PdfPoint(10f, 10f), PdfPoint(60f, 80f), PdfPoint(120f, 20f)), listOf(PdfPoint(5f, 5f)))) }
        PdfEditSession.open(out).use { s ->
            assertEquals(emptyList<PdfAnnotationInfo>(), s.annotations(0))
            assertEquals(listOf("Ink"), s.annotations(1).map { it.subtype })
        }
        assertEquals("the other pages are untouched", listOf(200f, 300f, 400f), widths(out))
    }

    @Test fun `text and note annotations keep their contents`() {
        val out = roundTrip(fixture()) {
            it.addText(0, 20f, 100f, "Hello (world) \\ done")
            it.addNote(0, 50f, 200f, "remember this")
            it.addText(2, 10f, 10f, "   ")
        }
        PdfEditSession.open(out).use { s ->
            val a = s.annotations(0)
            assertEquals(listOf("FreeText", "Text"), a.map { it.subtype })
            assertEquals("Hello (world) \\ done", a[0].contents)
            assertEquals("remember this", a[1].contents)
            assertEquals("a blank text adds nothing", emptyList<PdfAnnotationInfo>(), s.annotations(2))
        }
    }

    @Test fun `deleting a page removes exactly it, and the last page cannot be deleted`() {
        val out = roundTrip(fixture()) { it.deletePage(1) }
        assertEquals(listOf(200f, 400f), widths(out))
        val one = roundTrip(roundTrip(out) { it.deletePage(0) }) { it.deletePage(0) }
        assertEquals(listOf(400f), widths(one))
    }

    @Test fun `reordering and moving pages change the order and nothing else`() {
        assertEquals(listOf(400f, 200f, 300f), widths(roundTrip(fixture()) { it.reorder(listOf(2, 0, 1)) }))
        assertEquals(listOf(300f, 400f, 200f), widths(roundTrip(fixture()) { it.movePage(0, 2) }))
        assertEquals(listOf(200f, 300f, 400f), widths(roundTrip(fixture()) { it.movePage(1, 1) }))
    }

    @Test fun `annotations travel with their page when it moves`() {
        val out = roundTrip(roundTrip(fixture()) { it.addNote(2, 10f, 10f, "on the third") }) { it.movePage(2, 0) }
        PdfEditSession.open(out).use { s ->
            assertEquals(listOf("on the third"), s.annotations(0).map { it.contents })
            assertEquals(400f, s.pageSize(0).width, 0f)
        }
    }

    @Test fun `rotating a page is kept`() {
        val out = roundTrip(fixture()) { it.rotatePage(0); it.rotatePage(0); it.rotatePage(2, -90) }
        PdfEditSession.open(out).use { s ->
            assertEquals(180, s.pageSize(0).rotation)
            assertEquals(270, s.pageSize(2).rotation)
        }
    }

    @Test fun `a reorder that is not a permutation is refused`() {
        PdfEditSession.open(fixture()).use { s ->
            var refused = false
            try { s.reorder(listOf(0, 0, 1)) } catch (e: IllegalArgumentException) { refused = true }
            assertTrue(refused)
        }
    }

    /** A one-page PDF with a text field named "name" and a checkbox-free form. */
    private fun formFixture(): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle(300f, 300f))
        doc.addPage(page)
        val form = PDAcroForm(doc)
        doc.documentCatalog.acroForm = form
        val field = PDTextField(form)
        field.partialName = "name"
        field.defaultAppearance = "/Helv 12 Tf 0 g"
        val widget = PDAnnotationWidget()
        widget.rectangle = PDRectangle(20f, 200f, 150f, 24f)
        widget.page = page
        field.widgets = listOf(widget)
        page.annotations = page.annotations.also { it.add(widget) }
        form.fields = listOf(field)
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    @Test fun `a form field is listed, filled and read back after a save`() {
        val src = formFixture()
        PdfEditSession.open(src).use { s ->
            assertEquals(listOf(PdfFormField("name", PdfFormField.Kind.Text, "")), s.formFields())
        }
        val out = roundTrip(src) { s ->
            assertEquals(listOf("name"), s.fillForm(mapOf("name" to "Alice", "missing" to "x")))
        }
        PdfEditSession.open(out).use { s ->
            assertEquals(listOf(PdfFormField("name", PdfFormField.Kind.Text, "Alice")), s.formFields())
        }
    }

    @Test fun `a pdf with no form lists no fields and fills none`() {
        PdfEditSession.open(fixture()).use { s ->
            assertEquals(emptyList<PdfFormField>(), s.formFields())
            assertEquals(emptyList<String>(), s.fillForm(mapOf("a" to "b")))
        }
    }
}
