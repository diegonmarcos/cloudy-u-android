package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1800: every QR format the To field's camera button turns into recipients. */
class QrRecipientsTest {

    @Test fun `mailto with to, cc, bcc, subject and body`() {
        val r = parseQrRecipients("mailto:ana@example.org,bo@example.org?cc=cy@example.org&bcc=di%40example.org&subject=Hello%20there&body=Line%201%0ALine%202")!!
        assertEquals(listOf("ana@example.org", "bo@example.org"), r.to)
        assertEquals(listOf("cy@example.org"), r.cc)
        assertEquals(listOf("di@example.org"), r.bcc)
        assertEquals("Hello there", r.subject)
        assertEquals("Line 1\nLine 2", r.body)
        assertEquals(4, r.count)
    }

    @Test fun `mailto with a to parameter and an encoded path`() {
        val r = parseQrRecipients("MAILTO:ana%40example.org?to=bo@example.org")!!
        assertEquals(listOf("ana@example.org", "bo@example.org"), r.to)
    }

    @Test fun `MATMSG with TO, SUB and BODY, escapes kept`() {
        val r = parseQrRecipients("MATMSG:TO:ana@example.org;SUB:Offer\\; v2;BODY:See you;;")!!
        assertEquals(listOf("ana@example.org"), r.to)
        assertEquals("Offer; v2", r.subject)
        assertEquals("See you", r.body)
    }

    @Test fun `MECARD with two EMAIL entries`() {
        val r = parseQrRecipients("MECARD:N:Doe,Ana;TEL:+100;EMAIL:ana@example.org;EMAIL:ana.work@example.com;;")!!
        assertEquals(listOf("ana@example.org", "ana.work@example.com"), r.to)
    }

    @Test fun `vCard EMAIL entries with and without parameters`() {
        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ana Doe\r\nEMAIL;TYPE=INTERNET,WORK:ana@example.org\r\nitem1.EMAIL:ana.home@example.net\r\nEMAIL:ANA@example.org\r\nEND:VCARD"
        val r = parseQrRecipients(card)!!
        assertEquals("duplicates folded", listOf("ana@example.org", "ana.home@example.net"), r.to)
    }

    @Test fun `plain text with several addresses`() {
        val r = parseQrRecipients("Write to ana@example.org or bo.smith+mail@sub.example.co.uk, thanks")!!
        assertEquals(listOf("ana@example.org", "bo.smith+mail@sub.example.co.uk"), r.to)
    }

    @Test fun `a code with no address is null, so the composer says so`() {
        assertNull(parseQrRecipients("https://example.org/page"))
        assertNull(parseQrRecipients("WIFI:S:home;P:secret;;"))
        assertNull(parseQrRecipients("MECARD:N:Doe,Ana;TEL:+100;;"))
        assertNull(parseQrRecipients("mailto:?subject=hi"))
        assertNull(parseQrRecipients("   "))
    }
}
