package com.diegonmarcos.superapp.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownFormatterTest {
    private val front = MarkdownFormatter.Front("Screenshot_1.png", 1_700_000_000_000, "com.android.settings", "mlkit-text-latin", 0.9)

    @Test fun headingParagraphAndTableInReadingOrder() {
        val r = LayoutBuilder.build(Fixtures.battery(), 1080, 2400, "mlkit-text-latin")
        val md = MarkdownFormatter.format(r, null)
        val i1 = md.indexOf("# Battery usage"); val i2 = md.indexOf("Since last full charge"); val i3 = md.indexOf("| App |"); val i4 = md.indexOf("Tip: lower brightness to save power")
        assertTrue("order $md", i1 in 0 until i2 && i2 < i3 && i3 < i4)
    }

    @Test fun tableHasHeaderSeparatorAndExactNumbers() {
        val t = TableDetector.detect(Fixtures.battery()).tables[0]
        assertEquals(
            "| App | Screen time | Usage |\n| --- | --- | --- |\n| Chrome | 2 h 14 min | 12.5% |\n| Maps | 45 min | 7% |",
            MarkdownFormatter.table(t),
        )
    }

    @Test fun pipesInCellsAreEscaped() {
        val t = OcrTable(Box(0, 0, 9, 9), listOf(listOf(OcrCell("a|b", 0, 0, Box(0, 0, 1, 1)), OcrCell("c", 0, 1, Box(0, 0, 1, 1)))), 2, emptyList())
        assertEquals("| a\\|b | c |\n| --- | --- |", MarkdownFormatter.table(t))
    }

    @Test fun listsAreOneItemPerLine() {
        val lines = listOf(Fixtures.line("Todo", 40, 100, 300), Fixtures.line("• milk", 40, 140, 300), Fixtures.line("2) eggs", 40, 180, 300))
        val md = MarkdownFormatter.format(LayoutBuilder.build(lines, 1080, 2400, "e"), null)
        assertTrue(md, md.contains("- milk\n2. eggs"))
    }

    @Test fun frontMatterHasSourceTimeAppEngineConfidence() {
        val fm = MarkdownFormatter.frontMatter(front)
        assertEquals("---\nsource: Screenshot_1.png\ncaptured: 2023-11-14T22:13:20Z\napp: com.android.settings\nocr_engine: mlkit-text-latin\nmean_confidence: 0.900\n---\n", fm)
    }

    @Test fun frontMatterQuotesAwkwardValues() {
        assertTrue(MarkdownFormatter.frontMatter(front.copy(source = "a b: c.png")).contains("source: \"a b: c.png\""))
    }

    @Test fun emptyOcrGivesOnlyTheFrontMatter() {
        val md = MarkdownFormatter.format(LayoutBuilder.build(emptyList(), 10, 10, "e"), front)
        assertTrue(md.startsWith("---\n") && md.trimEnd().endsWith("---"))
    }
}
