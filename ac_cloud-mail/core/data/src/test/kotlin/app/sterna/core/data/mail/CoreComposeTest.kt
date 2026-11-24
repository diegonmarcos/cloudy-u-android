package app.sterna.core.data.mail

import app.sterna.core.imap.BlindCopies
import app.sterna.core.imap.OutgoingAttachment
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.OutgoingMime
import com.diegonmarcos.cloudlib.mailcore.MailCore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1837 phase 3: the compose call sites go through the core behind [MailCore.enabled] and fall back to [OutgoingMime]. */
class CoreComposeTest {
    private fun msg(html: String? = null, inline: Boolean = false, receipt: Boolean = false) = OutgoingMessage(
        from = "a@example.com", to = listOf("b@example.com"), bcc = listOf("c@example.com"), subject = "Reunión", body = "hola",
        html = html, messageId = "id@example.com", dateMillis = 0L, requestReceipt = receipt,
        attachments = listOf(OutgoingAttachment("n.pdf", "application/pdf", byteArrayOf(1, 2, 3), cid = if (inline) "c1" else null, inline = inline)),
    )

    @Test fun theRequestCarriesEveryField() {
        val o = Json.parseToJsonElement(CoreCompose.request(msg(html = "<p>x</p>"), BlindCopies.WRITTEN)).jsonObject
        assertEquals("compose", o["op"]!!.jsonPrimitive.content)
        assertEquals("true", o["writeBcc"]!!.jsonPrimitive.content)
        assertEquals("<p>x</p>", o["html"]!!.jsonPrimitive.content)
        assertEquals("AQID", o["attachments"]!!.jsonArray[0].jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("Thu, 1 Jan 1970 00:00:00 GMT", o["date"]!!.jsonPrimitive.content)
    }

    @Test fun whatTheCoreDoesNotExpressStaysOnTheKotlinBuilder() {
        assertTrue(CoreCompose.eligible(msg()))
        assertFalse(CoreCompose.eligible(msg(inline = true)))
        assertFalse(CoreCompose.eligible(msg(receipt = true)))
    }

    @Test fun withDefaultSwitchesBuildYieldsTheKotlinBytes() {
        assertFalse(MailCore.composeEnabled)
        assertFalse(MailCore.imapEnabled)
        CoreCompose.install()
        try {
            for (m in listOf(msg(), msg(html = "<p>x</p>"))) assertEquals(OutgoingMime.buildInKotlin(m), OutgoingMime.build(m))
            // Even if the core were loaded: the compose switch alone engages it.
            assertNull(CoreCompose.tryBuild(msg(), BlindCopies.OMITTED))
        } finally {
            OutgoingMime.coreBuilder = null
        }
    }

    @Test fun withoutTheCoreTheKotlinBuilderAnswersUnchanged() {
        // On the JVM libmail_core.so is not loaded: the core is unavailable and the answer is OutgoingMime's, byte for byte.
        val m = msg(html = "<p>x</p>")
        assertNull(CoreCompose.tryBuild(m, BlindCopies.OMITTED))
        assertEquals(OutgoingMime.buildInKotlin(m), CoreCompose.build(m))
        CoreCompose.install()
        try {
            assertEquals(OutgoingMime.buildInKotlin(m), OutgoingMime.build(m))
        } finally {
            OutgoingMime.coreBuilder = null
        }
        MailCore.enabled = false
        try {
            assertNull(CoreCompose.tryBuild(m, BlindCopies.OMITTED))
        } finally {
            MailCore.enabled = true
        }
    }
}
