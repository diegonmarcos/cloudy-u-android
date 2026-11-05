package com.diegonmarcos.cloudlib.odf

import com.diegonmarcos.cloudlib.odf.OdfXml.attr
import com.diegonmarcos.cloudlib.odf.OdfXml.child
import com.diegonmarcos.cloudlib.odf.OdfXml.children
import com.diegonmarcos.cloudlib.odf.OdfXml.create
import com.diegonmarcos.cloudlib.odf.OdfXml.fillText
import com.diegonmarcos.cloudlib.odf.OdfXml.isEl
import com.diegonmarcos.cloudlib.odf.OdfXml.removeAttr
import com.diegonmarcos.cloudlib.odf.OdfXml.setAttr
import com.diegonmarcos.cloudlib.odf.OdfXml.textOf
import org.w3c.dom.Element

enum class OdfKind { Text, Spreadsheet, Presentation }

/** One paragraph-level block of a text document. [level] is the outline level of a heading, else 0. */
data class OdtBlock(val kind: Kind, val level: Int, val text: String) {
    enum class Kind { Heading, Paragraph, ListItem }
}

/** A sheet as the grid of its cells' text (ragged rows are padded to the widest). */
data class OdsSheet(val name: String, val rows: List<List<String>>) {
    val width: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

/** A slide: its name and the texts of its text boxes, in order. */
data class OdpSlide(val name: String, val texts: List<String>)

/**
 * An OpenDocument text, spreadsheet or presentation, opened for reading and editing. Edits go straight into the DOM;
 * [save] writes the package (see [OdfPackage]). Everything the editor does not touch stays as it was.
 */
class OdfFile private constructor(private val pkg: OdfPackage, val kind: OdfKind, private val body: Element) {

    fun save(): ByteArray = pkg.toBytes()

    // ── text ────────────────────────────────────────────────────────────────

    private class Para(var el: Element, var block: OdtBlock)

