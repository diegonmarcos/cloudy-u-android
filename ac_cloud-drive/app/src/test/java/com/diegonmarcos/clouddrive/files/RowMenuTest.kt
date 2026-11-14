package com.diegonmarcos.clouddrive.files

import com.diegonmarcos.clouddrive.files.convert.Converter
import com.diegonmarcos.clouddrive.files.convert.ConverterRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-file dropdown is data: the converter registry and the archive types decide which items a row shows. */
class RowMenuTest {

    private val default = ConverterRegistry.default
    private fun items(name: String, registry: ConverterRegistry = default, dir: Boolean = false, local: Boolean = true) = RowMenu.itemsFor(name, dir, local, registry)

    private class Fake(override val id: String, private val ext: String) : Converter {
        override val label = "fake $id"
        override fun accepts(extension: String) = extension == ext
        override fun convert(source: File, destDir: File, progress: FileOps.Progress, cancelled: () -> Boolean): List<File> = emptyList()
    }

    @Test fun convertAppearsOnlyWhereTheRegistryHasAConverter() {
        for (name in listOf("a.mht", "a.mhtml", "a.png", "a.jpg", "a.jpeg", "a.webp", "a.md", "a.json", "a.txt")) {
            assertTrue("$name should offer Convert…", RowMenu.Item.CONVERT in items(name))
        }
        for (name in listOf("a.bin", "a.mp4", "a.zip", "README", "a.docx")) {
            assertTrue("$name should not offer Convert…", RowMenu.Item.CONVERT !in items(name))
        }
    }

    @Test fun anEmptyRegistryRemovesConvertEverywhere() {
        val empty = ConverterRegistry(emptyList())
        for (name in listOf("a.mht", "a.png", "a.md", "a.json", "a.txt")) assertEquals(listOf(RowMenu.Item.OPEN_WITH), items(name, empty))
    }

    @Test fun aNewConverterPlugsInWithoutTouchingTheMenu() {
        val registry = ConverterRegistry(emptyList()).plus(Fake("xyz-abc", "xyz"))
        assertEquals(listOf(RowMenu.Item.OPEN_WITH, RowMenu.Item.CONVERT), items("a.xyz", registry))
        assertEquals(listOf(RowMenu.Item.OPEN_WITH), items("a.abc", registry))
        assertEquals(listOf("xyz-abc"), registry.forName("A.XYZ").map { it.id })
    }

    @Test fun unzipAppearsOnlyForExtractableTypes() {
        for (name in listOf("a.zip", "a.jar", "a.apk", "a.tar", "a.tar.gz", "a.tgz", "a.tar.xz", "a.txz", "a.7z", "a.rar", "A.ZIP")) {
            assertEquals("$name", listOf(RowMenu.Item.OPEN_WITH, RowMenu.Item.UNZIP), items(name))
        }
        for (name in listOf("a.gz", "a.xz", "a.png", "a.zipx", ".zip", "zip")) assertTrue("$name", RowMenu.Item.UNZIP !in items(name))
    }

    @Test fun folderAndNonLocalRowsGetNoneOfThem() {
        assertEquals(emptyList<RowMenu.Item>(), items("a.zip", dir = true))
        assertEquals(emptyList<RowMenu.Item>(), items("a.zip", local = false))
        assertEquals(listOf(RowMenu.Item.OPEN_WITH), items("a.bin"))
    }

    @Test fun theDefaultRegistryOffersWhatTheTaskNames() {
        assertEquals(listOf("mhtml-html"), default.forName("p.mht").map { it.id })
        assertEquals(listOf("image-pdf", "to-jpg", "to-webp"), default.forName("p.png").map { it.id })
        assertEquals(listOf("image-pdf", "to-png", "to-webp"), default.forName("p.jpg").map { it.id })
        assertEquals(listOf("image-pdf", "to-jpg", "to-png"), default.forName("p.webp").map { it.id })
        assertEquals(listOf("md-html", "text-pdf"), default.forName("p.md").map { it.id })
        assertEquals(listOf("json-pretty", "text-pdf"), default.forName("p.json").map { it.id })
        assertEquals(listOf("text-pdf"), default.forName("p.txt").map { it.id })
        assertEquals(default.converters.size, default.converters.map { it.id }.toSet().size)
    }

    @Test fun openWithGetsTheTypeTheFileReallyIs() {
        assertEquals("text/markdown", MimeTypes.forName("n.md"))
        assertEquals("multipart/related", MimeTypes.forName("n.mht"))
        assertEquals("application/x-7z-compressed", MimeTypes.forName("n.7z"))
        assertEquals("image/webp", MimeTypes.forName("n.WEBP"))
        assertEquals("application/octet-stream", MimeTypes.forName("n"))
        assertEquals("application/octet-stream", MimeTypes.forName("n.zzz"))
        // the platform wins when it knows; octet-stream from the platform does not shadow the table
        assertEquals("text/x-special", MimeTypes.forName("n.md") { "text/x-special" })
        assertEquals("text/markdown", MimeTypes.forName("n.md") { "application/octet-stream" })
    }
}
