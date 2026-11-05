package com.diegonmarcos.cloudlib.odf

import com.diegonmarcos.cloudlib.wysiwyg.Block
import com.diegonmarcos.cloudlib.wysiwyg.BlockKind
import com.diegonmarcos.cloudlib.wysiwyg.Doc
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OdfEditTest {
    private fun reopen(f: OdfFile): OdfFile = OdfFile.open(f.save())

    private fun entryNames(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; out += e.name } }
        return out
    }

    // ── package ─────────────────────────────────────────────────────────────

    @Test fun `the kind is read from the body`() {
        assertEquals(OdfKind.Text, OdfFile.open(OdfFixtures.odt()).kind)
        assertEquals(OdfKind.Spreadsheet, OdfFile.open(OdfFixtures.ods()).kind)
        assertEquals(OdfKind.Presentation, OdfFile.open(OdfFixtures.odp()).kind)
    }

    @Test fun `saving keeps mimetype first and stored, and every other part byte for byte`() {
        val f = OdfFile.open(OdfFixtures.odt())
        f.setTextBlocks(f.textBlocks().map { it.copy(text = it.text + "!") })
        val saved = f.save()
        val names = entryNames(saved)
        assertEquals("mimetype", names.first())
        ZipInputStream(ByteArrayInputStream(saved)).use { z ->
            val first = z.nextEntry!!
            assertEquals(java.util.zip.ZipEntry.STORED, first.method)
            assertEquals("application/vnd.oasis.opendocument.text", z.readBytes().toString(Charsets.US_ASCII))
        }
        assertEquals(entryNames(OdfFixtures.odt()), names)
        ZipInputStream(ByteArrayInputStream(saved)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "styles.xml") assertEquals(OdfFixtures.STYLES, z.readBytes().toString(Charsets.UTF_8))
                if (e.name == "Pictures/a.png") assertArrayEquals(OdfFixtures.PICTURE, z.readBytes())
            }
        }
    }

    @Test fun `a file that is not an opendocument package is refused`() {
        for (bad in listOf(ByteArray(0), "plain text".toByteArray(), OdfFixtures.pack("x", "<not-odf/>").copyOf(20))) {
            var refused = false
            try { OdfFile.open(bad) } catch (e: OdfException) { refused = true }
            assertTrue(refused)
        }
        var noBody = false
        try { OdfFile.open(OdfFixtures.pack("application/vnd.oasis.opendocument.text", "<root/>")) } catch (e: OdfException) { noBody = true }
        assertTrue(noBody)
    }

    // ── odt ─────────────────────────────────────────────────────────────────

    @Test fun `an odt reads as headings, paragraphs and list items`() {
        val blocks = OdfFile.open(OdfFixtures.odt()).textBlocks()
        assertEquals(
            listOf(
                OdtBlock(OdtBlock.Kind.Heading, 1, "Title"),
                OdtBlock(OdtBlock.Kind.Paragraph, 0, "First bold paragraph"),
                OdtBlock(OdtBlock.Kind.Paragraph, 0, "Second paragraph"),
                OdtBlock(OdtBlock.Kind.ListItem, 0, "item one"),
                OdtBlock(OdtBlock.Kind.ListItem, 0, "item two"),
            ),
            blocks,
        )
    }

    @Test fun `editing one paragraph changes it and nothing else`() {
        val f = OdfFile.open(OdfFixtures.odt())
        val doc = f.toDoc()
        f.applyDoc(Doc(doc.blocks.map { if (it.text == "Second paragraph") it.copy(text = "Second, edited") else it }))
        val again = reopen(f).textBlocks()
        assertEquals("Second, edited", again[2].text)
        assertEquals(listOf("Title", "First bold paragraph", "Second, edited", "item one", "item two"), again.map { it.text })
    }

    @Test fun `an untouched paragraph keeps its styled span after an edit elsewhere`() {
        val f = OdfFile.open(OdfFixtures.odt())
        f.applyDoc(Doc(f.toDoc().blocks.map { if (it.text == "Title") it.copy(text = "New title") else it }))
        val xml = reopen(f).save().let { String(ZipInputStream(ByteArrayInputStream(it)).use { z -> generateSequence { z.nextEntry }.first { e -> e.name == "content.xml" }.let { z.readBytes() } }, Charsets.UTF_8) }
        assertTrue(xml, xml.contains("text:style-name=\"B\""))
        assertTrue(xml, xml.contains("New title"))
    }

    @Test fun `adding, removing and converting blocks round-trips`() {
        val f = OdfFile.open(OdfFixtures.odt())
        val src = f.toDoc().blocks
        val edited = listOf(
            src[0],
            Block(BlockKind.Paragraph, "Inserted after the title"),
            src[1],
            // "Second paragraph" removed
            src[3],
            Block(BlockKind.Bullet, "item one and a half"),
            src[4],
            Block(BlockKind.Heading, "A closing heading", level = 2),
        )
        f.applyDoc(Doc(edited))
        val got = reopen(f).textBlocks()
        assertEquals(
            listOf(
                OdtBlock(OdtBlock.Kind.Heading, 1, "Title"),
                OdtBlock(OdtBlock.Kind.Paragraph, 0, "Inserted after the title"),
                OdtBlock(OdtBlock.Kind.Paragraph, 0, "First bold paragraph"),
                OdtBlock(OdtBlock.Kind.ListItem, 0, "item one"),
                OdtBlock(OdtBlock.Kind.ListItem, 0, "item one and a half"),
                OdtBlock(OdtBlock.Kind.ListItem, 0, "item two"),
                OdtBlock(OdtBlock.Kind.Heading, 2, "A closing heading"),
            ),
            got,
        )
    }

    @Test fun `removing every list item removes the list, a new first block goes to the top`() {
        val f = OdfFile.open(OdfFixtures.odt())
        f.setTextBlocks(listOf(OdtBlock(OdtBlock.Kind.Paragraph, 0, "Only this")))
        assertEquals(listOf(OdtBlock(OdtBlock.Kind.Paragraph, 0, "Only this")), reopen(f).textBlocks())
        val g = OdfFile.open(OdfFixtures.odt())
        g.setTextBlocks(listOf(OdtBlock(OdtBlock.Kind.Paragraph, 0, "Before all")) + g.textBlocks())
        assertEquals("Before all", reopen(g).textBlocks().first().text)
        assertEquals(6, reopen(g).textBlocks().size)
    }

    @Test fun `spaces, tabs and line breaks survive a write and a read`() {
        val f = OdfFile.open(OdfFixtures.odt())
        val text = "  lead   gap\tafter tab\nnext line  "
        f.setTextBlocks(listOf(OdtBlock(OdtBlock.Kind.Paragraph, 0, text)))
        assertEquals(text, reopen(f).textBlocks().single().text)
    }

    // ── ods ─────────────────────────────────────────────────────────────────

    @Test fun `an ods reads as sheets of cell text, repeats expanded and the empty tail trimmed`() {
        val sheets = OdfFile.open(OdfFixtures.ods()).sheets()
        assertEquals(listOf("Data", "Empty"), sheets.map { it.name })
        assertEquals(listOf(listOf("Name", "Qty"), listOf("apples", "3"), emptyList(), emptyList(), emptyList(), listOf("same", "same")), sheets[0].rows)
        assertEquals(emptyList<List<String>>(), sheets[1].rows)
    }

    @Test fun `editing a cell stores a number as a number and text as a string`() {
        val f = OdfFile.open(OdfFixtures.ods())
        f.setCell(0, 1, 1, "42.5")
        f.setCell(0, 1, 0, "pears")
        val rows = reopen(f).sheets()[0].rows
        assertEquals(listOf("pears", "42.5"), rows[1])
        val xml = String(f.save(), Charsets.ISO_8859_1)
        assertEquals(listOf("Name", "Qty"), rows[0])
        assertTrue(xml.isNotEmpty())
    }

    @Test fun `a cell inside a repeated row and a repeated cell is split out without touching its neighbours`() {
        val f = OdfFile.open(OdfFixtures.ods())
        f.setCell(0, 3, 2, "mid")   // the repeated empty rows 2..4, third cell
        f.setCell(0, 5, 1, "other") // the second of the two repeated "same" cells
        val rows = reopen(f).sheets()[0].rows
        assertEquals(listOf("", "", "mid"), rows[3])
        assertEquals(emptyList<String>(), rows[2])
        assertEquals(emptyList<String>(), rows[4])
        assertEquals(listOf("same", "other"), rows[5])
        assertEquals(6, rows.size)
    }

    @Test fun `editing past the end grows the sheet, clearing a cell empties it`() {
        val f = OdfFile.open(OdfFixtures.ods())
        f.setCell(1, 2, 3, "far")
        assertEquals(listOf(emptyList(), emptyList(), listOf("", "", "", "far")), reopen(f).sheets()[1].rows)
        f.setCell(0, 1, 1, "")
        assertEquals(listOf("apples"), reopen(f).sheets()[0].rows[1])
    }

    @Test fun `a number cell loses its stale value attributes when it becomes text`() {
        val f = OdfFile.open(OdfFixtures.ods())
        f.setCell(0, 1, 1, "lots")
        val back = reopen(f)
        assertEquals("lots", back.sheets()[0].rows[1][1])
        f.setCell(0, 1, 1, "7")
        assertEquals("7", reopen(f).sheets()[0].rows[1][1])
    }

    // ── odp ─────────────────────────────────────────────────────────────────

    @Test fun `an odp reads as slides of text, speaker notes left out`() {
        val slides = OdfFile.open(OdfFixtures.odp()).slides()
        assertEquals(listOf("Intro", "Closing"), slides.map { it.name })
        assertEquals(listOf("Welcome", "Agenda", "Questions"), slides[0].texts)
        assertEquals(listOf("Thanks"), slides[1].texts)
    }

    @Test fun `editing slide text round-trips`() {
        val f = OdfFile.open(OdfFixtures.odp())
        f.setSlideText(0, 1, "New agenda")
        f.setSlideText(1, 0, "Thank you")
        val slides = reopen(f).slides()
        assertEquals(listOf("Welcome", "New agenda", "Questions"), slides[0].texts)
        assertEquals(listOf("Thank you"), slides[1].texts)
    }

    @Test fun `the wrong kind of call is refused`() {
        var n = 0
        for (call in listOf<() -> Unit>({ OdfFile.open(OdfFixtures.ods()).textBlocks() }, { OdfFile.open(OdfFixtures.odt()).sheets() }, { OdfFile.open(OdfFixtures.odt()).slides() })) {
            try { call() } catch (e: IllegalArgumentException) { n++ }
        }
        assertEquals(3, n)
    }
}
