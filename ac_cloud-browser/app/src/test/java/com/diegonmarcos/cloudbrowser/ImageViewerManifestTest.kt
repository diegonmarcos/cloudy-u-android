package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.cloudbrowser.imageview.ImagePage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * #1741 Cloud Browser is an image viewer. The manifest's filters, the build.json declaration and the fleet's file-type routing
 * (editor-types.json, which Cloud Drive's FileTypeRouter reads) are held to say the same thing.
 */
class ImageViewerManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val appDir = File(System.getProperty("user.dir"))
    private val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map { File(appDir, it) }.first { it.isFile }
    private val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
    private val decl = JSONObject(File(appDir, "../build.json").readText()).getJSONObject("image_viewer")

    private data class Filter(val actions: Set<String>, val categories: Set<String>, val schemes: Set<String>, val mimes: Set<String>, val paths: Set<String>)

    private fun Element.attr(n: String) = getAttributeNS(ns, n)
    private fun Element.kids(tag: String): List<Element> = (0 until getElementsByTagName(tag).length).map { getElementsByTagName(tag).item(it) as Element }
    private fun activity(name: String): Element? =
        doc.getElementsByTagName("activity").let { l -> (0 until l.length).map { l.item(it) as Element } }.firstOrNull { it.attr("name") == name }

    private fun filters(name: String): List<Filter> = activity(name)!!.kids("intent-filter").map { f ->
        val data = f.kids("data")
        Filter(f.kids("action").map { it.attr("name") }.toSet(), f.kids("category").map { it.attr("name") }.toSet(),
            data.map { it.attr("scheme") }.filter { it.isNotEmpty() }.toSet(), data.map { it.attr("mimeType") }.filter { it.isNotEmpty() }.toSet(),
            data.map { it.attr("pathPattern").replace("\\\\", "\\") }.filter { it.isNotEmpty() }.toSet())
    }

    private fun strings(key: String) = decl.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
    private val view = "android.intent.action.VIEW"
    private val send = "android.intent.action.SEND"

    @Test fun `VIEW of image star from content and file is declared with the DEFAULT category`() {
        val f = filters(".MainActivity").filter { view in it.actions && "image/*" in it.mimes }
        assertTrue("a VIEW image/* filter exists", f.isNotEmpty())
        assertTrue(f.any { it.schemes == setOf("content", "file") && "android.intent.category.DEFAULT" in it.categories })
    }

    @Test fun `SEND of image star is declared`() {
        assertTrue(filters(".MainActivity").any { send in it.actions && "image/*" in it.mimes && "android.intent.category.DEFAULT" in it.categories })
    }

    @Test fun `every declared extension is claimed by name for the senders that give a generic type`() {
        val byName = filters(".MainActivity").filter { view in it.actions && "*/*" in it.mimes }.flatMap { it.paths }.toSet()
        for (e in strings("extensions")) for (p in listOf(".*\\.$e", ".*\\..*\\.$e", ".*\\..*\\..*\\.$e")) assertTrue("$e: $p", p in byName)
    }

    @Test fun `the manifest filters equal the build json declaration`() {
        assertEquals(setOf(view, send), strings("actions").toSet())
        assertEquals(setOf("content", "file"), strings("schemes").toSet())
        assertEquals(listOf("image/*"), strings("mime_types"))
        assertEquals("the page's extension set is the declared one", strings("extensions").toSet(), ImagePage.EXTENSIONS)
    }

    @Test fun `the declared extensions are image formats the fleet file-type routing already routes`() {
        val routing = listOf("../../ab_cloud-libs-shared/libs/editor-core/src/main/resources/editor-types.json", "../../../ab_cloud-libs-shared/libs/editor-core/src/main/resources/editor-types.json")
            .map { File(appDir, it) }.first { it.isFile }
        val viewers = JSONObject(routing.readText()).getJSONArray("viewers")
        val images = HashSet<String>()
        for (i in 0 until viewers.length()) {
            val img = viewers.getJSONObject(i).getJSONObject("formats").optJSONArray("image") ?: continue
            for (k in 0 until img.length()) images += img.getString(k)
        }
        val declared = strings("extensions").toSet()
        assertTrue("routing has image formats", images.isNotEmpty())
        // svg is routed by the router as its own (served/source) format, so it is allowed to be absent from the plain image list
        assertEquals("declared but not routed: ${declared - images - "svg"}", emptySet<String>(), declared - images - "svg")
    }

    @Test fun `the Convert to PDF door is signature guarded and answers the declared action`() {
        val a = activity(".imageview.ConvertPdfActivity")
        assertNotNull("declared", a)
        assertEquals("true", a!!.attr("exported"))
        assertEquals("com.diegonmarcos.cloud.permission.CONSTELLATION_DATA", a.attr("permission"))
        val f = filters(".imageview.ConvertPdfActivity").single()
        assertEquals(setOf(decl.getString("convert_pdf_action")), f.actions)
        for (t in strings("convert_pdf_types")) assertTrue("$t is accepted", t in f.mimes)
    }

    @Test fun `the text and saved-page filters are still there (nothing removed)`() {
        val all = filters(".MainActivity")
        for (m in listOf("text/plain", "application/x-bittorrent", "multipart/related")) assertTrue("$m kept", all.any { m in it.mimes })
        assertTrue(all.any { "http" in it.schemes || it.schemes.isEmpty() })
    }
}