    private fun paras(): List<Para> {
        val out = ArrayList<Para>()
        fun walk(e: Element, inList: Boolean) {
            for (c in children(e)) {
                when {
                    isEl(c, OdfNs.TEXT, "h") -> out += Para(c, OdtBlock(OdtBlock.Kind.Heading, (attr(c, OdfNs.TEXT, "outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 6), textOf(c)))
                    isEl(c, OdfNs.TEXT, "p") -> out += Para(c, OdtBlock(if (inList) OdtBlock.Kind.ListItem else OdtBlock.Kind.Paragraph, 0, textOf(c)))
                    isEl(c, OdfNs.TEXT, "list") || isEl(c, OdfNs.TEXT, "list-item") || isEl(c, OdfNs.TEXT, "list-header") -> walk(c, true)
                    isEl(c, OdfNs.TEXT, "section") || isEl(c, OdfNs.TEXT, "ordered-list") || isEl(c, OdfNs.TEXT, "unordered-list") -> walk(c, inList)
                    else -> Unit // tables, frames, indexes and the like are not text blocks
                }
            }
        }
        walk(body, false)
        return out
    }

    /** The paragraphs, headings and list items of a text document in order. */
    fun textBlocks(): List<OdtBlock> {
        require(kind == OdfKind.Text) { "not a text document" }
        return paras().map { it.block }
    }

    /**
     * Makes the document hold [blocks]: blocks that did not change keep their element and so their styles; a changed block
     * gets its text replaced in place; added blocks are inserted after the block before them; blocks that are gone are removed.
     */
    fun setTextBlocks(blocks: List<OdtBlock>) {
        require(kind == OdfKind.Text) { "not a text document" }
        val old = paras()
        val n = old.size
        val m = blocks.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) dp[i][j] = if (old[i].block == blocks[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        var i = 0
        var j = 0
        var prev: Para? = null
        val removes = ArrayList<Para>()
        val adds = ArrayList<OdtBlock>()
        fun settle() {
            val pairs = minOf(removes.size, adds.size)
            for (k in 0 until pairs) {
                val r = removes[k]
                val a = adds[k]
                prev = if (r.block.kind == a.kind) modify(r, a) else { remove(r); insertAfter(prev, a) }
            }
            for (k in pairs until removes.size) remove(removes[k])
            for (k in pairs until adds.size) prev = insertAfter(prev, adds[k])
            removes.clear(); adds.clear()
        }
        while (i < n || j < m) {
            if (i < n && j < m && old[i].block == blocks[j]) { settle(); prev = old[i]; i++; j++ }
            else if (i < n && (j >= m || dp[i + 1][j] >= dp[i][j + 1])) { removes += old[i]; i++ }
            else { adds += blocks[j]; j++ }
        }
        settle()
    }

    /** Same kind: the text (and a heading's level) changes in place, the element and its style stay. */
    private fun modify(p: Para, to: OdtBlock): Para {
        fillText(p.el, to.text)
        if (to.kind == OdtBlock.Kind.Heading) setAttr(p.el, OdfNs.TEXT, "text:outline-level", to.level.coerceIn(1, 6).toString())
        p.block = to
        return p
    }

    private fun remove(p: Para) {
        var node: org.w3c.dom.Node = p.el
        // an emptied list item takes its item, and an emptied list its list, with it
        while (true) {
            val parent = node.parentNode ?: break
            parent.removeChild(node)
            val pe = parent as? Element ?: break
            val removable = isEl(pe, OdfNs.TEXT, "list-item") || isEl(pe, OdfNs.TEXT, "list")
            if (!removable || children(pe).isNotEmpty()) break
            node = pe
        }
    }

    private fun element(b: OdtBlock): Element {
        val doc = body.ownerDocument
        val el = if (b.kind == OdtBlock.Kind.Heading) create(doc, OdfNs.TEXT, "text:h").also { setAttr(it, OdfNs.TEXT, "text:outline-level", b.level.coerceIn(1, 6).toString()) } else create(doc, OdfNs.TEXT, "text:p")
        fillText(el, b.text)
        return el
    }

    private fun insertAfter(prev: Para?, b: OdtBlock): Para {
        val el = element(b)
        val doc = body.ownerDocument
        val previous = prev?.el
        if (b.kind == OdtBlock.Kind.ListItem && prev != null && prev.block.kind == OdtBlock.Kind.ListItem) {
            val item = previous!!.parentNode as Element
            val list = item.parentNode
            val li = create(doc, OdfNs.TEXT, "text:list-item")
            li.appendChild(el)
            list.insertBefore(li, item.nextSibling)
            return Para(el, b)
        }
        val node: Element = if (b.kind == OdtBlock.Kind.ListItem) {
            val list = create(doc, OdfNs.TEXT, "text:list")
            val li = create(doc, OdfNs.TEXT, "text:list-item")
            li.appendChild(el); list.appendChild(li)
            list
        } else el
        if (previous == null) {
            val first = children(body).firstOrNull { isEl(it, OdfNs.TEXT, "p") || isEl(it, OdfNs.TEXT, "h") || isEl(it, OdfNs.TEXT, "list") || isEl(it, OdfNs.TEXT, "section") || isEl(it, OdfNs.TABLE, "table") }
            body.insertBefore(node, first)
        } else {
            var anchor: org.w3c.dom.Node = previous
            while (anchor.parentNode != null && anchor.parentNode !== body) anchor = anchor.parentNode
            body.insertBefore(node, anchor.nextSibling)
        }
        return Para(el, b)
    }

    // ── spreadsheet ─────────────────────────────────────────────────────────

    private fun tables(): List<Element> = children(body).filter { isEl(it, OdfNs.TABLE, "table") }

    private fun rowElements(table: Element): List<Element> {
        val out = ArrayList<Element>()
        fun walk(e: Element) {
            for (c in children(e)) {
                when {
                    isEl(c, OdfNs.TABLE, "table-row") -> out += c
                    isEl(c, OdfNs.TABLE, "table-header-rows") || isEl(c, OdfNs.TABLE, "table-rows") || isEl(c, OdfNs.TABLE, "table-row-group") -> walk(c)
                }
            }
        }
        walk(table)
        return out
    }

    private fun cellElements(row: Element): List<Element> = children(row).filter { isEl(it, OdfNs.TABLE, "table-cell") || isEl(it, OdfNs.TABLE, "covered-table-cell") }

    private fun rep(e: Element, local: String): Int = (attr(e, OdfNs.TABLE, local)?.toIntOrNull() ?: 1).coerceAtLeast(1)

    private fun cellText(c: Element): String {
        val type = attr(c, OdfNs.OFFICE, "value-type")
        return when (type) {
            "float", "percentage", "currency" -> attr(c, OdfNs.OFFICE, "value") ?: ""
            "boolean" -> attr(c, OdfNs.OFFICE, "boolean-value") ?: ""
            "date" -> attr(c, OdfNs.OFFICE, "date-value") ?: ""
            "time" -> attr(c, OdfNs.OFFICE, "time-value") ?: ""
            else -> children(c).filter { isEl(it, OdfNs.TEXT, "p") || isEl(it, OdfNs.TEXT, "h") }.joinToString("\n") { textOf(it) }
        }
    }

    /** The sheets with their cell texts, at most [maxRows] x [maxCols] each. */
    fun sheets(maxRows: Int = 2000, maxCols: Int = 200): List<OdsSheet> {
        require(kind == OdfKind.Spreadsheet) { "not a spreadsheet" }
        return tables().map { t ->
            val rows = ArrayList<List<String>>()
            for (r in rowElements(t)) {
                if (rows.size >= maxRows) break
                val cells = ArrayList<String>()
                for (c in cellElements(r)) {
                    if (cells.size >= maxCols) break
                    val text = cellText(c)
                    repeat(minOf(rep(c, "number-columns-repeated"), maxCols - cells.size)) { cells += text }
                }
                while (cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.size - 1)
                repeat(minOf(rep(r, "number-rows-repeated"), maxRows - rows.size)) { rows += cells }
            }
            while (rows.isNotEmpty() && rows.last().isEmpty()) rows.removeAt(rows.size - 1)
            OdsSheet(attr(t, OdfNs.TABLE, "name").orEmpty(), rows)
        }
    }

    /** Sets the cell at ([row], [col]) of sheet [sheet] (all 0-based) to [text]; a number is stored as a number, anything else as a string, blank clears it. */
    fun setCell(sheet: Int, row: Int, col: Int, text: String) {
        require(kind == OdfKind.Spreadsheet) { "not a spreadsheet" }
        require(row in 0 until MAX_EDIT_ROWS && col in 0 until MAX_EDIT_COLS) { "cell out of range" }
        val table = tables().getOrNull(sheet) ?: throw IllegalArgumentException("no sheet $sheet")
        val cell = cellAt(rowAt(table, row), col)
        for (a in listOf("value", "boolean-value", "date-value", "time-value", "string-value", "currency")) removeAttr(cell, OdfNs.OFFICE, a)
        // value-type in any namespace (LibreOffice also writes calcext:value-type): the new one is set below
        val stale = (0 until cell.attributes.length).map { cell.attributes.item(it) }.filter { it.localName == "value-type" }.map { it.namespaceURI to it.localName }
        for ((ns, local) in stale) cell.removeAttributeNS(ns, local)
        removeAttr(cell, OdfNs.TABLE, "formula")
        removeAttr(cell, OdfNs.TABLE, "number-columns-repeated")
        while (cell.firstChild != null) cell.removeChild(cell.firstChild)
        val t = text.trim()
        if (t.isEmpty()) return
        val p = create(cell.ownerDocument, OdfNs.TEXT, "text:p")
        if (NUMBER.matches(t)) {
            setAttr(cell, OdfNs.OFFICE, "office:value-type", "float")
            setAttr(cell, OdfNs.OFFICE, "office:value", t)
            fillText(p, t)
        } else {
            setAttr(cell, OdfNs.OFFICE, "office:value-type", "string")
            fillText(p, text)
        }
        cell.appendChild(p)
    }

    /** The row element for logical row [index], splitting a repeated row or appending rows as needed. */
    private fun rowAt(table: Element, index: Int): Element {
        var at = 0
        for (r in rowElements(table)) {
            val n = rep(r, "number-rows-repeated")
            if (index < at + n) return split(r, index - at, n, "number-rows-repeated")
            at += n
        }
        var last: Element? = null
        while (at <= index) {
            val r = create(table.ownerDocument, OdfNs.TABLE, "table:table-row")
            table.appendChild(r)
            last = r
            at++
        }
        return last!!
    }

    private fun cellAt(row: Element, index: Int): Element {
        var at = 0
        for (c in cellElements(row)) {
            val n = rep(c, "number-columns-repeated")
            if (index < at + n) return split(c, index - at, n, "number-columns-repeated")
            at += n
        }
        var last: Element? = null
        while (at <= index) {
            val c = create(row.ownerDocument, OdfNs.TABLE, "table:table-cell")
            row.appendChild(c)
            last = c
            at++
        }
        return last!!
    }

    /** Cuts the repeated element [e] (repeated [n] times) so that the copy at [k] stands alone; returns that copy. */
    private fun split(e: Element, k: Int, n: Int, attrName: String): Element {
        if (n == 1) return e
        val parent = e.parentNode
        fun copy(count: Int): Element {
            val c = e.cloneNode(true) as Element
            if (count > 1) setAttr(c, OdfNs.TABLE, "table:$attrName", count.toString()) else removeAttr(c, OdfNs.TABLE, attrName)
            return c
        }
        val target = copy(1)
        if (k > 0) parent.insertBefore(copy(k), e)
        parent.insertBefore(target, e)
        if (n - k - 1 > 0) parent.insertBefore(copy(n - k - 1), e)
        parent.removeChild(e)
        return target
    }

    // ── presentation ────────────────────────────────────────────────────────

    private fun pages(): List<Element> = children(body).filter { isEl(it, OdfNs.DRAW, "page") }

    private fun slideTexts(page: Element): List<Element> {
        val out = ArrayList<Element>()
        fun walk(e: Element) {
            for (c in children(e)) {
                when {
                    isEl(c, OdfNs.PRESENTATION, "notes") -> Unit
                    isEl(c, OdfNs.TEXT, "p") || isEl(c, OdfNs.TEXT, "h") -> out += c
                    else -> walk(c)
                }
            }
        }
        walk(page)
        return out
    }

    fun slides(): List<OdpSlide> {
        require(kind == OdfKind.Presentation) { "not a presentation" }
        return pages().map { p -> OdpSlide(attr(p, OdfNs.DRAW, "name").orEmpty(), slideTexts(p).map { textOf(it) }) }
    }

    /** Replaces the text of the [index]th text element of slide [slide]. */
    fun setSlideText(slide: Int, index: Int, text: String) {
        require(kind == OdfKind.Presentation) { "not a presentation" }
        val el = slideTexts(pages().getOrNull(slide) ?: throw IllegalArgumentException("no slide $slide")).getOrNull(index) ?: throw IllegalArgumentException("no text $index on slide $slide")
        fillText(el, text)
    }

    companion object {
        private val NUMBER = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$")
        private const val MAX_EDIT_ROWS = 100_000
        private const val MAX_EDIT_COLS = 1_000

        fun open(bytes: ByteArray): OdfFile {
            val pkg = OdfPackage.read(bytes)
            val officeBody = child(pkg.content.documentElement, OdfNs.OFFICE, "body") ?: throw OdfException("no office:body")
            val inner = children(officeBody).firstOrNull() ?: throw OdfException("empty office:body")
            val kind = when {
                isEl(inner, OdfNs.OFFICE, "text") -> OdfKind.Text
                isEl(inner, OdfNs.OFFICE, "spreadsheet") -> OdfKind.Spreadsheet
                isEl(inner, OdfNs.OFFICE, "presentation") -> OdfKind.Presentation
                else -> throw OdfException("unsupported OpenDocument type: ${inner.localName}")
            }
            return OdfFile(pkg, kind, inner)
        }
    }
}
