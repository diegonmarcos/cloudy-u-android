package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1837: the Kotlin engine against the shared fixtures of the Rust mail core
 * (ab_cloud-libs-shared/libs/mail-core-rs/fixtures/parity). The Kotlin MimeParser stays until both engines pass every fixture.
 */
class MimeParserParityFixturesTest {
    private fun dir(): File {
        var d: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (d != null) {
            val c = File(d, "ab_cloud-libs-shared/libs/mail-core-rs/fixtures/parity")
            if (c.isDirectory) return c
            d = d.parentFile
        }
        error("parity fixtures not found from ${System.getProperty("user.dir")}")
    }

    private fun collapse(s: String) = s.replace(Regex("<[^>]*>"), "").trim().split(Regex("\\s+")).joinToString(" ")

    @Test
    fun everyFixtureReadsTheSameInTheKotlinEngine() {
        val emls = dir().listFiles { f -> f.extension == "eml" }!!.sortedBy { it.name }
        assertTrue("the fixture set shrank to ${emls.size}", emls.size >= 6)
        val bad = mutableListOf<String>()
        for (eml in emls) {
            val name = eml.nameWithoutExtension
            val body = MimeParser.parseBody(String(eml.readBytes(), Charsets.ISO_8859_1))
            val kind = if (body.html != null) "html" else "text"
            val visible = collapse(body.html ?: body.text.orEmpty())
            var wantAtts = 0
            for (line in File(eml.parentFile, "$name.expect").readLines().filter { it.isNotBlank() }) {
                val k = line.substringBefore(":")
                val v = line.substringAfter(": ", "")
                when (k) {
                    "kind" -> if (kind != v) bad += "$name: kind $kind != $v"
                    "contains" -> if (!visible.contains(v)) bad += "$name: missing '$v' in '$visible'"
                    "absent" -> if (visible.contains(v)) bad += "$name: '$v' present"
                    "attachment" -> {
                        wantAtts++
                        val (nm, ty) = v.split(" | ")
                        if (body.attachments.none { it.name == nm && it.type.lowercase() == ty }) bad += "$name: attachment $nm $ty not in ${body.attachments}"
                    }
                }
            }
            if (body.attachments.size != wantAtts) bad += "$name: ${body.attachments.size} attachments, expected $wantAtts"
        }
        assertEquals("parity failures:\n" + bad.joinToString("\n"), 0, bad.size)
    }

    /** Same facts as mail-core-rs tests/compose_parity.rs, from the Kotlin builder and parser. */
    @Test
    fun composeParity() {
        for (html in listOf(false, true)) for (att in listOf(false, true)) {
            val m = OutgoingMessage(
                from = "Alice <alice@example.com>", to = listOf("bob@example.com"),
                subject = "Reuni\u00f3n: Espa\u00f1a", body = "Ma\u00f1ana en M\u00e1laga, se\u00f1or Mu\u00f1oz.",
                html = if (html) "<p>Ma\u00f1ana en <b>M\u00e1laga</b>, se\u00f1or Mu\u00f1oz.</p>" else null,
                messageId = "id-1@example.com", dateMillis = 0L,
                attachments = if (att) listOf(OutgoingAttachment("nota.pdf", "application/pdf", "PDF-DATA".toByteArray())) else emptyList(),
            )
            val raw = OutgoingMime.build(m)
            val body = MimeParser.parseBody(String(raw.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1))
            assertEquals("html=$html att=$att", html, body.html != null)
            assertTrue("html=$html att=$att: ${body.text} / ${body.html}", collapse(body.html ?: body.text.orEmpty()).contains("Ma\u00f1ana en M\u00e1laga, se\u00f1or Mu\u00f1oz."))
            assertEquals("Reuni\u00f3n: Espa\u00f1a", MimeParser.decodedHeaderOf(raw, "Subject"))
            assertEquals("html=$html att=$att", if (att) listOf("nota.pdf" to "application/pdf") else emptyList<Pair<String, String>>(), body.attachments.map { it.name to it.type.lowercase() })
        }
    }

    /** The scripted IMAP transcript the Rust client reads in mail-core-rs tests/imap_fetch.rs: same server answers, same facts. */
    @Test
    fun imapTranscript() {
        val fx = dir().parentFile
        val eml = String(File(dir(), "latin1-qp.eml").readBytes(), Charsets.ISO_8859_1)
        val blocks = LinkedHashMap<String, StringBuilder>()
        var cur: StringBuilder? = null
        for (l in File(fx, "imap/transcript.txt").readLines()) {
            if (l.startsWith("## ")) { cur = StringBuilder().also { blocks[l.removePrefix("## ")] = it } } else cur?.append(l)?.append("\r\n")
        }
        val server = FakeImapServer { tag, line ->
            val tpl = blocks.entries.firstOrNull { line.startsWith(it.key) }?.value?.toString()
            tpl?.replace("{tag}", tag)?.replace("{LEN}", eml.length.toString())?.replace("{BODY}", eml) ?: ok(tag)
        }
        server.use {
            it.session().use { session ->
                val st = session.select("INBOX")
                assertEquals(1, st.exists)
                assertEquals(42L, st.uidValidity)
                assertEquals(8L, st.uidNext)
                assertEquals(listOf(7L), session.allUids(10))
                assertEquals(eml, session.fetchSource(7L))
            }
        }
    }
}
