package com.diegonmarcos.cloudlib.odf

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document

class OdfException(message: String, cause: Throwable? = null) : Exception(message, cause)

object OdfNs {
    const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    const val PRESENTATION = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
}

/**
 * An ODF package in memory: every zip entry as bytes in the original order, and content.xml as a DOM. [toBytes] writes the
 * package back with content.xml re-serialised and every other entry untouched; `mimetype` goes first, stored.
 */
class OdfPackage private constructor(private val entries: LinkedHashMap<String, ByteArray>, val content: Document) {

    val mimetype: String get() = entries["mimetype"]?.toString(Charsets.US_ASCII).orEmpty()

    /** The bytes of a part other than content.xml, or null. */
    fun part(name: String): ByteArray? = entries[name]

    fun partNames(): List<String> = entries.keys.toList()

    fun toBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val mime = entries["mimetype"]
            if (mime != null) {
                val e = ZipEntry("mimetype")
                e.method = ZipEntry.STORED
                e.size = mime.size.toLong()
                e.compressedSize = mime.size.toLong()
                e.crc = CRC32().also { it.update(mime) }.value
                zip.putNextEntry(e)
                zip.write(mime)
                zip.closeEntry()
            }
            for ((name, data) in entries) {
                if (name == "mimetype") continue
                zip.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) zip.write(if (name == CONTENT) serialize(content) else data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val CONTENT = "content.xml"
        private const val MAX_ENTRY = 64L * 1024 * 1024
        private const val MAX_TOTAL = 256L * 1024 * 1024

        fun read(bytes: ByteArray): OdfPackage {
            val entries = LinkedHashMap<String, ByteArray>()
            var total = 0L
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val name = e.name
                        if (name.startsWith("/") || name.split('/').contains("..")) throw OdfException("unsafe entry name: $name")
                        val data = if (e.isDirectory) ByteArray(0) else readLimited(zip, MAX_ENTRY)
                        total += data.size
                        if (total > MAX_TOTAL) throw OdfException("package too large")
                        entries[name] = data
                    }
                }
            } catch (e: OdfException) {
                throw e
            } catch (e: Exception) {
                throw OdfException("not a zip package", e)
            }
            val xml = entries[CONTENT] ?: throw OdfException("no content.xml: not an OpenDocument file")
            return OdfPackage(entries, parse(xml))
        }

        private fun readLimited(zip: ZipInputStream, max: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var n = 0L
            while (true) {
                val r = zip.read(buf)
                if (r < 0) break
                n += r
                if (n > max) throw OdfException("entry too large")
                out.write(buf, 0, r)
            }
            return out.toByteArray()
        }

        private fun parse(xml: ByteArray): Document {
            try {
                val f = DocumentBuilderFactory.newInstance()
                f.isNamespaceAware = true
                runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                f.isExpandEntityReferences = false
                return f.newDocumentBuilder().parse(ByteArrayInputStream(xml))
            } catch (e: Exception) {
                throw OdfException("content.xml is not valid XML", e)
            }
        }

        private fun serialize(doc: Document): ByteArray {
            val out = ByteArrayOutputStream()
            doc.xmlStandalone = true
            val t = TransformerFactory.newInstance().newTransformer()
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            t.setOutputProperty(OutputKeys.METHOD, "xml")
            t.transform(DOMSource(doc), StreamResult(out))
            return out.toByteArray()
        }
    }
}
