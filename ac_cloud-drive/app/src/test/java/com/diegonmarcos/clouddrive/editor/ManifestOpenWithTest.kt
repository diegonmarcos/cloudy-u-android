package com.diegonmarcos.clouddrive.editor

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** #1537 "Open with Cloud Drive": the manifest's intent-filters claim every type the Editor shows, for VIEW and EDIT, on content:// and file://. */
class ManifestOpenWithTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val manifest: File = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map { File(System.getProperty("user.dir"), it) }.first { it.isFile }
    private val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)

    private data class Filter(val actions: Set<String>, val categories: Set<String>, val schemes: Set<String>, val mimes: Set<String>)

    private fun Element.attr(name: String) = getAttributeNS(ns, name)
    private fun Element.kids(tag: String): List<Element> = (0 until getElementsByTagName(tag).length).map { getElementsByTagName(tag).item(it) as Element }

    private fun filters(activity: String): List<Filter> {
        val a = doc.getElementsByTagName("activity").let { l -> (0 until l.length).map { l.item(it) as Element } }.firstOrNull { it.attr("name") == activity }
        assertNotNull("$activity is declared", a)
        return a!!.kids("intent-filter").map { f ->
            Filter(f.kids("action").map { it.attr("name") }.toSet(), f.kids("category").map { it.attr("name") }.toSet(), f.kids("data").map { it.attr("scheme") }.filter { it.isNotEmpty() }.toSet(), f.kids("data").map { it.attr("mimeType") }.filter { it.isNotEmpty() }.toSet())
        }
    }

    private val view = "android.intent.action.VIEW"
    private val edit = "android.intent.action.EDIT"
    private val main = filters(".MainActivity")
    private val pdf = filters(".PdfReaderActivity")

    // Android type matching: an exact type, a "<type>/<star>" wildcard, or the star-star wildcard.
    private fun claims(mimes: Set<String>, type: String) = mimes.any { it == "*/*" || it == type || (it.endsWith("/*") && type.startsWith(it.dropLast(1))) }

    private val openFilters get() = main.filter { view in it.actions }

    @Test fun `every open filter takes VIEW and EDIT for content and file with the DEFAULT category`() {
        assertTrue("MainActivity declares open filters", openFilters.size >= 3)
        for (f in openFilters) {
            assertTrue("EDIT too: $f", edit in f.actions)
            assertTrue("DEFAULT category: $f", "android.intent.category.DEFAULT" in f.categories)
            assertEquals("content and file: $f", setOf("content", "file"), f.schemes)
            assertTrue("a type: $f", f.mimes.isNotEmpty())
        }
    }

    @Test fun `Drive claims image video audio text json xml markdown html and the OpenDocument family`() {
        val mimes = openFilters.flatMap { it.mimes }.toSet()
        for (t in listOf("image/png", "image/webp", "image/heic", "image/avif", "image/svg+xml", "video/mp4", "video/x-matroska", "audio/mpeg", "audio/flac", "text/plain", "text/markdown", "text/html", "text/xml", "text/csv", "application/json", "application/xml",
            "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.presentation")) assertTrue("$t is claimed", claims(mimes, t))
        for (wild in listOf("image/*", "video/*", "audio/*", "text/*")) assertTrue("$wild is declared as a wildcard", wild in mimes)
    }

    @Test fun `a generic star-star Open with Cloud Drive exists`() {
        assertTrue(openFilters.any { "*/*" in it.mimes && it.schemes == setOf("content", "file") })
        assertTrue("any type is claimed", claims(openFilters.flatMap { it.mimes }.toSet(), "application/x-anything"))
    }

    @Test fun `application pdf is answered by the PDF reader alone so Drive shows once`() {
        val pdfFilter = pdf.firstOrNull { view in it.actions && "application/pdf" in it.mimes }
        assertNotNull("the PDF reader takes VIEW application/pdf", pdfFilter)
        assertEquals(setOf("content", "file"), pdfFilter!!.schemes)
        assertTrue("MainActivity does not take application/pdf by name", openFilters.none { "application/pdf" in it.mimes })
    }

    @Test fun `the launcher filter and the image viewer door are as declared`() {
        assertTrue(main.any { "android.intent.action.MAIN" in it.actions && "android.intent.category.LAUNCHER" in it.categories })
        val image = doc.getElementsByTagName("activity").let { l -> (0 until l.length).map { l.item(it) as Element } }.first { it.attr("name") == ".viewer.ImageViewerActivity" }
        assertEquals("the image viewer is in-app only now", "false", image.attr("exported"))
        assertFalse("and answers no VIEW filter", image.kids("intent-filter").isNotEmpty())
    }
}
