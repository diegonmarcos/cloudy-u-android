package com.diegonmarcos.superapp.ocr

/** Lines -> reading order, blocks (paragraphs, cards) and tables: the engine-independent half of the OCR. */
object LayoutBuilder {
    fun build(lines: List<OcrLine>, width: Int, height: Int, engine: String, engineVersion: String = "", gapFactor: Double = 0.9): OcrResult {
        val clean = lines.filter { it.text.isNotBlank() }.map { withWords(it) }
        val split = TableDetector.detect(clean)
        return OcrResult(width, height, blocks(split.rest, gapFactor), split.tables, Languages.detect(clean.joinToString("\n") { it.text }), engine, engineVersion)
    }

    /** A line joins the previous block when it starts below it within [gapFactor] line heights, overlaps it horizontally and is about as tall (a bigger line is a heading). */
    fun blocks(lines: List<OcrLine>, gapFactor: Double = 0.9): List<OcrBlock> {
        val groups = ArrayList<MutableList<OcrLine>>()
        for (l in TableDetector.rows(lines).flatten()) {
            val g = groups.lastOrNull { grp ->
                val last = grp.last()
                val gap = l.box.t - last.box.b
                val overlap = minOf(l.box.r, last.box.r) - maxOf(l.box.l, last.box.l)
                gap >= -last.box.h / 2 && gap <= gapFactor * maxOf(last.box.h, l.box.h) && overlap > 0 &&
                    maxOf(last.box.h, l.box.h) <= 1.25 * minOf(last.box.h, l.box.h)
            }
            if (g != null) g.add(l) else groups.add(mutableListOf(l))
        }
        return groups.map { OcrBlock(it) }
    }

    /** Word boxes from a line the engine reported whole: the line's width shared by character count (flagged estimated). */
    fun withWords(line: OcrLine): OcrLine {
        if (line.words.isNotEmpty()) return line
        val parts = Regex("\\S+").findAll(line.text).toList()
        if (parts.isEmpty()) return line
        val total = maxOf(1, line.text.length)
        val words = parts.map { m ->
            val l = line.box.l + (line.box.w.toLong() * m.range.first / total).toInt()
            val r = line.box.l + (line.box.w.toLong() * (m.range.last + 1) / total).toInt()
            OcrWord(m.value, Box(l, line.box.t, r, line.box.b), line.confidence, estimated = true)
        }
        return line.copy(words = words)
    }
}
