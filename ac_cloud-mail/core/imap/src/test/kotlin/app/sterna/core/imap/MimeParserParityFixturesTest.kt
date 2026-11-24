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
}
