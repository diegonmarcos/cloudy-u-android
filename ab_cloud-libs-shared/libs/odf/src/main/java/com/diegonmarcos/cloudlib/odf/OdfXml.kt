package com.diegonmarcos.cloudlib.odf

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/** The small DOM helpers the ODF readers and writers share. */
internal object OdfXml {
    fun children(e: Element): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = e.firstChild
        while (n != null) { if (n is Element) out += n; n = n.nextSibling }
        return out
    }

    fun isEl(n: Node, ns: String, local: String) = n is Element && n.namespaceURI == ns && n.localName == local

    fun child(e: Element, ns: String, local: String): Element? = children(e).firstOrNull { isEl(it, ns, local) }

    fun attr(e: Element, ns: String, local: String): String? = if (e.hasAttributeNS(ns, local)) e.getAttributeNS(ns, local) else null

    fun setAttr(e: Element, ns: String, qualified: String, value: String) = e.setAttributeNS(ns, qualified, value)

    fun removeAttr(e: Element, ns: String, local: String) { if (e.hasAttributeNS(ns, local)) e.removeAttributeNS(ns, local) }

    fun create(doc: Document, ns: String, qualified: String): Element = doc.createElementNS(ns, qualified)

    /** The text a person sees in a paragraph: runs, text:s spaces, text:tab, text:line-break, spans and links; notes and comments are left out. */
    fun textOf(e: Element): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            var c: Node? = n.firstChild
            while (c != null) {
                when {
                    c.nodeType == Node.TEXT_NODE || c.nodeType == Node.CDATA_SECTION_NODE -> sb.append(c.nodeValue)
                    c is Element && c.namespaceURI == OdfNs.TEXT && c.localName == "s" -> sb.append(" ".repeat((attr(c, OdfNs.TEXT, "c")?.toIntOrNull() ?: 1).coerceIn(1, 1000)))
                    c is Element && c.namespaceURI == OdfNs.TEXT && c.localName == "tab" -> sb.append('\t')
                    c is Element && c.namespaceURI == OdfNs.TEXT && c.localName == "line-break" -> sb.append('\n')
                    c is Element && c.namespaceURI == OdfNs.TEXT && c.localName == "note" -> Unit
                    c is Element && c.namespaceURI == OdfNs.OFFICE && c.localName == "annotation" -> Unit
                    c is Element -> walk(c)
                }
                c = c.nextSibling
            }
        }
        walk(e)
        return sb.toString()
    }

    /** Replaces everything inside [e] with [text], using text:s for runs of spaces, text:tab and text:line-break. */
    fun fillText(e: Element, text: String) {
        while (e.firstChild != null) e.removeChild(e.firstChild)
        val doc = e.ownerDocument
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { e.appendChild(doc.createTextNode(buf.toString())); buf.setLength(0) } }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '\n' -> { flush(); e.appendChild(create(doc, OdfNs.TEXT, "text:line-break")) }
                '\t' -> { flush(); e.appendChild(create(doc, OdfNs.TEXT, "text:tab")) }
                ' ' -> {
                    var k = 0
                    while (i + k < text.length && text[i + k] == ' ') k++
                    // a space is literal only between two characters; at either end, or after a tab or break, it is text:s
                    val literal = if (buf.isNotEmpty() && i + k < text.length) 1 else 0
                    if (literal == 1) buf.append(' ')
                    val extra = k - literal
                    if (extra > 0) {
                        flush()
                        val s = create(doc, OdfNs.TEXT, "text:s")
                        if (extra > 1) setAttr(s, OdfNs.TEXT, "text:c", extra.toString())
                        e.appendChild(s)
                    }
                    i += k - 1
                }
                else -> if (c.code >= 0x20) buf.append(c)
            }
            i++
        }
        flush()
    }
}
