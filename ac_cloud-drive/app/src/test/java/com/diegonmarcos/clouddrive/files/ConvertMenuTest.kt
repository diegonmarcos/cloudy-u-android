package com.diegonmarcos.clouddrive.files

import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.files.convert.ConvertTargets
import com.diegonmarcos.clouddrive.files.convert.ConverterRegistry
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-selection dropdown's Convert section is the per-file one: same targets, same grouping, same engine
 * (ConverterRegistry / ToPdf / PdfConvert). Mixed types leave the unsupported targets unavailable with a reason.
 * Rule 16: every item of the previous menus is still declared (parity), and the dropdown rows are dense.
 */
class ConvertMenuTest {
    private val root = File(System.getProperty("user.dir") ?: ".").let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val files = Declarations.parseFiles(
        Json { ignoreUnknownKeys = true }.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["files"].toString(),
    )
    private val itemDecl = files.menu(FileMenu.ITEM)
    private val selDecl = files.menu(FileMenu.SELECTION)

    private fun enabledKeys(names: List<String>) = ConvertTargets.forNames(names).filter { it.enabled }.map { it.key }
    private fun target(names: List<String>, key: String) = ConvertTargets.forNames(names).first { it.key == key }

    @Test fun multiSelectOffersTheSameTargetsAsSingleFileForTheSameType() {
        for (name in listOf("a.png", "n.md", "p.mhtml", "d.json", "t.pdf", "t.txt", "z.zip")) {
            val single = ConvertTargets.forNames(listOf(name))
            val multi = ConvertTargets.forNames(listOf(name, "other_" + name, "third_" + name))
            assertEquals("same target list for $name", single.map { it.key to it.label }, multi.map { it.key to it.label })
            assertEquals("same availability for $name", single.map { it.enabled }, multi.map { it.enabled })
            // and the single-file offer IS the registry's: what the per-file Convert… dialog lists
            val conv = single.filter { it.kind == ConvertTargets.Kind.CONVERTER && it.enabled }.map { it.arg }
            assertEquals(ConverterRegistry.default.forName(name).map { it.id }, conv)
        }
        assertEquals(ConverterRegistry.default.converters.map { it.id }, ConvertTargets.forNames(listOf("a.png")).filter { it.kind == ConvertTargets.Kind.CONVERTER }.map { it.arg })
    }

    @Test fun mixedTypesDisableTheUnsupportedTargetsWithAReason() {
        val names = listOf("a.png", "b.md")
        assertTrue("to-jpg reads png only", target(names, "conv:to-jpg").enabled.not())
        assertTrue(target(names, "conv:to-jpg").reason!!.contains("b.md"))
        assertFalse(target(names, "conv:md-html").enabled)
        assertTrue(target(names, "conv:md-html").reason!!.contains("a.png"))
        assertTrue("both convert to PDF", target(names, "pdf").enabled)
        assertNull(target(names, "pdf").reason)
        val withDocx = listOf("a.png", "c.docx")
        assertFalse(target(withDocx, "pdf").enabled)
        assertTrue(target(withDocx, "pdf").reason!!.contains("c.docx"))
        assertEquals(listOf("a.png"), target(withDocx, "pdf").accepting)
        // from-PDF needs every selected file to be a PDF
        assertFalse(target(listOf("t.pdf", "a.png"), "frompdf:md").enabled)
        assertTrue(target(listOf("t.pdf", "u.pdf"), "frompdf:md").enabled)
        // targets are never dropped from the list, only disabled
        assertEquals(ConvertTargets.forNames(listOf("a.png")).map { it.key }, ConvertTargets.forNames(names + "c.docx").map { it.key })
        assertFalse("conv:to-jpg" in enabledKeys(listOf("a.png", "b.zip")))
    }

    @Test fun selectionConvertGroupMatchesTheItemConvertGroup() {
        val item = itemDecl.first { it.id == "convert" }
        val sel = selDecl.first { it.id == "convert" }
        assertEquals(item.label, sel.label)
        assertEquals(item.items, sel.items) // same ids, same labels, same order
        assertEquals(listOf("actions", "convert", "share", "meta", "git"), selDecl.map { it.id })
    }

    @Test fun selectionConvertEntriesEnableByWhatTheSelectionHolds() {
        val png = FileMenu.resolve(FileMenu.SELECTION, selDecl, FileMenu.Context(count = 2, canConvert = true))
        fun on(g: List<FileMenu.Group>) = g.first { it.id == "convert" }.items.filter { it.enabled }.map { it.id }
        assertEquals(listOf("compress", "convert", "to_pdf"), on(png))
        val none = FileMenu.resolve(FileMenu.SELECTION, selDecl, FileMenu.Context(count = 2, anyFile = false))
        assertEquals(listOf("compress"), on(none))
        assertEquals(5, none.first { it.id == "convert" }.items.size) // listed, disabled
        assertTrue("convert_pdf" in on(FileMenu.resolve(FileMenu.SELECTION, selDecl, FileMenu.Context(count = 2, isPdf = true))))
    }

    /** Rule 16: every id of the previous menus (before this change) is still declared. */
    @Test fun everyPreviousMenuItemIsStillPresent() {
        val prevItem = listOf("editor", "edit", "open_with", "open_other_pane", "unzip", "compress", "convert", "to_pdf", "convert_pdf",
            "copy_path", "copy_to_downloads", "copy_to_clipboard", "share", "rename", "delete", "properties", "bookmark",
            "git_rename", "git_move", "git_versions")
        val prevSel = listOf("delete_all", "move_all", "copy_all", "copy_to_other_pane", "move_to_other_pane", "zip_all", "tar_all",
            "clear_selection", "invert_selection", "select_all", "copy_paths", "copy_to_downloads", "share", "properties", "rename",
            "git_move_all", "git_rename")
        val nowItem = itemDecl.flatMap { g -> g.items.map { it.id } }
        val nowSel = selDecl.filter { it.id != "convert" }.flatMap { g -> g.items.map { it.id } }
        prevItem.forEach { assertTrue("item menu lost $it", it in nowItem) }
        prevSel.forEach { assertTrue("selection menu lost $it", it in nowSel) }
        assertEquals(prevItem.size, nowItem.size)
        assertEquals(prevSel.size, nowSel.size)
        // and the labels the owner reads
        assertTrue(selDecl.flatMap { it.items }.any { it.label == "Delete all" } && itemDecl.flatMap { it.items }.any { it.label == "Delete" })
    }

    @Test fun dropdownRowsAreDense() {
        val chrome = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/ui/Chrome.kt").readText()
        fun dp(name: String) = Regex("val $name = (\\d+)\\.dp").find(chrome)!!.groupValues[1].toInt()
        assertTrue("menu rows are denser than Material's 48dp and the table rows", dp("menuRow") <= 32 && dp("menuRow") < dp("rowHeight"))
        assertTrue(dp("menuRowTwoLine") <= dp("rowHeight"))
        val screen = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/files/FilesScreen.kt").readText()
        val menu = screen.substringAfter("private fun GroupedFileMenu").substringBefore("private fun ConvertTargetRows")
        assertTrue(menu.contains("DriveMetrics.menuRow"))
        assertNotNull(Regex("height\\(DriveMetrics\\.menuRow\\)").find(menu))
    }
}
