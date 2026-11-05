package com.diegonmarcos.superapp.ocr

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * OcrResult -> Markdown. Blocks and tables are interleaved by their top edge (reading order).
 * A single short line taller than the body is a heading (# by size ratio); lines starting with a
 * bullet or "1." are list items; everything else is a paragraph. A table is a GFM table whose
 * header is its first row. Text is copied verbatim: digits and units are never reformatted.
 */
object MarkdownFormatter {
    data class Front(val source: String, val capturedMs: Long?, val app: String?, val engine: String, val meanConfidence: Double?)

    private val BULLET = Regex("^[•·▪◦‣*\\-–]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\d{1,3})[.)]\\s+(.*)$")

    fun format(r: OcrResult, front: Front?): String {
        val sb = StringBuilder()
        if (front != null) sb.append(frontMatter(front)).append('\n')
        val heights = r.blocks.flatMap { it.lines }.map { it.box.h }.sorted()
        val body = if (heights.isEmpty()) 0 else heights[heights.size / 2]
        val items = (r.blocks.map { it.box.t to { block(it, body) } } + r.tables.map { it.box.t to { table(it) } }).sortedBy { it.first }
        sb.append(items.map { it.second() }.filter { it.isNotBlank() }.joinToString("\n\n"))
        return sb.toString().trimEnd() + "\n"
    }

    fun frontMatter(f: Front): String = buildString {
        append("---\n")
        append("source: ").append(yaml(f.source)).append('\n')
        f.capturedMs?.let { append("captured: ").append(iso(it)).append('\n') }
        f.app?.let { append("app: ").append(yaml(it)).append('\n') }
        append("ocr_engine: ").append(yaml(f.engine)).append('\n')
        f.meanConfidence?.let { append("mean_confidence: ").append(String.format(Locale.US, "%.3f", it)).append('\n') }
        append("---\n")
    }

    fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun yaml(s: String) = if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "._-/+" }) s else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun heading(line: OcrLine, body: Int): Int {
        if (body <= 0 || line.text.length > 80) return 0
        val ratio = line.box.h.toDouble() / body
        return when { ratio >= 1.8 -> 1; ratio >= 1.4 -> 2; ratio >= 1.2 -> 3; else -> 0 }
    }

    private fun block(b: OcrBlock, body: Int): String {
        if (b.lines.size == 1) {
            val l = b.lines[0]
            val h = heading(l, body)
            if (h > 0 && BULLET.matches(l.text.trim()).not()) return "#".repeat(h) + " " + l.text.trim()
        }
        val out = ArrayList<String>()
        val para = StringBuilder()
        fun flush() { if (para.isNotEmpty()) { out.add(para.toString()); para.setLength(0) } }
        for (l in b.lines) {
            val t = l.text.trim()
            val bu = BULLET.find(t); val nu = NUMBERED.find(t)
            when {
                bu != null -> { flush(); out.add("- " + bu.groupValues[1]) }
                nu != null -> { flush(); out.add(nu.groupValues[1] + ". " + nu.groupValues[2]) }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
        }
        flush()
        // List items stay one per line, paragraphs are separate; the two never mix in one run.
        return out.joinToString("\n")
    }

    fun table(t: OcrTable): String {
        fun cell(c: OcrCell) = c.text.replace("|", "\\|").replace("\n", " ").trim()
        val sb = StringBuilder()
        t.rows.forEachIndexed { i, row ->
            sb.append("| ").append(row.joinToString(" | ") { cell(it) }).append(" |\n")
            if (i == 0) sb.append("|").append(" --- |".repeat(t.columns)).append('\n')
        }
        return sb.toString().trimEnd()
    }
}
