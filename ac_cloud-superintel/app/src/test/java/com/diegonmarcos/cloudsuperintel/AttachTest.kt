package com.diegonmarcos.cloudsuperintel

import com.diegonmarcos.superapp.ai.AiAttachWire
import com.diegonmarcos.superapp.ai.AiAttachment
import com.diegonmarcos.superapp.ai.AiAttachments
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiDesign
import com.diegonmarcos.superapp.ai.AiHost
import com.diegonmarcos.superapp.ai.AiModel
import com.diegonmarcos.superapp.ai.AiOutcome
import com.diegonmarcos.superapp.ai.AiProtocol
import com.diegonmarcos.superapp.ai.AiFileSessions
import com.diegonmarcos.superapp.ai.AiMsg
import com.diegonmarcos.superapp.ai.AiSession
import com.diegonmarcos.superapp.ai.AiSink
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The (+) menu's attachments: the three kinds, the chips in the composer, what is sent with the message, and what the wire carries. */
class AttachTest {
    private val design = AiDesign.parse(LibAi.asset("ai-design.json"))

    private class Host : AiHost {
        override var model = "x/y"
        override var web = false
        var sentWith: List<AiAttachment>? = null
        var sentText = ""
        override fun newSession(model: String, now: Long) = AiSession("s", "", model, now, emptyList())
        override fun models() = emptyList<AiModel>()
        override fun send(session: AiSession, text: String, model: String, web: Boolean, now: Long, sink: AiSink) = AiOutcome(session, null, emptyList())
        override fun sendWith(session: AiSession, text: String, attachments: List<AiAttachment>, model: String, web: Boolean, now: Long, sink: AiSink): AiOutcome {
            sentWith = attachments
            sentText = text
            return AiOutcome(session.copy(messages = session.messages + AiMsg("user", text, attachments = attachments) + AiMsg("assistant", "ok")), null, emptyList())
        }
    }

    private fun att(id: String, name: String, mime: String) = AiAttachment(id, name, mime, "/nonexistent/$name", 3)

    @Test fun thePlusMenuAttachesFilesImageAndCameraAndCameraNeedsItsPermsRow() {
        assertEquals(listOf("files", "images", "camera"), design.attachKinds.map { it.first })
        assertEquals(listOf("Files", "Image", "Camera"), design.attachKinds.map { it.second })
        assertEquals(listOf("camera"), design.attachPerms.getValue("camera"))
        assertEquals(listOf("media_images"), design.attachPerms.getValue("images"))
        assertTrue(design.attachPerms.getValue("files").isEmpty())
        assertEquals("only Camera cannot run without its grant", setOf("camera"), design.attachRequires)
    }

    @Test fun theAppDeclaresTheCameraAndMediaPermsRowsItsMenuNeeds() {
        val needs = Data.buildJson().getJSONObject("perms").getJSONArray("needs")
        val ids = (0 until needs.length()).map { needs.getString(it) }
        for (p in design.attachPerms.values.flatten()) assertTrue("perms.needs holds $p", p in ids)
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.CAMERA") && manifest.contains("android.permission.READ_MEDIA_IMAGES"))
    }

    @Test fun chipsAreAddedRemovedAndSentWithTheMessageThenCleared() = runBlocking {
        val host = Host()
        val chat = AiChatState(host)
        val a = att("1", "photo.jpg", "image/jpeg")
        val b = att("2", "notes.txt", "text/plain")
        chat.attach(a); chat.attach(b)
        assertEquals(listOf("photo.jpg", "notes.txt"), chat.attachments.map { it.name })
        chat.detach("2")
        assertEquals(listOf("photo.jpg"), chat.attachments.map { it.name })
        chat.attach(a)
        assertEquals("the same attachment is one chip", 1, chat.attachments.size)
        chat.send("what is this")
        assertEquals(listOf("1"), host.sentWith!!.map { it.id })
        assertEquals("what is this", host.sentText)
        assertTrue("the chips leave with the message", chat.attachments.isEmpty())
        assertEquals(listOf("1"), chat.session.messages.first().attachments.map { it.id })
    }

    @Test fun anAttachmentAloneIsAMessageAndNothingAtAllIsNot() = runBlocking {
        val host = Host()
        val chat = AiChatState(host)
        chat.send("")
        assertEquals(null, host.sentWith)
        chat.attach(att("1", "a.png", "image/png"))
        chat.send("")
        assertEquals(1, host.sentWith!!.size)
    }

    @Test fun aNewChatDropsPendingChips() {
        val chat = AiChatState(Host())
        chat.attach(att("1", "a.png", "image/png"))
        chat.fresh()
        assertTrue(chat.attachments.isEmpty())
    }

    @Test fun theWireCarriesAnImageAsAPartATextFileInlineAndAnUnreadableOneByName() {
        val read = { a: AiAttachment -> if (a.name == "gone.bin") null else a.name.toByteArray() }
        val image = AiAttachWire.content("look", listOf(att("1", "p.png", "image/png")), read) as JSONArray
        assertEquals(2, image.length())
        assertEquals("text", image.getJSONObject(0).getString("type"))
        assertEquals("image_url", image.getJSONObject(1).getString("type"))
        assertTrue(image.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/png;base64,"))
        val text = AiAttachWire.content("read", listOf(att("2", "n.txt", "text/plain")), read) as String
        assertTrue(text.startsWith("read") && "n.txt" in text)
        val gone = AiAttachWire.content("q", listOf(att("3", "gone.bin", "application/octet-stream")), read) as String
        assertTrue("gone.bin" in gone && "unreadable" in gone)
        assertEquals("plain text with nothing attached", "hi", AiAttachWire.content("hi", emptyList(), read))
        val pdf = AiAttachWire.content("p", listOf(att("4", "d.pdf", "application/pdf")), read) as JSONArray
        assertEquals("file", pdf.getJSONObject(1).getString("type"))
    }

    @Test fun onlyTheLastUserMessageCarriesItsFilesInTheHistory() {
        val hist = listOf(
            AiMsg("user", "one", attachments = listOf(att("1", "a.png", "image/png"))), AiMsg("assistant", "r"),
            AiMsg("user", "two", attachments = listOf(att("2", "b.png", "image/png"))),
        )
        val wire = AiProtocol.messages(hist, 10) { it.name.toByteArray() }
        assertTrue("an earlier turn stays text", wire.getJSONObject(0).get("content") is String)
        assertTrue(wire.getJSONObject(2).get("content") is JSONArray)
        assertTrue("with no reader nothing is attached", AiProtocol.messages(hist, 10).getJSONObject(2).get("content") is String)
    }

    @Test fun attachmentsSurviveTheSessionFile() {
        val s = AiSession("s", "t", "m", 1, listOf(AiMsg("assistant", "x", attachments = listOf(att("9", "i.png", "image/png")))))
        val back = AiFileSessions.fromJson(AiFileSessions.toJson(s))
        assertEquals(listOf("i.png"), back.messages.single().attachments.map { it.name })
        assertTrue(back.messages.single().attachments.single().image)
    }

    @Test fun mimeTypesComeFromTheProviderThenTheExtension() {
        assertEquals("image/png", AiAttachments.mimeOf("x.PNG", null))
        assertEquals("application/pdf", AiAttachments.mimeOf("x", "application/pdf"))
        assertEquals("text/markdown", AiAttachments.mimeOf("r.md", "application/octet-stream"))
        assertEquals("application/octet-stream", AiAttachments.mimeOf("blob", null))
        assertEquals("a_b.png", AiAttachments.safeName("a b.png"))
        assertFalse(att("1", "n.bin", "application/octet-stream").image)
    }
}
