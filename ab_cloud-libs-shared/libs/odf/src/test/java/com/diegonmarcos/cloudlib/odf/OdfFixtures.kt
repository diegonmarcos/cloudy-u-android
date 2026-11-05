package com.diegonmarcos.cloudlib.odf

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small ODF packages built in the tests: mimetype first and stored, a manifest, styles.xml (an untouched part) and content.xml. */
object OdfFixtures {
    const val STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-styles xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"><office:styles/></office:document-styles>"
    private const val NS = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\" xmlns:presentation=\"urn:oasis:names:tc:opendocument:xmlns:presentation:1.0\" xmlns:calcext=\"urn:org:documentfoundation:names:experimental:calc:xmlns:calcext:1.0\""
    val PICTURE = byteArrayOf(1, 2, 3, 4, 5, 0, -1, -2)

    fun content(body: String) = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content $NS office:version=\"1.3\"><office:body>$body</office:body></office:document-content>"

    fun pack(mime: String, contentXml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val m = mime.toByteArray(Charsets.US_ASCII)
            val e = ZipEntry("mimetype")
            e.method = ZipEntry.STORED; e.size = m.size.toLong(); e.compressedSize = m.size.toLong(); e.crc = CRC32().also { it.update(m) }.value
            z.putNextEntry(e); z.write(m); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/")); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/manifest.xml")); z.write("<manifest/>".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("content.xml")); z.write(contentXml.toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry("styles.xml")); z.write(STYLES.toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry("Pictures/a.png")); z.write(PICTURE); z.closeEntry()
        }
        return out.toByteArray()
    }

    /** A text document: a title, two paragraphs (one with a styled span) and a two-item list. */
    fun odt(): ByteArray = pack(
        "application/vnd.oasis.opendocument.text",
        content(
            "<office:text><text:h text:outline-level=\"1\">Title</text:h>" +
                "<text:p text:style-name=\"P1\">First <text:span text:style-name=\"B\">bold</text:span> paragraph</text:p>" +
                "<text:p>Second paragraph</text:p>" +
                "<text:list><text:list-item><text:p>item one</text:p></text:list-item><text:list-item><text:p>item two</text:p></text:list-item></text:list>" +
                "</office:text>",
        ),
    )

    /** A spreadsheet: sheet "Data" with a header row, a number row, a repeated empty row and a row with a repeated cell; sheet "Empty". */
    fun ods(): ByteArray = pack(
        "application/vnd.oasis.opendocument.spreadsheet",
        content(
            "<office:spreadsheet>" +
                "<table:table table:name=\"Data\"><table:table-column table:number-columns-repeated=\"3\"/>" +
                "<table:table-row><table:table-cell office:value-type=\"string\"><text:p>Name</text:p></table:table-cell><table:table-cell office:value-type=\"string\"><text:p>Qty</text:p></table:table-cell></table:table-row>" +
                "<table:table-row><table:table-cell office:value-type=\"string\"><text:p>apples</text:p></table:table-cell><table:table-cell office:value-type=\"float\" office:value=\"3\" calcext:value-type=\"float\"><text:p>3.00</text:p></table:table-cell></table:table-row>" +
                "<table:table-row table:number-rows-repeated=\"3\"><table:table-cell table:number-columns-repeated=\"4\"/></table:table-row>" +
                "<table:table-row><table:table-cell table:number-columns-repeated=\"2\" office:value-type=\"string\"><text:p>same</text:p></table:table-cell></table:table-row>" +
                "</table:table>" +
                "<table:table table:name=\"Empty\"><table:table-row><table:table-cell/></table:table-row></table:table>" +
                "</office:spreadsheet>",
        ),
    )

    /** A presentation: two slides, the first with a title and a body, the second with one text; speaker notes on the first. */
    fun odp(): ByteArray = pack(
        "application/vnd.oasis.opendocument.presentation",
        content(
            "<office:presentation>" +
                "<draw:page draw:name=\"Intro\"><draw:frame><draw:text-box><text:p>Welcome</text:p></draw:text-box></draw:frame><draw:frame><draw:text-box><text:p>Agenda</text:p><text:p>Questions</text:p></draw:text-box></draw:frame>" +
                "<presentation:notes><draw:frame><draw:text-box><text:p>speaker only</text:p></draw:text-box></draw:frame></presentation:notes></draw:page>" +
                "<draw:page draw:name=\"Closing\"><draw:frame><draw:text-box><text:p>Thanks</text:p></draw:text-box></draw:frame></draw:page>" +
                "</office:presentation>",
        ),
    )
}
