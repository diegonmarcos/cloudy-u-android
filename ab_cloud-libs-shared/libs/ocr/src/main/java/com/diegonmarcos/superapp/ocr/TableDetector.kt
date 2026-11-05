package com.diegonmarcos.superapp.ocr

import kotlin.math.abs

/**
 * Table detection from line boxes, pure geometry (no pixels, no ML):
 *  1. ROWS: lines whose vertical centres lie within half a line height share a visual row.
 *  2. RUN: consecutive rows holding at least [MIN_CELLS] cells, with a vertical gap below
 *     [GAP_FACTOR] line heights, form a candidate table of at least [MIN_ROWS] rows.
 *  3. COLUMNS: cells are clustered by x-overlap into columns; two cells of the same row never
 *     share a column (a wide cell cannot glue two columns together).
 * The first row is the header. Text is never altered, so numbers and units stay exact.
 */
object TableDetector {
    const val MIN_CELLS = 2
    const val MIN_ROWS = 2
    const val MAX_COLUMNS = 8
    const val GAP_FACTOR = 2.0

    data class Result(val tables: List<OcrTable>, val rest: List<OcrLine>)

    fun rows(lines: List<OcrLine>): List<List<OcrLine>> {
        val rows = ArrayList<MutableList<OcrLine>>()
        for (l in lines.sortedBy { it.box.t }) {
            val row = rows.lastOrNull()
            val a = row?.first()
            if (a != null && abs(l.box.cy - a.box.cy) < maxOf(1, minOf(l.box.h, a.box.h)) / 2) row.add(l) else rows.add(mutableListOf(l))
        }
        return rows.map { r -> r.sortedBy { it.box.l } }
    }

    fun detect(lines: List<OcrLine>): Result {
        val rows = rows(lines)
        val tables = ArrayList<OcrTable>()
        val used = HashSet<OcrLine>()
        var i = 0
        while (i < rows.size) {
            if (rows[i].size < MIN_CELLS) { i++; continue }
            var j = i + 1
            while (j < rows.size && rows[j].size >= MIN_CELLS && gap(rows[j - 1], rows[j]) <= GAP_FACTOR * height(rows[j - 1], rows[j])) j++
            if (j - i >= MIN_ROWS) build(rows.subList(i, j))?.let { t -> tables.add(t); used.addAll(t.lines) }
            i = j
        }
        return Result(tables, lines.filter { it !in used })
    }

    private fun height(a: List<OcrLine>, b: List<OcrLine>) = maxOf(1, maxOf(a.maxOf { it.box.h }, b.maxOf { it.box.h }))
    private fun gap(a: List<OcrLine>, b: List<OcrLine>) = b.minOf { it.box.t } - a.maxOf { it.box.b }

    private fun build(run: List<List<OcrLine>>): OcrTable? {
        // Column clusters: [l, r] intervals grown by overlap, never taking two cells of one row.
        class Col(var l: Int, var r: Int, val rowsSeen: MutableSet<Int> = HashSet())
        val cols = ArrayList<Col>()
        val colOf = HashMap<OcrLine, Int>()
        val cells = run.flatMapIndexed { ri, r -> r.map { ri to it } }.sortedBy { it.second.box.l }
        for ((ri, line) in cells) {
            val c = cols.indexOfFirst { it.l < line.box.r && line.box.l < it.r && ri !in it.rowsSeen }
            val k = if (c >= 0) c else { cols.add(Col(line.box.l, line.box.r)); cols.lastIndex }
            cols[k].l = minOf(cols[k].l, line.box.l); cols[k].r = maxOf(cols[k].r, line.box.r); cols[k].rowsSeen.add(ri)
            colOf[line] = k
        }
        // Columns left to right; every column must be seen in at least two rows or it is noise.
        val order = cols.indices.sortedBy { cols[it].l }.filter { cols[it].rowsSeen.size >= 2 }
        if (order.size < MIN_CELLS || order.size > MAX_COLUMNS) return null
        val index = order.withIndex().associate { (n, k) -> k to n }
        val out = ArrayList<List<OcrCell>>()
        val used = ArrayList<OcrLine>()
        for ((ri, r) in run.withIndex()) {
            val byCol = HashMap<Int, MutableList<OcrLine>>()
            r.forEach { l -> index[colOf[l]]?.let { byCol.getOrPut(it) { mutableListOf() }.add(l) } }
            val row = (0 until order.size).map { c ->
                val ls = byCol[c].orEmpty()
                used.addAll(ls)
                OcrCell(ls.joinToString(" ") { it.text }, ri, c, ls.map { it.box }.reduceOrNull { a, b -> a.union(b) } ?: Box(cols[order[c]].l, r.first().box.t, cols[order[c]].r, r.first().box.b))
            }
            out.add(row)
        }
        if (used.isEmpty()) return null
        return OcrTable(used.map { it.box }.reduce { a, b -> a.union(b) }, out, order.size, used)
    }
}
